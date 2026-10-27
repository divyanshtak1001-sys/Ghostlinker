package com.ghostlink.app.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Looper
import android.util.Log
import com.ghostlink.app.model.DiscoveredDevice
import com.ghostlink.app.model.DeviceVerificationState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * WifiDirectManager — Wraps Android's WifiP2pManager to provide:
 *   1. Peer discovery (discoverPeers + PEERS_CHANGED broadcast)
 *   2. Connection requests (connect)
 *   3. Connection info retrieval (group owner IP for socket setup)
 *
 * Architecture notes:
 * - Requires ACCESS_FINE_LOCATION (API < 33) or NEARBY_WIFI_DEVICES (API 33+).
 * - Wi-Fi Direct P2P does NOT require a conventional Wi-Fi router.
 * - Group owner acts as a ServerSocket host; client connects to group owner IP.
 * - Peer names from WifiP2pDevice are device names (not GhostLink aliases).
 *   Real alias exchange happens at the application-layer socket handshake
 *   performed by P2PConnectionManager after the link is established.
 *
 * Limitations (documented honestly):
 * - On some manufacturer firmwares (e.g. Samsung, Huawei), Wi-Fi Direct
 *   discovery or group formation may fail. This is outside the app's control.
 * - Emulators generally do not support real Wi-Fi Direct hardware.
 *   All Wi-Fi Direct tests require physical Android devices.
 */
class WifiDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectManager"
        /** GhostLink DNS-SD service type used for peer identification */
        const val GHOSTLINK_SERVICE_TYPE = "_ghostlink._tcp"
    }

    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private var channel: WifiP2pManager.Channel? = null

    /**
     * Initialises the WifiP2pManager channel.
     * Must be called before any other method.
     * @return true if Wi-Fi Direct is available on this device.
     */
    fun initialize(): Boolean {
        val mgr = manager ?: run {
            Log.w(TAG, "WifiP2pManager not available on this device")
            return false
        }
        if (channel != null) return true
        // Looper.getMainLooper() is explicit here: WifiP2pManager requires a
        // Looper-bound channel for its broadcast callbacks. mainLooper is
        // always non-null on the app main thread, but getMainLooper() is
        // unambiguous and cannot return null.
        channel = try {
            mgr.initialize(context, Looper.getMainLooper(), null)
        } catch (e: SecurityException) {
            // API 33+ can throw if NEARBY_WIFI_DEVICES has not been granted.
            Log.w(TAG, "WifiP2pManager.initialize rejected: ${e.message}")
            null
        }
        return channel != null
    }

    /** True when a channel has been successfully initialised. */
    fun isInitialized(): Boolean = channel != null

    /**
     * Returns a Flow of peer lists discovered via Wi-Fi Direct.
     * Registers a BroadcastReceiver for WIFI_P2P_PEERS_CHANGED_ACTION and
     * calls discoverPeers() to trigger discovery.
     *
     * Emits each new peer list as it is received.
     * The flow completes (or the receiver is unregistered) when the
     * collector's coroutine is cancelled.
     */
    fun discoverPeersFlow(): Flow<List<DiscoveredDevice>> {
        // IMPORTANT: callbackFlow's lambda receiver is a ProducerScope (a SendChannel),
        // which SHADOWS this class's `channel` field. Read the field here, before the
        // coroutine receiver is in scope, so the Wi-Fi Direct channel can never be
        // confused with the coroutine channel.
        val wifiChannel = channel
        val wifiManager = manager

        return callbackFlow {
        val mgr = wifiManager ?: run {
            Log.w(TAG, "WifiP2pManager unavailable — cannot discover peers")
            close()
            return@callbackFlow
        }
        val ch = wifiChannel ?: run {
            Log.w(TAG, "Channel not initialized — call initialize() first")
            close()
            return@callbackFlow
        }

        // Android 13+ (API 33) delivers this receiver without the legacy
        // pre-33 code path; register with RECEIVER_NOT_EXPORTED so a
        // dynamically registered receiver for these system broadcasts is
        // explicit about not being exported (required on API 34+).
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        requestPeersSafely(mgr, ch) { peers ->
                            trySend(peers.toDiscoveredDevices())
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                            Log.w(TAG, "Wi-Fi Direct disabled")
                            trySend(emptyList())
                        }
                    }
                }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        registerReceiverSafely(receiver, intentFilter)

        // Trigger peer discovery safely
        try {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "discoverPeers initiated")
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "discoverPeers failed: reason=$reason")
                    // reason codes: ERROR=0, P2P_UNSUPPORTED=1, BUSY=2
                    if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                        trySend(emptyList())
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "discoverPeers rejected (permission): ${e.message}")
            trySend(emptyList())
        }

        awaitClose {
            Log.d(TAG, "Stopping peer discovery and unregistering receiver")
            // Guarded: unregistering a receiver that was never registered (or
            // already unregistered) throws IllegalArgumentException. Without
            // this guard, cancelling discovery crashed the app.
            unregisterReceiverSafely(receiver)
            mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) = Unit
            })
        }
        }
    }

    /**
     * Registers [receiver] with the export flag the running API level requires.
     * API 34+ rejects an untyped dynamic registration for non-system senders,
     * so the flag is passed explicitly on API 33+.
     */
    private fun registerReceiverSafely(receiver: BroadcastReceiver, filter: IntentFilter) {
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Unregisters [receiver], swallowing the "not registered" IllegalArgumentException. */
    private fun unregisterReceiverSafely(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "Receiver was not registered — nothing to unregister")
        }
    }

    /**
     * requestPeers() has been split into a permission-checked and a deprecated
     * form across API levels. This helper picks the right one and never throws
     * a SecurityException when NEARBY_WIFI_DEVICES is missing.
     */
    @Suppress("DEPRECATION")
    private fun requestPeersSafely(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onPeers: (WifiP2pDeviceList) -> Unit
    ) {
        try {
            mgr.requestPeers(ch, onPeers)
        } catch (e: SecurityException) {
            Log.w(TAG, "requestPeers rejected (permission): ${e.message}")
        }
    }

    /**
     * Initiates a connection to the given WifiP2pDevice.
     * The result (success/failure) is reported via [onResult].
     */
    fun connectToPeer(
        device: WifiP2pDevice,
        onResult: (success: Boolean, reason: Int?) -> Unit
    ) {
        val mgr = manager ?: return onResult(false, null)
        val ch = channel ?: return onResult(false, null)

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = android.net.wifi.WpsInfo.PBC
        }

        try {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connection request sent to ${device.deviceAddress}")
                    onResult(true, null)
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Connection request failed: reason=$reason")
                    onResult(false, reason)
                }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "connect rejected (permission): ${e.message}")
            onResult(false, null)
        }
    }

    /**
     * Retrieves the current WifiP2pInfo which contains:
     * - isGroupOwner: whether this device is the group owner (server role)
     * - groupOwnerAddress: the IP of the group owner (used for socket)
     */
    fun requestConnectionInfo(onInfo: (WifiP2pInfo?) -> Unit) {
        val mgr = manager ?: return onInfo(null)
        val ch = channel ?: return onInfo(null)
        try {
            mgr.requestConnectionInfo(ch) { info ->
                Log.d(TAG, "P2P info: isOwner=${info?.isGroupOwner}, ownerAddr=${info?.groupOwnerAddress}")
                onInfo(info)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "requestConnectionInfo rejected (permission): ${e.message}")
            onInfo(null)
        }
    }

    /**
     * Cancels an in-flight peer discovery without tearing down the channel.
     * Safe to call when discovery is not running.
     */
    fun stopPeerDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) {
                Log.d(TAG, "stopPeerDiscovery reported reason=$reason (ignored)")
            }
        })
    }

    /** Removes the existing P2P group (disconnects from the peer group) */
    fun removeGroup(onComplete: () -> Unit = {}) {
        val mgr = manager ?: return onComplete()
        val ch = channel ?: return onComplete()
        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { onComplete() }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "removeGroup failed: reason=$reason")
                    onComplete()
                }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "removeGroup rejected (permission): ${e.message}")
            onComplete()
        }
    }

    fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            channel?.close()
        }
        channel = null
    }
}

// ─── Extension ────────────────────────────────────────────────────────────────

/**
 * Converts a WifiP2pDeviceList into GhostLink DiscoveredDevice models.
 * At this stage, devices are marked Unverified — the GhostLink alias exchange
 * happens at the socket layer in P2PConnectionManager.
 */
private fun WifiP2pDeviceList.toDiscoveredDevices(): List<DiscoveredDevice> =
    // deviceList is null-initialised by the framework until the first real peer
    // list arrives; iterating it directly threw NullPointerException.
    deviceList.orEmpty().map { wifiDevice ->
        DiscoveredDevice(
            deviceId   = wifiDevice.deviceAddress,
            deviceName = wifiDevice.deviceName?.takeIf { it.isNotBlank() }
                ?: wifiDevice.deviceAddress,
            alias      = null, // Set after application-layer handshake
            // Wi-Fi Direct exposes no RSSI; the model keeps it null so the UI
            // never renders a signal level it did not measure.
            rssi       = null,
            verificationState = DeviceVerificationState.Unverified,
            macAddress = wifiDevice.deviceAddress
        )
    }
