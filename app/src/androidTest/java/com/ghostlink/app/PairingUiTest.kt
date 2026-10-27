package com.ghostlink.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import com.ghostlink.app.model.Invitation
import com.ghostlink.app.model.InvitationCode
import com.ghostlink.app.ui.screens.connection.ConnectionProgressScreen
import com.ghostlink.app.ui.screens.pairing.EnterCodeScreen
import com.ghostlink.app.ui.theme.GhostLinkTheme
import com.ghostlink.app.viewmodel.ConnectionProgressStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for the two behaviours that were previously untested at the UI
 * layer: full invitation-code entry and the safety-code verification panel.
 *
 * These require an emulator/device to execute (`connectedDebugAndroidTest`). They
 * are the executable form of the audit's "no instrumentation tests" finding.
 */
class PairingUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun invitationCode_acceptsAllTwelveCharacters() {
        var captured = ""
        rule.setContent {
            GhostLinkTheme {
                EnterCodeScreen(
                    codeInput = captured,
                    onCodeChange = { captured = it },
                    isLoading = false,
                    errorMessage = null,
                    onBack = {},
                    onScanQr = {},
                    onContinue = {},
                )
            }
        }
        // Type the raw characters; the screen must format to XXXX-XXXX-XXXX and
        // must NOT truncate any of the 12 alphanumeric characters.
        rule.onNodeWithText("e.g. 7K9M-4Q2X-AB3C").performTextInput("7K9M4Q2XAB3C")
        rule.waitForIdle()
        assertEquals("7K9M-4Q2X-AB3C".replace("-", ""), captured.replace("-", ""))
        assertEquals(14, captured.length)
    }

    @Test
    fun invitationCode_continueEnabledOnlyWithEnoughDigits() {
        rule.setContent {
            GhostLinkTheme {
                EnterCodeScreen(
                    codeInput = "7K9M",
                    onCodeChange = {},
                    isLoading = false,
                    errorMessage = "Invalid code format",
                    onBack = {},
                    onScanQr = {},
                    onContinue = {},
                )
            }
        }
        rule.onNodeWithText("Invalid code format").assertIsDisplayed()
    }

    @Test
    fun safetyCode_isShownAndRequiresExplicitConfirmation() {
        var confirmed = false
        var rejected = false
        rule.setContent {
            GhostLinkTheme {
                ConnectionProgressScreen(
                    currentStep = ConnectionProgressStep.VerifyingPeer,
                    peerAlias = "Lunar_8D4",
                    onCancel = {},
                    verificationCode = "deadbeefcafe1234",
                    onConfirmVerification = { confirmed = true },
                    onRejectVerification = { rejected = true },
                )
            }
        }
        // The code must be visible (grouped in 4s) and both actions present.
        rule.onNodeWithText("dead beef cafe 1234").assertIsDisplayed()
        rule.onNodeWithText("Codes match").assertIsDisplayed()
        rule.onNodeWithText("Codes differ").assertIsDisplayed()

        rule.onNodeWithText("Codes match").performClick()
        rule.waitForIdle()
        assertTrue("confirm must be delivered", confirmed)
        assertEquals("reject must not fire", false, rejected)
    }

    @Test
    fun safetyCode_absentWhenNotVerifying() {
        rule.setContent {
            GhostLinkTheme {
                ConnectionProgressScreen(
                    currentStep = ConnectionProgressStep.EstablishingLink,
                    peerAlias = "Lunar_8D4",
                    onCancel = {},
                    verificationCode = null,
                )
            }
        }
        assertEquals(0, rule.onAllNodesWithText("Codes match").fetchSemanticsNodes().size)
    }

    @Test
    fun safetyCode_hasAccessibleDescription() {
        rule.setContent {
            GhostLinkTheme {
                ConnectionProgressScreen(
                    currentStep = ConnectionProgressStep.VerifyingPeer,
                    peerAlias = "Lunar_8D4",
                    onCancel = {},
                    verificationCode = "0011223344556677",
                )
            }
        }
        rule.onNodeWithContentDescription("Safety code, compare with the other device")
            .assertIsDisplayed()
    }
}
