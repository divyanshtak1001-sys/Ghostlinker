package com.ghostlink.app.ui.screens.nearby

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.model.ConnectionMethod
import com.ghostlink.app.model.DeviceVerificationState
import com.ghostlink.app.model.DiscoveredDevice
import com.ghostlink.app.model.DiscoveryStatus
import com.ghostlink.app.model.GhostAlias
import com.ghostlink.app.ui.components.*
import com.ghostlink.app.ui.theme.*

@Composable
fun NearbyDevicesScreen(
    discoveryStatus: DiscoveryStatus = DiscoveryStatus.Scanning,
    errorMessage: String? = null,
    discoveredDevices: List<DiscoveredDevice> = emptyList(),
    onBack: () -> Unit,
    onStartDiscovery: () -> Unit = {},
    onStopDiscovery: () -> Unit = {},
    onSelectDevice: (DiscoveredDevice) -> Unit,
    wifiDirectReady: Boolean = true,
    wifiDirectReason: String? = null,
    bleAvailable: Boolean = true,
    activeTransport: ConnectionMethod? = null,
    hasInvitation: Boolean = true,
) {
    // No sample devices are fabricated. An empty list means nothing has been
    // discovered yet, and the screen says so instead of inventing peers.
    val displayList = discoveredDevices

    Scaffold(
        topBar = {
            GhostTopBar(
                title = "Nearby Devices",
                subtitle = "Select a device to connect.",
                onBack = onBack,
                actions = {
                    IconButton(onClick = onStartDiscovery) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh",
                            tint = GhostNeonGreen,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            )
        },
        containerColor = GhostMainBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))

            // Animated Radar View in the upper center
            RadarView(
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // Which transports are actually usable, right now, in one line.
            TransportStatusLine(
                wifiDirectReady = wifiDirectReady,
                bleAvailable = bleAvailable,
                hasInvitation = hasInvitation,
            )

            wifiDirectReason?.let { reason ->
                Text(
                    text = reason,
                    color = GhostWarning,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }

            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }

            if (discoveredDevices.isEmpty() && discoveryStatus == DiscoveryStatus.Scanning) {
                Text(
                    text = "Scanning… make sure the other device has GhostLink open and an invitation shown.",
                    color = GhostTextMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Discovered device list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(displayList, key = { it.deviceId }) { device ->
                    val aliasName = device.alias?.display ?: device.deviceName

                    GhostCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDevice(device) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GhostAvatar(size = 40.dp, color = GhostNeonGreen)

                            Spacer(Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = aliasName,
                                    fontFamily = GhostMono,
                                    fontSize = 15.sp,
                                    color = GhostTextPrimary
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TransportBadge(device.connectionMethod)
                                    Spacer(Modifier.width(10.dp))
                                    // Signal is shown only when a real BLE RSSI was measured.
                                    // Wi-Fi Direct peers carry rssi = null, so nothing is shown.
                                    device.rssi?.let { rssi ->
                                        SignalBars(rssi = rssi)
                                    }
                                }
                            }

                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = GhostTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Shows which transport the current/last session actually uses. */
@Composable
private fun TransportStatusLine(
    wifiDirectReady: Boolean,
    bleAvailable: Boolean,
    hasInvitation: Boolean,
) {
    val wifi = if (wifiDirectReady) "Wi-Fi Direct" else null
    val bt = if (bleAvailable) "Bluetooth" else null
    val available = listOfNotNull(wifi, bt)
    Text(
        text = when {
            available.isEmpty() -> "No local transport is available — check Wi-Fi/Bluetooth and permissions."
            !hasInvitation -> "${available.joinToString(" + ")} available — generate an invitation to authenticate peers."
            else -> "${available.joinToString(" + ")} active · scanning"
        },
        fontFamily = GhostMono,
        fontSize = 11.sp,
        color = if (available.isEmpty()) GhostWarning else GhostNeonGreen,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

@Composable
private fun TransportBadge(method: ConnectionMethod) {
    val (label, color) = when (method) {
        ConnectionMethod.WifiDirect -> "WI-FI DIRECT" to GhostBlueAccent
        ConnectionMethod.Bluetooth -> "BLUETOOTH" to GhostNeonGreen
        ConnectionMethod.BLE -> "BLE" to GhostNeonGreen
        ConnectionMethod.LocalNetwork -> "LAN" to GhostTextSecondary
    }
    Text(
        text = label,
        fontFamily = GhostMono,
        fontSize = 9.sp,
        color = color,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Preview(showBackground = true)
@Composable
fun NearbyDevicesScreenPreview() {
    GhostLinkTheme {
        NearbyDevicesScreen(
            onBack = {},
            onSelectDevice = {}
        )
    }
}
