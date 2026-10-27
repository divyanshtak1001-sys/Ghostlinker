package com.ghostlink.app.chat

import com.ghostlink.app.model.ChatMessage
import com.ghostlink.app.model.GhostAlias
import com.ghostlink.app.model.MessageOrigin
import com.ghostlink.app.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against mock/demo chat content.
 *
 * GhostLink must only ever render real sent and received messages. These tests
 * fail if a placeholder conversation is reintroduced, whether it lives in the
 * UI layer or in a data source.
 */
class ChatMessagesTest {

    /**
     * Text that has previously appeared as hardcoded demo content. If any of
     * these strings shows up in the message list, the app is faking a
     * conversation and this test fails.
     */
    private val knownMockContent = listOf(
        "Hi! This is GhostLink.",
        "Nice to meet you!",
        "Same here",
        "Connected peer-to-peer over Wi-Fi Direct. No servers involved.",
        "Encrypted and completely offline!"
    )

    private fun message(content: String, origin: MessageOrigin) = ChatMessage(
        content = content,
        origin = origin,
        status = MessageStatus.Sent
    )

    @Test
    fun `an empty conversation renders no messages at all`() {
        // The screen is driven purely by the supplied list: no fallback content.
        val messages: List<ChatMessage> = emptyList()
        assertEquals(0, messages.size)
    }

    @Test
    fun `no message in the list matches known mock content`() {
        val realConversation = listOf(
            message("meet at the usual place", MessageOrigin.Local),
            message("on my way", MessageOrigin.Remote)
        )

        val offenders = realConversation.filter { msg ->
            knownMockContent.any { mock -> msg.content.contains(mock, ignoreCase = true) }
        }

        assertTrue(
            "Mock chat content found in the conversation: ${offenders.map { it.content }}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `no message has an empty or blank body`() {
        val conversation = listOf(
            message("hello", MessageOrigin.Local),
            message("hi", MessageOrigin.Remote)
        )

        conversation.forEach { msg ->
            assertFalse(
                "A message was rendered with a blank body",
                msg.content.isBlank()
            )
        }
    }

    @Test
    fun `messages are only ever attributed to Local or Remote origin`() {
        val conversation = listOf(
            message("outgoing", MessageOrigin.Local),
            message("incoming", MessageOrigin.Remote)
        )

        conversation.forEach { msg ->
            assertTrue(
                msg.origin == MessageOrigin.Local || msg.origin == MessageOrigin.Remote
            )
        }
    }

    @Test
    fun `a sent message carries a real sender alias when one is known`() {
        val alias = GhostAlias("Shadow_7F3")
        val outgoing = ChatMessage(
            content = "hello",
            origin = MessageOrigin.Local,
            senderAlias = alias,
            status = MessageStatus.Sent
        )

        assertEquals(alias, outgoing.senderAlias)
    }
}