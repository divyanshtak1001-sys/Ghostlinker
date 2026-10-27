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
import com.ghostlink.app.ui.components.*
import com.ghostlink.app.ui.theme.*

@Composable
fun ExitDisconnectScreen(
    locationStatus: String = "Enabled",
    connectionStatus: String = "Connected",
    batteryStatus: String = "Optimized",
    onDisconnect: () -> Unit,
    onBackToHome: () -> Unit
) {
    Scaffold(
        topBar = {
            GhostTopBar(
                title = "Exit / Disconnect",
                onBack = onBackToHome
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
            Spacer(Modifier.height(16.dp))

            // Ghost Logo
            GhostLogo(size = 80.dp, strokeColor = GhostNeonGreen)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "GhostLink",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    ),
                    color = GhostNeonGreen
                )
                Text(
                    text = "Small steps. Big privacy.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = GhostTextSecondary
                )
            }

            Spacer(Modifier.height(8.dp))

            // Status rows card
            GhostCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Location",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = GhostTextSecondary
                    )
                    Text(
                        text = locationStatus,
                        fontFamily = GhostMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GhostSuccess
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
                        text = "Connection",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = GhostTextSecondary
                    )
                    Text(
                        text = connectionStatus,
                        fontFamily = GhostMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GhostSuccess
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
                        text = "Battery",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = GhostTextSecondary
                    )
                    Text(
                        text = batteryStatus,
                        fontFamily = GhostMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GhostSuccess
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Two buttons: Disconnect & Back to Home
            GhostDestructiveButton(
                text = "Disconnect",
                onClick = onDisconnect
            )

            GhostOutlinedButton(
                text = "Back to Home",
                onClick = onBackToHome
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ExitDisconnectScreenPreview() {
    GhostLinkTheme {
        ExitDisconnectScreen(
            locationStatus = "Enabled",
            connectionStatus = "Connected",
            batteryStatus = "Optimized",
            onDisconnect = {},
            onBackToHome = {}
        )
    }
}
