package com.ghostlink.app.networking

import org.junit.Assert.*
import org.junit.Test

class GhostProtocolTest {

    @Test
    fun `buildHello creates standard greeting format`() {
        val hello = GhostProtocol.buildHello("Shadow_7F3")
        assertEquals("GHLK/1 HELLO Shadow_7F3", hello)
        assertTrue(GhostProtocol.isHello(hello))
    }

    @Test
    fun `buildAck creates standard acknowledgment format`() {
        val ack = GhostProtocol.buildAck("Specter_1A9")
        assertEquals("GHLK/1 ACK Specter_1A9", ack)
        assertFalse(GhostProtocol.isHello(ack))
    }

    @Test
    fun `parseHandshake parses alias from hello and ack`() {
        assertEquals("Shadow_7F3", GhostProtocol.parseHandshake("GHLK/1 HELLO Shadow_7F3"))
        assertEquals("Specter_1A9", GhostProtocol.parseHandshake("GHLK/1 ACK Specter_1A9"))
        assertNull(GhostProtocol.parseHandshake("INVALID PROTOCOL LINE"))
        assertNull(GhostProtocol.parseHandshake("GHLK/1 HELLO "))
    }

    @Test
    fun `sanitises alias against CRLF injection and caps length`() {
        val malicious = "Alias\r\nINJECTED_COMMAND_TOO_LONG_123456789012345"
        val hello = GhostProtocol.buildHello(malicious)
        assertFalse(hello.contains("\r"))
        assertFalse(hello.contains("\n"))
        val parsed = GhostProtocol.parseHandshake(hello)
        assertNotNull(parsed)
        assertTrue(parsed!!.length <= 32)
    }
}
