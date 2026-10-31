package com.matnotes.app.sync

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Cross-implementation vectors for the web client (web/src/sync/crypto.ts).
 * - `WRITE_WEB_VECTORS=1` writes web/test-vectors/kotlin.json (made here, checked by the web tests).
 * - web/test-vectors/web.json (made by the web tests with `WRITE_WEB_VECTORS=1 npm test`) is checked here when present.
 * The files hold throwaway test keys only.
 */
class WebVectorsTest {
    private val dir = File("../web/test-vectors")
    private val password = "pässwörd ✓ 12345"
    private val folderId = "3b1f2a64-0000-4000-8000-000000000001"
    private val docId = "3b1f2a64-0000-4000-8000-000000000002"

    @Test fun writeKotlinVectors() {
        assumeTrue(System.getenv("WRITE_WEB_VECTORS") == "1")
        val acc = Identity.create(password)
        val s = acc.secrets
        val fk = Vault.newFolderKey()
        val plain = Crypto.random(3000)
        val sealed = Vault.sealDoc(fk, docId, 7, plain)
        val o = JSONObject()
            .put("password", password)
            .put("secrets", JSONObject().put("kdfSalt", s.kdfSalt).put("kdfIterations", s.kdfIterations).put("authKey", s.authKey).put("publicKey", s.publicKey)
                .put("encPrivateKey", s.encPrivateKey).put("recoveryAuthKey", s.recoveryAuthKey).put("encPrivateKeyRecovery", s.encPrivateKeyRecovery))
            .put("privateKey", Crypto.b64(acc.privateKey)).put("recoveryKey", acc.recoveryKey)
            .put("fingerprint", Crypto.fingerprint(acc.publicKey))
            .put("folderId", folderId).put("docId", docId)
            .put("folderKey", Crypto.b64(fk))
            .put("wrappedFolderKey", Vault.wrapFolderKey(acc.publicKey, fk))
            .put("folderName", "Schule äöü").put("sealedFolderName", Vault.sealFolderName(fk, folderId, "Schule äöü"))
            .put("docName", "Bericht ✓.docx").put("sealedDocName", Vault.sealDocName(fk, docId, "Bericht ✓.docx"))
            .put("version", 7).put("plain", Crypto.b64(plain)).put("sealedDoc", Crypto.b64(sealed.data)).put("wrappedDocKey", sealed.wrappedKey)
        dir.mkdirs()
        File(dir, "kotlin.json").writeText(o.toString(1))
    }

    @Test fun readWebVectors() {
        val f = File(dir, "web.json")
        assumeTrue(f.exists())
        val o = JSONObject(f.readText())
        val sec = o.getJSONObject("secrets")
        val keys = Identity.loginKeys(o.getString("password"), sec.getString("kdfSalt"), sec.getInt("kdfIterations"))
        assertEquals(sec.getString("authKey"), keys.authKey)
        val priv = Identity.unlock(keys, sec.getString("encPrivateKey"))
        assertArrayEquals(Crypto.unb64(o.getString("privateKey")), priv)
        assertArrayEquals(priv, Identity.unlockWithRecovery(o.getString("recoveryKey"), sec.getString("encPrivateKeyRecovery")))
        assertEquals(sec.getString("recoveryAuthKey"), Identity.recoveryAuthKey(o.getString("recoveryKey")))
        assertEquals(o.getString("fingerprint"), Crypto.fingerprint(Crypto.unb64(sec.getString("publicKey"))))
        val fk = Vault.unwrapFolderKey(priv, o.getString("wrappedFolderKey"))
        assertArrayEquals(Crypto.unb64(o.getString("folderKey")), fk)
        assertEquals(o.getString("folderName"), Vault.openFolderName(fk, o.getString("folderId"), o.getString("sealedFolderName")))
        assertEquals(o.getString("docName"), Vault.openDocName(fk, o.getString("docId"), o.getString("sealedDocName")))
        val plain = Vault.openDoc(fk, o.getString("docId"), o.getInt("version"), o.getString("wrappedDocKey"), Crypto.unb64(o.getString("sealedDoc")))
        assertArrayEquals(Crypto.unb64(o.getString("plain")), plain)
    }
}
