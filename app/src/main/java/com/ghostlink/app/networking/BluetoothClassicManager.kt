package com.ghostlink.app.networking

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.ghostlink.app.model.ConnectionMethod
import com.ghostlink.app.model.DeviceVerificationState
import com.ghostlink.app.model.DiscoveredDevice
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Bluetooth Classic RFCOMM transport. The RFCOMM link is Bluetooth-encrypted
 * by Android when the platform requires pairing; this transport does not add
 * application-layer end-to-end encryption.
 */
class BluetoothClassicManager(
    context: Context,
    private val aliasProvider: () -> String
) : GhostTransport {

    companion object {
        private const val TAG = "BluetoothClassic"
        private const val SERVICE_NAME = "GhostLink"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val SEND_QUEUE_CAPACITY = 64
        private val SERVICE_UUID: UUID =
            UUID.fromString("2fb0a3a0-8c23-4a67-ae3f-59e33c5fc2a1")
        private val ADDRESS_PATTERN = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? by lazy {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val lock = Any()
    @Volatile private var listeners: GhostTransportListeners? = null
    @Volatile private var closed = false

    private var discoveryGeneration = 0L
    private var discoveryStarting = false
    private var discoveryThread: HandlerThread? = null
    private var discoveryReceiver: BroadcastReceiver? = null
    private val discovered = LinkedHashMap<String, DiscoveredDevice>()

    private var hostingGeneration = 0L
    private var hostingStarting = false
    private var serverSocket: BluetoothServerSocket? = null

    private var connectionGeneration = 0L
    private var pendingSocket: BluetoothSocket? = null
    @Volatile
    private var activeConnection: ActiveConnection? = null

    private data class ActiveConnection(
        val socket: BluetoothSocket,
        val output: DataOutputStream,
        val outgoing: ArrayBlockingQueue<String>,
        @Volatile var writerThread: Thread? = null
    )

    override val displayName: String = "Bluetooth"

    override fun isAvailable(): Boolean {
        val localAdapter = adapter ?: return false
        return try {
            !closed && localAdapter.isEnabled &&
                hasConnectPermission() && hasScanPermission()
        } catch (_: SecurityException) {
            false
        }
    }

    override fun setListeners(listeners: GhostTransportListeners) {
        this.listeners = listeners
    }

    override fun startHosting() {
        if (closed) return
        val localAdapter = adapter
        if (localAdapter == null) {
            reportError("Bluetooth is not available on this device.")
            return
        }
        if (!hasConnectPermission()) {
            reportError("Bluetooth permission is required to host a connection.")
            return
        }

        val generation = synchronized(lock) {
            if (closed || hostingStarting || serverSocket != null ||
                pendingSocket != null || activeConnection != null
            ) return
            hostingStarting = true
            ++hostingGeneration
        }

        Thread({
            var createdServer: BluetoothServerSocket? = null
            try {
                val listeningSocket = localAdapter.listenUsingRfcommWithServiceRecord(
                    SERVICE_NAME,
                    SERVICE_UUID
                )
                createdServer = listeningSocket
                val shouldAccept = synchronized(lock) {
                    if (closed || generation != hostingGeneration) {
                        hostingStarting = false
                        false
                    } else {
                        serverSocket = listeningSocket
                        hostingStarting = false
                        true
                    }
                }
                if (!shouldAccept) {
                    listeningSocket.close()
                    return@Thread
                }

                listeners?.onWaitingForConnection()
                val incoming = listeningSocket.accept()
                listeningSocket.close()
                synchronized(lock) {
                    if (serverSocket === listeningSocket) serverSocket = null
                    if (generation == hostingGeneration) hostingStarting = true
                }
                acceptIncoming(incoming, generation)
            } catch (e: SecurityException) {
                synchronized(lock) {
                    if (generation == hostingGeneration) hostingStarting = false
                }
                closeQuietly(createdServer)
                if (!closed && generation == synchronized(lock) { hostingGeneration }) {
                    reportError("Bluetooth permission was denied while starting the host.")
                }
            } catch (e: IOException) {
                synchronized(lock) {
                    if (generation == hostingGeneration) {
                        hostingStarting = false
                        if (serverSocket === createdServer) serverSocket = null
                    }
                }
                closeQuietly(createdServer)
                if (!closed && generation == synchronized(lock) { hostingGeneration }) {
                    reportError("Could not start or continue listening for Bluetooth connections.")
                }
            }
        }, "GhostLink-RFCOMM-host").start()
    }

    override fun stopHosting() {
        val listening = synchronized(lock) {
            ++hostingGeneration
            hostingStarting = false
            serverSocket.also { serverSocket = null }
        }
        closeQuietly(listening)
    }

    override fun startDiscovery() {
        if (closed) return
        val localAdapter = adapter
        if (localAdapter == null) {
            reportError("Bluetooth is not available on this device.")
            return
        }
        if (!hasConnectPermission() || !hasScanPermission()) {
            reportError("Bluetooth scan and connect permissions are required to find devices.")
            return
        }

        val generation = synchronized(lock) {
            if (closed || discoveryStarting || discoveryReceiver != null) return
            discoveryStarting = true
            discovered.clear()
            ++discoveryGeneration
        }

        Thread({
            val callbackThread = HandlerThread("GhostLink-Bluetooth-discovery")
            callbackThread.start()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            @Suppress("DEPRECATION")
                            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                            if (device != null) addDiscoveredDevice(device, generation)
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            val devices = synchronized(lock) {
                                if (generation == discoveryGeneration) discovered.values.toList()
                                else return
                            }
                            listeners?.onDevicesFound(devices)
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(
                        receiver,
                        filter,
                        null,
                        Handler(callbackThread.looper),
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    appContext.registerReceiver(
                        receiver,
                        filter,
                        null,
                        Handler(callbackThread.looper)
                    )
                }
                val registered = synchronized(lock) {
                    if (closed || generation != discoveryGeneration) {
                        false
                    } else {
                        discoveryThread = callbackThread
                        discoveryReceiver = receiver
                        discoveryStarting = false
                        true
                    }
                }
                if (!registered) {
                    appContext.unregisterReceiver(receiver)
                    callbackThread.quitSafely()
                    return@Thread
                }

                listeners?.onDiscovering()
                localAdapter.bondedDevices.forEach { addDiscoveredDevice(it, generation) }
                if (!localAdapter.startDiscovery()) {
                    reportError("Bluetooth device discovery could not be started.")
                }
            } catch (e: SecurityException) {
                synchronized(lock) {
                    if (generation == discoveryGeneration) discoveryStarting = false
                }
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                    Log.d(TAG, "Discovery receiver was not registered.")
                }
                callbackThread.quitSafely()
                if (!closed) reportError("Bluetooth scan permission was denied.")
            } catch (e: IOException) {
                synchronized(lock) {
                    if (generation == discoveryGeneration) discoveryStarting = false
                }
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                    Log.d(TAG, "Discovery receiver was not registered.")
                }
                callbackThread.quitSafely()
                if (!closed) reportError("Could not initialize Bluetooth device discovery.")
            } catch (e: IllegalArgumentException) {
                synchronized(lock) {
                    if (generation == discoveryGeneration) discoveryStarting = false
                }
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                    Log.d(TAG, "Discovery receiver was not registered.")
                }
                callbackThread.quitSafely()
                if (!closed) reportError("Could not register the Bluetooth discovery receiver.")
            }
        }, "GhostLink-Bluetooth-discovery-setup").start()
    }

    private fun addDiscoveredDevice(device: BluetoothDevice, generation: Long) {
        if (!hasConnectPermission()) {
            reportError("Bluetooth connect permission is required to read nearby device details.")
            return
        }
        try {
            val address = device.address
            val label = device.name?.takeIf { it.isNotBlank() } ?: address
            val peer = DiscoveredDevice(
                deviceId = address,
                deviceName = label,
                verificationState = DeviceVerificationState.Unverified,
                macAddress = address,
                connectionMethod = ConnectionMethod.Bluetooth
            )
            val snapshot = synchronized(lock) {
                if (closed || generation != discoveryGeneration) return
                discovered[address] = peer
                discovered.values.toList()
            }
            listeners?.onDevicesFound(snapshot)
        } catch (e: SecurityException) {
            reportError("Bluetooth connect permission is required to read nearby device details.")
        }
    }

    override fun stopDiscovery() {
        val resources = synchronized(lock) {
            ++discoveryGeneration
            discoveryStarting = false
            val receiver = discoveryReceiver
            val thread = discoveryThread
            discoveryReceiver = null
            discoveryThread = null
            discovered.clear()
            receiver to thread
        }

        Thread({
            try {
                if (hasScanPermission()) adapter?.cancelDiscovery()
            } catch (e: SecurityException) {
                reportError("Bluetooth scan permission was denied while stopping discovery.")
            }
            resources.first?.let { receiver ->
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    Log.d(TAG, "Discovery receiver was already unregistered.")
                }
            }
            resources.second?.quitSafely()
        }, "GhostLink-Bluetooth-discovery-stop").start()
    }

    override fun connect(device: DiscoveredDevice) {
        if (closed) return
        if (!hasConnectPermission()) {
            reportError("Bluetooth connect permission is required to connect to a device.")
            return
        }
        val localAdapter = adapter
        if (localAdapter == null) {
            reportError("Bluetooth is not available on this device.")
            return
        }
        val address = device.macAddress ?: device.deviceId
        if (!ADDRESS_PATTERN.matches(address)) {
            reportError("The selected device does not have a valid Bluetooth address.")
            return
        }

        stopDiscovery()
        stopHosting()
        disconnectConnection(notify = true)
        val generation = synchronized(lock) {
            if (closed) return
            ++connectionGeneration
        }

        Thread({
            var socket: BluetoothSocket? = null
            try {
                if (!hasConnectPermission()) {
                    reportError("Bluetooth connect permission is required to connect to a device.")
                    return@Thread
                }
                try {
                    localAdapter.cancelDiscovery()
                } catch (e: SecurityException) {
                    reportError("Bluetooth scan permission is required before connecting.")
                    return@Thread
                }
                val remoteDevice = localAdapter.getRemoteDevice(address)
                val connectionSocket = remoteDevice.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket = connectionSocket
                val accepted = synchronized(lock) {
                    if (closed || generation != connectionGeneration) false
                    else {
                        pendingSocket = connectionSocket
                        true
                    }
                }
                if (!accepted) {
                    closeQuietly(connectionSocket)
                    return@Thread
                }

                listeners?.onConnecting(device.deviceName)
                val timeout = Thread({
                    try {
                        Thread.sleep(CONNECT_TIMEOUT_MS.toLong())
                        val stillConnecting = synchronized(lock) {
                            pendingSocket === connectionSocket &&
                                generation == connectionGeneration
                        }
                        if (stillConnecting) closeQuietly(connectionSocket)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }, "GhostLink-RFCOMM-connect-timeout")
                timeout.start()
                try {
                    connectionSocket.connect()
                } finally {
                    timeout.interrupt()
                }
                val input = DataInputStream(connectionSocket.inputStream)
                val output = DataOutputStream(connectionSocket.outputStream)
                val response = withSocketTimeout(connectionSocket, HANDSHAKE_TIMEOUT_MS) {
                    GhostMessageCodec.writeMessage(output, GhostProtocol.buildHello(localAlias()))
                    GhostMessageCodec.readMessage(input)
                }
                val alias = response
                    ?.takeIf { it.startsWith(GhostProtocol.ACK_PREFIX) }
                    ?.let(GhostProtocol::parseHandshake)
                if (alias == null) throw IOException("Peer did not complete the GhostLink handshake.")

                val session = activateConnection(connectionSocket, output, generation) ?: return@Thread
                listeners?.onConnected(alias, device.deviceName)
                startWriter(session)
                receiveMessages(session, input)
            } catch (e: SecurityException) {
                finishPendingConnection(socket, generation)
                if (!closed && generation == synchronized(lock) { connectionGeneration }) {
                    reportError("Bluetooth connect permission was denied.")
                }
            } catch (e: IOException) {
                finishPendingConnection(socket, generation)
                if (!closed && generation == synchronized(lock) { connectionGeneration }) {
                    reportError("Could not connect to ${device.deviceName}. Check that the peer is nearby and running GhostLink.")
                }
            }
        }, "GhostLink-RFCOMM-connect").start()
    }

    private fun acceptIncoming(socket: BluetoothSocket, hostGeneration: Long) {
        val generation = synchronized(lock) {
            if (closed || hostGeneration != hostingGeneration ||
                activeConnection != null || pendingSocket != null
            ) {
                if (hostGeneration == hostingGeneration) hostingStarting = false
                closeQuietly(socket)
                return
            }
            ++connectionGeneration
            pendingSocket = socket
            hostingStarting = false
            connectionGeneration
        }

        Thread({
            try {
                val input = DataInputStream(socket.inputStream)
                val output = DataOutputStream(socket.outputStream)
                val peerLabel = try {
                    socket.remoteDevice.name?.takeIf { it.isNotBlank() } ?: socket.remoteDevice.address
                } catch (_: SecurityException) {
                    "Bluetooth peer"
                }
                listeners?.onConnecting(peerLabel)
                val alias = withSocketTimeout(socket, HANDSHAKE_TIMEOUT_MS) {
                    val hello = GhostMessageCodec.readMessage(input)
                    if (hello == null || !GhostProtocol.isHello(hello)) {
                        throw IOException("Peer did not send a GhostLink handshake.")
                    }
                    val peerAlias = GhostProtocol.parseHandshake(hello)
                        ?: throw IOException("Peer sent an invalid GhostLink alias.")
                    GhostMessageCodec.writeMessage(output, GhostProtocol.buildAck(localAlias()))
                    peerAlias
                }
                val session = activateConnection(socket, output, generation) ?: return@Thread
                listeners?.onConnected(alias, peerLabel)
                startWriter(session)
                receiveMessages(session, input)
            } catch (e: SecurityException) {
                finishPendingConnection(socket, generation)
                if (!closed) reportError("Bluetooth connect permission was denied during the handshake.")
            } catch (e: IOException) {
                finishPendingConnection(socket, generation)
                if (!closed && generation == synchronized(lock) { connectionGeneration }) {
                    reportError("The incoming connection did not complete the GhostLink handshake.")
                }
            }
        }, "GhostLink-RFCOMM-accept").start()
    }

    private fun activateConnection(
        socket: BluetoothSocket,
        output: DataOutputStream,
        generation: Long
    ): ActiveConnection? = synchronized(lock) {
        if (closed || generation != connectionGeneration || pendingSocket !== socket) {
            closeQuietly(socket)
            return null
        }
        ActiveConnection(
            socket = socket,
            output = output,
            outgoing = ArrayBlockingQueue<String>(SEND_QUEUE_CAPACITY),
        ).also {
            pendingSocket = null
            activeConnection = it
        }
    }

    private fun startWriter(connection: ActiveConnection) {
        val writer = Thread({
            try {
                while (!Thread.currentThread().isInterrupted && isCurrent(connection)) {
                    val text = connection.outgoing.poll(1, TimeUnit.SECONDS) ?: continue
                    GhostMessageCodec.writeMessage(connection.output, text)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: IOException) {
                if (isCurrent(connection)) {
                    reportError("The Bluetooth message could not be sent.")
                    finishConnection(connection, "Bluetooth message send failed.")
                }
            }
        }, "GhostLink-RFCOMM-writer")
        connection.writerThread = writer
        writer.start()
    }

    private fun receiveMessages(connection: ActiveConnection, input: DataInputStream) {
        try {
            while (!Thread.currentThread().isInterrupted && isCurrent(connection)) {
                val text = GhostMessageCodec.readMessage(input) ?: break
                listeners?.onMessageReceived(text)
            }
            finishConnection(connection, "The Bluetooth peer closed the connection.")
        } catch (e: IOException) {
            if (isCurrent(connection)) finishConnection(connection, "The Bluetooth connection was interrupted.")
        }
    }

    override fun sendMessage(text: String): Boolean {
        val connection = activeConnection ?: return false
        if (closed || !isCurrent(connection)) return false
        return connection.outgoing.offer(text)
    }

    override fun disconnect() {
        disconnectConnection(notify = true)
    }

    private fun disconnectConnection(notify: Boolean) {
        val resources = synchronized(lock) {
            ++connectionGeneration
            val active = activeConnection
            activeConnection = null
            val pending = pendingSocket
            pendingSocket = null
            Triple(active, pending, connectionGeneration)
        }
        closeQuietly(resources.second)
        resources.first?.let { connection ->
            closeQuietly(connection.socket)
            connection.writerThread?.interrupt()
            if (notify && !closed) reportDisconnected(null, resources.third)
        }
    }

    private fun finishPendingConnection(socket: BluetoothSocket?, generation: Long) {
        val shouldClose = synchronized(lock) {
            if (generation == connectionGeneration && pendingSocket === socket) {
                pendingSocket = null
                true
            } else false
        }
        if (shouldClose) closeQuietly(socket)
    }

    private fun finishConnection(connection: ActiveConnection, reason: String?) {
        val finishedGeneration = synchronized(lock) {
            if (activeConnection === connection) {
                activeConnection = null
                connectionGeneration
            } else null
        }
        if (finishedGeneration != null) {
            closeQuietly(connection.socket)
            connection.writerThread?.interrupt()
            if (!closed) reportDisconnected(reason, finishedGeneration)
        }
    }

    private fun isCurrent(connection: ActiveConnection): Boolean =
        !closed && synchronized(lock) { activeConnection === connection }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        stopDiscovery()
        stopHosting()
        disconnectConnection(notify = false)
        listeners = null
    }

    private fun reportError(message: String) {
        Thread({ if (!closed) listeners?.onError(message) }, "GhostLink-RFCOMM-callback").start()
    }

    private fun reportDisconnected(reason: String?, generation: Long) {
        Thread({
            val stillDisconnected = synchronized(lock) {
                !closed && generation == connectionGeneration && activeConnection == null
            }
            if (stillDisconnected) listeners?.onDisconnected(reason)
        }, "GhostLink-RFCOMM-callback").start()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun localAlias(): String = aliasProvider()

    private fun <T> withSocketTimeout(
        socket: BluetoothSocket,
        timeoutMs: Int,
        operation: () -> T
    ): T {
        val operationFinished = AtomicBoolean(false)
        val timeoutThread = Thread({
            try {
                Thread.sleep(timeoutMs.toLong())
                if (operationFinished.compareAndSet(false, true)) closeQuietly(socket)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "GhostLink-RFCOMM-handshake-timeout")
        timeoutThread.start()
        try {
            return operation()
        } finally {
            operationFinished.set(true)
            timeoutThread.interrupt()
        }
    }

    private fun closeQuietly(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.d(TAG, "Socket was already closed.")
        }
    }

    private fun closeQuietly(socket: BluetoothServerSocket?) {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.d(TAG, "Server socket was already closed.")
        }
    }
}
