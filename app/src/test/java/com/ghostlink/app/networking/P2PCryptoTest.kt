package com.ghostlink.app.networking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitive-level checks for the cipher GhostSessionProtocol uses.
 *
 * Scope note: this class verifies AES-256-GCM behaviour only. It exercises the
 * raw primitive independently of the session protocol, whose authentication,
 * replay and ordering rules are covered by [GhostSessionProtocolTest].
 */
class P2PCryptoTest {

    private fun encrypt(plaintext: ByteArray, key: SecretKey, iv: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(plaintext)
    }

    private fun decrypt(ciphertext: ByteArray, key: SecretKey, iv: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(ciphertext)
    }

    private fun key(seed: ByteArray): SecretKey =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(seed), "AES")

    private fun iv() = ByteArray(12) { (it * 7 + 1).toByte() }

    @Test
    fun `AES-256-GCM round trip encrypts and decrypts correctly`() {
        val message = "Top secret GhostLink message payload"
        val encrypted = encrypt(message.toByteArray(), key("token".toByteArray()), iv())
        assertNotEquals(message, String(encrypted, Charsets.ISO_8859_1))
        assertEquals(message, String(decrypt(encrypted, key("token".toByteArray()), iv())))
    }

    @Test
    fun `session formula round trip with AAD and sequence`() {
        // Mirrors GhostSessionProtocol.CipherState: AAD is the protocol label, and
        // the sequence number rides inside the plaintext.
        val k = key("invitation".toByteArray())
        val iv = iv()
        val aad = "GHLK/2".toByteArray()
        val seq = ByteArray(8) { i -> (i + 1).toByte() }

        val sealed = encrypt("payload".toByteArray() + seq, k, iv, aad)
        val opened = decrypt(sealed, k, iv, aad)
        assertTrue(opened.copyOfRange(opened.size - 8, opened.size).contentEquals(seq))
        assertEquals("payload", String(opened.copyOfRange(0, opened.size - 8)))
    }

    @Test(expected = AEADBadTagException::class)
    fun `detects ciphertext tampering and rejects with AEADBadTagException`() {
        val k = key("test".toByteArray())
        val iv = iv()
        val encrypted = encrypt("Original Untampered Text".toByteArray(), k, iv)
        encrypted[encrypted.size / 2] = (encrypted[encrypted.size / 2].toInt() xor 0xFF).toByte()
        decrypt(encrypted, k, iv)
    }

    @Test(expected = AEADBadTagException::class)
    fun `detects mismatched key and fails auth tag verification`() {
        val iv = iv()
        val encrypted = encrypt("Confidential P2P Content".toByteArray(), key("TOKEN_A".toByteArray()), iv)
        decrypt(encrypted, key("TOKEN_B".toByteArray()), iv)
    }

    @Test(expected = AEADBadTagException::class)
    fun `detects modified AAD`() {
        val k = key("token".toByteArray())
        val iv = iv()
        val encrypted = encrypt("msg".toByteArray(), k, iv, "GHLK/2".toByteArray())
        decrypt(encrypted, k, iv, "GHLK/3".toByteArray())
    }
}
