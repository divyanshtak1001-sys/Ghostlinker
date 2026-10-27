package com.ghostlink.app.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * ConnectivityPermissions — central definition of the runtime permissions
 * GhostLink needs for Wi-Fi Direct, Bluetooth Classic, and BLE.
 *
 * Android changed these permissions twice:
 *
 *   API 26–30 (Android 8–11)
 *     - Wi-Fi Direct discovery  -> ACCESS_FINE_LOCATION
 *     - Classic/BLE scanning    -> ACCESS_FINE_LOCATION (location-gated)
 *     - RFCOMM connections      -> legacy BLUETOOTH install-time permission
 *     - BLE advertising         -> no runtime permission (manifest only)
 *
 *   API 31–32 (Android 12–12L)
 *     - Classic/BLE scanning      -> BLUETOOTH_SCAN
 *     - RFCOMM and device names   -> BLUETOOTH_CONNECT
 *     - BLE advertise/visibility  -> BLUETOOTH_ADVERTISE
 *     - Wi-Fi Direct discovery     -> ACCESS_FINE_LOCATION (still required)
 *
 *   API 33+ (Android 13+)
 *     - Wi-Fi Direct discovery     -> NEARBY_WIFI_DEVICES (ACCESS_FINE_LOCATION
 *                                     no longer required for Wi-Fi Direct)
 *     - Bluetooth operations use BLUETOOTH_SCAN / _ADVERTISE / _CONNECT
 *
 * This helper returns the correct set for the running OS so the caller never
 * requests a permission that does not exist on that API level, and always
 * requests one that does.
 */
object ConnectivityPermissions {

    /**
     * The full set of runtime permissions GhostLink may need for connectivity.
     * The caller must request only those that are not already granted.
     */
    fun required(): List<String> {
        val perms = mutableListOf<String>()

        // Wi-Fi Direct discovery gating
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: NEARBY_WIFI_DEVICES replaces location for Wi-Fi Direct.
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            // API 26–32: Wi-Fi Direct discovery requires fine location.
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }

        // BLE scan / advertise / connect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: the split BLUETOOTH_* permissions.
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            // API 26–30: BLE scanning is location-gated; legacy BLUETOOTH /
            // BLUETOOTH_ADMIN are install-time (maxSdkVersion=30 in the manifest).
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }

        // API 33+: the optional background-session foreground service must post
        // a notification, which requires a runtime permission from API 33.
        // Requesting it here means the foreground service can start legally
        // instead of being killed by the OS on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }

        return perms.distinct()
    }

    /**
     * True when the app may post notifications on the running OS.
     * Always true below API 33, where notifications are granted at install time.
     */
    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)

    /**
     * Permissions that must be granted for Wi-Fi Direct peer discovery to work
     * on the running OS.
     */
    fun wifiDirectRequired(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * Permissions that must be granted for BLE scanning / advertising to work
     * on the running OS.
     */
    fun bluetoothRequired(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** True when every permission in [required] is already granted. */
    fun allGranted(context: Context): Boolean =
        required().all { isGranted(context, it) }

    /** True when Wi-Fi Direct discovery may proceed. */
    fun wifiDirectGranted(context: Context): Boolean =
        wifiDirectRequired().all { isGranted(context, it) }

    /** True when BLE scanning / advertising may proceed. */
    fun bluetoothGranted(context: Context): Boolean =
        bluetoothRequired().all { isGranted(context, it) }

    /** True when the camera may be opened for QR scanning. */
    fun cameraGranted(context: Context): Boolean =
        isGranted(context, Manifest.permission.CAMERA)

    /**
     * Human-readable list of the permissions still missing on this device,
     * used by the UI to explain WHY discovery cannot run instead of silently
     * showing an empty device list.
     */
    fun missing(context: Context): List<String> =
        required().filterNot { isGranted(context, it) }

    /** Single-permission check that is safe on every supported API level. */
    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
