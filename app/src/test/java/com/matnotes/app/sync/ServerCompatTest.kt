package com.matnotes.app.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Runs the real client crypto and wire format against a real server. Skipped unless MATNOTES_TEST_SERVER is set, e.g.
 * `MATNOTES_TEST_SERVER=http://127.0.0.1:3000/api ./gradlew :app:testDebugUnitTest` with `server/` running on a throwaway database
 * (RATE_LIMIT_DISABLED=1).
 */
class ServerCompatTest {
    private val base = System.getenv("MATNOTES_TEST_SERVER")

    private class Session(val email: String, val password: String, val acc: NewAccount, var token: String, val id: String)

    private suspend fun register(name: String): Session {
        val email = "${name.lowercase()}-${UUID.randomUUID()}@example.com"
        val password = "pässwörd-12345-$name"
        val acc = Identity.create(password)
        val s = acc.secrets
        val r = SyncApi.json(
            "POST", "/auth/register",
            JSONObject().put("name", name).put("email", email).put("deviceName", "jvm")
                .put("authKey", s.authKey).put("kdfSalt", s.kdfSalt).put("kdfIterations", s.kdfIterations)
                .put("publicKey", s.publicKey).put("encPrivateKey", s.encPrivateKey)
                .put("recoveryAuthKey", s.recoveryAuthKey).put("encPrivateKeyRecovery", s.encPrivateKeyRecovery),
        )
        return Session(email, password, acc, r.getString("accessToken"), r.getJSONObject("user").getString("id"))
    }

    @Test fun registerLoginShareAndTransferADocument() = runBlocking {
        assumeTrue(base != null)
        SyncApi.baseUrl = base!!
        val anna = register("Anna")
        val ben = register("Ben")

        // login on a "new device": derive keys from the password, open the private key
        val params = SyncApi.json("POST", "/auth/params", JSONObject().put("email", anna.email))
        val keys = Identity.loginKeys(anna.password, params.getString("salt"), params.getInt("iterations"))
        val login = SyncApi.json("POST", "/auth/login", JSONObject().put("email", anna.email).put("authKey", keys.authKey))
        val priv = Identity.unlock(keys, login.getJSONObject("user").getString("encPrivateKey"))
        assertArrayEquals(anna.acc.privateKey, priv)
        anna.token = login.getString("accessToken")

        // Anna creates a shared folder and uploads an encrypted document
        val folderId = UUID.randomUUID().toString()
        val folderKey = Vault.newFolderKey()
        SyncApi.json(
            "POST", "/folders",
            JSONObject().put("id", folderId).put("encName", Vault.sealFolderName(folderKey, folderId, "Schule")).put("wrappedKey", Vault.wrapFolderKey(anna.acc.publicKey, folderKey)),
            anna.token,
        )
        val docId = UUID.randomUUID().toString()
        val plain = Crypto.random(200_000)
        val sealed = Vault.sealDoc(folderKey, docId, 1, plain)
        val put = SyncApi.bytes(
            "PUT", "/folders/$folderId/docs/$docId", anna.token,
            mapOf("If-Match" to "0", "X-Enc-Name" to Vault.sealDocName(folderKey, docId, "Bericht.docx"), "X-Wrapped-Key" to sealed.wrappedKey),
            sealed.data,
        )
        assertEquals(200, put.status)

        // Anna invites Ben: look up his public key, wrap the folder key for him
        val lookup = SyncApi.json("GET", "/users/lookup?email=" + java.net.URLEncoder.encode(ben.email, "UTF-8"), token = anna.token)
        val benPublic = Crypto.unb64(lookup.getString("publicKey"))
        assertArrayEquals(ben.acc.publicKey, benPublic)
        SyncApi.json("POST", "/folders/$folderId/members", JSONObject().put("userId", ben.id).put("wrappedKey", Vault.wrapFolderKey(benPublic, folderKey)), anna.token)

        // Ben sees the invitation, decrypts the folder name, accepts, downloads and decrypts the document
        val feed = SyncApi.json("GET", "/sync/changes?since=0", token = ben.token)
        val f = feed.getJSONArray("folders").getJSONObject(0)
        assertEquals("invited", f.getString("status"))
        val benFolderKey = Vault.unwrapFolderKey(ben.acc.privateKey, f.getString("wrappedKey"))
        assertEquals("Schule", Vault.openFolderName(benFolderKey, folderId, f.getString("encName")))
        SyncApi.json("POST", "/folders/$folderId/accept", token = ben.token)
        val after = SyncApi.json("GET", "/sync/changes?since=0", token = ben.token)
        val d = after.getJSONArray("docs").getJSONObject(0)
        assertEquals("Bericht.docx", Vault.openDocName(benFolderKey, d.getString("id"), d.getString("encName")))
        val blob = SyncApi.bytes("GET", "/folders/$folderId/docs/$docId/blob", ben.token)
        val version = blob.headers["x-version"]!!.toInt()
        assertArrayEquals(plain, Vault.openDoc(benFolderKey, docId, version, blob.headers["x-wrapped-key"]!!, blob.body))

        // Ben saves a new version, Anna reads it
        val v2 = Vault.sealDoc(benFolderKey, docId, 2, plain.reversedArray())
        SyncApi.bytes(
            "PUT", "/folders/$folderId/docs/$docId", ben.token,
            mapOf("If-Match" to "1", "X-Enc-Name" to Vault.sealDocName(benFolderKey, docId, "Bericht.docx"), "X-Wrapped-Key" to v2.wrappedKey), v2.data,
        )
        val back = SyncApi.bytes("GET", "/folders/$folderId/docs/$docId/blob", anna.token)
        assertEquals("2", back.headers["x-version"])
        assertArrayEquals(plain.reversedArray(), Vault.openDoc(folderKey, docId, 2, back.headers["x-wrapped-key"]!!, back.body))

        // a stale write is rejected with the server version
        try {
            SyncApi.bytes("PUT", "/folders/$folderId/docs/$docId", anna.token, mapOf("If-Match" to "1", "X-Enc-Name" to Vault.sealDocName(folderKey, docId, "x.docx"), "X-Wrapped-Key" to v2.wrappedKey), v2.data)
            throw AssertionError("expected a conflict")
        } catch (e: ApiException) {
            assertEquals(409, e.status)
            assertEquals(2, e.serverVersion)
        }
    }

    @Test fun recoveryKeyRestoresAccess() = runBlocking {
        assumeTrue(base != null)
        SyncApi.baseUrl = base!!
        val u = register("Recover")
        val start = SyncApi.json("POST", "/auth/recover/start", JSONObject().put("email", u.email))
        val priv = Identity.unlockWithRecovery(u.acc.recoveryKey, start.getString("encPrivateKeyRecovery"))!!
        assertArrayEquals(u.acc.privateKey, priv)
        val w = Identity.rewrap("a brand new password", priv)
        val done = SyncApi.json(
            "POST", "/auth/recover/finish",
            JSONObject().put("email", u.email).put("recoveryAuthKey", Identity.recoveryAuthKey(u.acc.recoveryKey)!!)
                .put("newAuthKey", w.authKey).put("newKdfSalt", w.kdfSalt).put("newKdfIterations", w.kdfIterations).put("newEncPrivateKey", w.encPrivateKey),
        )
        assertTrue(done.getString("accessToken").isNotEmpty())
        val params = SyncApi.json("POST", "/auth/params", JSONObject().put("email", u.email))
        val keys = Identity.loginKeys("a brand new password", params.getString("salt"), params.getInt("iterations"))
        val login = SyncApi.json("POST", "/auth/login", JSONObject().put("email", u.email).put("authKey", keys.authKey))
        assertArrayEquals(u.acc.privateKey, Identity.unlock(keys, login.getJSONObject("user").getString("encPrivateKey")))
    }
}
