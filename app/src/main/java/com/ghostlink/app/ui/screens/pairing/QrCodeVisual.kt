package com.ghostlink.app.ui.screens.pairing

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.BuildConfig
import com.ghostlink.app.ui.components.GhostShapeMedium
import com.ghostlink.app.ui.theme.GhostBorderSubtle
import com.ghostlink.app.ui.theme.GhostError
import com.ghostlink.app.ui.theme.GhostNeonGreen
import com.ghostlink.app.ui.theme.GhostTextMuted
import com.ghostlink.app.ui.theme.GhostTextSecondary
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "QrCodeVisual"

/** Length of the white quiet zone around the symbol, in modules (QR spec: 4). */
private const val QUIET_ZONE_MODULES = 4

/** State of the QR generation coroutine. */
private sealed interface QrState {
    object Generating : QrState
    data class Ready(val bitmap: Bitmap) : QrState
    data class Error(val message: String) : QrState
}

/**
 * Renders a real, scannable QR code for [payload] using the bundled
 * Nayuki QR Code generator (MIT, pure Java — no network, no extra dependency).
 *
 * - Black modules on white, 4-module quiet zone, integer pixels per module.
 * - Minimum 250dp box, centered inside a white rounded card so it stays
 *   scannable on the dark theme.
 * - Encoding + Bitmap filling run on [Dispatchers.Default].
 * - Loading placeholder while generating, error state with Retry if encoding fails.
 *
 * @param payload  The complete string to encode.
 * @param size     Side length of the square QR card.
 * @param onRetry  Invoked when the user taps Retry after a failure.
 */
@Composable
fun RealQrCode(
    payload: String,
    modifier: Modifier = Modifier,
    size: Dp = 260.dp,
    onRetry: (() -> Unit)? = null
) {
    // The bitmap only changes when the payload changes — this is the remember(payload)
    // cache for the (relatively expensive) encode + raster step.
    var state by remember(payload) { mutableStateOf<QrState>(QrState.Generating) }

    LaunchedEffect(payload) {
        state = QrState.Generating
        state = try {
            QrState.Ready(withContext(Dispatchers.Default) { generateQrBitmap(payload) })
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "QR generation failed: ${e.message}")
            QrState.Error(e.message ?: "Unknown error")
        }
    }

    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "qr_state",
        modifier = modifier.size(size)
    ) { qrState ->
        when (qrState) {
            is QrState.Generating -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            color = GhostNeonGreen,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Generating QR…",
                            fontSize = 12.sp,
                            color = GhostTextMuted
                        )
                    }
                }
            }

            is QrState.Ready -> {
                // White card with a rounded border. The bitmap already carries the
                // 4-module quiet zone, so the bitmap itself is drawn edge to edge
                // with no resampling (FilterQuality.None) to keep module edges crisp.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, GhostBorderSubtle, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = qrState.bitmap.asImageBitmap(),
                        contentDescription = "QR code for this invitation — scan with another GhostLink device",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            is QrState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1A0808), GhostShapeMedium)
                        .border(1.dp, GhostError.copy(alpha = 0.5f), GhostShapeMedium),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = GhostError,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "QR failed to generate",
                            fontSize = 13.sp,
                            color = GhostError
                        )
                        Text(
                            text = "Use the text code below, or tap Retry.",
                            fontSize = 11.sp,
                            color = GhostTextSecondary
                        )
                        if (onRetry != null) {
                            TextButton(onClick = onRetry) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = null,
                                    tint = GhostNeonGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Retry", color = GhostNeonGreen, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Encodes [content] with Nayuki's `QrCode.encodeText(..., Ecc.MEDIUM)` and paints the
 * modules into a [Bitmap].
 *
 * Must be called off the main thread (use `Dispatchers.Default`).
 *
 * @param content The string to encode.
 * @param targetPx Desired side length in px; the actual bitmap uses the largest
 *                 integer scale that still fits, so every module is the same size.
 * @throws IllegalArgumentException if the content does not fit in a QR Code.
 */
internal fun generateQrBitmap(content: String, targetPx: Int = 1024): Bitmap {
    val qr = QrCode.encodeText(content, QrCode.Ecc.MEDIUM)

    val border = QUIET_ZONE_MODULES
    val modulesPerSide = qr.size + border * 2
    val scale = (targetPx / modulesPerSide).coerceAtLeast(1)
    val dimension = modulesPerSide * scale

    val pixels = IntArray(dimension * dimension) { QrColors.WHITE }
    for (y in 0 until qr.size) {
        for (x in 0 until qr.size) {
            if (!qr.getModule(x, y)) continue // white background already filled
            val left = (x + border) * scale
            val top = (y + border) * scale
            for (py in top until top + scale) {
                val rowStart = py * dimension
                for (px in left until left + scale) {
                    pixels[rowStart + px] = QrColors.BLACK
                }
            }
        }
    }

    val bmp = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, dimension, 0, 0, dimension, dimension)
    return bmp
}

/** int ARGB constants kept out of the function body so they are compile-time constants. */
private object QrColors {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
}
