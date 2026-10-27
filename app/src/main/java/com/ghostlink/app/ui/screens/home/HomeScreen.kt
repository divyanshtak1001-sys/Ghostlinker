package com.ghostlink.app.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.model.GhostAlias
import com.ghostlink.app.ui.components.*
import com.ghostlink.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    alias: GhostAlias,
    onStartPairing: () -> Unit,
    onMyInvitations: () -> Unit,
    onNearbyDevices: () -> Unit,
    onSettings: () -> Unit,
    onRegenerateAlias: () -> Unit,
    onBottomNavNavigate: (String) -> Unit = {}
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Android back button closes the drawer when it is open.
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color(0xCC020502),
        drawerContent = {
            GhostDrawerContent(
                alias = alias,
                onStartPairing = {
                    scope.launch { drawerState.close() }
                    onStartPairing()
                },
                onMyInvitations = {
                    scope.launch { drawerState.close() }
                    onMyInvitations()
                },
                onNearbyDevices = {
                    scope.launch { drawerState.close() }
                    onNearbyDevices()
                },
                onSettings = {
                    scope.launch { drawerState.close() }
                    onSettings()
                },
                onClose = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "GhostLink",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                color = GhostNeonGreen
                            )
                            Text(
                                text = "Connect • Chat • Stay Private",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp
                                ),
                                color = GhostTextSecondary
                            )
                        }
                    },
                    navigationIcon = {
                        // Hamburger — opens drawer. Pressed state gives visual feedback.
                        HamburgerButton(
                            onClick = { scope.launch { drawerState.open() } }
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = onSettings,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Settings" }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = null,
                                tint = GhostTextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = GhostMainBackground,
                        titleContentColor = GhostNeonGreen
                    )
                )
            },
            bottomBar = {
                GhostBottomNav(
                    selectedRoute = "home",
                    onNavigate = onBottomNavNavigate
                )
            },
            containerColor = GhostMainBackground
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Alias Card ────────────────────────────────────────────────────
                GhostCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GhostAvatar(size = 46.dp, color = GhostNeonGreen)

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Your Alias",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = GhostTextSecondary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = alias.display,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = GhostMono,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                ),
                                color = GhostTextPrimary
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(GhostSuccess)
                                )
                                Text(
                                    text = "Online",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontFamily = GhostMono
                                    ),
                                    color = GhostSuccess
                                )
                            }
                        }

                        // 48dp touch target for the regenerate button
                        IconButton(
                            onClick = onRegenerateAlias,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Regenerate alias" }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF0F2618))
                                    .border(1.dp, GhostBorderSubtle, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = null,
                                    tint = GhostNeonGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // ── Menu Cards ────────────────────────────────────────────────────
                GhostMenuCard(
                    icon = Icons.Outlined.QrCodeScanner,
                    title = "Pair with Someone",
                    subtitle = "Scan, enter code or share link",
                    onClick = onStartPairing
                )

                GhostMenuCard(
                    icon = Icons.Outlined.Key,
                    title = "Your Invitations",
                    subtitle = "View and manage your codes",
                    onClick = onMyInvitations
                )

                GhostMenuCard(
                    icon = Icons.Outlined.Wifi,
                    title = "Nearby Devices",
                    subtitle = "Discover GhostLink devices",
                    onClick = onNearbyDevices
                )

                GhostMenuCard(
                    icon = Icons.Outlined.Security,
                    title = "Settings",
                    subtitle = "Privacy, security and more",
                    onClick = onSettings
                )
            }
        }
    }
}

/**
 * Hamburger button with a proper 48dp touch target and a visible pressed state
 * (the icon brightens to full white when pressed).
 */
@Composable
private fun HamburgerButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val iconTint by animateColorAsState(
        targetValue = if (isPressed) Color.White else GhostNeonGreen,
        animationSpec = tween(80),
        label = "hamburger_tint"
    )

    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(48.dp)
            .semantics {
                contentDescription = "Open navigation menu"
                role = Role.Button
            }
    ) {
        Icon(
            imageVector = Icons.Outlined.Menu,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Side drawer content shown when the hamburger is tapped.
 * Provides quick access to all top-level destinations.
 */
@Composable
private fun GhostDrawerContent(
    alias: GhostAlias,
    onStartPairing: () -> Unit,
    onMyInvitations: () -> Unit,
    onNearbyDevices: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier.width(280.dp),
        drawerContainerColor = GhostCardBackground,
        drawerContentColor = GhostTextPrimary
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(GhostMainBackground)
                .padding(24.dp)
        ) {
            Column {
                GhostAvatar(size = 48.dp, color = GhostNeonGreen)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = alias.display,
                    fontFamily = GhostMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = GhostNeonGreen
                )
                Text(
                    text = "GhostLink",
                    fontSize = 12.sp,
                    color = GhostTextSecondary
                )
            }
        }

        GhostDivider()
        Spacer(Modifier.height(8.dp))

        DrawerNavItem(
            icon = Icons.Outlined.QrCodeScanner,
            label = "Pair with Someone",
            onClick = onStartPairing
        )
        DrawerNavItem(
            icon = Icons.Outlined.Key,
            label = "Your Invitations",
            onClick = onMyInvitations
        )
        DrawerNavItem(
            icon = Icons.Outlined.Wifi,
            label = "Nearby Devices",
            onClick = onNearbyDevices
        )
        DrawerNavItem(
            icon = Icons.Outlined.Settings,
            label = "Settings",
            onClick = onSettings
        )

        Spacer(Modifier.weight(1f))

        GhostDivider()

        // Close button at the bottom
        DrawerNavItem(
            icon = Icons.Outlined.Close,
            label = "Close menu",
            onClick = onClose
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DrawerNavItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = GhostNeonGreen,
                modifier = Modifier.size(20.dp)
            )
        },
        label = {
            Text(
                text = label,
                fontFamily = GhostMono,
                fontSize = 14.sp,
                color = GhostTextPrimary
            )
        },
        selected = false,
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .height(52.dp),       // ensures ≥48dp touch target
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = Color.Transparent,
            selectedContainerColor = Color(0xFF0F2618)
        )
    )
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    GhostLinkTheme {
        HomeScreen(
            alias = GhostAlias("Shadow7F3"),
            onStartPairing = {},
            onMyInvitations = {},
            onNearbyDevices = {},
            onSettings = {},
            onRegenerateAlias = {}
        )
    }
}
