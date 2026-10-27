package com.ghostlink.app.ui.screens.connection

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.ui.components.*
import com.ghostlink.app.ui.theme.*
import com.ghostlink.app.ui.theme.GhostMono
import com.ghostlink.app.viewmodel.ConnectionProgressStep

@Composable
fun ConnectionProgressScreen(
    currentStep: ConnectionProgressStep,
    peerAlias: String,
    onCancel: () -> Unit,
    errorMessage: String? = null,
    /**
     * Safety code to compare when the state is [ConnectionProgressStep.VerifyingPeer].
     * Null means no verification is pending and no panel is shown.
     */
    verificationCode: String? = null,
    onConfirmVerification: () -> Unit = {},
    onRejectVerification: () -> Unit = {},
) {
    val colors = GhostColors

    val steps = listOf(
        ConnectionProgressStep.Discovering,
        ConnectionProgressStep.EstablishingLink,
        ConnectionProgressStep.VerifyingPeer,
        ConnectionProgressStep.EstablishingSession,
        ConnectionProgressStep.Ready
    )

    Scaffold(
        topBar = {
            GhostTopBar(
                title = "Connecting",
                actions = {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", style = MaterialTheme.typography.labelMedium, color = GhostTextMuted)
                    }
                }
            )
        },
        containerColor = GhostBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            GhostSpacer(32.dp)

            Text(
                text = peerAlias,
                style = GhostAliasStyle.copy(fontSize = 18.sp),
                color = GhostTextPrimary
            )
            Text(
                text = "Establishing session…",
                style = MaterialTheme.typography.bodyMedium,
                color = GhostTextSecondary
            )

            GhostSpacer(16.dp)

            // ── Progress stages ───────────────────────────────────────────────
            GhostCard(modifier = Modifier.fillMaxWidth()) {
                steps.forEach { step ->
                    val state = when {
                        step == currentStep -> StageState.Active
                        steps.indexOf(step) < steps.indexOf(currentStep) -> StageState.Done
                        else -> StageState.Pending
                    }
                    GhostProgressStage(label = step.label, state = state)
                }

                // Show failed if step is failed/rejected
                if (currentStep == ConnectionProgressStep.Failed ||
                    currentStep == ConnectionProgressStep.Rejected) {
                    GhostDivider()
                    Spacer(Modifier.height(8.dp))
                    GhostProgressStage(label = currentStep.label, state = StageState.Failed)
                }
            }

            // ── Stage description ─────────────────────────────────────────────
            val stageDesc = when (currentStep) {
                ConnectionProgressStep.Discovering       -> "Looking for the other device nearby."
                ConnectionProgressStep.EstablishingLink  -> "Opening a direct local channel with the device."
                ConnectionProgressStep.VerifyingPeer     -> "Confirming the peer runs GhostLink."
                ConnectionProgressStep.EstablishingSession -> "Setting up an in-memory session channel."
                ConnectionProgressStep.Ready             -> "Session is ready."
                ConnectionProgressStep.Failed            -> "Could not complete the connection. Please try again."
                ConnectionProgressStep.Rejected          -> "The connection request was rejected."
                else -> ""
            }

            Text(
                text = errorMessage
                    ?.takeIf {
                        currentStep == ConnectionProgressStep.Failed ||
                            currentStep == ConnectionProgressStep.Rejected
                    }
                    ?: stageDesc,
                style = MaterialTheme.typography.bodySmall,
                color = GhostTextMuted,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // ── Safety-code verification (fail-closed until confirmed) ───────
            if (verificationCode != null && currentStep == ConnectionProgressStep.VerifyingPeer) {
                SafetyCodePanel(
                    code = verificationCode,
                    peerAlias = peerAlias,
                    onConfirm = onConfirmVerification,
                    onReject = onRejectVerification,
                )
            }

            if (currentStep == ConnectionProgressStep.Failed ||
                currentStep == ConnectionProgressStep.Rejected) {
                GhostOutlinedButton(
                    text = "Go back",
                    onClick = onCancel,
                    modifier = Modifier.widthIn(max = 240.dp)
                )
            }
        }
    }
}

/**
 * Explains the safety-code step and provides explicit confirm/reject actions.
 *
 * The code is rendered as grouped hex digits because the two users must read it
 * aloud or compare visually; rejection is as prominent as confirmation so a
 * mismatched code is not skipped by reflex.
 */
@Composable
private fun SafetyCodePanel(
    code: String,
    peerAlias: String,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    val grouped = code.chunked(4).joinToString(" ")
    GhostCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "SAFETY CODE",
                fontFamily = GhostMono,
                fontSize = 10.sp,
                color = GhostTextMuted,
            )
            Text(
                text = grouped,
                fontFamily = GhostMono,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = GhostNeonGreen,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    contentDescription = "Safety code, compare with the other device"
                },
            )
            Text(
                text = "Check that $peerAlias shows exactly the same code. " +
                    "If it differs, someone may be intercepting the connection — reject it.",
                fontSize = 12.sp,
                color = GhostTextSecondary,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GhostOutlinedButton(
                    text = "Codes differ",
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                )
                GhostPrimaryButton(
                    text = "Codes match",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
