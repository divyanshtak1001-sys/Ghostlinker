package com.ghostlink.app.networking

import com.ghostlink.app.model.DiscoveredDevice

/**
 * GhostTransport — the transport abstraction the UI and the ViewModel talk to.
 *
 * The whole point of this interface is requirement 10: message processing must
 * not depend on the transport. GhostLink ships one implementation today,
 * [BluetoothClassicManager] (RFCOMM), and Wi-Fi Direct can be added later by
 * writing a second implementation that satisfies this same contract — without
 * touching a single Compose screen or the chat logic in GhostViewModel.
 *
 * Implementations are responsible for:
 *   - their own discovery mechanism (Bluetooth discovery here),
 *   - their own host/client connection roles,
 *   - running every blocking call off the main thread,
 *   - releasing sockets, receivers and threads when [close] is called.
 */
interface GhostTransport {

    /** Human-readable transport name for the UI (for example "Bluetooth"). */
    val displayName: String

    /**
     * True when this transport's hardware and permissions allow it to be used
     * right now. False means the UI should say why instead of showing a
     * discoverable-looking but dead action.
     */
    fun isAvailable(): Boolean

    /**
     * Starts this device acting as the connection host (RFCOMM server).
     * The transport listens for one incoming connection and reports it through
     * [listeners].
     */
    fun startHosting()

    /** Stops hosting without tearing down the rest of the transport. */
    fun stopHosting()

    /**
     * Starts looking for nearby peers on this transport.
     * Results are reported through [listeners].
     */
    fun startDiscovery()

    /** Stops an in-progress discovery run. */
    fun stopDiscovery()

    /**
     * Connects to [device] as the client (RFCOMM socket connect).
     * The outcome is reported through [listeners].
     */
    fun connect(device: DiscoveredDevice)

    /** Closes the active connection, keeping the transport reusable. */
    fun disconnect()

    /**
     * The user confirmed that the SAS shown on both devices matched.
     * Only after this may the transport deliver messages or report [onConnected].
     * @return false when [sessionId] is not the pending session (stale id).
     */
    fun confirmVerification(sessionId: String): Boolean = false

    /** The user rejected the SAS, or the attempt was abandoned. Closes the socket. */
    fun rejectVerification(sessionId: String) {}

    /**
     * Sends [text] to the connected peer, framed by [GhostMessageCodec].
     * @return false when no connection is active.
     */
    fun sendMessage(text: String): Boolean

    /**
     * Registers the callbacks for this transport instance.
     * Kept as a single object rather than a growing list of lambda parameters so
     * the contract stays stable when more events are added.
     */
    fun setListeners(listeners: GhostTransportListeners)

    /**
     * Releases every resource held by this transport: sockets, threads, the
     * broadcast receiver and any discovery run. After [close] the instance must
     * not be reused.
     */
    fun close()
}

/**
 * Events a [GhostTransport] reports back to the ViewModel.
 * All callbacks are invoked from background threads owned by the transport;
 * the ViewModel is responsible for marshalling state updates safely (it uses a
 * MutableStateFlow, which is safe to update from any thread).
 */
interface GhostTransportListeners {

    /** The transport is now searching for peers. */
    fun onDiscovering()

    /** The host is listening and waiting for an incoming connection. */
    fun onWaitingForConnection()

    /** One or more nearby peers were found. Replaces the previous list. */
    fun onDevicesFound(devices: List<DiscoveredDevice>)

    /** A peer is being connected to / handshaking with. */
    fun onConnecting(deviceLabel: String)

    /**
     * The peer's transport encryption is established AND the peer proved possession
     * of the invitation, but the human confirmation step has not happened yet.
     *
     * The transport is **not** connected at this point: no message may be sent or
     * received until the originator of the attempt calls
     * `GhostTransport.confirmVerification(sessionId)` or `rejectVerification(sessionId)`.
     *
     * @param sessionId identifies *this* attempt. A later callback carrying a stale
     *        id must be ignored by the consumer.
     * @param sasHex short authentication string both devices must display and compare.
     * @param peerLabel display label for the peer.
     */
    fun onPeerAuthenticated(sessionId: String, sasHex: String, peerLabel: String)

    /**
     * The connection is established, authenticated and human-verified.
     * @param peerAlias the temporary alias the peer reported, or null when the
     *        peer did not send a GhostLink handshake.
     * @param peerLabel a display label to use when [peerAlias] is null.
     */
    fun onConnected(peerAlias: String?, peerLabel: String)

    /** A chat message arrived from the peer. */
    fun onMessageReceived(text: String)

    /** The connection ended. [reason] is null for a clean local disconnect. */
    fun onDisconnected(reason: String?)

    /** A user-facing error. [message] is safe to show and never contains message text. */
    fun onError(message: String)

    /**
     * Called when a verification is cancelled, rejected, timed out or belongs to a
     * session that is no longer current. The transport must already have closed its
     * socket by the time this is delivered.
     */
    fun onVerificationFailed(sessionId: String, reason: String) {}
}
