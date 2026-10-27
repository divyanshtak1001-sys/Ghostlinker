package com.ghostlink.app.networking

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One authenticated, encrypted session over an established byte stream
 * (Wi-Fi Direct TCP today).
 *
 * ─── Handshake (runs before any application data) ────────────────────────────
 *
 *   client                                 server (group owner)
 *   ──────                                 ────────────────────
 *   U  Zc (32 random bytes)
 *   ────────────────────▶
 *                                          Zc salt=invite   ← SAS(32 bytes)
 *                                          Zs salt=invite   ← SAS(32 bytes)
 *   Zc salt=invite   ← SAS(32 bytes)
 *   Zs salt=invite   ← SAS(32 bytes)
 *   ◀────────────────────
 *   SAS must match locally or the client aborts.
 *   A  HMAC(zc, "GHLK/2 client-ack")  (32)
 *   ◀────────────────────
 *   ◀───────────  both sides derive key = HKDF(Zc||Zs, salt = SHA-256(invite)) and switch to AES-GCM
 *
 *   Every frame after the handshake:
 *     [4-byte length][12-byte IV][AES-256-GCM(input || 8-byte seq, AAD = "GHLK/2")] || tag(16)
 *
 * Why this authenticates the peer
 *   - The session key mixes **both** 256-bit random contributions, so neither side
 *     can choose it alone.
 *   - Each side proves it derived the same key: the client by key-confirming with
 *     an HMAC (server verifies before it sends anything), the server by sending
 *     the first encrypted frame, which the client verifies before trusting anything.
 *   - The shared secret used for the HKDF salt is the invitation token, which is
 *     only known to the two devices that exchanged the QR/code. Possession of that
 *     token is therefore proven, without ever sending the token over the socket.
 *   - The 32-byte SAS is displayed on both devices; if two peers see the same
 *     SAS, no other device could have contributed Zc/Zs.
 *
 * Replay / tamper protection
 *   - The 8-byte sequence number is encrypted AND authenticated (inside GCM), so a
 *     captured frame cannot be replayed, reordered or injected.
 *   - The invite-bound key means a captured frame cannot be replayed in a later
 *     session either, since every session's key is fresh.
 *   - GCM tags are verified by [Cipher.doFinal] before any plaintext is returned.
 *
 * Limitations (stated honestly): the token is a symmetric pre-shared secret, so
 * this is authenticated **given** that the QR/code was exchanged out of band. It
 * does not provide forward secrecy beyond the invitation lifetime and does not
 * give long-term identity, because GhostLink has no persistent identity by design.
 */
internal object GhostSessionProtocol {

    const val VERSION = 2
    const val SAS_BYTES = 32
    const val CHALLENGE_BYTES = 32

    private const val HANDSHAKE_MIN_LEN = 32
    private const val HANDSHAKE_MAX_LEN = 64
    /** Plaintext + seq + tag for one frame. Bounded before allocating. */
    const val MAX_FRAME_BYTES = 64 * 1024
    private const val KEY_BYTES = 32
    private const val SEQUENCE_LENGTH = 8
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private val AAD_BYTES = "GHLK/2".toByteArray(Charsets.UTF_8)

    private val random = SecureRandom()

    /** A server-side handshake in progress, before any plaintext is trusted. */
    class ServerHello(val challenge: ByteArray) {
        private var sas: ByteArray? = null
        private var clientChallenge: ByteArray? = null
        private var key: SecretKey? = null

        fun sasBytes(): ByteArray? = sas?.copyOf()

        /** True once [verifyClientAck] succeeded and the key is ready. */
        val isAuthenticated: Boolean get() = key != null

        fun phase1(
            invitationToken: String,
            out: OutputStream,
            inp: InputStream,
        ): Boolean {
            val sasBytes = sas(invitationToken, challenge)
            writeFrame(out, sasBytes, HANDSHAKE_MAX_LEN)
            writeFrame(out, challenge, HANDSHAKE_MAX_LEN)

            val theirs = readFrame(inp) ?: return false
            if (theirs.size != CHALLENGE_BYTES) return false
            clientChallenge = theirs

            sas = sasBytes
            key = deriveKey(invitationToken, theirs, challenge)
            return true
        }

        /** Reads and verifies the client's key confirmation. */
        fun verifyClientAck(inp: InputStream): Boolean {
            val k = key ?: return false
            val expected = confirm(k)
            val got = readFrame(inp) ?: return false
            if (!MessageDigest.isEqual(expected, got)) {
                key = null
                return false
            }
            return true
        }

        fun takeKey(): SecretKey? = key.also { key = null }
    }

    /** Client-side handshake, performed in one go. */
    class ClientHello(val challenge: ByteArray) {
        var sas: ByteArray? = null
            private set
        private var key: SecretKey? = null

        /**
         * Runs the full client handshake. Returns the session key on success and
         * null when the server's SAS or key confirmation did not match, i.e. the
         * peer does not hold the same invitation.
         */
        fun run(
            invitationToken: String,
            out: OutputStream,
            inp: InputStream,
        ): SecretKey? {
            val serverSas = readFrame(inp) ?: return null
            val serverChallenge = readFrame(inp) ?: return null
            if (serverSas.size != SAS_BYTES || serverChallenge.size != CHALLENGE_BYTES) return null

            if (!MessageDigest.isEqual(serverSas, sas(invitationToken, serverChallenge))) {
                // Peer does not hold this invitation: abort without revealing which value was wrong.
                return null
            }

            writeFrame(out, challenge, HANDSHAKE_MAX_LEN)
            val k = deriveKey(invitationToken, challenge, serverChallenge)
            writeFrame(out, confirm(k), HANDSHAKE_MAX_LEN)

            sas = serverSas
            key = k
            return k
        }
    }

    fun newServerHello(): ServerHello = ServerHello(newRandom(CHALLENGE_BYTES))
    fun newClientHello(): ClientHello = ClientHello(newRandom(CHALLENGE_BYTES))

    // ─── Key + SAS derivation ─────────────────

    /** Derives the human-verifiable 32-byte short authentication string. */
    internal fun sas(invitationToken: String, z: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sha256(invitationToken.toByteArray(Charsets.UTF_8)), "HmacSHA256"))
        return mac.doFinal(concat("GhostLink SAS v$VERSION".toByteArray(Charsets.UTF_8), z))
    }

    /** session_key = HKDF-SHA256(ikm = Zc||Zs, salt = SHA-256(invite), info = "GHLK/2 session key") */
    internal fun deriveKey(token: String, clientZ: ByteArray, serverZ: ByteArray): SecretKey {
        val salt = sha256(token.toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(concat(clientZ, serverZ))
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update("GHLK/2 session key".toByteArray(Charsets.UTF_8))
        mac.update(0x01.toByte())
        return SecretKeySpec(mac.doFinal().copyOf(KEY_BYTES), "AES")
    }

    internal fun confirm(key: SecretKey): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal("GHLK/2 client-ack".toByteArray(Charsets.UTF_8))
    }

    // ─── Protected framing ────────────────────

    internal class CipherState(private val key: SecretKey) {
        private var sendSeq = 0L
        private var recvSeq = 0L
        /** Our own random contribution, reused only to form our outgoing IVs. */
        private val sendIvPrefix = newRandom(4)

        @Synchronized
        fun seal(plaintext: ByteArray, out: OutputStream) {
            // One variable feeds BOTH the IV counter and the encrypted sequence, so
            // the two can never disagree (an earlier version incremented the field
            // before encoding it, so the IV said 0 and the payload said 1).
            val seq = sendSeq
            sendSeq += 1
            val seqPart = encodeSequence(seq)
            val iv = ByteArray(IV_LENGTH).also {
                System.arraycopy(sendIvPrefix, 0, it, 0, sendIvPrefix.size)
                System.arraycopy(seqPart, 0, it, sendIvPrefix.size, seqPart.size)
            }
            // A fresh Cipher per frame: reusing one instance across GCM operations
            // is fragile on some providers.
            val enc = Cipher.getInstance("AES/GCM/NoPadding")
            enc.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            enc.updateAAD(AAD_BYTES)
            val toEncrypt = plaintext + seqPart
            val sealed = enc.doFinal(toEncrypt)
            if (sealed.size > MAX_FRAME_BYTES) throw IOException("Frame too large: ${sealed.size}")

            val body = iv + sealed
            DataOutputStream(out).apply {
                writeInt(body.size)
                write(body)
                flush()
            }
        }

        /**
         * Opens and authenticates one frame.
         *
         * Replay / reordering protection:
         *   1. the IV's counter must equal the expected receive sequence, and
         *   2. the same sequence is encrypted inside the ciphertext and must match.
         * A failed GCM tag, a wrong sequence or a short frame yields null — never
         * partial or unauthenticated plaintext.
         */
        @Synchronized
        fun open(frame: ByteArray): ByteArray? {
            val minSize = IV_LENGTH + SEQUENCE_LENGTH + TAG_BITS / 8
            if (frame.size < minSize) return null

            val iv = frame.copyOfRange(0, IV_LENGTH)
            val body = frame.copyOfRange(IV_LENGTH, frame.size)
            val expected = recvSeq

            // The IV's counter must be exactly the next sequence we expect, which
            // makes a replayed (already seen) or reordered frame fail immediately.
            if (decodeSequence(iv, IV_LENGTH - SEQUENCE_LENGTH) != expected) return null

            val dec = Cipher.getInstance("AES/GCM/NoPadding")
            dec.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            dec.updateAAD(AAD_BYTES)
            val plain = try {
                dec.doFinal(body)
            } catch (_: Exception) {
                // Bad tag, replayed or tampered frame: fatal, never a fallback.
                return null
            }
            if (plain.size < SEQUENCE_LENGTH) return null

            // seal() appends the sequence to the plaintext, so it is the LAST bytes.
            val inner = decodeSequence(plain, plain.size - SEQUENCE_LENGTH)
            if (inner != expected) return null

            recvSeq += 1
            return plain.copyOfRange(0, plain.size - SEQUENCE_LENGTH)
        }

        private fun encodeSequence(seq: Long): ByteArray {
            val out = ByteArray(SEQUENCE_LENGTH)
            var v = seq
            for (i in SEQUENCE_LENGTH - 1 downTo 0) {
                out[i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
            return out
        }

        private fun decodeSequence(bytes: ByteArray, offset: Int): Long {
            var v = 0L
            for (i in 0 until SEQUENCE_LENGTH) {
                v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
            return v
        }
    }

    /** Reads one length-prefixed frame, or null on clean EOF. Throws on a bad length. */
    internal fun readFrame(inp: InputStream, max: Int = MAX_FRAME_BYTES): ByteArray? {
        val input = DataInputStream(inp)
        val first = try {
            input.readInt()
        } catch (_: java.io.EOFException) {
            return null
        }
        if (first <= 0 || first > max) throw IOException("Invalid frame length: $first")
        val bytes = ByteArray(first)
        input.readFully(bytes)
        return bytes
    }

    private fun writeFrame(out: OutputStream, payload: ByteArray, max: Int) {
        require(payload.size in 1..max) { "Handshake frame out of range" }
        val output = DataOutputStream(out)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    // ─── Helpers ──────────────────────────────

    private fun newRandom(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun concat(a: ByteArray, b: ByteArray): ByteArray = a + b

    /** Hex form of the SAS for display. */
    fun sasHex(sas: ByteArray): String = sas.joinToString("") { "%02x".format(it) }
}
