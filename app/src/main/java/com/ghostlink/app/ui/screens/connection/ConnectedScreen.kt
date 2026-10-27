package com.ghostlink.app.ui.screens.connection

import androidx.compose.foundation.layout.*
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
fun ConnectedScreen(
    peer: PeerInfo,
    onStartChat: () -> Unit,
    onDisconnect: () -> Unit = {}
) {
    val peerAlias = peer.alias.display

    Scaffold(
        topBar = {
            GhostTopBar(
                title = "Connected",
                onBack = null
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

            // Glowing Ghost
            GhostLogo(size = 90.dp, strokeColor = GhostNeonGreen)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Connected!",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    ),
                    color = GhostNeonGreen
                )
                Text(
                    text = "You are now securely connected with $peerAlias.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = GhostTextSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(10.dp))

            // Info rows card matching image
            GhostCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Temporary Alias",
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
                        text = "Signal Strength",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = GhostTextSecondary
                    )
                    // Shown only when a real RSSI was measured for this peer.
                    peer.rssi?.let { rssi -> SignalBars(rssi = rssi) }
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
                        text = "Connection Type",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = GhostTextSecondary
                    )
                    Text(
                        text = "Wi-Fi Direct",
                        fontFamily = GhostMono,
                        fontSize = 13.sp,
                        color = GhostNeonGreen
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            GhostPrimaryButton(
                text = "Start Chat",
                onClick = onStartChat
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ConnectedScreenPreview() {
    GhostLinkTheme {
        ConnectedScreen(
            peer = PeerInfo(alias = GhostAlias("Lunar_8D4")),
            onStartChat = {},
            onDisconnect = {}
        )
    }
}
