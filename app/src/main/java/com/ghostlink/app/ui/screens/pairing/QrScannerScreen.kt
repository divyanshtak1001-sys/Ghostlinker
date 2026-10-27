package com.ghostlink.app.ui.screens.pairing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ghostlink.app.ui.components.CornerBracketFrame
import com.ghostlink.app.ui.components.GhostPrimaryButton
import com.ghostlink.app.ui.components.GhostTopBar
import com.ghostlink.app.ui.theme.*
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "QrScanner"

/**
 * Full-screen QR scanner for the "Pair with Someone" flow.
 *
 * CameraX supplies frames to ML Kit's bundled QR decoder (on-device, offline).
 * The first decoded string is handed to [onQrDecoded] exactly once; the click on
 * "Scan again" (shown after a failure) re-arms the detector via [scanKey].
 *
 * @param scanKey   Bump this to re-arm scanning after an error. Ignored while the
 *                  scanner is idle-but-armed, so a stale callback cannot fire twice.
 * @param statusMessage Shown above the preview when a scanned code was rejected
 *                  (not a GhostLink QR, expired, malformed).
 * @param onQrDecoded Called on the main thread with the raw decoded string.
 * @param onBack    Back arrow in the top bar.
 */
@Composable
fun QrScannerScreen(
    scanKey: Int,
    onQrDecoded: (String) -> Unit,
    statusMessage: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    // Set when the user has answered the system camera prompt at least once.
    var deniedOnce by remember { mutableStateOf(false) }

    // Treat "denied and no longer askable" as permanently denied so the UI can
    // point at the system settings instead of looping the permission dialog.
    val activity = context as? androidx.activity.ComponentActivity
    val permanentlyDenied by remember(hasPermission) {
        mutableStateOf(
            !hasPermission &&
                activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) deniedOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Camera handle + torch state, published by CameraPreview once bound.
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    TorchEffect(camera = camera, torchOn = torchOn)

    // Set once a decode has been delivered; prevents double navigation.
    var delivered by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    // Binding generation: bumped by Retry to force a full re-bind of the camera.
    var bindKey by remember { mutableIntStateOf(0) }

    // Re-arm after a rejected code: the host bumps scanKey and shows an error.
    // We must also clear "delivered", otherwise no new frame would ever be decoded.
    LaunchedEffect(scanKey) {
        delivered = false
        cameraError = null
    }

    // One executor for analysis frames for the whole screen lifetime.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    Scaffold(
        topBar = {
            GhostTopBar(
                title = "Scan QR Code",
                subtitle = "Point the camera at the other device's QR.",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { torchOn = !torchOn }) {
                        Icon(
                            imageVector = if (torchOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                            contentDescription = if (torchOn) "Turn flashlight off" else "Turn flashlight on",
                            tint = if (torchOn) GhostNeonGreen else GhostTextSecondary
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        containerColor = GhostMainBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (statusMessage != null) {
                ScannerStatusBanner(text = statusMessage, isError = true)
            }

            when {
                !hasPermission -> CameraPermissionDenied(
                    denied = deniedOnce,
                    permanentlyDenied = permanentlyDenied,
                    onRequestAgain = {
                        if (permanentlyDenied) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                )
                            }
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onUseCodeInstead = onBack,
                )

                cameraError != null -> ScannerError(
                    message = cameraError ?: "",
                    onRetry = {
                        cameraError = null
                        delivered = false
                        bindKey++
                    },
                    onUseCodeInstead = onBack,
                )

                else -> {
                    CornerBracketFrame(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        bracketColor = GhostNeonGreen
                    ) {
                        CameraPreview(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .semantics { contentDescription = "Camera preview for QR scanning" },
                            lifecycleOwner = lifecycleOwner,
                            bindKey = bindKey,
                            scanKey = scanKey,
                            isConsumed = { delivered },
                            analysisExecutor = analysisExecutor,
                            onCameraReady = { cam -> camera = cam },
                            onDecoded = { text ->
                                if (!delivered) {
                                    delivered = true
                                    onQrDecoded(text)
                                }
                            },
                            onError = { msg -> cameraError = msg },
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = GhostNeonGreen,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Searching for a GhostLink QR code…",
                            fontSize = 12.sp,
                            color = GhostTextSecondary
                        )
                    }
                }
            }

            Text(
                text = "The QR code contains a one-time invitation token. Nothing is uploaded — " +
                    "decoding happens entirely on this device.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = GhostTextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

/**
 * CameraX preview + ML Kit analysis pipeline.
 *
 * The Preview use case is attached to the PreviewView, and the ImageAnalysis use
 * case receives YUV frames on [analysisExecutor]. Only one decode is in flight at
 * a time ([inFlight]); once a QR is found, [QrScannerScreen] marks the screen as
 * consumed and the analyzer returns early.
 */
@Composable
private fun CameraPreview(
    modifier: Modifier,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    bindKey: Int,
    scanKey: Int,
    isConsumed: () -> Boolean,
    analysisExecutor: java.util.concurrent.Executor,
    onCameraReady: (androidx.camera.core.Camera) -> Unit,
    onDecoded: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val inFlight = remember { AtomicBoolean(false) }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    DisposableEffect(Unit) {
        onDispose { scanner.close() }
    }

    // Re-arm whenever the screen asks for a rescan.
    LaunchedEffect(scanKey) { inFlight.set(false) }

    // Bind once per (lifecycle owner, bindKey); unbind on dispose.
    DisposableEffect(lifecycleOwner, previewView, bindKey) {
        val future = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(analysisExecutor) { imageProxy ->
                    if (isConsumed()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    if (!inFlight.compareAndSet(false, true)) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    decodeFrame(scanner, imageProxy) { text ->
                        inFlight.set(false)
                        if (text != null && !isConsumed()) onDecoded(text)
                    }
                }
            }

        var cameraProvider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()
                val cam = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                onCameraReady(cam)
            } catch (e: Exception) {
                Log.w(TAG, "Camera bind failed: ${e.message}")
                onError(e.message ?: "Camera could not be started")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProvider?.unbindAll()
            // Not a permanent stop: a Retry bumps bindKey and rebinds, so the flag
            // must go back to "idle" (frames stop arriving on their own anyway).
            inFlight.set(false)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

/**
 * Turns the torch on/off for the currently bound camera. Cameras without a
 * flash unit simply ignore the request (CameraX throws, so it is caught).
 */
@Composable
internal fun TorchEffect(camera: androidx.camera.core.Camera?, torchOn: Boolean) {
    LaunchedEffect(camera, torchOn) {
        val target = camera ?: return@LaunchedEffect
        if (!target.cameraInfo.hasFlashUnit()) return@LaunchedEffect
        runCatching {
            target.cameraControl.enableTorch(torchOn)
        }.onFailure { e -> Log.w(TAG, "Torch toggle failed: ${e.message}") }
    }
}

/**
 * Decodes one YUV frame with ML Kit. The frame must be closed exactly once, which
 * is done by the OnCompleteListener regardless of success or failure.
 *
 * `ImageProxy.image` is annotated @ExperimentalGetImage in CameraX 1.3; the opt-in
 * below is the documented way to use it with ML Kit's InputImage on API 21+.
 */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun decodeFrame(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onResult: (String?) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        onResult(null)
        return
    }
    val rotation = imageProxy.imageInfo.rotationDegrees
    val input = InputImage.fromMediaImage(mediaImage, rotation)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            val text = barcodes.firstOrNull { it.rawValue != null }?.rawValue
            onResult(text)
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Decode failed: ${e.message}")
            onResult(null)
        }
        .addOnCompleteListener { imageProxy.close() }
}

@Composable
private fun CameraPermissionDenied(
    denied: Boolean,
    permanentlyDenied: Boolean,
    onRequestAgain: () -> Unit,
    onUseCodeInstead: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.NoPhotography,
            contentDescription = null,
            tint = GhostWarning,
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = "Camera permission needed",
            color = GhostTextPrimary,
            fontSize = 16.sp
        )
        Text(
            text = when {
                permanentlyDenied ->
                    "Permission was denied. Open the app settings and allow Camera, then come back."
                denied ->
                    "Camera access was declined. Allow it to scan, or type the code instead."
                else ->
                    "GhostLink needs the camera to scan a QR code. The camera feed is decoded on " +
                        "this device and never leaves it."
            },
            color = GhostTextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        GhostPrimaryButton(
            text = if (permanentlyDenied) "Open Settings" else "Allow Camera",
            onClick = onRequestAgain,
            leadingIcon = Icons.Outlined.CameraAlt
        )
        TextButton(onClick = onUseCodeInstead) {
            Text("Enter the code instead", color = GhostNeonGreen, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ScannerError(
    message: String,
    onRetry: () -> Unit,
    onUseCodeInstead: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = GhostError,
            modifier = Modifier.size(40.dp)
        )
        Text(text = "Camera unavailable", color = GhostError, fontSize = 15.sp)
        Text(
            text = message,
            color = GhostTextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        GhostPrimaryButton(
            text = "Retry",
            onClick = onRetry,
            leadingIcon = Icons.Outlined.Refresh
        )
        TextButton(onClick = onUseCodeInstead) {
            Text("Enter the code instead", color = GhostNeonGreen, fontSize = 13.sp)
        }
    }
}

/** Dimmed background used while a scanned payload is being validated. */
@Composable
fun ScannerStatusBanner(
    text: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isError) Color(0xFF1A0808) else Color(0xFF0A130E))
            .border(
                1.dp,
                if (isError) GhostError.copy(alpha = 0.5f) else GhostBorderSubtle,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.QrCodeScanner,
            contentDescription = null,
            tint = if (isError) GhostError else GhostNeonGreen,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (isError) GhostError else GhostTextPrimary
        )
    }
}
