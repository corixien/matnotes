package com.matnotes.app.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface AccountState {
    data object LoggedOut : AccountState
    data class LoggedIn(val userId: String, val name: String, val email: String) : AccountState
}

/**
 * The signed-in account: tokens, name and keys. The refresh token and the account private key are stored encrypted with the
 * Android Keystore; the password is never stored. All server calls that need a login go through [call] / [bytes], which refresh
 * the short-lived access token when needed.
 */
object Account {
    private lateinit var prefs: SharedPreferences
    private val refreshLock = Mutex()
    private val _state = MutableStateFlow<AccountState>(AccountState.LoggedOut)
    val state: StateFlow<AccountState> get() = _state

    @Volatile private var cachedPrivateKey: ByteArray? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("account", Context.MODE_PRIVATE)
        _state.value = readState()
    }

    private fun readState(): AccountState {
        val id = prefs.getString("userId", null) ?: return AccountState.LoggedOut
        if (prefs.getString("refresh", null) == null) return AccountState.LoggedOut
        return AccountState.LoggedIn(id, prefs.getString("name", "") ?: "", prefs.getString("email", "") ?: "")
    }

    val loggedIn: Boolean get() = _state.value is AccountState.LoggedIn
    val userId: String? get() = (_state.value as? AccountState.LoggedIn)?.userId
    val publicKey: ByteArray? get() = prefs.getString("publicKey", null)?.let { Crypto.unb64(it) }

    /** The account private key, decrypted from the Keystore-protected copy; null when logged out or unreadable. */
    val privateKey: ByteArray?
        get() = cachedPrivateKey ?: prefs.getString("privateKey", null)?.let { SecureStore.open(it) }?.also { cachedPrivateKey = it }

    private fun store(session: JSONObject, user: JSONObject, privateKey: ByteArray) {
        cachedPrivateKey = privateKey
        prefs.edit()
            .putString("userId", user.getString("id"))
            .putString("name", user.getString("name"))
            .putString("email", user.getString("email"))
            .putString("publicKey", user.getString("publicKey"))
            .putString("privateKey", SecureStore.seal(privateKey))
            .apply()
        storeTokens(session)
        _state.value = readState()
    }

    private fun storeTokens(t: JSONObject) {
        prefs.edit()
            .putString("refresh", SecureStore.seal(t.getString("refreshToken").toByteArray()))
            .putString("access", t.getString("accessToken"))
            .putLong("accessExp", System.currentTimeMillis() + t.getLong("expiresIn") * 1000L)
            .apply()
    }

    private fun deviceName() = android.os.Build.MODEL ?: "Android"

    // ---- flows (heavy key derivation runs on Dispatchers.Default) ----

    /** Creates the account and signs in. Returns the recovery key; it is shown once and is the only way back in after a forgotten password. */
    suspend fun register(name: String, email: String, password: String): String {
        val acc = withContext(Dispatchers.Default) { Identity.create(password) }
        val s = acc.secrets
        val r = SyncApi.json(
            "POST", "/auth/register",
            JSONObject().put("name", name.trim()).put("email", email.trim()).put("deviceName", deviceName())
                .put("authKey", s.authKey).put("kdfSalt", s.kdfSalt).put("kdfIterations", s.kdfIterations)
                .put("publicKey", s.publicKey).put("encPrivateKey", s.encPrivateKey)
                .put("recoveryAuthKey", s.recoveryAuthKey).put("encPrivateKeyRecovery", s.encPrivateKeyRecovery),
        )
        store(r, r.getJSONObject("user"), acc.privateKey)
        return acc.recoveryKey
    }

    suspend fun login(email: String, password: String) {
        val params = SyncApi.json("POST", "/auth/params", JSONObject().put("email", email.trim()))
        val keys = withContext(Dispatchers.Default) { Identity.loginKeys(password, params.getString("salt"), params.getInt("iterations")) }
        val r = SyncApi.json("POST", "/auth/login", JSONObject().put("email", email.trim()).put("authKey", keys.authKey).put("deviceName", deviceName()))
        val user = r.getJSONObject("user")
        val priv = Identity.unlock(keys, user.getString("encPrivateKey"))
        store(r, user, priv)
    }

    /** Forgotten password: the recovery key opens the private key, a new password re-wraps it. Throws GeneralSecurityException for a wrong key. */
    suspend fun recover(email: String, recoveryKeyText: String, newPassword: String) {
        val start = SyncApi.json("POST", "/auth/recover/start", JSONObject().put("email", email.trim()))
        val proof = Identity.recoveryAuthKey(recoveryKeyText) ?: throw java.security.GeneralSecurityException("malformed recovery key")
        val priv = withContext(Dispatchers.Default) { Identity.unlockWithRecovery(recoveryKeyText, start.getString("encPrivateKeyRecovery")) }
            ?: throw java.security.GeneralSecurityException("malformed recovery key")
        val w = withContext(Dispatchers.Default) { Identity.rewrap(newPassword, priv) }
        val t = SyncApi.json(
            "POST", "/auth/recover/finish",
            JSONObject().put("email", email.trim()).put("recoveryAuthKey", proof).put("deviceName", deviceName())
                .put("newAuthKey", w.authKey).put("newKdfSalt", w.kdfSalt).put("newKdfIterations", w.kdfIterations).put("newEncPrivateKey", w.encPrivateKey),
        )
        val me = SyncApi.json("GET", "/auth/me", token = t.getString("accessToken")).getJSONObject("user")
        store(t, me, priv)
    }

    /** Changes the password; the other devices have to sign in again. */
    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val me = call("GET", "/auth/me").getJSONObject("user")
        val cur = withContext(Dispatchers.Default) { Identity.authKey(currentPassword, me.getString("kdfSalt"), me.getInt("kdfIterations")) }
        val priv = privateKey ?: throw IllegalStateException("not signed in")
        val w = withContext(Dispatchers.Default) { Identity.rewrap(newPassword, priv) }
        val t = call(
            "POST", "/auth/password",
            JSONObject().put("currentAuthKey", cur).put("deviceName", deviceName())
                .put("newAuthKey", w.authKey).put("newKdfSalt", w.kdfSalt).put("newKdfIterations", w.kdfIterations).put("newEncPrivateKey", w.encPrivateKey),
        )
        storeTokens(t)
    }

    suspend fun deleteAccount(password: String) {
        val me = call("GET", "/auth/me").getJSONObject("user")
        val key = withContext(Dispatchers.Default) { Identity.authKey(password, me.getString("kdfSalt"), me.getInt("kdfIterations")) }
        call("POST", "/auth/delete", JSONObject().put("authKey", key))
        clearLocal()
    }

    suspend fun logout() {
        val refresh = prefs.getString("refresh", null)?.let { SecureStore.open(it) }?.toString(Charsets.UTF_8)
        if (refresh != null) runCatching { SyncApi.json("POST", "/auth/logout", JSONObject().put("refreshToken", refresh)) }
        clearLocal()
    }

    private fun clearLocal() {
        cachedPrivateKey = null
        prefs.edit().clear().apply()
        _state.value = AccountState.LoggedOut
        onSignedOut?.invoke()
    }

    /** Set by the sync layer: wipes its local state (folder keys, cursor, mappings) when the account goes away. */
    var onSignedOut: (() -> Unit)? = null

    // ---- authorized calls ----

    private suspend fun accessToken(forceRefresh: Boolean = false): String = refreshLock.withLock {
        val exp = prefs.getLong("accessExp", 0L)
        val token = prefs.getString("access", null)
        if (!forceRefresh && token != null && exp - System.currentTimeMillis() > 60_000L) return token
        val refresh = prefs.getString("refresh", null)?.let { SecureStore.open(it) }?.toString(Charsets.UTF_8)
            ?: throw ApiException(401, "unauthorized", "Not signed in")
        try {
            val t = SyncApi.json("POST", "/auth/refresh", JSONObject().put("refreshToken", refresh))
            storeTokens(t)
            t.getString("accessToken")
        } catch (e: ApiException) {
            if (e.status == 401) clearLocal() // the session was revoked (password changed elsewhere, or removed): back to logged out
            throw e
        }
    }

    suspend fun call(method: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): JSONObject {
        try {
            return SyncApi.json(method, path, body, accessToken(), headers)
        } catch (e: ApiException) {
            if (e.status != 401) throw e
        }
        return SyncApi.json(method, path, body, accessToken(forceRefresh = true), headers)
    }

    suspend fun bytes(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): SyncApi.Response {
        try {
            return SyncApi.bytes(method, path, accessToken(), headers, body)
        } catch (e: ApiException) {
            if (e.status != 401) throw e
        }
        return SyncApi.bytes(method, path, accessToken(forceRefresh = true), headers, body)
    }
}
