package com.ghostlink.app.networking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.spec.SecretKeySpec

/**
 * JVM tests for [GhostSessionProtocol] — the authenticated session used by Wi-Fi Direct.
 *
 * The real handshake classes are driven end to end over in-memory streams, so these
 * cover the security properties without a device, camera or radio: peer
 * authentication, wrong-invitation rejection, tamper detection, replay rejection,
 * ordering and oversized-frame rejection.
 */
class GhostSessionProtocolTest {

    private val invite = "a".repeat(64)
    private val otherInvite = "b".repeat(64)

    /** Result of a completed handshake: independent senders + receivers. */
    private class Session(
        val clientSend: GhostSessionProtocol.CipherState,
        val serverRecv: GhostSessionProtocol.CipherState,
    )

    /**
     * Runs the real sequence:
     *   server emits SAS+challenge -> client verifies + replies challenge+ack ->
     *   server verifies the ack. Both keys are derived by production code.
     */
    private fun handshake(
        serverInvite: String = invite,
        clientInvite: String = invite,
    ): Session? {
        val server = GhostSessionProtocol.newServerHello()
        val client = GhostSessionProtocol.newClientHello()

        // ── server -> client: SAS(Zs) + Zs ──────────────────────────────
        val s2c = ByteArrayOutputStream()
        writeRecord(s2c, GhostSessionProtocol.sas(serverInvite, server.challenge))
        writeRecord(s2c, server.challenge)

        // ── client verifies and writes Zc + key confirmation ────────────
        val c2s = ByteArrayOutputStream()
        val clientKey = client.run(clientInvite, c2s, ByteArrayInputStream(s2c.toByteArray()))
            ?: return null

        // ── server reads Zc, derives its key, verifies the ack ──────────
        val serverIn = ByteArrayInputStream(c2s.toByteArray())
        val clientChallenge = GhostSessionProtocol.readFrame(serverIn)!!
        val serverKey = GhostSessionProtocol.deriveKey(serverInvite, clientChallenge, server.challenge)
        val ack = GhostSessionProtocol.readFrame(serverIn)!!
        assertArrayEquals(GhostSessionProtocol.confirm(serverKey), ack)

        return Session(
            clientSend = GhostSessionProtocol.CipherState(clientKey),
            serverRecv = GhostSessionProtocol.CipherState(serverKey),
        )
    }

    private fun writeRecord(out: ByteArrayOutputStream, payload: ByteArray) {
        out.write(payload.size ushr 24)
        out.write(payload.size ushr 16)
        out.write(payload.size ushr 8)
        out.write(payload.size)
        out.write(payload)
    }

    private fun readRecord(bytes: ByteArray): ByteArray =
        GhostSessionProtocol.readFrame(ByteArrayInputStream(bytes))!!

    private fun sealed(sender: GhostSessionProtocol.CipherState, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        sender.seal(text.toByteArray(), out)
        return readRecord(out.toByteArray())
    }

    // ─── Happy path ─────────────────────────────────────

    @Test
    fun `matching invitations complete the handshake and exchange messages`() {
        val s = handshake() ?: throw AssertionError("handshake must succeed")
        assertEquals("hello ghost", String(s.serverRecv.open(sealed(s.clientSend, "hello ghost"))!!))
    }

    @Test
    fun `client and server agree on the same SAS`() {
        val server = GhostSessionProtocol.newServerHello()
        val client = GhostSessionProtocol.newClientHello()
        val s2c = ByteArrayOutputStream()
        writeRecord(s2c, GhostSessionProtocol.sas(invite, server.challenge))
        writeRecord(s2c, server.challenge)

        assertNotNull(client.run(invite, ByteArrayOutputStream(), ByteArrayInputStream(s2c.toByteArray())))
        assertEquals(
            GhostSessionProtocol.sasHex(GhostSessionProtocol.sas(invite, server.challenge)),
            client.sas?.let(GhostSessionProtocol::sasHex),
        )
    }

    @Test
    fun `sas is per-session, not a constant an attacker could predict`() {
        // Two handshakes with the SAME invitation must produce DIFFERENT codes,
        // otherwise a recorded code would be reusable to social-engineer a user.
        fun sasOf(): String {
            val server = GhostSessionProtocol.newServerHello()
            val client = GhostSessionProtocol.newClientHello()
            val s2c = ByteArrayOutputStream()
            writeRecord(s2c, GhostSessionProtocol.sas(invite, server.challenge))
            writeRecord(s2c, server.challenge)
            assertNotNull(client.run(invite, ByteArrayOutputStream(), ByteArrayInputStream(s2c.toByteArray())))
            return GhostSessionProtocol.sasHex(client.sas!!)
        }
        assertNotEquals(sasOf(), sasOf())
    }

    @Test
    fun `sas is not derivable without the invitation`() {
        // An observer who knows the challenge but not the invitation cannot compute
        // the code the users compare.
        val challenge = GhostSessionProtocol.newServerHello().challenge
        assertNotEquals(
            GhostSessionProtocol.sasHex(GhostSessionProtocol.sas(invite, challenge)),
            GhostSessionProtocol.sasHex(GhostSessionProtocol.sas(otherInvite, challenge)),
        )
    }

    // ─── Authentication failures ────────────────────────

    @Test
    fun `a peer with a different invitation cannot complete the handshake`() {
        assertNull("client must abort when the SAS does not match", handshake(otherInvite, invite))
    }

    @Test
    fun `session keys differ between handshakes even with the same invitation`() {
        val first = handshake()!!
        val second = handshake()!!
        assertNull(second.serverRecv.open(sealed(first.clientSend, "x")))
    }

    // ─── Frame protection ───────────────────────────────

    @Test
    fun `tampered frame is rejected`() {
        val s = handshake()!!
        val frame = sealed(s.clientSend, "secret")
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0x01).toByte()
        assertNull(s.serverRecv.open(frame))
    }

    @Test
    fun `replayed frame is rejected`() {
        val s = handshake()!!
        val frame = sealed(s.clientSend, "once")
        assertEquals("once", String(s.serverRecv.open(frame)!!))
        assertNull("the same frame must never be accepted twice", s.serverRecv.open(frame))
    }

    @Test
    fun `out of order frame is rejected`() {
        val s = handshake()!!
        // Sealed in order (sequence 0 then 1), delivered in reverse.
        val first = sealed(s.clientSend, "1")
        val second = sealed(s.clientSend, "2")
        assertNull("frame #2 arrives before #1", s.serverRecv.open(second))
        assertEquals("1", String(s.serverRecv.open(first)!!))
    }

    @Test
    fun `oversized frame length is rejected before allocation`() {
        val bogus = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        try {
            GhostSessionProtocol.readFrame(ByteArrayInputStream(bogus), 4096)
            throw AssertionError("expected IOException for an oversized frame")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `truncated frame is an error, never an empty message`() {
        val truncated = byteArrayOf(0, 0, 0, 16, 1, 2, 3)
        try {
            GhostSessionProtocol.readFrame(ByteArrayInputStream(truncated))
            throw AssertionError("expected the truncated frame to fail")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `clean eof returns null`() {
        assertNull(GhostSessionProtocol.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `frame shorter than iv plus tag is rejected`() {
        val cipher = GhostSessionProtocol.CipherState(SecretKeySpec(ByteArray(32), "AES"))
        assertNull(cipher.open(ByteArray(10)))
    }

    @Test
    fun `sas depends on the invitation`() {
        val z = ByteArray(32) { it.toByte() }
        val a = GhostSessionProtocol.sas("invite-a", z)
        val b = GhostSessionProtocol.sas("invite-b", z)
        assertEquals(GhostSessionProtocol.SAS_BYTES, a.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `deriveKey mixes both random contributions`() {
        val zc = ByteArray(32) { 1 }
        val zs = ByteArray(32) { 2 }
        val a = GhostSessionProtocol.deriveKey(invite, zc, zs).encoded
        val b = GhostSessionProtocol.deriveKey(invite, zs, zc).encoded
        val c = GhostSessionProtocol.deriveKey(invite, zc, ByteArray(32) { 3 }).encoded
        assertFalse("order must matter", a.contentEquals(b))
        assertFalse("server contribution must matter", a.contentEquals(c))
        assertTrue(a.size == 32)
    }
}
