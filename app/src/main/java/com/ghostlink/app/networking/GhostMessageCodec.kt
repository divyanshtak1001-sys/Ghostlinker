package com.ghostlink.app.networking

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * GhostMessageCodec — length-prefixed message framing for the GhostLink wire
 * protocol over a raw byte stream (Bluetooth RFCOMM, and later Wi-Fi Direct).
 *
 * ─── Why framing is required ────────────────────────────────────────────────
 * A Bluetooth RFCOMM socket (like a TCP socket) is a byte STREAM, not a message
 * stream. If two chat messages are written back to back, the receiver's
 * read() call may return:
 *   - both messages in a single buffer (merged), or
 *   - only the first half of one message (split).
 *
 * Writing the payload length in front of the payload removes that ambiguity:
 * the receiver first reads exactly 4 bytes to learn how many payload bytes to
 * expect, then reads exactly that many bytes. A message therefore cannot be
 * merged with its neighbour or truncated, no matter how the OS chunks the data.
 *
 * ─── Wire format ────────────────────────────────────────────────────────────
 *   [4 bytes]  payload length   — signed big-endian int (DataOutputStream.writeInt)
 *   [N bytes]  payload          — UTF-8 encoded message text
 *
 * This codec carries ONLY the message text. It deliberately does not carry the
 * sender alias, timestamps or any identifier: the peer alias is exchanged once
 * in the separate handshake frame, and timestamps are assigned locally when the
 * message is received. Keeping the payload minimal means the least possible
 * information crosses the wire.
 *
 * ─── Bounds ─────────────────────────────────────────────────────────────────
 * [MAX_MESSAGE_BYTES] caps a single frame so a corrupt or hostile length prefix
 * cannot make the receiver allocate an unbounded buffer. Longer text is sent as
 * multiple frames, so nothing is silently dropped.
 *
 * This class is transport-agnostic and has no Android dependencies, so it is
 * unit-testable on the JVM without a device or emulator.
 */
object GhostMessageCodec {

    /** Hard upper bound for one frame's payload, in bytes. */
    const val MAX_MESSAGE_BYTES: Int = 8192

    /** Number of bytes in the big-endian length prefix. */
    const val LENGTH_PREFIX_BYTES: Int = 4

    /**
     * Encodes [text] as a complete length-prefixed record (header + payload).
     * Used by transports that seal whole records (Wi-Fi Direct AES-GCM frames)
     * instead of writing straight to a stream.
     */
    fun encodePayload(text: String): String = String(
        ByteArray(LENGTH_PREFIX_BYTES).also {
            val length = text.toByteArray(Charsets.UTF_8).size
            for (i in 0 until LENGTH_PREFIX_BYTES) {
                it[i] = (length ushr (8 * (LENGTH_PREFIX_BYTES - 1 - i))).toByte()
            }
        } + text.toByteArray(Charsets.UTF_8),
        Charsets.ISO_8859_1,
    )

    /**
     * Inverse of [encodePayload]: parses one complete record and returns its text,
     * or null when the record is malformed or its length does not match the data.
     */
    fun decodePayload(record: ByteArray): String? {
        if (record.size < LENGTH_PREFIX_BYTES) return null
        var length = 0
        for (i in 0 until LENGTH_PREFIX_BYTES) {
            length = (length shl 8) or (record[i].toInt() and 0xFF)
        }
        if (length < 0 || length > MAX_MESSAGE_BYTES) return null
        if (record.size != LENGTH_PREFIX_BYTES + length) return null
        return String(record, LENGTH_PREFIX_BYTES, length, Charsets.UTF_8)
    }

    /**
     * Frames [text] and writes it to [out] as a single length-prefixed record.
     *
     * [text] longer than [MAX_MESSAGE_BYTES] is split into consecutive frames so
     * no content is lost. Writes are synchronised on [out] so that a send from
     * the UI thread and an automatic protocol reply cannot interleave and
     * corrupt each other's bytes.
     *
     * @throws IOException when the underlying stream fails (for example when the
     *         peer has closed the socket).
     */
    @Throws(IOException::class)
    fun writeMessage(out: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)

        if (bytes.isEmpty()) {
            writeFrame(out, bytes, 0, 0)
            return
        }

        var written = 0
        while (written < bytes.size) {
            val end = minOf(written + MAX_MESSAGE_BYTES, bytes.size)
            writeFrame(out, bytes, written, end)
            written = end
        }
    }

    @Throws(IOException::class)
    private fun writeFrame(out: OutputStream, payload: ByteArray, from: Int, to: Int) {
        synchronized(out) {
            val length = to - from
            // Big-endian length prefix, then the payload. flush() pushes the frame
            // out of the socket buffer so the peer's read() returns promptly
            // instead of waiting for the buffer to fill.
            out.write(
                byteArrayOf(
                    (length ushr 24).toByte(),
                    (length ushr 16).toByte(),
                    (length ushr 8).toByte(),
                    length.toByte()
                )
            )
            out.write(payload, from, length)
            out.flush()
        }
    }

    /**
     * Reads exactly one framed message from [input].
     *
     * @return the decoded UTF-8 text, or null when the peer closed the stream
     *         cleanly before sending a new frame. A partial frame (connection
     *         dropped mid-message) is reported as an [IOException] rather than
     *         being silently returned as a truncated message.
     * @throws IOException on a malformed length prefix or a broken stream.
     */
    @Throws(IOException::class)
    fun readMessage(input: InputStream): String? {
        val length = readLengthPrefix(input) ?: return null
        if (length < 0 || length > MAX_MESSAGE_BYTES) {
            throw IOException("Malformed frame length: $length")
        }
        if (length == 0) return ""

        val payload = ByteArray(length)
        readFully(input, payload)
        return String(payload, Charsets.UTF_8)
    }

    /**
     * Reads the 4-byte length prefix.
     * Returns null only when the very first byte read hits end-of-stream, which
     * means the peer closed the connection between messages.
     */
    @Throws(IOException::class)
    private fun readLengthPrefix(input: InputStream): Int? {
        val header = ByteArray(LENGTH_PREFIX_BYTES)
        val first = input.read()
        if (first == -1) return null
        header[0] = first.toByte()

        var read = 1
        while (read < LENGTH_PREFIX_BYTES) {
            val n = input.read(header, read, LENGTH_PREFIX_BYTES - read)
            if (n == -1) throw IOException("Stream ended inside a frame header")
            read += n
        }

        return ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
    }

    /**
     * Reads until [buffer] is completely filled.
     * InputStream.read() may return fewer bytes than requested; looping here is
     * what guarantees a frame is never truncated.
     */
    @Throws(IOException::class)
    private fun readFully(input: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n == -1) throw IOException("Stream ended inside a frame payload")
            read += n
        }
    }
}
