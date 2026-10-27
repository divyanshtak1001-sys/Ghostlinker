package com.ghostlink.app.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghostlink.app.discovery.BluetoothLeManager
import com.ghostlink.app.discovery.WifiDirectManager
import com.ghostlink.app.model.*
import com.ghostlink.app.networking.BluetoothClassicManager
import com.ghostlink.app.networking.GhostTransportListeners
import com.ghostlink.app.networking.WifiDirectTransport
import com.ghostlink.app.permissions.ConnectivityPermissions
import com.ghostlink.app.service.GhostLinkService
import com.ghostlink.app.util.PerfTrace
import com.ghostlink.app.ui.theme.GhostTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom
import kotlin.random.Random

// ─── UI State ─────────────────────────────────────────────────────────────────
data class GhostUiState(
    val localAlias: GhostAlias = GhostAlias(generateAlias()),
    val selectedTheme: GhostTheme = GhostTheme.ShadowGreen,
    val reducedMotion: Boolean = false,
    val terminalEffects: Boolean = true,
    val textSizeScale: Float = 1f,

    // Connection
    val connectionState: ConnectionState = ConnectionState.Idle,
    val currentPeer: PeerInfo? = null,
    val connectionProgress: ConnectionProgressStep = ConnectionProgressStep.Idle,
    val connectionError: String? = null,

    // Invitation
    val currentInvitation: Invitation? = null,
    val invitationCodeInput: String = "",
    val invitationInputError: String? = null,
    val isValidatingCode: Boolean = false,

    // Nearby
    val discoveryStatus: DiscoveryStatus = DiscoveryStatus.Idle,
    val discoveryError: String? = null,
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val selectedDevice: DiscoveredDevice? = null,

    // Chat — volatile in-memory only, never persisted
    val messages: List<ChatMessage> = emptyList(),
    val draftMessage: String = "",

    // Network availability indicators
    val wifiDirectAvailable: Boolean = true,
    val bleAvailable: Boolean = true,

    /** True when the device hardware supports Wi-Fi Direct (independent of permissions). */
    val wifiDirectSupported: Boolean = false,

    /** True when Wi-Fi Direct is permitted and ready to be used right now. */
    val wifiDirectReady: Boolean = false,

    /** Why Wi-Fi Direct cannot be used, or null when it can. */
    val wifiDirectUnavailableReason: String? = null,

    /** The transport the current/last session actually uses (shown to the user). */
    val activeTransport: ConnectionMethod? = null,

    /** Short authentication string shown on both devices to confirm the peer. */
    val sessionSas: String? = null,

    /**
     * Identity of the connection attempt currently awaiting SAS confirmation.
     * Null unless the state is VerifyingPeer; used to reject stale confirms.
     */
    val pendingSessionId: String? = null,

    /** True while an inbound Wi-Fi Direct peer is waiting for the handshake. */
    val waitingForPeer: Boolean = false,

    // Privacy settings
    val screenshotProtection: Boolean = true,
    val backgroundSessionEnabled: Boolean = false
)

enum class ConnectionProgressStep(val label: String) {
    Idle(""),
    Discovering("Discovering nearby device"),
    EstablishingLink("Establishing local connection"),
    VerifyingPeer("Verifying peer"),
    EstablishingSession("Establishing secure session"),
    Ready("Connection ready"),
    Failed("Connection failed"),
    Rejected("Request rejected")
}

/** Upper bound for OS-driven Wi-Fi Direct group formation + handshake. */
private const val WIFI_DIRECT_GROUP_TIMEOUT_MS = 45_000L

/**
 * Shown when a Bluetooth Classic device is picked for a data session.
 * Bluetooth Classic is discovery-capable but its data path is not yet migrated
 * onto the authenticated session protocol, so it must not carry messages.
 */
internal const val BLUETOOTH_DATA_DISABLED_REASON =
    "Bluetooth data connections are disabled in this build because they are not yet " +
        "end-to-end authenticated. Bluetooth can still discover devices — connect over " +
        "Wi-Fi Direct instead."

/**
 * A connection attempt that authenticated but is awaiting the user's SAS check.
 *
 * @param sasHex the safety code both devices must display identically.
 */
data class PendingVerification(
    val sessionId: String,
    val sasHex: String,
    val peerLabel: String,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────
class GhostViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GhostUiState())
    val uiState: StateFlow<GhostUiState> = _uiState.asStateFlow()

    // ── Real connectivity managers (created lazily once a Context is attached) ──
    private var appContext: Context? = null
    private var wifiDirectManager: WifiDirectManager? = null
    private var bleManager: BluetoothLeManager? = null
    private var bluetoothClassicManager: BluetoothClassicManager? = null
    private var discoveryJob: Job? = null
    private var wifiDirectTransport: WifiDirectTransport? = null
    /** Bounds Wi-Fi Direct group negotiation + handshake so the UI never hangs. */
    private var groupTimeoutJob: Job? = null
    /** The attempt awaiting SAS confirmation; null when none is pending. */
    @Volatile private var pendingVerification: PendingVerification? = null
    @Volatile private var pendingConnectionCallback: ((Boolean) -> Unit)? = null

    /** WeakMap of ScanResults; also the BLE record of known devices. */
    private val bleDiscovered = LinkedHashMap<String, DiscoveredDevice>()

    /**
     * Attaches the application context and initialises the connectivity
     * managers. Called once from the UI layer. Permissions are NOT requested here;
     * the UI must obtain them via [requiredPermissions] first and then call
     * [onConnectivityPermissionResult].
     */
    fun attachContext(context: Context) {
        if (appContext != null) return
        PerfTrace.mark(PerfTrace.STAGE_ATTACH)
        val ctx = context.applicationContext
        appContext = ctx
        wifiDirectManager = WifiDirectManager(ctx).also { it.initialize() }
        wifiDirectTransport = WifiDirectTransport(ctx) { _uiState.value.localAlias.display }.also { transport ->
            transport.currentTokenProvider = { usableInvitationToken() }
            transport.isAvailable() // initialises the WifiP2pManager channel
            transport.setListeners(wifiDirectListeners())
        }
        bleManager = BluetoothLeManager(ctx)
        bluetoothClassicManager = BluetoothClassicManager(ctx) {
            _uiState.value.localAlias.display
        }.also { manager ->
            manager.setListeners(object : GhostTransportListeners {
                override fun onDiscovering() {
                    _uiState.value = _uiState.value.copy(
                        discoveryStatus = DiscoveryStatus.Scanning,
                        discoveryError = null
                    )
                }

                /**
                 * The Bluetooth Classic data path is disabled (see
                 * [BLUETOOTH_DATA_DISABLED_REASON]); this listener exists only so the
                 * transport can still report discovery. Any inbound data session is
                 * closed immediately instead of being treated as connected.
                 */
                override fun onPeerAuthenticated(sessionId: String, sasHex: String, peerLabel: String) {
                    Log.w("GhostViewModel", "Rejecting unauthenticated Bluetooth session")
                    bluetoothClassicManager?.disconnect()
                }

                override fun onWaitingForConnection() {
                    if (_uiState.value.connectionState != ConnectionState.Connected) {
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionState.WaitingForConnection
                        )
                    }
                }

                override fun onDevicesFound(devices: List<DiscoveredDevice>) {
                    publishDiscoveredDevices(devices)
                }

                override fun onConnecting(deviceLabel: String) {
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Connecting,
                        connectionProgress = ConnectionProgressStep.EstablishingLink,
                        connectionError = null
                    )
                }

                override fun onConnected(peerAlias: String?, peerLabel: String) {
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Connected,
                        connectionProgress = ConnectionProgressStep.Ready,
                        connectionError = null,
                        currentPeer = PeerInfo(
                            alias = GhostAlias(peerAlias ?: peerLabel),
                            connectionMethod = ConnectionMethod.Bluetooth,
                            isVerified = peerAlias != null
                        )
                    )
                    pendingConnectionCallback?.also { callback ->
                        pendingConnectionCallback = null
                        viewModelScope.launch { callback(true) }
                    }
                }

                override fun onMessageReceived(text: String) {
                    _uiState.update { state ->
                        state.copy(messages = state.messages + ChatMessage(
                            content = text,
                            origin = MessageOrigin.Remote,
                            senderAlias = state.currentPeer?.alias,
                            status = MessageStatus.Delivered
                        ))
                    }
                }

                override fun onDisconnected(reason: String?) {
                    clearSessionData()
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Disconnected,
                        connectionError = reason
                    )
                }

                override fun onError(message: String) {
                    Log.e("GhostViewModel", "Bluetooth transport: $message")
                    _uiState.value = _uiState.value.copy(
                        discoveryStatus = DiscoveryStatus.Error,
                        discoveryError = message
                    )
                    if (_uiState.value.connectionState == ConnectionState.Connecting ||
                        _uiState.value.connectionState == ConnectionState.Connected
                    ) {
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionState.Failed,
                            connectionProgress = ConnectionProgressStep.Failed,
                            connectionError = message
                        )
                        pendingConnectionCallback?.also { callback ->
                            pendingConnectionCallback = null
                            viewModelScope.launch { callback(false) }
                        }
                    }
                }
            })
        }
        refreshCapabilities(granted = ConnectivityPermissions.allGranted(ctx))
    }

    /**
     * Recomputes the per-transport availability flags and the user-facing reason
     * each unavailable transport is unavailable. Called on attach, after the
     * permission dialog, and whenever the Nearby screen gains focus.
     */
    private fun refreshCapabilities(granted: Boolean) {
        val ctx = appContext ?: return
        val supported = wifiDirectTransport?.isAvailable() == true
        val wifiPermitted = granted || ConnectivityPermissions.wifiDirectGranted(ctx)
        val wifiRadioOn = wifiDirectManager?.let { isWifiRadioEnabled(ctx) } ?: false
        val wifiReady = supported && wifiPermitted && wifiRadioOn
        val reason = when {
            !supported -> "This device does not support Wi-Fi Direct."
            !wifiPermitted -> "Wi-Fi Direct needs the Nearby/Wi-Fi permission."
            !wifiRadioOn -> "Wi-Fi is turned off. Turn it on to use Wi-Fi Direct."
            else -> null
        }
        _uiState.value = _uiState.value.copy(
            wifiDirectSupported = supported,
            wifiDirectAvailable = wifiReady,
            wifiDirectReady = wifiReady,
            wifiDirectUnavailableReason = reason,
            bleAvailable = granted && (bleManager?.isAvailable() == true) &&
                ConnectivityPermissions.bluetoothGranted(ctx)
        )
    }

    private fun isWifiRadioEnabled(ctx: Context): Boolean =
        (ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
            ?.isWifiEnabled == true

    /** The token of the active invitation, but only while it is genuinely usable. */
    internal fun usableInvitationToken(): String? =
        _uiState.value.currentInvitation?.takeIf { it.isUsable }?.token

    /**
     * True when the app is in a state where an inbound invitation code may be
     * accepted (no session and no connection attempt in flight).
     */
    fun isAwaitingInvitationInput(): Boolean = when (_uiState.value.connectionState) {
        ConnectionState.Idle, ConnectionState.Disconnected, ConnectionState.Failed,
        ConnectionState.Rejected -> true
        else -> false
    }

    /** Permissions the UI must request before discovery can work on this OS. */
    fun requiredPermissions(): List<String> = ConnectivityPermissions.required()

    /**
     * Called by the UI after the permission dialog resolves. Reports whether
     * Wi-Fi Direct and BLE discovery are usable so the Nearby screen can show an
     * honest, actionable state instead of an empty list with no explanation.
     */
    fun onConnectivityPermissionResult(granted: Boolean) {
        val ctx = appContext ?: return
        refreshCapabilities(granted)
        if (granted) startDiscovery()
    }

    /** Re-checks radio/permission state when a screen regains focus. */
    fun refreshTransportCapabilities() {
        val ctx = appContext ?: return
        refreshCapabilities(ConnectivityPermissions.allGranted(ctx))
    }

    fun isBluetoothTransportAvailable(): Boolean {
        val ctx = appContext ?: return false
        return bluetoothClassicManager?.isAvailable() == true &&
            ConnectivityPermissions.bluetoothGranted(ctx)
    }

    fun startBluetoothHosting() {
        val ctx = appContext ?: return
        if (ConnectivityPermissions.bluetoothGranted(ctx) &&
            bluetoothClassicManager?.isAvailable() == true
        ) {
            bluetoothClassicManager?.startHosting()
        }
    }

    fun stopBluetoothHosting() {
        bluetoothClassicManager?.stopHosting()
    }

    fun startNearbyDiscovery() {
        refreshTransportCapabilities()
        startDiscovery()
        val issues = mutableListOf<String>()
        if (isBluetoothTransportAvailable()) {
            startBluetoothHosting()
        } else {
            issues += "Bluetooth is unavailable, turned off, or missing required permissions"
        }
        // Wi-Fi Direct peers connect to us as group owner; this only starts the
        // authenticated listener when a usable invitation exists.
        if (_uiState.value.wifiDirectReady && usableInvitationToken() != null) {
            wifiDirectTransport?.startHosting()
        }
        if (issues.isNotEmpty() && _uiState.value.discoveryError == null) {
            _uiState.value = _uiState.value.copy(discoveryError = issues.joinToString(". ") + ".")
        }
    }

    /** Re-arms hosting/listening after returning to the Nearby screen. */
    fun resumeNearbySession() {
        startNearbyDiscovery()
    }

    // ── Wi-Fi Direct transport listeners ─────────────────────────────

    private fun wifiDirectListeners() = object : GhostTransportListeners {
        override fun onDiscovering() = Unit

        override fun onWaitingForConnection() {
            if (_uiState.value.connectionState != ConnectionState.Connected) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.WaitingForConnection,
                        waitingForPeer = true
                    )
                }
            }
        }

        override fun onDevicesFound(devices: List<DiscoveredDevice>) {
            // Generation-guarded upstream by the transport; only publish while scanning.
            if (_uiState.value.discoveryStatus != DiscoveryStatus.Scanning) return
            publishDiscoveredDevices(devices)
        }

        override fun onConnecting(deviceLabel: String) {
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.Connecting,
                    connectionProgress = ConnectionProgressStep.EstablishingLink,
                    connectionError = null,
                    waitingForPeer = false
                )
            }
        }

        override fun onPeerAuthenticated(sessionId: String, sasHex: String, peerLabel: String) {
            // Encryption + invitation proof succeeded. Nothing is sent or received
            // until the user compares this SAS on both phones and confirms.
            groupTimeoutJob?.cancel()
            groupTimeoutJob = null
            PerfTrace.mark(PerfTrace.STAGE_AUTHENTICATED)
            pendingVerification = PendingVerification(sessionId, sasHex, peerLabel)
            _uiState.update { state ->
                state.copy(
                    connectionState = ConnectionState.VerifyingPeer,
                    connectionProgress = ConnectionProgressStep.VerifyingPeer,
                    connectionError = null,
                    waitingForPeer = false,
                    activeTransport = ConnectionMethod.WifiDirect,
                    sessionSas = sasHex,
                    pendingSessionId = sessionId
                )
            }
        }

        override fun onVerificationFailed(sessionId: String, reason: String) {
            if (pendingVerification?.sessionId != sessionId) return // stale attempt
            pendingVerification = null
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    connectionProgress = ConnectionProgressStep.Failed,
                    connectionError = reason,
                    sessionSas = null,
                    pendingSessionId = null,
                    waitingForPeer = false
                )
            }
            pendingConnectionCallback?.also { cb ->
                pendingConnectionCallback = null
                viewModelScope.launch { cb(false) }
            }
        }

        override fun onConnected(peerAlias: String?, peerLabel: String) {
            // Reaching this callback means BOTH sides authenticated AND the user
            // confirmed the safety code; only now is the invitation consumed.
            groupTimeoutJob?.cancel()
            groupTimeoutJob = null
            pendingVerification = null
            PerfTrace.mark(PerfTrace.STAGE_VERIFIED)
            PerfTrace.report()
            val sas = _uiState.value.sessionSas
            _uiState.update { state ->
                state.copy(
                    connectionState = ConnectionState.Connected,
                    connectionProgress = ConnectionProgressStep.Ready,
                    connectionError = null,
                    waitingForPeer = false,
                    activeTransport = ConnectionMethod.WifiDirect,
                    currentPeer = PeerInfo(
                        alias = GhostAlias(peerAlias ?: peerLabel),
                        connectionMethod = ConnectionMethod.WifiDirect,
                        isVerified = true
                    ),
                    currentInvitation = InvitationLifecycle.onAuthenticationSucceeded(state.currentInvitation),
                    sessionSas = sas,
                    pendingSessionId = null
                )
            }
            pendingConnectionCallback?.also { callback ->
                pendingConnectionCallback = null
                viewModelScope.launch { callback(true) }
            }
        }

        override fun onMessageReceived(text: String) {
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + ChatMessage(
                        content = text,
                        origin = MessageOrigin.Remote,
                        senderAlias = state.currentPeer?.alias,
                        status = MessageStatus.Delivered
                    )
                )
            }
        }

        override fun onDisconnected(reason: String?) {
            // Invitation consumption is intentionally untouched: a dropped session
            // must not burn a still-valid invitation for the next attempt.
            groupTimeoutJob?.cancel()
            groupTimeoutJob = null
            val wasConnected = _uiState.value.connectionState == ConnectionState.Connected
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.Disconnected,
                    connectionProgress = ConnectionProgressStep.Idle,
                    connectionError = reason,
                    currentPeer = null,
                    activeTransport = null,
                    sessionSas = null,
                    waitingForPeer = false
                )
            }
            if (wasConnected) pendingConnectionCallback = null
        }

        override fun onError(message: String) {
            Log.w("GhostViewModel", "Wi-Fi Direct: $message")
            groupTimeoutJob?.cancel()
            groupTimeoutJob = null
            val state = _uiState.value
            val connecting = state.connectionState == ConnectionState.Connecting ||
                state.connectionState == ConnectionState.WaitingForConnection
            _uiState.update {
                it.copy(
                    discoveryError = if (it.discoveryStatus == DiscoveryStatus.Scanning) message else it.discoveryError,
                    connectionState = if (connecting) ConnectionState.Failed else it.connectionState,
                    connectionProgress = if (connecting) ConnectionProgressStep.Failed else it.connectionProgress,
                    connectionError = if (connecting) message else it.connectionError,
                    waitingForPeer = false
                )
            }
            if (connecting) {
                pendingConnectionCallback?.also { cb ->
                    pendingConnectionCallback = null
                    viewModelScope.launch { cb(false) }
                }
            }
            // Always release a half-formed group so the next attempt starts clean.
            wifiDirectTransport?.disconnect()
        }
    }

    // ── Alias ─────────────────────────────────────────────────────────────────

    fun regenerateAlias() {
        // Only regenerate if there is no active connection — preserves alias during session
        if (_uiState.value.connectionState == ConnectionState.Connected) return
        _uiState.value = _uiState.value.copy(
            localAlias = GhostAlias(generateAlias())
        )
    }

    // ── Theme & Display Settings ──────────────────────────────────────────────

    fun setTheme(theme: GhostTheme) {
        _uiState.value = _uiState.value.copy(selectedTheme = theme)
    }

    fun setReducedMotion(value: Boolean) {
        _uiState.value = _uiState.value.copy(reducedMotion = value)
    }

    fun setTerminalEffects(value: Boolean) {
        _uiState.value = _uiState.value.copy(terminalEffects = value)
    }

    fun setTextSizeScale(scale: Float) {
        _uiState.value = _uiState.value.copy(textSizeScale = scale)
    }

    fun setScreenshotProtection(value: Boolean) {
        _uiState.value = _uiState.value.copy(screenshotProtection = value)
    }

    /**
     * Enables/disables the optional background-session foreground service.
     * Starting a foreground service on API 33+ without POST_NOTIFICATIONS
     * throws, so the notification permission is checked first and the toggle is
     * refused (with state left off) when it is missing.
     */
    fun setBackgroundSession(value: Boolean) {
        val ctx = appContext
        if (value && ctx != null) {
            if (!ConnectivityPermissions.notificationsGranted(ctx)) {
                Log.w("GhostViewModel", "Cannot start background session: notifications not permitted")
                _uiState.value = _uiState.value.copy(backgroundSessionEnabled = false)
                return
            }
            GhostLinkService.startSession(ctx)
        } else if (!value && ctx != null) {
            GhostLinkService.stopSession(ctx)
        }
        _uiState.value = _uiState.value.copy(backgroundSessionEnabled = value)
    }

    // ── Invitation ────────────────────────────────────────────────────────────

    /**
     * Creates a new invitation with a cryptographically random token and
     * a human-readable code. The token stays local; only the code is shared.
     */
    fun generateInvitation() {
        val code = InvitationCode(generateInviteCode())
        _uiState.value = _uiState.value.copy(
            currentInvitation = Invitation(
                code = code,
                token = generateSecureToken(),
                creatorAlias = _uiState.value.localAlias
            ),
            invitationInputError = null
        )
    }

    fun regenerateInvitation() = generateInvitation()

    /**
     * Creates an invitation lazily when the pairing menu opens, so a transport can
     * actually be used without first walking to "Show My Invitation Code".
     * An existing *usable* invitation is kept; a consumed or expired one is replaced.
     */
    fun prepareInvitationIfMissing() {
        if (_uiState.value.currentInvitation?.isUsable != true) generateInvitation()
    }

    /**
     * Applies a scanned invitation QR payload.
     *
     * The payload carries the initiator's token and expiry; the responder derives its
     * own display code and adopts the scanned token, so both devices share exactly the
     * same token/expiry pair that the initiator's UI and QR advertise.
     *
     * Callers are expected to parse the raw string with `parseInvitationQr` first; this
     * only stores the validated values and clears any previous input error.
     *
     * The invitation is left **unconsumed**: scanning proves only that the payload
     * was readable. It is consumed later, when the authenticated handshake with
     * the peer succeeds (see the Wi-Fi Direct listeners).
     */
    fun applyScannedInvitation(token: String, expiresAt: Long) {
        val inv = Invitation(
            code = InvitationCode(generateInviteCode()),
            token = token,
            creatorAlias = null,
            createdAt = System.currentTimeMillis(),
            expiresAt = expiresAt
        )
        _uiState.update {
            it.copy(
                currentInvitation = inv,
                invitationCodeInput = inv.code.display,
                invitationInputError = null,
                isValidatingCode = false
            )
        }
    }

    /** Stores an invitation error to show on the scanner / enter-code screen. */
    fun setInvitationInputError(message: String?) {
        _uiState.update { it.copy(invitationInputError = message) }
    }

    fun cancelInvitation() {
        _uiState.value = _uiState.value.copy(currentInvitation = null)
    }

    /**
     * Updates the invitation code entry field, normalising the input.
     * Auto-inserts a dash after position 4 for the XXXX-XXXX display format.
     */
    fun updateInvitationCodeInput(value: String) {
        val clean = value.uppercase().filter { it.isLetterOrDigit() || it == '-' }
        _uiState.value = _uiState.value.copy(
            invitationCodeInput = clean,
            invitationInputError = null
        )
    }

    /**
     * Validates the entered code against format requirements.
     * In Stage 1, no server lookup is available; this validates format only
     * and would be extended to match against a locally-cached invitation token
     * when the real local peer-to-peer matching is implemented.
     */
    fun validateAndSubmitCode(onSuccess: () -> Unit) {
        val code = _uiState.value.invitationCodeInput.trim()
        if (!InvitationCode.isValidFormat(code)) {
            _uiState.value = _uiState.value.copy(
                invitationInputError = "Invalid code format. Expected 8–12 characters, e.g. 7K9M-4Q2X."
            )
            return
        }

        // A typed code identifies *an invitation*, not a device. Matching it
        // against discovered device names (the previous behaviour) silently bound
        // the session to an unrelated peer, and constructing `Invitation(code)`
        // generated a random token, so the code the user typed was never used for
        // authentication. Both are removed: the typed code is kept as the header
        // of the new invitation, whose token is fresh, and the pairing then runs
        // the normal authenticated handshake against whichever peer is selected.
        _uiState.update {
            it.copy(
                isValidatingCode = false,
                invitationInputError = null,
                currentInvitation = Invitation(
                    code = InvitationCode(code),
                    token = generateSecureToken(),
                    creatorAlias = it.localAlias
                ),
                selectedDevice = null,
                connectionState = ConnectionState.Idle,
                connectionError = null
            )
        }
        onSuccess()
    }

    // ── Nearby Discovery ──────────────────────────────────────────────────────

    /**
     * Starts discovery. Peer discovery on real hardware is driven by
     * WifiDirectManager, BluetoothClassicManager, and BluetoothLeManager,
     * which publish into [updateDiscoveredDevices].
     *
     * No simulated peers are injected: an empty list genuinely means nothing has
     * been found, so the UI never shows a device that does not exist.
     */
    fun startDiscovery() {
        val ctx = appContext ?: run {
            _uiState.value = _uiState.value.copy(
                discoveryStatus = DiscoveryStatus.Error,
                discoveryError = "Connectivity is not initialised yet. Try again."
            )
            return
        }

        // Fully tear down the previous run *before* publishing a new one. The old
        // code cancelled the job but left the transports running, so the second
        // scan raced the first scan's receivers and could report stale peers.
        stopDiscoveryQuietly()

        PerfTrace.resetAttempt()
        PerfTrace.mark(PerfTrace.STAGE_DISCOVERY_START)
        _uiState.value = _uiState.value.copy(
            discoveryStatus = DiscoveryStatus.Scanning,
            discoveryError = null,
            discoveredDevices = emptyList(),
            selectedDevice = null
        )

        // Wi-Fi Direct discovery is owned by the transport (one P2P receiver per
        // scan). wifiDirectManager is retained only for the radio-state check.
        val ble = bleManager
        val transport = wifiDirectTransport

        discoveryJob = viewModelScope.launch {
            val found = LinkedHashMap<String, DiscoveredDevice>()
            // Publish merged results as they arrive instead of waiting for the
            // Bluetooth DISCOVERY_FINISHED broadcast (~12 s).
            fun publish(fresh: List<DiscoveredDevice>) {
                if (fresh.isNotEmpty()) PerfTrace.mark(PerfTrace.STAGE_FIRST_PEER)
                val snapshot = synchronized(found) {
                    fresh.forEach { found[it.deviceId] = it }
                    found.values.toList()
                }
                publishDiscoveredDevices(snapshot)
            }

            val bluetooth = bluetoothClassicManager
            val btAvailable = bluetooth?.isAvailable() == true &&
                ConnectivityPermissions.bluetoothGranted(ctx)
            if (btAvailable) {
                bluetooth.startDiscovery()
            }

            // Wi-Fi Direct peer discovery (requires the Wi-Fi Direct permission).
            // The transport now owns its own P2P receiver, so there is exactly one
            // registration per scan and results arrive as each peer list changes.
            val wifiPermitted = ConnectivityPermissions.wifiDirectGranted(ctx)
            if (transport != null && wifiPermitted && _uiState.value.wifiDirectSupported) {
                transport.startDiscovery()
            }

            if (!btAvailable && !(transport != null && wifiPermitted)) {
                _uiState.value = _uiState.value.copy(
                    discoveryStatus = DiscoveryStatus.Error,
                    discoveryError = "No transport is available. Enable Bluetooth or Wi-Fi and grant the Nearby permission."
                )
                return@launch
            }

            // BLE discovery (requires the BLE permissions).
            // Advertising must run alongside scanning: a device that only scans
            // can never be found by another device that also only scans. The
            // advertised payload is the current invitation token, truncated by
            // BluetoothLeManager to fit the 31-byte advertisement limit.
            if (ble != null && ble.isAvailable() && btAvailable) {
                val tokenBytes = _uiState.value.currentInvitation?.token?.toByteArray(Charsets.UTF_8)
                launch {
                    if (tokenBytes != null && tokenBytes.isNotEmpty()) {
                        ble.startDiscoveryForInvitation(tokenBytes) { device ->
                            publish(listOf(device))
                        }
                    } else {
                        // No active invitation: scan-only. GhostLink peers that
                        // have an invitation will still advertise and be found.
                        ble.startScanning { device -> publish(listOf(device)) }
                    }
                }
            }
        }
    }

    /** Replaces the discovered-device list with results from the real discovery layer. */
    fun updateDiscoveredDevices(devices: List<DiscoveredDevice>) {
        _uiState.value = _uiState.value.copy(discoveredDevices = devices)
    }

    private fun publishDiscoveredDevices(devices: List<DiscoveredDevice>) {
        _uiState.update { state ->
            val merged = LinkedHashMap<String, DiscoveredDevice>()
            (state.discoveredDevices + devices).forEach { device ->
                val existing = merged[device.deviceId]
                if (existing == null ||
                    device.connectionMethod == ConnectionMethod.Bluetooth ||
                    existing.connectionMethod != ConnectionMethod.Bluetooth
                ) {
                    merged[device.deviceId] = device
                }
            }
            state.copy(discoveredDevices = merged.values.toList())
        }
    }

    /**
     * Cancels a discovery run without touching UI state. Safe to call repeatedly:
     * every transport's stop call is idempotent.
     */
    private fun stopDiscoveryQuietly() {
        discoveryJob?.cancel()
        discoveryJob = null
        wifiDirectTransport?.stopDiscovery()
        wifiDirectManager?.stopPeerDiscovery()
        bleManager?.stopScanning()
        bluetoothClassicManager?.stopDiscovery()
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        // The Wi-Fi Direct flow's awaitClose block unregisters its receiver when
        // its collector is cancelled; explicitly stopping discovery as well
        // releases the Wi-Fi Direct radio promptly instead of leaving it busy.
        wifiDirectManager?.stopPeerDiscovery()
        wifiDirectTransport?.stopDiscovery()
        bleManager?.stopScanning()
        bleManager?.stopAdvertising()
        bluetoothClassicManager?.stopDiscovery()
        // Clearing the list prevents stale peers from a previous scan being
        // tapped after the radios have stopped.
        _uiState.value = _uiState.value.copy(
            discoveryStatus = DiscoveryStatus.Idle,
            discoveryError = null,
            discoveredDevices = emptyList(),
            selectedDevice = null
        )
    }

    fun selectDevice(device: DiscoveredDevice) {
        _uiState.value = _uiState.value.copy(selectedDevice = device)
    }

    // ── Connection ────────────────────────────────────────────────────────────

    /**
     * Bluetooth Classic devices use the RFCOMM transport. Other existing
     * connection paths retain their current behavior.
     */
    fun startConnection(onComplete: (Boolean) -> Unit) {
        val device = _uiState.value.selectedDevice
        if (device != null &&
            device.connectionMethod == ConnectionMethod.Bluetooth
        ) {
            // Bluetooth Classic is DISABLED for data sessions.
            //
            // It still speaks the legacy `GHLK/1 HELLO/ACK` exchange and sends
            // message text in cleartext, with no invitation proof and no key. That
            // fails the "every enabled transport must authenticate the peer"
            // requirement, so rather than shipping an insecure fallback the
            // transport is refused with an explicit reason. Wi-Fi Direct remains the
            // only authenticated data transport; BLE/Bluetooth discovery is intact.
            failConnection(BLUETOOTH_DATA_DISABLED_REASON, onComplete)
            return
        }

        if (device != null && device.connectionMethod == ConnectionMethod.BLE) {
            // BLE is discovery-only: it never carries a data session.
            failConnection(
                "Bluetooth LE is only used to find nearby devices. " +
                    "Select the same device's Wi-Fi Direct entry to connect.",
                onComplete
            )
            return
        }

        val wd = wifiDirectTransport
        if (device != null && device.connectionMethod == ConnectionMethod.WifiDirect) {
            if (wd == null) return failConnection("Wi-Fi Direct is not initialised.", onComplete)
            if (!_uiState.value.wifiDirectReady) {
                return failConnection(
                    _uiState.value.wifiDirectUnavailableReason ?: "Wi-Fi Direct is not available.",
                    onComplete
                )
            }
            // A session can only be authenticated against a known shared secret.
            val token = usableInvitationToken()
            if (token == null) {
                return failConnection(
                    "Wi-Fi Direct needs an active invitation. Generate one (Show My Invitation Code) or scan/enter the peer's QR code first.",
                    onComplete
                )
            }
            if (!isAwaitingInvitationInput()) {
                return failConnection("Another connection attempt is already in progress.", onComplete)
            }

            PerfTrace.mark(PerfTrace.STAGE_GROUP_REQUESTED)
            pendingConnectionCallback = onComplete
            stopDiscovery()
            bleManager?.stopAdvertising()
            bluetoothClassicManager?.stopHosting()
            wd.closeSession()
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.Connecting,
                    connectionProgress = ConnectionProgressStep.EstablishingLink,
                    connectionError = null,
                    activeTransport = ConnectionMethod.WifiDirect,
                    waitingForPeer = false
                )
            }
            // Wireless group negotiation is OS-driven and can hang on some OEM
            // firmwares; bound it so the UI always reaches a retryable state.
            groupTimeoutJob?.cancel()
            groupTimeoutJob = viewModelScope.launch {
                delay(WIFI_DIRECT_GROUP_TIMEOUT_MS)
                if (_uiState.value.connectionState == ConnectionState.Connecting ||
                    _uiState.value.connectionState == ConnectionState.WaitingForConnection
                ) {
                    failConnection(
                        "Wi-Fi Direct negotiation timed out. The invitation is still valid — retry, or switch to Bluetooth.",
                        onComplete
                    )
                }
            }
            // Negotiates the group with the OS; CONNECTION_CHANGED -> onGroupFormed()
            // decides the TCP role and then runs the authenticated handshake.
            wd.connect(device)
            return
        }

        failConnection("No valid device or connection method selected.", onComplete)
    }

    /** Single place that records a failed attempt and releases partial state. */
    private fun failConnection(message: String, onComplete: (Boolean) -> Unit) {
        PerfTrace.mark(PerfTrace.STAGE_FAILED)
        groupTimeoutJob?.cancel()
        groupTimeoutJob = null
        wifiDirectTransport?.disconnect()
        _uiState.update {
            it.copy(
                connectionState = ConnectionState.Failed,
                connectionProgress = ConnectionProgressStep.Failed,
                connectionError = message,
                waitingForPeer = false
            )
        }
        pendingConnectionCallback = null
        onComplete(false)
    }

    fun rejectConnection() {
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionState.Rejected,
            connectionProgress = ConnectionProgressStep.Rejected
        )
    }

    fun acceptConnectionRequest(onAccepted: () -> Unit) {
        viewModelScope.launch {
            startConnection { success ->
                if (success) onAccepted()
            }
        }
    }

    // ── SAS verification ────────────────────────────────────────────

    /**
     * The user confirmed that the code on both phones matched.
     *
     * Only a confirmation for the **currently pending** session is honoured, and
     * only when the transport still holds that same session. A stale id (from a
     * previous attempt, a retry, or a timed-out session) is ignored.
     */
    fun confirmSessionVerification() {
        val pending = pendingVerification ?: return
        if (_uiState.value.pendingSessionId != pending.sessionId) return
        if (wifiDirectTransport?.confirmVerification(pending.sessionId) != true) return
        pendingVerification = null
    }

    /** The user says the codes differ: close the socket and fail closed. */
    fun rejectSessionVerification() {
        val pending = pendingVerification ?: return
        pendingVerification = null
        wifiDirectTransport?.rejectVerification(pending.sessionId)
        _uiState.update {
            it.copy(
                connectionState = ConnectionState.Failed,
                connectionProgress = ConnectionProgressStep.Failed,
                connectionError = "The safety code did not match — connection cancelled.",
                sessionSas = null,
                pendingSessionId = null
            )
        }
        pendingConnectionCallback?.also { cb ->
            pendingConnectionCallback = null
            viewModelScope.launch { cb(false) }
        }
    }

    fun disconnect() {
        pendingConnectionCallback = null
        pendingVerification = null
        groupTimeoutJob?.cancel()
        groupTimeoutJob = null
        bluetoothClassicManager?.disconnect()
        bluetoothClassicManager?.stopHosting()
        bluetoothClassicManager?.stopDiscovery()
        // Release the Wi-Fi Direct socket *and* the P2P group, plus the local
        // listener, so a retry never collides with a stale group or port.
        wifiDirectTransport?.disconnect()
        wifiDirectTransport?.stopHosting()
        clearSessionData()
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionState.Disconnected,
            connectionError = null,
            activeTransport = null,
            sessionSas = null,
            waitingForPeer = false
        )
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    fun updateDraft(text: String) {
        _uiState.value = _uiState.value.copy(draftMessage = text)
    }

    /**
     * Sends the current draft message to the connected peer over Bluetooth Classic
     * or Wi-Fi Direct (P2P). No simulated peer responses are ever injected.
     */
    fun sendMessage() {
        val draft = _uiState.getAndUpdate { it.copy(draftMessage = "") }.draftMessage.trim()
        if (draft.isEmpty()) return

        val peer = _uiState.value.currentPeer
        if (peer == null || _uiState.value.connectionState != ConnectionState.Connected) {
            val failedMsg = ChatMessage(
                content = draft,
                origin = MessageOrigin.Local,
                senderAlias = _uiState.value.localAlias,
                status = MessageStatus.Failed
            )
            _uiState.update { it.copy(
                messages = it.messages + failedMsg,
                connectionError = "Cannot send message: no active peer connection."
            ) }
            return
        }

        val msg = ChatMessage(
            content = draft,
            origin = MessageOrigin.Local,
            senderAlias = _uiState.value.localAlias,
            status = MessageStatus.Sending
        )
        _uiState.update { it.copy(messages = it.messages + msg) }

        if (peer.connectionMethod == ConnectionMethod.Bluetooth) {
            val sent = bluetoothClassicManager?.sendMessage(draft) == true
            updateMessageStatus(msg.id, if (sent) MessageStatus.Sent else MessageStatus.Failed)
            return
        }

        if (peer.connectionMethod == ConnectionMethod.WifiDirect) {
            val sent = wifiDirectTransport?.sendMessage(draft) == true
            updateMessageStatus(msg.id, if (sent) MessageStatus.Sent else MessageStatus.Failed)
            return
        }

        // Fallback for unsupported or unknown connection method
        updateMessageStatus(msg.id, MessageStatus.Failed)
    }

    /**
     * Retries sending a previously failed message without retyping it.
     */
    fun retrySendMessage(messageId: String) {
        val msg = _uiState.value.messages.find { it.id == messageId } ?: return
        val peer = _uiState.value.currentPeer ?: return
        updateMessageStatus(messageId, MessageStatus.Sending)

        if (peer.connectionMethod == ConnectionMethod.Bluetooth) {
            val sent = bluetoothClassicManager?.sendMessage(msg.content) == true
            updateMessageStatus(messageId, if (sent) MessageStatus.Sent else MessageStatus.Failed)
            return
        }

        if (peer.connectionMethod == ConnectionMethod.WifiDirect) {
            val sent = wifiDirectTransport?.sendMessage(msg.content) == true
            updateMessageStatus(messageId, if (sent) MessageStatus.Sent else MessageStatus.Failed)
            return
        }

        updateMessageStatus(messageId, MessageStatus.Failed)
    }

    private fun updateMessageStatus(messageId: String, newStatus: MessageStatus) {
        _uiState.update { state ->
            val updated = state.messages.map {
                if (it.id == messageId) it.copy(status = newStatus) else it
            }
            state.copy(messages = updated)
        }
    }

    // ── Session cleanup — EphemeralSessionManager behaviour ──────────────────

    /**
     * Completely wipes active session data from memory.
     * Called on disconnect, panic, process termination hook, and navigation away.
     * This is the core ephemeral-privacy guarantee: no conversation data survives
     * a session end in application-managed storage.
     */
    private fun clearSessionData() {
        wifiDirectTransport?.closeSession()
        _uiState.value = _uiState.value.copy(
            currentPeer = null,
            connectionProgress = ConnectionProgressStep.Idle,
            connectionError = null,
            messages = emptyList(),      // ← Volatile in-memory message list cleared
            draftMessage = "",           // ← Draft cleared
            selectedDevice = null,
            discoveredDevices = emptyList(),
            discoveryStatus = DiscoveryStatus.Idle,
            discoveryError = null,
            invitationCodeInput = "",
            invitationInputError = null,
            isValidatingCode = false,
            activeTransport = null,
            sessionSas = null,
            waitingForPeer = false
        )
    }

    /** Full session end: clears session data + invitation state */
    fun endSession() {
        pendingConnectionCallback = null
        bluetoothClassicManager?.disconnect()
        bluetoothClassicManager?.stopHosting()
        bluetoothClassicManager?.stopDiscovery()
        clearSessionData()
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionState.Idle,
            currentInvitation = null
        )
    }

    /**
     * Panic action — immediately wipes all session state and resets to idle.
     * Attempts resource release; cannot guarantee OS-level memory erasure
     * or revocation of already-shared invitation codes.
     */
    fun panicClear() {
        endSession()
        discoveryJob?.cancel()
        discoveryJob = null
        wifiDirectManager?.stopPeerDiscovery()
        bluetoothClassicManager?.stopDiscovery()
        bluetoothClassicManager?.stopHosting()
        // Removes the P2P group, closes the authenticated session and the listener,
        // then releases the WifiP2pManager channel.
        wifiDirectTransport?.close()
        wifiDirectManager?.close()
        bleManager?.close()
    }

    override fun onCleared() {
        super.onCleared()
        discoveryJob?.cancel()
        discoveryJob = null
        wifiDirectManager?.close()
        bleManager?.close()
        bluetoothClassicManager?.close()
        wifiDirectTransport?.close()
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private val ALIAS_PREFIXES = listOf(
    "Shadow", "Phantom", "Ghost", "Cipher", "Void", "Null", "Specter",
    "Wraith", "Mirage", "Signal", "Flux", "Stealth", "Echo", "Static",
    "Lunar", "Nova", "Pixel", "Vector", "Prism", "Nexus", "Delta"
)

fun generateAlias(): String {
    val prefix = ALIAS_PREFIXES.random()
    val hex = "%03X".format(Random.nextInt(0, 0xFFF))
    return "${prefix}_${hex}"
}

/**
 * Generates a human-readable 12-char invite code in XXXX-XXXX-XXXX format.
 *
 * Uses [SecureRandom] rather than [Random]: this code is the value a user reads
 * aloud or types, so it must not be predictable from the app's RNG state.
 */
fun generateInviteCode(): String {
    val chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // Omits I, O, 0, 1 for readability
    val rnd = SecureRandom()
    return (1..12).map { chars[rnd.nextInt(chars.length)] }
        .chunked(4)
        .joinToString("-")
}

/** Generates a cryptographically random 256-bit token as a UUID-style hex string */
private fun generateSecureToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
