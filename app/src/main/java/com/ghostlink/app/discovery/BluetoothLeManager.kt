package com.ghostlink.app.discovery

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.ghostlink.app.model.DiscoveredDevice
import com.ghostlink.app.model.DeviceVerificationState
import com.ghostlink.app.model.ConnectionMethod
import java.util.UUID

/**
 * BluetoothLeManager — Manages BLE scanning and advertising for GhostLink peer discovery.
 *
 * Role in the architecture:
 *   BLE is used ONLY for discovery and initial invitation rendezvous.
 *   It is NOT used for chat message transport (Wi-Fi Direct handles that).
 *
 * Advertising payload:
 *   Service UUID: GhostLink-specific 128-bit UUID — identifies the app.
 *   Service data: temporary session token (first 16 bytes, not the user alias).
 *   Nothing in the advertisement contains:
 *     - The user's real identity
 *     - The GhostLink alias
 *     - Chat messages
 *     - Persistent tracking identifiers
 *
 * Privacy notes:
 *   - BLE MAC addresses are randomised on modern Android (API 26+) and vary per session.
 *   - GhostLink uses a rotating service data payload keyed to the current invitation.
 *   - Advertising stops immediately when the invitation expires or is cancelled.
 *   - The OS MAC address randomisation is outside GhostLink's control.
 *
 * Hardware limitations (documented honestly):
 *   - Not all Android devices support simultaneous scanning + advertising.
 *   - Emulators do not support BLE hardware.
 *   - Certain devices (e.g. some Xiaomi models) restrict background BLE advertising.
 *   - All BLE tests require physical Android devices with BLE support.
 */
class BluetoothLeManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothLeManager"

        /**
         * GhostLink service UUID — identifies GhostLink advertisements.
         * Devices not running GhostLink will not advertise this UUID.
         *
         * IMPORTANT — why this is a 16-bit UUID and not a 128-bit one:
         *
         * A legacy BLE advertisement carries a hard limit of 31 payload bytes.
         * Each AD structure costs [1 length byte] + [1 type byte] + value:
         *
         *   Flags          : 1 + 1 + 3              =  5 bytes
         *   16-bit UUID    : 1 + 1 + 2              =  4 bytes
         *   Service data   : 1 + 1 + 2 (uuid) + 8   = 12 bytes
         *                                   TOTAL  = 21 bytes  ✓ (10 spare)
         *
         * With a 128-bit UUID the same advertisement costs:
         *
         *   Flags          : 1 + 1 + 3              =  5 bytes
         *   128-bit UUID   : 1 + 1 + 16             = 18 bytes
         *   Service data   : 1 + 1 + 16 (uuid) + 8  = 26 bytes
         *                                   TOTAL  = 49 bytes  ✗ (18 over)
         *
         * That overflow is what silently disabled GhostLink discovery: the
         * 128-bit UUID version exceeded 31 bytes, AdvertiseCallback reported
         * ADVERTISE_FAILED_DATA_TOO_LARGE, and no advertisement was ever sent,
         * so scanning devices could never find a peer.
         *
         * A 16-bit UUID is generated from the Bluetooth SIG base UUID
         * (0000xxxx-0000-1000-8000-00805F9B34FB) so it is a valid, standard
         * short UUID while still being unique to GhostLink in practice.
         */
        const val GHOSTLINK_SERVICE_UUID_16: String = "FD9C"

        val GHOSTLINK_SERVICE_UUID: UUID =
            UUID.fromString("0000FD9C-0000-1000-8000-00805F9B34FB")

        val GHOSTLINK_PARCEL_UUID = ParcelUuid(GHOSTLINK_SERVICE_UUID)

        /**
         * Bytes of invitation-token material carried in the advertisement.
         * 8 bytes keeps the full payload at 21/31 bytes — see the budget
         * breakdown on [GHOSTLINK_SERVICE_UUID].
         */
        private const val MAX_SERVICE_DATA_BYTES = 8

        /** True when the OS-level BLUETOOTH_CONNECT permission is held. */
        private fun hasConnectPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED

        /** True when the OS-level BLUETOOTH_SCAN permission is held. */
        private fun hasScanPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED

        /** True when the OS-level BLUETOOTH_ADVERTISE permission is held. */
        private fun hasAdvertisePermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_ADVERTISE
                ) == PackageManager.PERMISSION_GRANTED
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var activeScanCallback: ScanCallback? = null

    /** Bounded recovery counter for SCAN_FAILED_APPLICATION_REGISTRATION_FAILED. */
    private var retries = 0

    @Volatile private var closedFlag = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var activeAdvertiseCallback: AdvertiseCallback? = null

    /** True when the hardware supports BLE at all (independent of power state). */
    fun isSupported(): Boolean = bluetoothAdapter != null

    /** True when the Bluetooth radio is currently powered on. */
    fun isEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * @return true if BLE scanning/advertising can actually run right now:
     *         the radio is supported, powered on, and the runtime permissions
     *         required on this API level have been granted.
     */
    fun isAvailable(): Boolean =
        isEnabled() &&
            bluetoothAdapter?.isMultipleAdvertisementSupported == true &&
            hasScanPermission(context) &&
            hasAdvertisePermission(context)

    /**
     * Starts BLE scanning for nearby GhostLink devices.
     * Filters by the GhostLink service UUID so only GhostLink peers are reported.
     *
     * @param onDeviceFound Called with each newly discovered DiscoveredDevice.
     */
    fun startScanning(onDeviceFound: (DiscoveredDevice) -> Unit) {
        val adapter = bluetoothAdapter ?: run {
            Log.w(TAG, "Bluetooth adapter unavailable")
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled — enable it to scan")
            return
        }
        // API 31+ throws SecurityException from the BLE APIs without these.
        if (!hasScanPermission(context)) {
            Log.w(TAG, "BLUETOOTH_SCAN not granted — cannot scan")
            return
        }
        if (activeScanCallback != null) {
            Log.d(TAG, "Scan already running — ignoring duplicate start")
            return
        }

        val localScanner = adapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "BLE scanner unavailable")
            return
        }
        scanner = localScanner

        // Filter by the 16-bit GhostLink UUID. The framework matches a 16-bit
        // filter against the corresponding 128-bit base UUID transmitted on air.
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(GHOSTLINK_PARCEL_UUID)
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val serviceData = result.scanRecord
                    ?.getServiceData(GHOSTLINK_PARCEL_UUID)

                // Extract temporary session token from service data
                val sessionToken = serviceData?.take(16)?.joinToString("") { "%02x".format(it) }

                val discovered = DiscoveredDevice(
                    deviceId = device.address,  // Hardware MAC (randomised by OS)
                    deviceName = tryGetDeviceName(device) ?: "GhostLink Device",
                    alias = null,  // Alias exchanged at application layer after connection
                    rssi = result.rssi,
                    verificationState = DeviceVerificationState.GhostLinkPeer,
                    macAddress = device.address,
                    connectionMethod = ConnectionMethod.BLE
                )
                Log.d(TAG, "GhostLink BLE peer found: ${device.address}, rssi=${result.rssi}")
                onDeviceFound(discovered)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: errorCode=$errorCode")
                // The framework has already stopped the scan. Leaving the callback
                // installed made every later startScanning() a no-op ("already
                // running"), which is why the second and later scans found nothing.
                if (activeScanCallback === this) activeScanCallback = null
                // SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (2) is recoverable by
                // re-registering; retry once, bounded.
                if (errorCode == 2 && retries < 1) {
                    retries++
                    handler.postDelayed({
                        if (!closedFlag) startScanning(onDeviceFound)
                    }, 1000L)
                }
            }
        }

        activeScanCallback = callback
        try {
            localScanner.startScan(listOf(scanFilter), scanSettings, callback)
            Log.d(TAG, "BLE scanning started")
        } catch (e: SecurityException) {
            // Defensive: permission can be revoked between the check and the call.
            Log.w(TAG, "BLE scan rejected (permission): ${e.message}")
            activeScanCallback = null
        }
    }

    fun stopScanning() {
        val cb = activeScanCallback ?: return
        activeScanCallback = null
        try {
            scanner?.stopScan(cb)
            Log.d(TAG, "BLE scanning stopped")
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE stopScan rejected (permission): ${e.message}")
        }
    }

    /**
     * Starts BLE advertising so nearby GhostLink devices can discover this device.
     *
     * Advertisement payload:
     *   - GhostLink service UUID (identifies the app)
     *   - Service data: first 8 bytes of the current invitation token (opaque to observers)
     *
     * @param invitationTokenBytes First 8 bytes of the invitation token — uniquely identifies
     *                              this invitation to a scanning GhostLink peer without exposing
     *                              the full token or any user identity.
     */
    fun startAdvertising(invitationTokenBytes: ByteArray) {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled — cannot advertise")
            return
        }
        if (!hasAdvertisePermission(context)) {
            Log.w(TAG, "BLUETOOTH_ADVERTISE not granted — cannot advertise")
            return
        }
        if (activeAdvertiseCallback != null) {
            Log.d(TAG, "Advertising already running — ignoring duplicate start")
            return
        }

        val localAdvertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "BLE advertising not supported on this device")
            return
        }
        advertiser = localAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)  // Discovery only — not GATT connectable
            .setTimeout(0) // Continuous until stopAdvertising()
            .build()

        // Truncate to fit the legacy 31-byte advertisement payload together with
        // the 16-bit service UUID. See MAX_SERVICE_DATA_BYTES for the math.
        val tokenSlice = invitationTokenBytes.copyOf(
            minOf(MAX_SERVICE_DATA_BYTES, invitationTokenBytes.size)
        )

        // addServiceUuid for a 16-bit UUID emits a 2-byte UUID AD structure;
        // addServiceData emits the 2-byte UUID + payload. Together with the
        // automatic flags field this stays well inside the 31-byte limit.
        val data = AdvertiseData.Builder()
            .addServiceUuid(GHOSTLINK_PARCEL_UUID)
            .addServiceData(GHOSTLINK_PARCEL_UUID, tokenSlice)
            .setIncludeDeviceName(false)  // Do not include device name for privacy
            .setIncludeTxPowerLevel(false)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "BLE advertising started")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE advertising failed: errorCode=$errorCode")
                // Common causes, all logged with their numeric code:
                //   1 INTERNAL_ERROR, 2 TOO_MANY_ADVERTISERS, 3 ALREADY_STARTED,
                //   4 DATA_TOO_LARGE, 5 FEATURE_UNSUPPORTED
                activeAdvertiseCallback = null
            }
        }

        activeAdvertiseCallback = callback
        try {
            localAdvertiser.startAdvertising(settings, data, callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE advertise rejected (permission): ${e.message}")
            activeAdvertiseCallback = null
        }
    }

    fun stopAdvertising() {
        val cb = activeAdvertiseCallback ?: return
        activeAdvertiseCallback = null
        try {
            advertiser?.stopAdvertising(cb)
            Log.d(TAG, "BLE advertising stopped")
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE stopAdvertising rejected (permission): ${e.message}")
        }
    }

    fun close() {
        closedFlag = true
        handler.removeCallbacksAndMessages(null)
        stopScanning()
        stopAdvertising()
    }

    @Suppress("MissingPermission")
    private fun tryGetDeviceName(device: android.bluetooth.BluetoothDevice): String? =
        try { device.name } catch (e: SecurityException) { null }

    /**
     * Convenience: start BLE discovery for an active invitation.
     * [invitationTokenBytes] is the raw invitation token; only the first
     * [MAX_SERVICE_DATA_BYTES] are broadcast as opaque service data.
     */
    fun startDiscoveryForInvitation(
        invitationTokenBytes: ByteArray,
        onDeviceFound: (DiscoveredDevice) -> Unit
    ) {
        startAdvertising(invitationTokenBytes)
        startScanning(onDeviceFound)
    }
}
