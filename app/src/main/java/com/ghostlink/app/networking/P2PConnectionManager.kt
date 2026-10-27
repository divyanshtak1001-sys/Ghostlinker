package com.ghostlink.app.networking

/**
 * @deprecated Superseded by [WifiDirectTransport] (Wi-Fi Direct) and
 *             [BluetoothClassicManager] (Bluetooth RFCOMM).
 *
 * The Stage 1 class that lived here is documented in
 * `P2PConnectionManagerLegacyRemoved.txt`. It has been reduced to this marker so
 * there is exactly one connection state machine and one crypto path in the app.
 *
 * Defects removed with it:
 *   1. The session key was `SHA-256(invitationToken)` on both sides, so the
 *      "handshake" compared a constant string and proved nothing: any peer that
 *      reached the socket was accepted, and the token was never used for auth.
 *   2. The receiver loop and `receiveRaw()` each wrapped `socket.getInputStream()`
 *      in a new `DataInputStream`, so buffered bytes were consumed by the wrong
 *      wrapper and messages were lost.
 *   3. The key was derived *after* the unauthenticated handshake bytes.
 *   4. No sequence numbers: a captured frame could be replayed within a session
 *      and was accepted, because each frame's IV was attacker-chosen.
 *   5. `SecureRandom` was allocated per message (correct but wasteful).
 *
 * All of the above are addressed by [GhostSessionProtocol]: both sides contribute
 * 256 random bits, the key is HKDF-derived with the invitation as salt, the peer
 * proves key possession (HMAC key confirmation + first authenticated frame), and
 * every frame is bound to a monotonic sequence number inside the GCM tag.
 */
internal object P2PConnectionManager {
    /** True when the replacement authenticated transport is in use. */
    const val REPLACED_BY = "WifiDirectTransport"
}
