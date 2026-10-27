package com.ghostlink.app.networking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class GhostMessageCodecTest {

    @Test
    fun `round trips UTF-8 messages as distinct frames`() {
        val output = ByteArrayOutputStream()
        GhostMessageCodec.writeMessage(output, "hello 👻")
        GhostMessageCodec.writeMessage(output, "second message")

        val input = ByteArrayInputStream(output.toByteArray())
        assertEquals("hello 👻", GhostMessageCodec.readMessage(input))
        assertEquals("second message", GhostMessageCodec.readMessage(input))
        assertNull(GhostMessageCodec.readMessage(input))
    }

    @Test
    fun `round trips empty messages`() {
        val output = ByteArrayOutputStream()
        GhostMessageCodec.writeMessage(output, "")

        assertEquals("", GhostMessageCodec.readMessage(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun `splits oversized text into consecutive frames without losing content`() {
        val text = "x".repeat(GhostMessageCodec.MAX_MESSAGE_BYTES + 1)
        val output = ByteArrayOutputStream()
        GhostMessageCodec.writeMessage(output, text)

        val input = ByteArrayInputStream(output.toByteArray())
        val firstFrame = GhostMessageCodec.readMessage(input)
        val secondFrame = GhostMessageCodec.readMessage(input)

        assertEquals(GhostMessageCodec.MAX_MESSAGE_BYTES, firstFrame?.length ?: -1)
        assertEquals(1, secondFrame?.length ?: -1)
        assertEquals(text, firstFrame.orEmpty() + secondFrame.orEmpty())
        assertNull(GhostMessageCodec.readMessage(input))
    }

    @Test(expected = IOException::class)
    fun `rejects a frame larger than the protocol limit`() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).writeInt(GhostMessageCodec.MAX_MESSAGE_BYTES + 1)

        GhostMessageCodec.readMessage(ByteArrayInputStream(output.toByteArray()))
    }
}
