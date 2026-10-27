package com.ghostlink.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.ui.components.*
import com.ghostlink.app.ui.theme.*

@Composable
fun SettingsScreen(
    currentAlias: String = "Shadow7F3",
    selectedTheme: GhostTheme = GhostTheme.ShadowGreen,
    reducedMotion: Boolean = false,
    terminalEffects: Boolean = true,
    textSizeScale: Float = 1.0f,
    screenshotProtection: Boolean = true,
    backgroundSession: Boolean = false,
    onThemeChange: (GhostTheme) -> Unit = {},
    onReducedMotionChange: (Boolean) -> Unit = {},
    onTerminalEffectsChange: (Boolean) -> Unit = {},
    onTextSizeChange: (Float) -> Unit = {},
    onScreenshotProtectionChange: (Boolean) -> Unit = {},
    onBackgroundSessionChange: (Boolean) -> Unit = {},
    onRegenerateAlias: () -> Unit = {},
    onPanic: () -> Unit = {},
    onBack: () -> Unit,
    appVersion: String = "Version 1.0.0 (Stage 1)"
) {
    var expandedSection by remember { mutableStateOf<String?>(null) }
    var showPanicConfirm by remember { mutableStateOf(false) }

    // Panic confirmation dialog
    if (showPanicConfirm) {
        AlertDialog(
            onDismissRequest = { showPanicConfirm = false },
            containerColor = GhostCardBackground,
            title = {
                Text(
                    text = "Clear Session?",
                    fontFamily = GhostMono,
                    color = GhostError,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will immediately close any active session, clear the message list, and cancel all pending invitations.\n\nNote: Data already received by the other device cannot be erased remotely.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = GhostTextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPanicConfirm = false
                    onPanic()
                }) {
                    Text("Clear Now", color = GhostError, fontFamily = GhostMono)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicConfirm = false }) {
                    Text("Cancel", color = GhostNeonGreen, fontFamily = GhostMono)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            GhostTopBar(
                title = "Settings",
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

            // ── 1. Your Alias ──────────────────────────────────────────────────
            SettingsSectionCard(
                icon = Icons.Outlined.Person,
                title = "Your Alias",
                isExpanded = expandedSection == "alias",
                onHeaderClick = {
                    expandedSection = if (expandedSection == "alias") null else "alias"
                },
                collapsedContent = {
                    Text(
                        text = currentAlias,
                        fontFamily = GhostMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = GhostNeonGreen
                    )
                }
            ) {
                Spacer(Modifier.height(12.dp))
                GhostDivider()
                Spacer(Modifier.height(12.dp))

                GhostCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Active Alias", color = GhostTextSecondary, fontSize = 12.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = currentAlias,
                                fontFamily = GhostMono,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = GhostNeonGreen
                            )
                        }
                        IconButton(
                            onClick = onRegenerateAlias,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0F2618))
                                .border(1.dp, GhostBorderSubtle, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Regenerate alias",
                                tint = GhostNeonGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Your alias is temporary and session-scoped. It is not used for authentication and does not identify you cryptographically. Changing it during an active connection may confuse your peer.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = GhostTextMuted,
                    lineHeight = 16.sp
                )
            }

            // ── 2. Connection Preferences ──────────────────────────────────────
            SettingsSectionCard(
                icon = Icons.Outlined.Wifi,
                title = "Connection Preferences",
                subtitle = "Wi-Fi Direct, BLE, discovery",
                isExpanded = expandedSection == "connection",
                onHeaderClick = {
                    expandedSection = if (expandedSection == "connection") null else "connection"
                }
            ) {
                Spacer(Modifier.height(12.dp))
                GhostDivider()
                Spacer(Modifier.height(12.dp))

                ToggleSettingRow(
                    label = "Keep Session in Background",
                    sublabel = "Uses foreground service. Battery impact may vary by device.",
                    checked = backgroundSession,
                    onCheckedChange = onBackgroundSessionChange
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Wi-Fi Direct is the primary transport for nearby messaging. Bluetooth LE is used for device discovery. Both require physical Android devices — these features cannot be tested in an emulator.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = GhostTextMuted,
                    lineHeight = 16.sp
                )
            }

            // ── 3. Privacy & Security ──────────────────────────────────────────
            SettingsSectionCard(
                icon = Icons.Outlined.Shield,
                title = "Privacy & Security",
                subtitle = "Permissions, data control & theme",
                isExpanded = expandedSection == "privacy",
                onHeaderClick = {
                    expandedSection = if (expandedSection == "privacy") null else "privacy"
                }
            ) {
                Spacer(Modifier.height(12.dp))
                GhostDivider()
                Spacer(Modifier.height(12.dp))

                // Theme selector
                Text(
                    text = "APP THEME",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = GhostMono,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    ),
                    color = GhostTextSecondary
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ThemeOptionChip(
                        label = "Ghost Green",
                        accentColor = GhostNeonGreen,
                        isSelected = selectedTheme == GhostTheme.ShadowGreen,
                        onClick = { onThemeChange(GhostTheme.ShadowGreen) },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeOptionChip(
                        label = "Midnight Blue",
                        accentColor = GhostBlueAccent,
                        isSelected = selectedTheme == GhostTheme.QuietBlue,
                        onClick = { onThemeChange(GhostTheme.QuietBlue) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(16.dp))
                GhostDivider()
                Spacer(Modifier.height(12.dp))

                // Screenshot protection
                ToggleSettingRow(
                    label = "Screenshot Protection",
                    sublabel = "Prevents screenshots and recent apps preview (FLAG_SECURE)",
                    checked = screenshotProtection,
                    onCheckedChange = onScreenshotProtectionChange
                )

                Spacer(Modifier.height(16.dp))
                GhostDivider()
                Spacer(Modifier.height(12.dp))

                // Panic action
                Text(
                    text = "PANIC / CLEAR SESSION",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = GhostMono,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    ),
                    color = GhostTextSecondary
                )
                Spacer(Modifier.height(8.dp))
                GhostDestructiveButton(
                    text = "Clear Active Session Now",
                    onClick = { showPanicConfirm = true }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Immediately closes any active connection, clears the message list, and cancels invitations. Cannot remotely erase data received by the other device or data held by the OS, keyboards, or third-party apps.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = GhostTextMuted,
                    lineHeight = 16.sp
                )
            }

            // ── 4. About GhostLink ─────────────────────────────────────────────
            SettingsSectionCard(
                icon = Icons.Outlined.Info,
                title = "About GhostLink",
                subtitle = appVersion,
                isExpanded = expandedSection == "about",
                onHeaderClick = {
                    expandedSection = if (expandedSection == "about") null else "about"
                }
            ) {
                Spacer(Modifier.height(12.dp))
                GhostDivider()
                Spacer(Modifier.height(12.dp))

                AboutRow("Version", appVersion)
                AboutRow("Transport", "Wi-Fi Direct (local only)")
                AboutRow("Discovery", "Bluetooth LE + Wi-Fi Direct")
                AboutRow("Encryption", "AES-256-GCM (Stage 1)")
                AboutRow("Message History", "None — ephemeral only")
                AboutRow("Tracking", "None — zero analytics")
                AboutRow("Account Required", "No")
                AboutRow("Internet Required", "No — local P2P only")

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "GhostLink does not store conversations, require accounts, track users, or contact any server for local operation. All messages are held only in volatile memory and cleared on session end.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = GhostTextMuted,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ─── Private composables ──────────────────────────────────────────────────────

@Composable
private fun SettingsSectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    isExpanded: Boolean,
    onHeaderClick: () -> Unit,
    collapsedContent: (@Composable () -> Unit)? = null,
    expandedContent: @Composable ColumnScope.() -> Unit
) {
    GhostCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onHeaderClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0F2618))
                    .border(1.dp, GhostBorderSubtle, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = GhostNeonGreen,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = GhostTextPrimary
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = GhostTextSecondary
                    )
                }
            }

            if (collapsedContent != null && !isExpanded) {
                collapsedContent()
                Spacer(Modifier.width(8.dp))
            }

            Icon(
                imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = GhostTextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        if (isExpanded) {
            expandedContent()
        }
    }
}

@Composable
private fun ToggleSettingRow(
    label: String,
    sublabel: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = GhostTextPrimary
            )
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = GhostTextMuted
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GhostNeonGreen,
                checkedTrackColor = Color(0xFF0F2618),
                checkedBorderColor = GhostBorderSubtle,
                uncheckedThumbColor = GhostTextMuted,
                uncheckedTrackColor = GhostCardBackground,
                uncheckedBorderColor = GhostBorderSubtle
            )
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = GhostTextSecondary
        )
        Text(
            text = value,
            fontFamily = GhostMono,
            fontSize = 12.sp,
            color = GhostNeonGreen
        )
    }
}

@Composable
private fun ThemeOptionChip(
    label: String,
    accentColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(GhostShapeSmall)
            .background(if (isSelected) Color(0xFF0F2618) else GhostCardBackground)
            .border(
                1.dp,
                if (isSelected) accentColor else GhostBorderSubtle,
                GhostShapeSmall
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Text(
                text = label,
                fontFamily = GhostMono,
                fontSize = 12.sp,
                color = if (isSelected) GhostTextPrimary else GhostTextSecondary
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    GhostLinkTheme {
        SettingsScreen(
            currentAlias = "Shadow7F3",
            onBack = {}
        )
    }
}
