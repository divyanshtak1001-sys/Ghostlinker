package com.ghostlink.app.model

import java.util.UUID

// ─── Session & Identity ───────────────────────────────────────────────────────

data class GhostAlias(
    val display: String,          // e.g. "Shadow_7F3"
    val sessionId: String = UUID.randomUUID().toString()
)

/**
 * A typed wrapper around the human-readable invitation code string.
 * Keeps format enforcement in one place: e.g. "7K9M-4Q2X-AB3C"
 */
data class InvitationCode(val raw: String) {
    /** One-time code the user reads out. QR/share links were removed from GhostLink. */
    val display: String get() = raw.uppercase().trim()

    companion object {
        /** Validates that the code is 8–12 alphanumeric chars (ignoring dashes) */
        fun isValidFormat(input: String): Boolean {
            val stripped = input.replace("-", "").uppercase()
            return stripped.length in 8..12 && stripped.all { it.isLetterOrDigit() }
        }
    }
}

data class PeerInfo(
    val alias: GhostAlias,
    val connectionMethod: ConnectionMethod = ConnectionMethod.WifiDirect,
    val isVerified: Boolean = false,
    val rssi: Int? = null         // null when platform cannot supply real value
)

// ─── Connection ───────────────────────────────────────────────────────────────

enum class ConnectionMethod { WifiDirect, BLE, LocalNetwork, Bluetooth }

enum class ConnectionState {
    Idle,
    Discovering,

    /** The host is listening on the RFCOMM server socket for an incoming peer. */
    WaitingForConnection,
    Connecting,
    VerifyingPeer,
    EstablishingSession,
    Connected,
    Rejected,
    Failed,
    Disconnected
}

// ─── Invitation ───────────────────────────────────────────────────────────────

data class Invitation(
    /** Typed invitation code wrapper */
    val code: InvitationCode,
    /**
     * Cryptographically random token used for peer matching and as the shared
     * secret that authenticates the session. 256 bits, base64url, 43 chars.
     */
    val token: String = "unset",
    val creatorAlias: GhostAlias? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Default: 10-minute lifetime as per design spec */
    val expiresAt: Long = createdAt + (10L * 60L * 1000L),
    /**
     * Set once a session was successfully established with this invitation.
     *
     * Deliberately NOT set by a failed connection attempt: a network failure
     * before authentication must leave a still-valid invitation retryable.
     * A token is consumed exactly once, when the handshake completes.
     */
    val consumedAt: Long? = null,
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt
    val remainingMs: Long  get() = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
    val remainingSec: Int  get() = (remainingMs / 1000L).toInt()

    /** Usable for a *new* connection attempt (expired or consumed invitations are not). */
    val isUsable: Boolean get() = consumedAt == null && !isExpired

    companion object {
        /** Sandbox-local invitation lifetime, matching the 10-minute design spec. */
        const val LIFETIME_MS = 10L * 60L * 1000L
    }
}

// ─── Messages ─────────────────────────────────────────────────────────────────

enum class MessageOrigin { Local, Remote }

enum class MessageStatus { Sending, Sent, Delivered, Failed }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val origin: MessageOrigin,
    /** Optional: alias of the sender for display in the bubble */
    val senderAlias: GhostAlias? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.Sent
)

// ─── Nearby Devices ──────────────────────────────────────────────────────────

enum class DeviceVerificationState { Unverified, Verifying, GhostLinkPeer, NotGhostLink }

data class DiscoveredDevice(
    val deviceId: String,
    val deviceName: String,
    val alias: GhostAlias? = null,       // null until handshake
    val rssi: Int? = null,
    val verificationState: DeviceVerificationState = DeviceVerificationState.Unverified,
    val macAddress: String? = null,      // Hardware address used to open the transport connection
    /**
     * The transport this device was found on.
     * Kept on the model so a device discovered over Bluetooth is connected over
     * Bluetooth, and a Wi-Fi Direct peer (added later) over Wi-Fi Direct.
     */
    val connectionMethod: ConnectionMethod = ConnectionMethod.WifiDirect
)

// ─── Nearby Discovery State ───────────────────────────────────────────────────

enum class DiscoveryStatus { Idle, Scanning, TimedOut, PermissionDenied, Error }

// ─── Security ─────────────────────────────────────────────────────────────────

/**
 * Opaque holder for the active session's security fingerprint.
 * The 6-word safety code is derived from the shared session key material
 * so both parties can visually verify they are talking to the same peer.
 *
 * NOTE: This is a holder class for stage 1. Actual key derivation is wired
 * in SecureSession.kt.
 */
data class SessionFingerprint(
    /** Short, human-readable safety code derived from shared key */
    val safetyCode: String,
    /** Full hex of the fingerprint for display in advanced settings */
    val fullHex: String
)
