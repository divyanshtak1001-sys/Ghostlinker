package com.ghostlink.app.ui.screens.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.ui.components.*
import com.ghostlink.app.ui.theme.*

@Composable
fun InvitationConfirmedScreen(
    peerAlias: String,
    pairingCode: String,
    connectionCode: String,
    peerRssi: Int? = null,
    onProceed: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            GhostTopBar(
                title = "Invitation Confirmed",
                onBack = onBack
            )
        },
        containerColor = GhostMainBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            // Green shield-check icon
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0F2618))
                    .border(1.5.dp, GhostNeonGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = GhostNeonGreen,
                    modifier = Modifier.size(36.dp)
                )
            }

            Text(
                text = "Valid invitation found!",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = GhostNeonGreen,
                textAlign = TextAlign.Center
            )

            Text(
                text = "You're connecting with:",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = GhostTextSecondary,
                textAlign = TextAlign.Center
            )

            // Peer info card matching image
            GhostCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GhostAvatar(size = 46.dp, color = GhostNeonGreen)

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peerAlias,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = GhostMono,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            color = GhostTextPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        // Real signal only; nothing is shown when no RSSI was measured.
                        peerRssi?.let { rssi -> SignalBars(rssi = rssi) }
                    }
                }

                Spacer(Modifier.height(16.dp))
                GhostDivider()
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Pairing code:",
                        fontFamily = GhostMono,
                        fontSize = 12.sp,
                        color = GhostTextSecondary
                    )
                    Text(
                        text = pairingCode,
                        fontFamily = GhostMono,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GhostTextPrimary
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Connection code:",
                        fontFamily = GhostMono,
                        fontSize = 12.sp,
                        color = GhostTextSecondary
                    )
                    Text(
                        text = connectionCode,
                        fontFamily = GhostMono,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GhostNeonGreen
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            GhostPrimaryButton(
                text = "Proceed",
                onClick = onProceed
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun InvitationConfirmedScreenPreview() {
    GhostLinkTheme {
        InvitationConfirmedScreen(
            peerAlias = "Mystic_2A9",
            pairingCode = "7K9M-4Q2X",
            connectionCode = "3F78-921Q",
            onProceed = {},
            onBack = {}
        )
    }
}
