package com.matnotes.app.sync

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives of the end-to-end encryption. Only javax.crypto: AES-256-GCM, PBKDF2-HMAC-SHA256, HMAC-SHA256 and RSA-3072 OAEP.
 *
 * Key hierarchy (the server never sees a password or a key):
 * password --PBKDF2--> master --HMAC--> auth key (sent to the server) and enc key (wraps the private key)
 * RSA private key  --opens--> folder key  --wraps--> document key  --encrypts--> document (one key per version)
 */
object Crypto {
    const val KDF_ITERATIONS = 600_000
    const val RSA_BITS = 3072
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun hmac(key: ByteArray, label: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(label.toByteArray(Charsets.UTF_8))

    /** Master key from the password; the salt is public and per account. */
    fun deriveMaster(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(Normalizer.normalize(password, Normalizer.Form.NFKC).toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // ---- AES-GCM: output = nonce(12) || ciphertext || tag(16). The AAD binds a ciphertext to its place (folder, document, version). ----

    fun seal(key: ByteArray, plain: ByteArray, aad: String): ByteArray {
        val nonce = random(NONCE)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return nonce + c.doFinal(plain)
    }

    /** Throws [java.security.GeneralSecurityException] when the key, the AAD or the data is wrong or was tampered with. */
    fun open(key: ByteArray, sealed: ByteArray, aad: String): ByteArray {
        require(sealed.size > NONCE + TAG_BITS / 8) { "ciphertext too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, NONCE)))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return c.doFinal(sealed, NONCE, sealed.size - NONCE)
    }

    // ---- RSA-OAEP (SHA-256 and MGF1-SHA-256 set explicitly: Android's default MGF1 digest is SHA-1) ----

    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    class RsaKeys(val publicDer: ByteArray, val privateDer: ByteArray)

    fun generateRsa(): RsaKeys {
        val p = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_BITS, rng) }.generateKeyPair()
        return RsaKeys(p.public.encoded, p.private.encoded)
    }

    fun rsaWrap(publicDer: ByteArray, secret: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicDer))
        return Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.ENCRYPT_MODE, key, oaep) }.doFinal(secret)
    }

    fun rsaUnwrap(privateDer: ByteArray, wrapped: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateDer))
        return Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.DECRYPT_MODE, key, oaep) }.doFinal(wrapped)
    }

    /** Short code of a public key, shown to both people so they can compare it (a swapped key would show a different code). */
    fun fingerprint(publicDer: ByteArray): String =
        sha256(publicDer).take(10).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")

    // ---- recovery key: 32 random bytes shown once as Base32 groups ----

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encodeRecoveryKey(key: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in key) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) { sb.append(B32[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(B32[(buffer shl (5 - bits)) and 31])
        return sb.chunked(4).joinToString("-")
    }

    /** Null when the text is not a well-formed recovery key. Accepts lower case, spaces and missing dashes. */
    fun decodeRecoveryKey(text: String): ByteArray? {
        val clean = text.uppercase().filter { it in B32 }
        if (clean.length != 52) return null
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            buffer = (buffer shl 5) or B32.indexOf(ch)
            bits += 5
            if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray().takeIf { it.size == 32 }
    }
}
