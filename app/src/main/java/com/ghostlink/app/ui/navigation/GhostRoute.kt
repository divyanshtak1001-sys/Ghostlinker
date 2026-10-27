package com.ghostlink.app.ui.navigation

// ─── Navigation Routes ────────────────────────────────────────────────────────
sealed class GhostRoute(val route: String) {

    // Top-level
    object Splash              : GhostRoute("splash")
    object Home                : GhostRoute("home")
    object Settings            : GhostRoute("settings")

    // Pairing flow (read/show the one-time code, or scan the other device's QR)
    object PairingMenu         : GhostRoute("pairing/menu")
    object ScanQr              : GhostRoute("pairing/scan")
    object EnterCode           : GhostRoute("pairing/code")
    object MyInvitation        : GhostRoute("pairing/invitation")
    object InvitationConfirmed : GhostRoute("connection/confirmed")

    // Discovery & Connection flow
    object NearbyDevices       : GhostRoute("nearby")
    object ConnectionReq       : GhostRoute("connection/request")
    object Connecting          : GhostRoute("connection/progress")
    object Connected           : GhostRoute("connection/connected")

    // Chat
    object Chat                : GhostRoute("chat")
    object ChatBlue            : GhostRoute("chat/blue")

    // Permission & Exit
    object PermissionRequest   : GhostRoute("permission/request")
    object ExitDisconnect      : GhostRoute("session/exit")
}
