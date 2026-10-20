package com.newoether.agora.util

import android.util.Base64
import android.os.Build
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ShellCrypto {
    private const val GCM_NONCE_SIZE = 12
    private const val GCM_TAG_SIZE = 128
    private const val HKDF_INFO = "conch-agora-v2"

    private val secureRandom = SecureRandom()
    private const val B64_URL_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    // Android exposes X25519 through XDH from API 33. Older releases use the bundled
    // implementation without registering or replacing a process-wide provider.
    private val legacyX25519Provider by lazy { BouncyCastleProvider() }

    // --- Key Generation (X25519) ---

    fun generateEphemeralKeyPair(): java.security.KeyPair {
        val generator = if (Build.VERSION.SDK_INT >= 33) KeyPairGenerator.getInstance("XDH")
            else KeyPairGenerator.getInstance("X25519", legacyX25519Provider)
        return generator.generateKeyPair()
    }

    fun encodePublicKey(publicKey: java.security.PublicKey): String {
        // X25519 SPKI encoding deterministically ends with the 32-byte raw key
        val encoded = publicKey.encoded
        val rawKey = encoded.copyOfRange(encoded.size - 32, encoded.size)
        return Base64.encodeToString(rawKey, B64_URL_FLAGS)
    }

    private val X25519_SPKI_PREFIX: ByteArray by lazy {
        val kp = generateEphemeralKeyPair()
        val encoded = kp.public.encoded
        encoded.copyOfRange(0, encoded.size - 32)
    }

    fun decodePublicKey(base64urlKey: String): java.security.PublicKey {
        val rawKey = Base64.decode(base64urlKey, Base64.URL_SAFE or Base64.NO_WRAP)
        if (rawKey.size != 32) {
            throw IllegalArgumentException("invalid X25519 public key length: ${rawKey.size}")
        }
        val spki = X25519_SPKI_PREFIX + rawKey
        val keySpec = X509EncodedKeySpec(spki)
        val factory = if (Build.VERSION.SDK_INT >= 33) KeyFactory.getInstance("XDH")
            else KeyFactory.getInstance("X25519", legacyX25519Provider)
        return factory.generatePublic(keySpec)
    }

    // --- ECDH + HKDF ---

    fun deriveAesKey(
        ourPrivateKey: java.security.PrivateKey,
        theirPublicKey: java.security.PublicKey
    ): ByteArray {
        val keyAgreement = if (Build.VERSION.SDK_INT >= 33) KeyAgreement.getInstance("XDH")
            else KeyAgreement.getInstance("X25519", legacyX25519Provider)
        keyAgreement.init(ourPrivateKey)
        keyAgreement.doPhase(theirPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()
        // RFC 5869: raw Diffie-Hellman output needs Extract before Expand.
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val pseudorandomKey = extract.doFinal(sharedSecret)
        return hkdfExpand(pseudorandomKey, HKDF_INFO.toByteArray(Charsets.UTF_8), 32)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i: Byte = 1
        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(i)
            t = mac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            i++
        }
        return result
    }

    // --- AES-256-GCM ---

    fun encrypt(key: ByteArray, plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(GCM_NONCE_SIZE)
        secureRandom.nextBytes(nonce)
        val spec = GCMParameterSpec(GCM_TAG_SIZE, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        val ciphertext = cipher.doFinal(plaintext)
        val combined = ByteArray(nonce.size + ciphertext.size)
        System.arraycopy(nonce, 0, combined, 0, nonce.size)
        System.arraycopy(ciphertext, 0, combined, nonce.size, ciphertext.size)
        return Base64.encodeToString(combined, B64_URL_FLAGS)
    }

    fun decrypt(key: ByteArray, encoded: String): ByteArray {
        val raw = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
        if (raw.size < GCM_NONCE_SIZE + 1) {
            throw IllegalArgumentException("ciphertext too short: ${raw.size} bytes")
        }
        val nonce = raw.copyOfRange(0, GCM_NONCE_SIZE)
        val ciphertext = raw.copyOfRange(GCM_NONCE_SIZE, raw.size)
        val spec = GCMParameterSpec(GCM_TAG_SIZE, nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        return cipher.doFinal(ciphertext)
    }

    // --- HMAC-SHA256 ---

    fun sign(apiKey: String, timestamp: Long, method: String, path: String, bodySha256: String, nonce: String, clientPubKey: String): String {
        val message = "$timestamp|$method|$path|$bodySha256|$nonce|$clientPubKey"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    internal fun responseSignature(sessionKey: ByteArray, status: Int, body: String): String {
        val key = hkdfExpand(sessionKey, "conch-response-auth-v1".toByteArray(Charsets.UTF_8), 32)
        val payload = "conch-response-v1|$status|${sha256Hex(body.toByteArray(Charsets.UTF_8))}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    internal fun verifyResponseSignature(sessionKey: ByteArray, status: Int, body: String, signature: String): Boolean =
        sessionKey.size == 32 && signature.length == 64 && MessageDigest.isEqual(
            responseSignature(sessionKey, status, body).toByteArray(Charsets.US_ASCII),
            signature.toByteArray(Charsets.US_ASCII),
        )

    fun generateNonce(): String {
        val bytes = ByteArray(12)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, B64_URL_FLAGS)
    }
}
