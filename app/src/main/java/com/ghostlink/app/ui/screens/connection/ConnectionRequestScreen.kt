package com.ghostlink.app.ui.screens.connection

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.model.GhostAlias
import com.ghostlink.app.model.PeerInfo
import com.ghostlink.app.ui.components.*
import com.ghostlink.app.ui.theme.*

@Composable
fun ConnectionRequestScreen(
    peer: PeerInfo?,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onBack: () -> Unit
) {
    val peerAlias = peer?.alias?.display ?: "Lunar_8D4"

    Scaffold(
        topBar = {
            GhostTopBar(
                title = "Connection Request",
                onBack = onBack
            )
        },
        containerColor = GhostMainBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // Large ghost avatar
            GhostAvatar(size = 96.dp, color = GhostNeonGreen)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = peerAlias,
                    fontFamily = GhostMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = GhostTextPrimary
                )
                Text(
                    text = "wants to connect with you.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = GhostTextSecondary
                )
            }

            Spacer(Modifier.height(10.dp))

            // Card with details
            GhostCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Temporary Alias:",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = GhostTextSecondary
                    )
                    Text(
                        text = peerAlias,
                        fontFamily = GhostMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GhostTextPrimary
                    )
                }

                Spacer(Modifier.height(14.dp))
                GhostDivider()
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Signal Strength:",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = GhostTextSecondary
                    )
                    // Shown only when a real RSSI was measured for this peer.
                    peer?.rssi?.let { rssi -> SignalBars(rssi = rssi) }
                }
            }

            Spacer(Modifier.weight(1f))

            // Two buttons: Reject and Accept
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GhostDestructiveButton(
                    text = "Reject",
                    onClick = onReject,
                    modifier = Modifier.weight(1f)
                )

                GhostPrimaryButton(
                    text = "Accept",
                    onClick = onAccept,
                    leadingIcon = Icons.Outlined.Check,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ConnectionRequestScreenPreview() {
    GhostLinkTheme {
        ConnectionRequestScreen(
            peer = PeerInfo(alias = GhostAlias("Lunar_8D4")),
            onAccept = {},
            onReject = {},
            onBack = {}
        )
    }
}
