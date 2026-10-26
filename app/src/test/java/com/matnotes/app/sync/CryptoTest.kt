package com.matnotes.app.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.security.GeneralSecurityException

class CryptoTest {
    companion object {
        lateinit var account: NewAccount

        @BeforeClass @JvmStatic fun setup() { account = Identity.create("correct horse battery staple") }
    }

    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("expected a GeneralSecurityException") } catch (e: GeneralSecurityException) { /* ok */ }
    }

    @Test fun gcmRoundTripAndTamper() {
        val key = Crypto.random(32)
        val sealed = Crypto.seal(key, "hello".toByteArray(), "ctx")
        assertEquals("hello", String(Crypto.open(key, sealed, "ctx")))
        assertRejected { Crypto.open(key, sealed, "other") }
        assertRejected { Crypto.open(Crypto.random(32), sealed, "ctx") }
        val flipped = sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertRejected { Crypto.open(key, flipped, "ctx") }
        assertNotEquals(Crypto.b64(sealed), Crypto.b64(Crypto.seal(key, "hello".toByteArray(), "ctx")))
    }

    @Test fun rsaWrapRoundTrip() {
        val secret = Crypto.random(32)
        val wrapped = Crypto.rsaWrap(account.publicKey, secret)
        assertEquals(Crypto.RSA_BITS / 8, wrapped.size)
        assertArrayEquals(secret, Crypto.rsaUnwrap(account.privateKey, wrapped))
        val other = Crypto.generateRsa()
        assertRejected { Crypto.rsaUnwrap(other.privateDer, wrapped) }
    }

    @Test fun passwordUnlocksAndWrongPasswordFails() {
        val s = account.secrets
        assertArrayEquals(account.privateKey, Identity.unlock("correct horse battery staple", s.kdfSalt, s.kdfIterations, s.encPrivateKey))
        assertRejected { Identity.unlock("wrong", s.kdfSalt, s.kdfIterations, s.encPrivateKey) }
        assertEquals(s.authKey, Identity.authKey("correct horse battery staple", s.kdfSalt, s.kdfIterations))
        assertNotEquals(s.authKey, Identity.authKey("wrong", s.kdfSalt, s.kdfIterations))
    }

    @Test fun passwordIsNormalized() {
        // "é" as one code point and as e + combining accent derive the same keys
        val salt = Crypto.b64(Crypto.random(16))
        assertEquals(Identity.authKey("café", salt, 100_000), Identity.authKey("café", salt, 100_000))
    }

    @Test fun recoveryKeyUnlocksAndPasswordCanBeReset() {
        val s = account.secrets
        val pk = Identity.unlockWithRecovery(account.recoveryKey, s.encPrivateKeyRecovery)
        assertArrayEquals(account.privateKey, pk)
        assertEquals(s.recoveryAuthKey, Identity.recoveryAuthKey(account.recoveryKey))
        assertNull(Identity.unlockWithRecovery("not a key", s.encPrivateKeyRecovery))
        val wrong = Crypto.encodeRecoveryKey(Crypto.random(32))
        assertRejected { Identity.unlockWithRecovery(wrong, s.encPrivateKeyRecovery) }

        val r = Identity.rewrap("new password", pk!!)
        assertArrayEquals(account.privateKey, Identity.unlock("new password", r.kdfSalt, r.kdfIterations, r.encPrivateKey))
        assertEquals(r.authKey, Identity.authKey("new password", r.kdfSalt, r.kdfIterations))
    }

    @Test fun recoveryKeyTextFormat() {
        val key = Crypto.random(32)
        val text = Crypto.encodeRecoveryKey(key)
        assertEquals(52 + 12, text.length) // 52 chars in 13 groups of 4, 12 dashes
        assertArrayEquals(key, Crypto.decodeRecoveryKey(text))
        assertArrayEquals(key, Crypto.decodeRecoveryKey(text.lowercase().replace("-", " ")))
        assertNull(Crypto.decodeRecoveryKey(text.dropLast(4)))
    }

    @Test fun folderAndDocumentEncryptionAreBoundToTheirContext() {
        val fk = Vault.newFolderKey()
        val wrapped = Vault.wrapFolderKey(account.publicKey, fk)
        assertArrayEquals(fk, Vault.unwrapFolderKey(account.privateKey, wrapped))

        val name = Vault.sealFolderName(fk, "folder-1", "Schule überall")
        assertEquals("Schule überall", Vault.openFolderName(fk, "folder-1", name))
        assertRejected { Vault.openFolderName(fk, "folder-2", name) }

        val plain = Crypto.random(100_000)
        val d = Vault.sealDoc(fk, "doc-1", 3, plain)
        assertEquals(plain.size + 12 + 16, d.data.size)
        assertArrayEquals(plain, Vault.openDoc(fk, "doc-1", 3, d.wrappedKey, d.data))
        assertRejected { Vault.openDoc(fk, "doc-1", 4, d.wrappedKey, d.data) } // swapped version
        assertRejected { Vault.openDoc(fk, "doc-2", 3, d.wrappedKey, d.data) } // swapped document
        assertRejected { Vault.openDoc(Vault.newFolderKey(), "doc-1", 3, d.wrappedKey, d.data) }
        val docName = Vault.sealDocName(fk, "doc-1", "Bericht.docx")
        assertEquals("Bericht.docx", Vault.openDocName(fk, "doc-1", docName))
        assertRejected { Vault.openDocName(fk, "doc-2", docName) }
    }

    @Test fun fingerprintIsStableAndKeySpecific() {
        val a = Crypto.fingerprint(account.publicKey)
        assertEquals(a, Crypto.fingerprint(account.publicKey))
        assertNotNull(Regex("^([0-9A-F]{4} ){4}[0-9A-F]{4}$").matchEntire(a))
        assertTrue(a != Crypto.fingerprint(Crypto.generateRsa().publicDer))
    }
}
