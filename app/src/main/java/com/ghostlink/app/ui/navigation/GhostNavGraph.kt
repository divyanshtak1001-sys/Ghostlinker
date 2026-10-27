package com.ghostlink.app.ui.navigation

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.ghostlink.app.model.ConnectionMethod
import com.ghostlink.app.model.GhostAlias
import com.ghostlink.app.model.PeerInfo
import com.ghostlink.app.ui.screens.chat.ChatScreen
import com.ghostlink.app.ui.screens.connection.*
import com.ghostlink.app.ui.screens.home.HomeScreen
import com.ghostlink.app.ui.screens.nearby.NearbyDevicesScreen
import com.ghostlink.app.ui.screens.pairing.*
import com.ghostlink.app.ui.screens.pairing.QrScanOutcome
import com.ghostlink.app.ui.screens.pairing.parseInvitationQr
import com.ghostlink.app.ui.screens.settings.SettingsScreen
import com.ghostlink.app.ui.screens.splash.SplashScreen
import com.ghostlink.app.ui.theme.GhostTheme
import com.ghostlink.app.viewmodel.ConnectionProgressStep
import com.ghostlink.app.viewmodel.GhostViewModel

@Composable
fun GhostNavGraph(
    navController: NavHostController,
    vm: GhostViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.connectionState, uiState.currentPeer) {
        val activeRoute = navController.currentDestination?.route
        if (uiState.connectionState == com.ghostlink.app.model.ConnectionState.Connected &&
            uiState.currentPeer?.connectionMethod == ConnectionMethod.Bluetooth &&
            activeRoute != GhostRoute.Connected.route &&
            activeRoute != GhostRoute.Connecting.route
        ) {
            navController.navigate(GhostRoute.Connected.route)
        }
    }
    LaunchedEffect(uiState.connectionState) {
        val activeRoute = navController.currentDestination?.route
        if (uiState.connectionState == com.ghostlink.app.model.ConnectionState.Disconnected &&
            activeRoute != null && activeRoute in setOf(
                GhostRoute.Connected.route,
                GhostRoute.Chat.route,
                GhostRoute.ChatBlue.route
            )
        ) {
            navController.navigate(GhostRoute.Home.route) {
                popUpTo(GhostRoute.Home.route) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = GhostRoute.Splash.route,
        // Smooth cross-fade between screens; enter/exit tweens are 0 when the
        // user has reduced motion enabled, which makes the transition instant.
        enterTransition = {
            fadeIn(animationSpec = tween(if (uiState.reducedMotion) 0 else 320))
        },
        exitTransition = {
            fadeOut(animationSpec = tween(if (uiState.reducedMotion) 0 else 220))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(if (uiState.reducedMotion) 0 else 320))
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(if (uiState.reducedMotion) 0 else 220))
        }
    ) {

        // ── 1) Splash Screen ──────────────────────────────────────────────────
        composable(GhostRoute.Splash.route) {
            SplashScreen(
                reducedMotion = uiState.reducedMotion,
                onComplete = {
                    navController.navigate(GhostRoute.Home.route) {
                        popUpTo(GhostRoute.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        // ── 2) Home Screen ────────────────────────────────────────────────────
        composable(GhostRoute.Home.route) {
            HomeScreen(
                alias = uiState.localAlias,
                onStartPairing = { navController.navigate(GhostRoute.PairingMenu.route) },
                onMyInvitations = {
                    vm.generateInvitation()
                    navController.navigate(GhostRoute.MyInvitation.route)
                },
                onNearbyDevices = { navController.navigate(GhostRoute.NearbyDevices.route) },
                onSettings = { navController.navigate(GhostRoute.Settings.route) },
                onRegenerateAlias = vm::regenerateAlias,
                onBottomNavNavigate = { route ->
                    when (route) {
                        "home" -> { /* Already home */ }
                        "chats" -> { navController.navigate(GhostRoute.Chat.route) }
                        "devices" -> { navController.navigate(GhostRoute.NearbyDevices.route) }
                        "profile" -> { navController.navigate(GhostRoute.Settings.route) }
                    }
                }
            )
        }

        // ── 3) Pairing Menu ───────────────────────────────────────────────────
        composable(GhostRoute.PairingMenu.route) {
            // Re-evaluate radio/permission state each time the menu is opened so
            // "Wi-Fi Direct: Wi-Fi is turned off" cannot go stale.
            LaunchedEffect(Unit) {
                vm.refreshTransportCapabilities()
                vm.prepareInvitationIfMissing()
            }
            PairingMenuScreen(
                onBack = { navController.popBackStack() },
                onScanQr = { navController.navigate(GhostRoute.ScanQr.route) },
                onEnterCode = { navController.navigate(GhostRoute.EnterCode.route) },
                onShowMyInvitation = { navController.navigate(GhostRoute.MyInvitation.route) },
                onNearbyDirect = { navController.navigate(GhostRoute.NearbyDevices.route) },
                wifiDirectReady = uiState.wifiDirectReady,
                wifiDirectReason = uiState.wifiDirectUnavailableReason,
                bleAvailable = uiState.bleAvailable,
                hasInvitation = uiState.currentInvitation?.isUsable == true,
            )
        }


        // ── 3b) Scan QR code (CameraX + ML Kit, on-device) ──────────
        composable(GhostRoute.ScanQr.route) {
            // Re-keying restarts decoding after a bad / expired / foreign QR code.
            var scanKey by remember { mutableIntStateOf(0) }
            QrScannerScreen(
                scanKey = scanKey,
                statusMessage = uiState.invitationInputError,
                onQrDecoded = { raw ->
                    when (val outcome = parseInvitationQr(raw)) {
                        is QrScanOutcome.Success -> {
                            vm.applyScannedInvitation(outcome.token, outcome.expiresAt)
                            navController.navigate(GhostRoute.InvitationConfirmed.route)
                        }
                        is QrScanOutcome.Invalid -> {
                            vm.setInvitationInputError(outcome.reason)
                            scanKey++
                        }
                        is QrScanOutcome.NotGhostLink -> {
                            vm.setInvitationInputError("That QR code is not a GhostLink invitation.")
                            scanKey++
                        }
                    }
                },
                onBack = {
                    vm.setInvitationInputError(null)
                    navController.popBackStack()
                }
            )
        }


        // ── 4) Show My Invitation ─────────────────────────────────────────────
        composable(GhostRoute.MyInvitation.route) {
            MyInvitationScreen(
                invitation = uiState.currentInvitation,
                onBack = { navController.popBackStack() },
                onRegenerate = vm::regenerateInvitation
            )
        }


        // ── 5) Enter Invitation Code ──────────────────────────────────────────
        composable(GhostRoute.EnterCode.route) {
            EnterCodeScreen(
                codeInput = uiState.invitationCodeInput,
                onCodeChange = vm::updateInvitationCodeInput,
                isLoading = uiState.isValidatingCode,
                errorMessage = uiState.invitationInputError,
                onBack = { navController.popBackStack() },
                onScanQr = {
                    vm.setInvitationInputError(null)
                    navController.navigate(GhostRoute.ScanQr.route)
                },
                onContinue = {
                    vm.validateAndSubmitCode {
                        navController.navigate(GhostRoute.InvitationConfirmed.route)
                    }
                }
            )
        }


        // ── 6) Invitation Confirmed ───────────────────────────────────────────
        composable(GhostRoute.InvitationConfirmed.route) {
            InvitationConfirmedScreen(
                peerAlias = uiState.selectedDevice?.alias?.display
                    ?: uiState.selectedDevice?.deviceName
                    ?: "Unknown peer",
                pairingCode = uiState.invitationCodeInput,
                connectionCode = uiState.invitationCodeInput,
                peerRssi = uiState.selectedDevice?.rssi,
                // A typed/verified invitation sets no peer. The old code navigated to
                // "Connecting" anyway and then failed silently; now it sends the user
                // to pick the nearby device that holds this invitation.
                onProceed = {
                    if (uiState.selectedDevice == null) {
                        navController.navigate(GhostRoute.NearbyDevices.route)
                    } else {
                        navController.navigate(GhostRoute.Connecting.route)
                        vm.acceptConnectionRequest {
                            navController.navigate(GhostRoute.Connected.route) {
                                popUpTo(GhostRoute.InvitationConfirmed.route) { inclusive = true }
                            }
                        }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ── 7) Nearby Devices ─────────────────────────────────────────────────
        composable(GhostRoute.NearbyDevices.route) {
            LaunchedEffect(Unit) {
                vm.prepareInvitationIfMissing()
                vm.startNearbyDiscovery()
                if (vm.isBluetoothTransportAvailable()) {
                    context.startActivity(
                        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                    )
                }
            }
            NearbyDevicesScreen(
                discoveryStatus = uiState.discoveryStatus,
                errorMessage = uiState.discoveryError,
                discoveredDevices = uiState.discoveredDevices,
                onBack = {
                    vm.stopDiscovery()
                    vm.stopBluetoothHosting()
                    navController.popBackStack()
                },
                // A rescan must fully restart every transport, not append to a
                // half-cancelled previous run.
                onStartDiscovery = vm::startNearbyDiscovery,
                onStopDiscovery = vm::stopDiscovery,
                wifiDirectReady = uiState.wifiDirectReady,
                wifiDirectReason = uiState.wifiDirectUnavailableReason,
                bleAvailable = uiState.bleAvailable,
                activeTransport = uiState.activeTransport,
                hasInvitation = uiState.currentInvitation?.isUsable == true,
                onSelectDevice = { device ->
                    vm.selectDevice(device)
                    navController.navigate(GhostRoute.ConnectionReq.route)
                }
            )
        }

        // ── 8) Connection Request ─────────────────────────────────────────────
        composable(GhostRoute.ConnectionReq.route) {
            val peer = uiState.selectedDevice?.let { device ->
                PeerInfo(
                    alias = device.alias ?: GhostAlias(device.deviceName),
                    connectionMethod = device.connectionMethod,
                    isVerified = device.alias != null
                )
            } ?: PeerInfo(
                // No peer has been identified yet: say so rather than inventing an alias.
                alias = GhostAlias("Unknown peer"),
                connectionMethod = ConnectionMethod.Bluetooth
            )

            ConnectionRequestScreen(
                peer = peer,
                onAccept = {
                    navController.navigate(GhostRoute.Connecting.route)
                    vm.acceptConnectionRequest {
                        navController.navigate(GhostRoute.Connected.route) {
                            popUpTo(GhostRoute.ConnectionReq.route) { inclusive = true }
                        }
                    }
                },
                onReject = {
                    vm.rejectConnection()
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ── Connecting (Progress) ─────────────────────────────────────────────
        composable(GhostRoute.Connecting.route) {
            ConnectionProgressScreen(
                currentStep = uiState.connectionProgress,
                peerAlias = uiState.currentPeer?.alias?.display
                    ?: uiState.selectedDevice?.alias?.display
                    ?: uiState.selectedDevice?.deviceName
                    ?: "Unknown peer",
                errorMessage = uiState.connectionError,
                // Shown only while the transport is waiting for the user's check.
                verificationCode = uiState.sessionSas
                    ?.takeIf { uiState.connectionProgress == ConnectionProgressStep.VerifyingPeer },
                onConfirmVerification = vm::confirmSessionVerification,
                onRejectVerification = vm::rejectSessionVerification,
                onCancel = {
                    // Cancelling during verification must fail closed and drop the peer.
                    vm.rejectSessionVerification()
                    vm.endSession()
                    navController.navigate(GhostRoute.Home.route) {
                        popUpTo(GhostRoute.Home.route) { inclusive = false }
                    }
                }
            )
        }

        // ── 9) Connected ──────────────────────────────────────────────────────
        composable(GhostRoute.Connected.route) {
            val peer = uiState.currentPeer ?: PeerInfo(alias = GhostAlias("Ghost Peer"))
            ConnectedScreen(
                peer = peer,
                onStartChat = {
                    if (uiState.selectedTheme == GhostTheme.QuietBlue) {
                        navController.navigate(GhostRoute.ChatBlue.route)
                    } else {
                        navController.navigate(GhostRoute.Chat.route)
                    }
                },
                onDisconnect = {
                    navController.navigate(GhostRoute.ExitDisconnect.route)
                }
            )
        }

        // ── 10) Chat (Terminal Style - Green) ─────────────────────────────────
        composable(GhostRoute.Chat.route) {
            val peer = uiState.currentPeer ?: PeerInfo(alias = GhostAlias("Ghost Peer"))
            ChatScreen(
                peer = peer,
                messages = uiState.messages,
                draft = uiState.draftMessage,
                onDraftChange = vm::updateDraft,
                onSend = vm::sendMessage,
                onDisconnect = {
                    navController.navigate(GhostRoute.ExitDisconnect.route)
                },
                localAlias = uiState.localAlias.display,
                isBlueTheme = false
            )
        }

        // ── 11) Chat (Blue Optional Theme) ────────────────────────────────────
        composable(GhostRoute.ChatBlue.route) {
            val peer = uiState.currentPeer ?: PeerInfo(alias = GhostAlias("Ghost Peer"))
            ChatScreen(
                peer = peer,
                messages = uiState.messages,
                draft = uiState.draftMessage,
                onDraftChange = vm::updateDraft,
                onSend = vm::sendMessage,
                onDisconnect = {
                    navController.navigate(GhostRoute.ExitDisconnect.route)
                },
                localAlias = uiState.localAlias.display,
                isBlueTheme = true
            )
        }

        // ── 12) Settings ──────────────────────────────────────────────────────
        composable(GhostRoute.Settings.route) {
            SettingsScreen(
                currentAlias = uiState.localAlias.display,
                selectedTheme = uiState.selectedTheme,
                reducedMotion = uiState.reducedMotion,
                terminalEffects = uiState.terminalEffects,
                textSizeScale = uiState.textSizeScale,
                onThemeChange = vm::setTheme,
                onReducedMotionChange = vm::setReducedMotion,
                onTerminalEffectsChange = vm::setTerminalEffects,
                onTextSizeChange = vm::setTextSizeScale,
                screenshotProtection = uiState.screenshotProtection,
                backgroundSession = uiState.backgroundSessionEnabled,
                onScreenshotProtectionChange = vm::setScreenshotProtection,
                onBackgroundSessionChange = vm::setBackgroundSession,
                onRegenerateAlias = vm::regenerateAlias,
                onPanic = {
                    vm.panicClear()
                    navController.navigate(GhostRoute.Home.route) {
                        popUpTo(GhostRoute.Home.route) { inclusive = false }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ── 13) Permission Request ────────────────────────────────────────────
        composable(GhostRoute.PermissionRequest.route) {
            PermissionRequestScreen(
                onAllowWhileUsing = {
                    navController.navigate(GhostRoute.Home.route) {
                        popUpTo(GhostRoute.PermissionRequest.route) { inclusive = true }
                    }
                },
                onAllowOnlyThisTime = {
                    navController.navigate(GhostRoute.Home.route) {
                        popUpTo(GhostRoute.PermissionRequest.route) { inclusive = true }
                    }
                },
                onDontAllow = {
                    navController.popBackStack()
                }
            )
        }

        // ── 14) Exit / Disconnect ─────────────────────────────────────────────
        composable(GhostRoute.ExitDisconnect.route) {
            ExitDisconnectScreen(
                locationStatus = "Enabled",
                connectionStatus = uiState.currentPeer?.let {
                    if (it.connectionMethod == ConnectionMethod.Bluetooth) "Bluetooth" else "Connected"
                } ?: "Idle",
                batteryStatus = "Optimized",
                onDisconnect = {
                    vm.disconnect()
                    navController.navigate(GhostRoute.Home.route) {
                        popUpTo(GhostRoute.Home.route) { inclusive = true }
                    }
                },
                onBackToHome = {
                    navController.navigate(GhostRoute.Home.route) {
                        popUpTo(GhostRoute.Home.route) { inclusive = false }
                    }
                }
            )
        }
    }
}
