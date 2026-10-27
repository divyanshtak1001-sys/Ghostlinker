package com.ghostlink.app.ui.screens.pairing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.ui.components.*
import com.ghostlink.app.ui.theme.*

/**
 * "Pair with Someone" — picking *how* to reach the other device.
 *
 * The list is grouped on purpose: pairing/verification methods first (they are
 * what establishes trust), then the network transports. A transport cannot be
 * used for GhostLink without an invitation, because the invitation is the shared
 * secret that authenticates the peer, so the transport row explains that rather
 * than offering a connection that would have to be unauthenticated.
 */
@Composable
fun PairingMenuScreen(
    onBack: () -> Unit,
    onScanQr: () -> Unit,
    onEnterCode: () -> Unit,
    onShowMyInvitation: () -> Unit,
    onNearbyDirect: () -> Unit = {},
    wifiDirectReady: Boolean = false,
    wifiDirectReason: String? = null,
    bleAvailable: Boolean = false,
    hasInvitation: Boolean = false,
) {
    Scaffold(
        topBar = {
            GhostTopBar(
                title = "Pair with Someone",
                subtitle = "Choose how you want to connect.",
                onBack = onBack
            )
        },
        containerColor = GhostMainBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionLabel("Pair & verify")

            GhostMenuCard(
                icon = Icons.Outlined.QrCodeScanner,
                title = "Scan QR Code",
                subtitle = "Point your camera at the other person's invitation QR.",
                onClick = onScanQr
            )

            GhostMenuCard(
                icon = Icons.Outlined.Keyboard,
                title = "Enter Invitation Code",
                subtitle = "Type the one-time code someone read to you.",
                onClick = onEnterCode
            )

            GhostMenuCard(
                icon = Icons.Outlined.Share,
                title = "Show My Invitation Code",
                subtitle = "Generate a QR code and a code to read out.",
                onClick = onShowMyInvitation
            )

            SectionLabel("Local transports")

            // Wi-Fi Direct: preferred high-throughput transport when the radio,
            // hardware and permissions allow it.
            TransportRow(
                icon = Icons.Outlined.Wifi,
                title = "Wi-Fi Direct",
                subtitle = "Preferred high-throughput local transport when supported.",
                statusText = when {
                    wifiDirectReason != null -> wifiDirectReason
                    !hasInvitation -> "Needs an active invitation to authenticate the peer."
                    else -> "Ready — also available from Nearby Devices."
                },
                statusIsWarning = wifiDirectReason != null && hasInvitation,
                onClick = if (wifiDirectReady && hasInvitation) onNearbyDirect else null,
            )

            // Bluetooth is discovery-only in this build: its data path is not yet
            // migrated onto the authenticated session protocol, so it must not be
            // offered as an encrypted transport.
            TransportRow(
                icon = Icons.Outlined.Bluetooth,
                title = "Bluetooth (discovery only)",
                subtitle = "Finds nearby devices. Data sessions are not available over Bluetooth yet.",
                statusText = if (bleAvailable) {
                    "Discovery ready — connect using Wi-Fi Direct."
                } else {
                    "Bluetooth is off or permission is missing."
                },
                statusIsWarning = false,
                onClick = null,
            )

            Text(
                text = "QR codes and invitation codes are pairing methods, not transports — " +
                    "they verify the peer over whichever local transport you pick.",
                fontSize = 11.sp,
                color = GhostTextMuted,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontFamily = GhostMono,
        fontSize = 10.sp,
        color = GhostTextMuted,
        modifier = Modifier.padding(start = 2.dp, top = 6.dp)
    )
}

@Preview(showBackground = true)
@Composable
fun PairingMenuScreenPreview() {
    GhostLinkTheme {
        PairingMenuScreen(
            onBack = {},
            onScanQr = {},
            onEnterCode = {},
            onShowMyInvitation = {}
        )
    }
}
