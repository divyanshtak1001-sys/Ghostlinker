package com.ghostlink.app.ui.screens.pairing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.model.GhostAlias
import com.ghostlink.app.model.Invitation
import com.ghostlink.app.model.InvitationCode
import com.ghostlink.app.ui.components.*
import com.ghostlink.app.ui.theme.*
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Shows the one-time invitation:
 *  - A real, scannable QR code (Nayuki QR Code generator, vendored — works fully offline),
 *    placed above the text code.
 *  - The text code for verbal/manual entry.
 *  - An expiry countdown with colour change when under 60 s.
 *  - A regenerate button.
 *
 * QR payload format (short JSON, scannable by any GhostLink device):
 *   { "v": 1, "t": "<invitation token>", "e": <Unix epoch expiry>,
 *     "svc": "FD9C" }
 * The QR and the text code are both derived from the same [Invitation], so they
 * always change together when a new invitation is generated.
 */
@Composable
fun MyInvitationScreen(
    invitation: Invitation?,
    onBack: () -> Unit,
    onRegenerate: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val currentInv = invitation

    // Live countdown — re-reads remainingSec from the invitation every second so
    // it always reflects the real wall-clock time rather than a remembered delta.
    var remainingSeconds by remember(currentInv?.token) {
        mutableIntStateOf(currentInv?.remainingSec ?: 0)
    }

    LaunchedEffect(currentInv?.token) {
        val inv = currentInv ?: return@LaunchedEffect
        while (remainingSeconds > 0) {
            delay(1_000)
            remainingSeconds = inv.remainingSec
        }
    }

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val countdownText = String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds)
    val isWarning = remainingSeconds in 1..59

    // Build the QR payload JSON from the active invitation.
    // Kept deliberately short: version, full invitation token (needed by the peer
    // for key derivation), expiry, and the BLE service UUID. No alias, no packet
    // prefix, no keys, no long strings.
    val qrPayload = remember(currentInv?.token, currentInv?.expiresAt) {
        currentInv?.let { inv -> invitationQrPayload(inv) }
    }

    var qrRetryKey by remember(currentInv?.token) { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            GhostTopBar(
                title = "Your Invitation",
                onBack = onBack
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        containerColor = GhostMainBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // Landscape/short screens: content stays scrollable and clear of
                // the gesture or three-button navigation area.
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Share this QR or read the code to the other person. They scan or type it into GhostLink.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = GhostTextSecondary,
                textAlign = TextAlign.Center
            )

            when {
                // ── No invitation yet ─────────────────────────────────────────
                currentInv == null -> {
                    GhostCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.HourglassEmpty,
                                contentDescription = null,
                                tint = GhostTextSecondary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "No active invitation",
                                fontFamily = GhostMono,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = GhostTextPrimary
                            )
                            Text(
                                text = "Generate a one-time code to start pairing.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = GhostTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    GhostPrimaryButton(
                        text = "Generate Invitation",
                        onClick = onRegenerate,
                        leadingIcon = Icons.Outlined.Refresh
                    )
                }

                // ── Expired ───────────────────────────────────────────────────
                remainingSeconds <= 0 -> {
                    GhostCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.TimerOff,
                                contentDescription = "Invitation expired",
                                tint = GhostError,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "Invitation Expired",
                                fontFamily = GhostMono,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = GhostError
                            )
                            Text(
                                text = "This invitation has expired. Generate a new one to connect.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = GhostTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    GhostPrimaryButton(
                        text = "Generate New Invitation",
                        onClick = onRegenerate,
                        leadingIcon = Icons.Outlined.Refresh
                    )
                }

                // ── Active invitation (QR above text code) ────────────────
                else -> {
                    // ── Real scannable QR (top) ───────────────────────────────
                    if (qrPayload != null) {
                        CornerBracketFrame(
                            modifier = Modifier
                                // 250dp minimum keeps 37 modules + quiet zone crisp,
                                // but shrink on short/landscape screens so the code
                                // and copy button remain reachable.
                                .sizeIn(minWidth = 250.dp, minHeight = 250.dp, maxWidth = 300.dp, maxHeight = 300.dp)
                                .fillMaxWidth(0.8f)
                                .aspectRatio(1f)
                                .semantics {
                                    contentDescription =
                                        "QR code for invitation code ${currentInv.code.display}. Scan with another GhostLink device."
                                },
                            bracketColor = GhostNeonGreen
                        ) {
                            // key() restarts the generation with a fresh state when Retry is tapped
                            key(qrRetryKey) {
                                RealQrCode(
                                    payload = qrPayload,
                                    modifier = Modifier.fillMaxSize(),
                                    size = 280.dp,
                                    onRetry = { qrRetryKey++ }
                                )
                            }
                        }
                    }

                    // ── Text code ─────────────────────────────────────────────
                    GhostCodeField(
                        label = "Invitation Code",
                        code = currentInv.code.display,
                        onCopy = {
                            clipboard.setText(AnnotatedString(currentInv.code.display))
                        }
                    )

                    // ── Expiry countdown ──────────────────────────────────────
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Timer,
                            contentDescription = null,
                            tint = if (isWarning) GhostWarning else GhostTextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Expires in $countdownText",
                            fontFamily = GhostMono,
                            fontSize = 12.sp,
                            color = if (isWarning) GhostWarning else GhostTextSecondary
                        )
                    }

                    //
                    // ── Regenerate ────────────────────────────
                    GhostOutlinedButton(
                        text = "Regenerate",
                        onClick = onRegenerate,
                        leadingIcon = Icons.Outlined.Refresh,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "This invitation does not contain private keys or message content.\nIt is valid for one connection attempt only.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = GhostTextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

// ─── QR payload ──────────────────────────────

private const val QR_PAYLOAD_VERSION = 1
/** GhostLink BLE service UUID short form — identifies the app to the scanner. */
private const val QR_BLE_SERVICE_UUID = "FD9C"

/**
 * Builds the compact JSON payload embedded in the invitation QR code.
 *
 *   { "v": 1, "t": "<token>", "e": <expiresAt>, "svc": "FD9C" }
 *
 * The text invitation code shown under the QR is derived from the same
 * [Invitation], and both are rebuilt whenever the invitation changes.
 */
internal fun invitationQrPayload(inv: Invitation): String =
    JSONObject().apply {
        put("v", QR_PAYLOAD_VERSION)
        put("t", inv.token)
        put("e", inv.expiresAt)
        put("svc", QR_BLE_SERVICE_UUID)
    }.toString()

@Preview(showBackground = true)
@Composable
fun MyInvitationScreenPreview() {
    GhostLinkTheme {
        MyInvitationScreen(
            invitation = Invitation(
                code = InvitationCode("7K9M-4Q2X-AB3C"),
                creatorAlias = GhostAlias("Shadow7F3")
            ),
            onBack = {},
            onRegenerate = {}
        )
    }
}