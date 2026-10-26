package com.matnotes.app.sync

import java.security.GeneralSecurityException

/** What the server stores per account (all opaque to it) plus the values the client keeps while logged in. */
class AccountSecrets(
    val kdfSalt: String,
    val kdfIterations: Int,
    val authKey: String,
    val publicKey: String,
    val encPrivateKey: String,
    val recoveryAuthKey: String,
    val encPrivateKeyRecovery: String,
)

class NewAccount(val secrets: AccountSecrets, val privateKey: ByteArray, val publicKey: ByteArray, val recoveryKey: String)

/** Account key handling: create, unlock with the password, change the password, recover with the recovery key. */
object Identity {
    private const val AAD_PRIVATE = "account-private-key"
    private const val AAD_RECOVERY = "account-private-key-recovery"

    private class Derived(val auth: ByteArray, val enc: ByteArray)

    private fun derive(password: String, salt: ByteArray, iterations: Int): Derived {
        val master = Crypto.deriveMaster(password, salt, iterations)
        return Derived(Crypto.hmac(master, "matnotes-auth-v1"), Crypto.hmac(master, "matnotes-enc-v1"))
    }

    private fun recoveryDerived(recoveryKey: ByteArray) = Derived(Crypto.hmac(recoveryKey, "matnotes-recovery-auth-v1"), Crypto.hmac(recoveryKey, "matnotes-recovery-enc-v1"))

    fun create(password: String): NewAccount {
        val salt = Crypto.random(16)
        val d = derive(password, salt, Crypto.KDF_ITERATIONS)
        val rsa = Crypto.generateRsa()
        val recovery = Crypto.random(32)
        val r = recoveryDerived(recovery)
        return NewAccount(
            AccountSecrets(
                kdfSalt = Crypto.b64(salt),
                kdfIterations = Crypto.KDF_ITERATIONS,
                authKey = Crypto.b64(d.auth),
                publicKey = Crypto.b64(rsa.publicDer),
                encPrivateKey = Crypto.b64(Crypto.seal(d.enc, rsa.privateDer, AAD_PRIVATE)),
                recoveryAuthKey = Crypto.b64(r.auth),
                encPrivateKeyRecovery = Crypto.b64(Crypto.seal(r.enc, rsa.privateDer, AAD_RECOVERY)),
            ),
            rsa.privateDer, rsa.publicDer, Crypto.encodeRecoveryKey(recovery),
        )
    }

    fun authKey(password: String, saltB64: String, iterations: Int): String = Crypto.b64(derive(password, Crypto.unb64(saltB64), iterations).auth)

    /** Both keys of one password derivation (the slow step runs once per login). */
    class LoginKeys internal constructor(val authKey: String, internal val enc: ByteArray)

    fun loginKeys(password: String, saltB64: String, iterations: Int): LoginKeys {
        val d = derive(password, Crypto.unb64(saltB64), iterations)
        return LoginKeys(Crypto.b64(d.auth), d.enc)
    }

    /** The account private key; throws [GeneralSecurityException] when the password is wrong. */
    fun unlock(keys: LoginKeys, encPrivateKeyB64: String): ByteArray = Crypto.open(keys.enc, Crypto.unb64(encPrivateKeyB64), AAD_PRIVATE)

    fun unlock(password: String, saltB64: String, iterations: Int, encPrivateKeyB64: String): ByteArray =
        unlock(loginKeys(password, saltB64, iterations), encPrivateKeyB64)

    class Rewrapped(val kdfSalt: String, val kdfIterations: Int, val authKey: String, val encPrivateKey: String)

    /** New salt, new keys, same private key: for a password change and for recovery. */
    fun rewrap(newPassword: String, privateKey: ByteArray): Rewrapped {
        val salt = Crypto.random(16)
        val d = derive(newPassword, salt, Crypto.KDF_ITERATIONS)
        return Rewrapped(Crypto.b64(salt), Crypto.KDF_ITERATIONS, Crypto.b64(d.auth), Crypto.b64(Crypto.seal(d.enc, privateKey, AAD_PRIVATE)))
    }

    fun recoveryAuthKey(recoveryKeyText: String): String? =
        Crypto.decodeRecoveryKey(recoveryKeyText)?.let { Crypto.b64(recoveryDerived(it).auth) }

    /** The account private key from the recovery key; null for a malformed key, throws when it is the wrong key. */
    fun unlockWithRecovery(recoveryKeyText: String, encPrivateKeyRecoveryB64: String): ByteArray? {
        val key = Crypto.decodeRecoveryKey(recoveryKeyText) ?: return null
        return Crypto.open(recoveryDerived(key).enc, Crypto.unb64(encPrivateKeyRecoveryB64), AAD_RECOVERY)
    }
}

/** Folder and document encryption. Names and contents are bound to their folder/document/version through the AAD. */
object Vault {
    fun newFolderKey(): ByteArray = Crypto.random(32)

    fun wrapFolderKey(memberPublicKeyDer: ByteArray, folderKey: ByteArray): String = Crypto.b64(Crypto.rsaWrap(memberPublicKeyDer, folderKey))
    fun unwrapFolderKey(privateKeyDer: ByteArray, wrappedB64: String): ByteArray = Crypto.rsaUnwrap(privateKeyDer, Crypto.unb64(wrappedB64))

    fun sealFolderName(folderKey: ByteArray, folderId: String, name: String): String =
        Crypto.b64(Crypto.seal(folderKey, name.toByteArray(Charsets.UTF_8), "folder-name|$folderId"))

    fun openFolderName(folderKey: ByteArray, folderId: String, sealedB64: String): String =
        String(Crypto.open(folderKey, Crypto.unb64(sealedB64), "folder-name|$folderId"), Charsets.UTF_8)

    fun sealDocName(folderKey: ByteArray, docId: String, name: String): String =
        Crypto.b64(Crypto.seal(folderKey, name.toByteArray(Charsets.UTF_8), "doc-name|$docId"))

    fun openDocName(folderKey: ByteArray, docId: String, sealedB64: String): String =
        String(Crypto.open(folderKey, Crypto.unb64(sealedB64), "doc-name|$docId"), Charsets.UTF_8)

    class SealedDoc(val data: ByteArray, val wrappedKey: String)

    /** One fresh document key per version; the key travels wrapped with the folder key. */
    fun sealDoc(folderKey: ByteArray, docId: String, version: Int, plain: ByteArray): SealedDoc {
        val docKey = Crypto.random(32)
        return SealedDoc(
            Crypto.seal(docKey, plain, "doc|$docId|$version"),
            Crypto.b64(Crypto.seal(folderKey, docKey, "doc-key|$docId|$version")),
        )
    }

    fun openDoc(folderKey: ByteArray, docId: String, version: Int, wrappedKeyB64: String, sealed: ByteArray): ByteArray {
        val docKey = Crypto.open(folderKey, Crypto.unb64(wrappedKeyB64), "doc-key|$docId|$version")
        return Crypto.open(docKey, sealed, "doc|$docId|$version")
    }
}
