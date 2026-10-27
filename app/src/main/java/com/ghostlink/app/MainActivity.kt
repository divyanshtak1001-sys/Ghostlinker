package com.ghostlink.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.ghostlink.app.ui.navigation.GhostNavGraph
import com.ghostlink.app.ui.theme.GhostLinkTheme
import com.ghostlink.app.viewmodel.GhostViewModel

class MainActivity : ComponentActivity() {

    /**
     * Requests Wi-Fi Direct, Bluetooth Classic, and BLE runtime permissions.
     * The permission set is computed by ConnectivityPermissions so it always
     * matches the running API level (location on API 26–32, NEARBY_WIFI_DEVICES
     * and BLUETOOTH_* on newer versions).
     */
    private val connectivityPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Granted only when every requested permission came back true.
            val allGranted = result.isNotEmpty() && result.values.all { it }
            ghostViewModel?.onConnectivityPermissionResult(allGranted)
            if (allGranted) requestBluetoothEnableIfNeeded()
        }

    private var ghostViewModel: GhostViewModel? = null

    /**
     * System dialog that asks the user to switch Bluetooth on. Without this,
     * a device with Bluetooth disabled cannot discover peers or accept RFCOMM
     * connections.
     */
    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // The BLE managers re-read the adapter state on every call, so no
            // follow-up action is needed: if the user enabled Bluetooth, the
            // next discovery pass works; if not, it stays unavailable.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stage timing for connection/discovery diagnostics. Debug builds only: the
        // release APK must not emit pipeline timing into logcat.
        com.ghostlink.app.util.PerfTrace.enabled = BuildConfig.DEBUG
        enableEdgeToEdge()
        setContent {
            val vm: GhostViewModel = viewModel()
            ghostViewModel = vm
            val uiState by vm.uiState.collectAsState()
            val navController = rememberNavController()

            // Attach the application context so the Wi-Fi Direct and Bluetooth managers
            // can be initialised, then request the permissions discovery needs.
            LaunchedEffect(Unit) {
                vm.attachContext(applicationContext)
                handleDeepLink(intent)
                val needed = vm.requiredPermissions()
                val missing = needed.filter {
                    checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isNotEmpty()) {
                    connectivityPermissionLauncher.launch(missing.toTypedArray())
                } else {
                    vm.onConnectivityPermissionResult(true)
                }
                requestBluetoothEnableIfNeeded()
            }

            // Dynamic FLAG_SECURE management based on privacy settings
            LaunchedEffect(uiState.screenshotProtection) {
                if (uiState.screenshotProtection) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            GhostLinkTheme(selectedTheme = uiState.selectedTheme) {
                GhostNavGraph(
                    navController = navController,
                    vm            = vm
                )
            }
        }
    }

    /**
     * Prompts the user to enable Bluetooth when the radio is off.
     * ACTION_REQUEST_ENABLE shows a system dialog and never throws, which is
     * why it is preferred over the silently-failing BluetoothAdapter.enable()
     * (itself removed for third-party apps on API 33+).
     */
    private fun requestBluetoothEnableIfNeeded() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return  // Device has no Bluetooth hardware — nothing to enable.
        if (adapter.isEnabled) return

        // ACTION_REQUEST_ENABLE needs BLUETOOTH_CONNECT on API 31+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return  // Permission dialog will handle this; retry on next launch.
        }

        runCatching {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Handles `ghostlink://invite/CODE` and `ghostlink://pair?code=CODE`.
     *
     * The URI is untrusted input from any app on the device. It is only ever used
     * to *pre-fill* the code field: it is format-validated here, and the normal
     * user-confirmation + authenticated-handshake path still runs afterwards, so
     * a deep link cannot jump straight to a connected session.
     *
     * Rejected: unknown hosts, other schemes, codes that are too long or contain
     * characters outside the legal alphabet, and repeated parameters. The raw
     * value is never logged.
     */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "ghostlink") return
        val rawCode = when (uri.host) {
            "invite" -> uri.lastPathSegment
            "pair" -> {
                // Reject duplicated parameters: ?code=A&code=B is ambiguous.
                val values = uri.getQueryParameters("code")
                if (values.size > 1) null else values.firstOrNull()
            }
            else -> null
        } ?: return
        if (rawCode.length > 64) return
        val cleanCode = rawCode.uppercase().filter { it.isLetterOrDigit() || it == '-' }
        if (cleanCode.replace("-", "").length !in 8..12) return
        val vm = ghostViewModel ?: return
        if (!vm.isAwaitingInvitationInput()) {
            // Do not hijack an established or in-progress session.
            return
        }
        vm.updateInvitationCodeInput(cleanCode)
    }
}
