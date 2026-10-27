package com.ghostlink.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ghostlink.app.model.ChatMessage
import com.ghostlink.app.model.GhostAlias
import com.ghostlink.app.model.MessageOrigin
import com.ghostlink.app.model.PeerInfo
import com.ghostlink.app.ui.screens.chat.ChatScreen
import com.ghostlink.app.ui.theme.GhostLinkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Keyboard/inset regression tests for the chat composer.
 *
 * The composer is the control most likely to be pushed under the keyboard, so these
 * assert it stays on screen and stays usable after the field takes focus.
 *
 * Requires a device/emulator: `./gradlew :app:connectedDebugAndroidTest`.
 * Not executed in this environment (no emulator installed).
 */
class ChatKeyboardTest {

    @get:Rule
    val rule = createComposeRule()

    private var draft by mutableStateOf("")

    private fun showChat(messages: List<ChatMessage>, onSend: () -> Unit = {}) {
        draft = ""
        rule.setContent {
            GhostLinkTheme {
                ChatScreen(
                    peer = PeerInfo(alias = GhostAlias("Lunar_8D4")),
                    messages = messages,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = onSend,
                    onDisconnect = {},
                )
            }
        }
    }

    @Test
    fun composerAndSendStayReachableWhileTyping() {
        showChat(emptyList())
        rule.onNodeWithText("> Type a message...").performClick()
        rule.onNodeWithText("> Type a message...").performTextInput("hello")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test
    fun draftSurvivesRecomposition() {
        showChat(emptyList())
        rule.onNodeWithText("> Type a message...").performTextInput("draft text")
        rule.waitForIdle()
        assertEquals("draft text", draft)
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test
    fun sendDeliversDraft() {
        var sent = false
        showChat(emptyList()) { sent = true }
        rule.onNodeWithText("> Type a message...").performTextInput("ping")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitForIdle()
        assertTrue(sent)
    }

    @Test
    fun composerStaysVisibleWithManyMessages() {
        val many = (1..40).map {
            ChatMessage(
                id = "m$it",
                content = "line $it",
                origin = if (it % 2 == 0) MessageOrigin.Local else MessageOrigin.Remote,
            )
        }
        showChat(many)
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }
}
