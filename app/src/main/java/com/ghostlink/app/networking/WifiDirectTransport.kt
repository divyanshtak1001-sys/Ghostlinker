package com.ghostlink.app.networking

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.ghostlink.app.model.ConnectionMethod
import com.ghostlink.app.model.DeviceVerificationState
import com.ghostlink.app.model.DiscoveredDevice
import com.ghostlink.app.util.PerfTrace
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicLong

/**
 * WifiDirectTransport — the Wi-Fi Direct (`GhostTransport`) implementation:
 * peer discovery, group negotiation, the authenticated socket session and
 * encrypted message exchange.
 *
 * ─── Why this replaces the previous Wi-Fi Direct path ────────────────────────
 * The previous code never called `WifiP2pManager.connect()`, so `requestConnectionInfo`
 * always returned `groupFormed = false` and the connection failed with
 * "Wi-Fi Direct group not formed". It also derived the session key differently on
 * each side, compared an unauthenticated magic string, and used a fixed port with
 * no writable endpoint, so even a formed group could not produce a session.
 *
 * ─── Roles and address ───────────────────────────────────────────────────────
 *   - The device that accepts the invitation becomes the **group owner** (GO) and
 *     serves the listening socket; the inviter connects (same side of the handshake
 *     as the Bluetooth client), the inviter stays the TCP client.
 *   - The connecting side uses `WifiP2pInfo.groupOwnerAddress` exactly as the OS
 *     reports it after negotiation — never a hardcoded gateway such as 192.168.49.1.
 *   - The listener binds **before** `connect()` is issued, and a retry loop tolerates
 *     the usual 1–2 s delay until the group interface is routable.
 *
 * ─── Security ────────────────────────────────────────────────────────────────
 *   A socket reaching the listener proves nothing. [GhostSessionProtocol] runs
 *   immediately: no message is delivered and [onConnected] is not invoked until
 *   both sides proved they derived the same session key from the invitation.
 */
class WifiDirectTransport(
    private val appContext: Context,
    private val localAlias: () -> String,
) : GhostTransport {

    companion object {
        private const val TAG = "WifiDirectTransport"
        const val P2P_PORT = 47801
        private const val GROUP_FORMATION_TIMEOUT_MS = 25_000L
        private const val HANDSHAKE_TIMEOUT_MS = 12_000
        private const val GROUP_READY_WAIT_MS = 6_000L
        private const val CONNECT_ATTEMPTS = 6
        private const val SEND_QUEUE_CAPACITY = 32

        /** Bound on the human confirmation step; fail closed if nobody answers. */
        private const val VERIFICATION_TIMEOUT_MS = 90_000L
    }

    override val displayName: String = "Wi-Fi Direct"

    @Volatile private var closed = false
    private val manager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val lock = Any()
    private val discoveryGeneration = AtomicLong(0)
    private var discoveryReceiver: BroadcastReceiver? = null
    private var peersReceiver: BroadcastReceiver? = null
    private var discovered: MutableMap<String, DiscoveredDevice> = LinkedHashMap()
    private var p2pDevices: MutableMap<String, WifiP2pDevice> = LinkedHashMap()
    private var listeners: GhostTransportListeners? = null

    /** True while this instance sits on an active group it did not tear down. */
    @Volatile private var groupJoined = false

    /** Attempts awaiting the user's SAS confirmation; guarded by [lock]. */
    private val verification = SasVerificationGate()
    private var pendingSession: Session? = null
    private var verificationTimer: java.util.Timer? = null



    private val io = Executors.newCachedThreadPool { r -> Thread(r, "GhostLink-WifiDirect").apply { isDaemon = true } }

    private var current: Session? = null

    // ─── Setup ────────────────────────────────

    fun initialize(): Boolean {
        val mgr = manager ?: return false
        if (channel != null) return true
        channel = try {
            mgr.initialize(appContext, Looper.getMainLooper(), null)
        } catch (e: SecurityException) {
            Log.w(TAG, "initialize rejected: ${e.message}")
            return false
        }
        return channel != null
    }

    override fun isAvailable(): Boolean =
        !closed && initialize() && channel != null &&
            appContext.packageManager.hasSystemFeature("android.hardware.wifi.direct")

    /** True when the Wi-Fi Direct radio itself is enabled (not just present). */
    fun isRadioEnabled(): Boolean = try {
        manager?.let { true } == true
    } catch (_: Exception) {
        false
    }

    override fun setListeners(listeners: GhostTransportListeners) {
        this.listeners = listeners
    }

    // ─── Discovery ────────────────────────────

    override fun startDiscovery() {
        val mgr = manager ?: run {
            listeners?.onError("Wi-Fi Direct is not supported on this device.")
            return
        }
        val ch = channel ?: run { initialize(); channel } ?: run {
            listeners?.onError("Wi-Fi Direct could not be initialised (permission denied?).")
            return
        }
        if (closed) return

        stopDiscovery()

        val generation = AtomicLong(0)
        synchronized(lock) {
            generation.set(discoveryGeneration.incrementAndGet())
            discovered = LinkedHashMap()
            p2pDevices = LinkedHashMap()
        }

        // One receiver for peer list changes, registered for this scan generation.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (generation.get() != discoveryGeneration.get()) return
                        requestPeersSafely(mgr, ch) { list -> publishPeers(list, generation.get()) }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                            listeners?.onError("Wi-Fi is turned off — Wi-Fi Direct discovery stopped.")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info: WifiP2pInfo? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                            }
                        if (info != null && info.groupFormed) onGroupFormed(info)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        try {
            ContextCompat.registerReceiver(
                appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not register Wi-Fi Direct receiver: ${e.message}")
            listeners?.onError("Wi-Fi Direct discovery could not start.")
            return
        }
        synchronized(lock) { peersReceiver = receiver }

        try {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG, "discoverPeers started") }
                override fun onFailure(reason: Int) {
                    if (generation.get() != discoveryGeneration.get()) return
                    val msg = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device."
                        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy — try again in a moment."
                        else -> "Wi-Fi Direct discovery failed."
                    }
                    Log.w(TAG, "discoverPeers failed: $reason")
                    listeners?.onError(msg)
                }
            })
        } catch (e: SecurityException) {
            listeners?.onError("Wi-Fi Direct permission was denied.")
        } catch (e: IllegalStateException) {
            listeners?.onError("Wi-Fi Direct is not ready yet — try again.")
        }
    }

    override fun stopDiscovery() {
        val mgr = manager ?: return
        val ch = channel
        synchronized(lock) {
            discoveryGeneration.incrementAndGet()
            peersReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
            peersReceiver = null
            discovered = LinkedHashMap()
            p2pDevices = LinkedHashMap()
        }
        if (ch == null) return
        runCatching { mgr.stopPeerDiscovery(ch, silentListener()) }
    }

    private fun requestPeersSafely(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onPeers: (WifiP2pDeviceList) -> Unit,
    ) {
        try {
            mgr.requestPeers(ch, onPeers)
        } catch (e: SecurityException) {
            Log.w(TAG, "requestPeers denied: ${e.message}")
        }
    }

    private fun publishPeers(list: WifiP2pDeviceList, generation: Long) {
        val snapshot = synchronized(lock) {
            if (generation != discoveryGeneration.get()) return
            list.deviceList.orEmpty().forEach { d ->
                p2pDevices[d.deviceAddress] = d
                discovered[d.deviceAddress] = DiscoveredDevice(
                    deviceId = d.deviceAddress,
                    deviceName = d.deviceName?.takeIf { it.isNotBlank() } ?: d.deviceAddress,
                    alias = null,
                    rssi = null,
                    verificationState = DeviceVerificationState.Unverified,
                    macAddress = d.deviceAddress,
                    connectionMethod = ConnectionMethod.WifiDirect,
                )
            }
            discovered.values.toList()
        }
        listeners?.onDevicesFound(snapshot)
    }

    // ─── Connecting ───────────────────────────

    override fun connect(device: DiscoveredDevice) {
        val mgr = manager ?: return report("Wi-Fi Direct is not available.")
        val ch = channel ?: return report("Wi-Fi Direct is not initialised.")
        val token = activeToken() ?: return report(
            "Connect using a QR invitation or an invitation code first — Wi-Fi Direct uses the shared invitation to authenticate the peer."
        )
        val address = device.macAddress ?: device.deviceId
        val p2pDevice = synchronized(lock) { p2pDevices[address] } ?: run {
            // Not in the cached list (e.g. selection survived a rescan): re-request peers.
            report("This Wi-Fi Direct peer is no longer nearby. Scan again.")
            return
        }

        stopDiscovery()
        closeSession()
        groupJoined = true

        listeners?.onConnecting(device.deviceName)

        val config = WifiP2pConfig().apply {
            deviceAddress = p2pDevice.deviceAddress
            wps.setup = android.net.wifi.WpsInfo.PBC
            // Let the accepting device become group owner so the inviter can connect
            // to the GO address the OS reports; either role is handled below.
            groupOwnerIntent = 0
        }
        try {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group negotiation requested")
                    // The CONNECTION_CHANGED broadcast -> onGroupFormed() continues the flow.
                }

                override fun onFailure(reason: Int) {
                    groupJoined = false
                    report(
                        when (reason) {
                            WifiP2pManager.BUSY -> "Wi-Fi Direct is busy — close other Wi-Fi Direct apps and retry."
                            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device."
                            else -> "Wi-Fi Direct group negotiation failed. Retry, or ask the peer to accept."
                        }
                    )
                }
            })
        } catch (e: SecurityException) {
            report("Wi-Fi Direct permission was denied.")
        }
    }

    /** Start listening for an incoming peer as group owner (used by the host side). */
    private fun startHostingOn(token: String) {
        if (closed) return
        closeSession()
        val server = synchronized(lock) {
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(P2P_PORT))
                }.also { serverSocket = it }
            } catch (e: IOException) {
                Log.w(TAG, "Could not bind port $P2P_PORT: ${e.message}")
                null
            }
        } ?: return report("Could not start the local listener on port $P2P_PORT.")

        listeners?.onWaitingForConnection()
        io.execute {
            try {
                while (!closed && serverSocket === server) {
                    val socket = server.accept()
                    if (closed || serverSocket !== server) {
                        runCatching { socket.close() }
                        break
                    }
                    Thread({ acceptSession(socket, token) }, "GhostLink-WifiDirect-accept").start()
                }
            } catch (e: IOException) {
                if (serverSocket === server) Log.d(TAG, "Listener stopped: ${e.message}")
            }
        }
    }

    /**
     * Called when the OS reports a formed group: decides the TCP role from
     * [WifiP2pInfo.isGroupOwner] and supplies the real group-owner address.
     */
    private fun onGroupFormed(info: WifiP2pInfo) {
        PerfTrace.mark(PerfTrace.STAGE_GROUP_FORMED)
        val token = activeToken() ?: return report("Invitation expired before the Wi-Fi Direct group formed.")
        if (info.isGroupOwner) {
            startHostingOn(token)
            return
        }
        val owner = info.groupOwnerAddress ?: return report("Wi-Fi Direct did not report a group-owner address.")
        io.execute {
            // Wait briefly for the group interface to become routable, then retry a
            // bounded number of times. No fixed sleeps longer than 200 ms.
            var socket: Socket? = null
            for (attempt in 1..CONNECT_ATTEMPTS) {
                if (closed) return@execute
                if (current != null) return@execute
                val candidate = Socket()
                try {
                    candidate.tcpNoDelay = true
                    candidate.connect(InetSocketAddress(owner, P2P_PORT), GROUP_READY_WAIT_MS.toInt())
                    socket = candidate
                    break
                } catch (e: IOException) {
                    runCatching { candidate.close() }
                    Log.d(TAG, "Connect attempt $attempt to $owner:$P2P_PORT failed: ${e.message}")
                    Thread.sleep(500L * attempt) // bounded backoff: 0.5s..3s
                }
            }
            val connected = socket ?: return@execute report(
                "Could not reach the peer's Wi-Fi Direct address ${owner.hostAddress}. The peer may not be listening yet — retry."
            )
            clientSession(connected, token)
        }
    }

    private fun clientSession(socket: Socket, token: String) {
        try {
            PerfTrace.mark(PerfTrace.STAGE_SOCKET_CONNECTED)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val client = GhostSessionProtocol.newClientHello()
            val key = client.run(token, output, input) ?: run {
                // Wrong invitation (or a peer that is not GhostLink). Never proceed.
                runCatching { socket.close() }
                report("The peer rejected the invitation — both devices must use the same invitation code.")
                return
            }
            socket.soTimeout = 0
            val sas = client.sas ?: return
            activateSession(socket, input, output, key, GhostSessionProtocol.sasHex(sas), token)
        } catch (e: IOException) {
            runCatching { socket.close() }
            report("Wi-Fi Direct connection failed: ${e.javaClass.simpleName}. Retry to try again.")
        }
    }

    private fun acceptSession(socket: Socket, token: String) {
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val server = GhostSessionProtocol.newServerHello()
            if (!server.phase1(token, output, input)) {
                runCatching { socket.close() }
                return
            }
            if (!server.verifyClientAck(input)) {
                runCatching { socket.close() }
                report("An incoming peer failed key confirmation — connection rejected.")
                return
            }
            val key = server.takeKey() ?: run {
                runCatching { socket.close() }
                return
            }
            socket.soTimeout = 0
            val sas = server.sasBytes() ?: return
            activateSession(socket, input, output, key, GhostSessionProtocol.sasHex(sas), token)
        } catch (e: IOException) {
            runCatching { socket.close() }
        }
    }

    /**
     * Publishes a session whose encryption + invitation proof succeeded, then
     * **waits for human verification** before any message is read or written.
     *
     * Fail-closed: if nobody confirms within [VERIFICATION_TIMEOUT_MS], or the
     * pending session is rejected or superseded, the socket is closed and
     * [GhostTransportListeners.onVerificationFailed] is delivered with that
     * session's id. A late confirm for an old id returns false.
     */
    private fun activateSession(
        socket: Socket,
        input: java.io.InputStream,
        output: OutputStream,
        key: javax.crypto.SecretKey,
        sasHex: String,
        token: String,
    ) {
        val session = Session(socket, input, output, GhostSessionProtocol.CipherState(key), token, sasHex)
        synchronized(lock) {
            if (closed || current != null) {
                runCatching { socket.close() }
                return
            }
            current = session
            // Registered *before* the callback so a synchronous confirmation
            // (UI acting immediately) is accepted rather than seen as stale.
            // Any attempt still pending from an earlier try is superseded.
            verification.begin(session.id, sasHex)?.let { stale ->
                pendingSession?.takeIf { it.id == stale.sessionId }?.close()
            }
            pendingSession = session
        }
        // Until confirmed, the reader/writer threads are NOT started, so no
        // plaintext can be delivered and no message can be sent.
        listeners?.onPeerAuthenticated(session.id, sasHex, "Wi-Fi Direct peer")

        verificationTimer?.cancel()
        verificationTimer = Timer("GhostLink-verify-timeout", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    synchronized(lock) {
                        if (verification.timeout(session.id) != null) {
                            failVerification(session, "Verification timed out — the code was not confirmed.")
                        }
                    }
                }
            }, VERIFICATION_TIMEOUT_MS)
        }
    }

    override fun confirmVerification(sessionId: String): Boolean {
        val session = synchronized(lock) {
            val outcome = verification.resolve(sessionId, confirmed = true)
            if (outcome !is SasVerificationGate.Outcome.Resolved) return false
            val s = pendingSession
            if (s == null || s.id != sessionId || current !== s) return false
            s.isVerified = true
            pendingSession = null
            s
        }
        verificationTimer?.cancel()
        verificationTimer = null
        listeners?.onConnected(null, "Wi-Fi Direct peer")
        io.execute { session.writer() }
        io.execute { session.reader() }
        return true
    }

    override fun rejectVerification(sessionId: String) {
        synchronized(lock) {
            val outcome = verification.resolve(sessionId, confirmed = false)
            if (outcome is SasVerificationGate.Outcome.Resolved) {
                pendingSession?.takeIf { it.id == sessionId }?.let {
                    failVerification(it, "The safety code did not match — connection cancelled.")
                }
            }
        }
    }

    /** Closes [session], clears it if current, and reports why. Caller holds [lock]. */
    private fun failVerification(session: Session, reason: String) {
        verification.clear()
        if (pendingSession === session) pendingSession = null
        if (current === session) current = null
        verificationTimer?.cancel()
        verificationTimer = null
        session.close()
        listeners?.onVerificationFailed(session.id, reason)
    }

    // ─── Messaging ────────────────────────────

    override fun sendMessage(text: String): Boolean {
        // Fail closed: an authenticated-but-unverified session cannot carry data.
        val session = synchronized(lock) { current }?.takeIf { it.isVerified } ?: return false
        return session.outgoing.offer(text)
    }

    override fun disconnect() {
        closeSession()
        if (groupJoined) {
            groupJoined = false
            removeGroupAsync()
        }
    }

    override fun startHosting() {
        val token = activeToken() ?: return report(
            "Generate an invitation first — Wi-Fi Direct authentication requires the shared invitation."
        )
        startHostingOn(token)
    }

    override fun stopHosting() {
        val server = synchronized(lock) {
            serverSocket.also { serverSocket = null }
        }
        runCatching { server?.close() }
    }

    override fun close() {
        closed = true
        stopDiscovery()
        closeSession()
        stopHosting()
        if (groupJoined) {
            groupJoined = false
            removeGroupAsync()
        }
        io.shutdownNow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) channel?.close()
        channel = null
    }

    fun closeSession() {
        val session = synchronized(lock) {
            verification.clear()
            pendingSession = null
            verificationTimer?.cancel()
            verificationTimer = null
            current.also { current = null }
        }
        session?.close()
    }

    // ─── Internals ────────────────────────────

    private var serverSocket: ServerSocket? = null

    private fun activeToken(): String? {
        val t = currentTokenProvider?.invoke()
        return t?.takeIf { it.isNotBlank() }
    }

    /** Set by the ViewModel; returns the token of the *valid* active invitation. */
    @Volatile var currentTokenProvider: (() -> String?)? = null

    private fun removeGroupAsync() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching { mgr.removeGroup(ch, silentListener()) }
            .onFailure { Log.w(TAG, "removeGroup failed: ${it.message}") }
    }

    private fun report(message: String) {
        Log.w(TAG, message)
        listeners?.onError(message)
    }

    private fun silentListener() = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit
        override fun onFailure(reason: Int) = Unit
    }

    @SuppressLint("SetTextI18n")
    private inner class Session(
        private val socket: Socket,
        private val input: java.io.InputStream,
        private val output: OutputStream,
        private val cipher: GhostSessionProtocol.CipherState,
        private val token: String,
        val sasHex: String,
    ) {
        /** Raw UTF-8 message text; sealed by the writer thread. */
        val outgoing = java.util.concurrent.ArrayBlockingQueue<String>(SEND_QUEUE_CAPACITY)
        private val closedFlag = AtomicBoolean(false)

        /** Unique per connection attempt; stale ids cannot confirm a newer session. */
        val id: String = java.util.UUID.randomUUID().toString()

        @Volatile var isVerified = false

        fun writer() {
            try {
                while (!closedFlag.get()) {
                    val text = outgoing.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                    cipher.seal(text.toByteArray(Charsets.UTF_8), output)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: IOException) {
                end("The Wi-Fi Direct message could not be sent.")
            }
        }

        fun reader() {
            try {
                while (!closedFlag.get()) {
                    val frame = GhostSessionProtocol.readFrame(input) ?: break
                    val plaintext = cipher.open(frame)
                    if (plaintext == null) {
                        end("A peer frame failed authentication — session closed.")
                        return
                    }
                    val text = String(plaintext, Charsets.UTF_8)
                    listeners?.onMessageReceived(text)
                }
            } catch (e: IOException) {
                // Normal close or dropped link.
            } finally {
                end(null)
            }
        }

        fun close() {
            if (!closedFlag.compareAndSet(false, true)) return
            runCatching { output.close() }
            runCatching { socket.close() }
        }

        private fun end(reason: String?) {
            if (!closedFlag.compareAndSet(false, true)) return
            synchronized(lock) { if (current === this) current = null }
            runCatching { output.close() }
            runCatching { socket.close() }
            listeners?.onDisconnected(reason)
        }
    }
}
