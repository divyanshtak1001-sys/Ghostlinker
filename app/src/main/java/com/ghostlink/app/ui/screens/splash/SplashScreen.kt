package com.ghostlink.app.ui.screens.splash

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.ui.components.GhostDivider
import com.ghostlink.app.ui.components.GhostLogo
import com.ghostlink.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onComplete: () -> Unit,
    reducedMotion: Boolean = false
) {
    // ── Entrance animation ────────────────────────────────────────────────────
    // The animation is a presentation detail only: it never gates onComplete, and
    // it is skipped entirely when the user has reduced motion enabled.
    val animSpec = tween<Float>(durationMillis = if (reducedMotion) 0 else 550, easing = FastOutSlowInEasing)

    val logoProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = animSpec,
        label = "logoEntrance"
    )
    val textProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else 450,
            delayMillis = if (reducedMotion) 0 else 220,
            easing = FastOutSlowInEasing
        ),
        label = "textEntrance"
    )
    val claimsProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else 450,
            delayMillis = if (reducedMotion) 0 else 400,
            easing = FastOutSlowInEasing
        ),
        label = "claimsEntrance"
    )
    val bottomProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else 450,
            delayMillis = if (reducedMotion) 0 else 600,
            easing = FastOutSlowInEasing
        ),
        label = "bottomEntrance"
    )

    // Honour the system "remove animations" accessibility setting.
    val context = LocalContext.current
    val systemReducedMotion = remember {
        val scale = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        } catch (e: Exception) {
            1f
        }
        scale == 0f
    }
    val noMotion = reducedMotion || systemReducedMotion

    LaunchedEffect(Unit) {
        delay(if (noMotion) 1200 else 2200)
        onComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GhostMainBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // Centered glowing ghost logo — fades and scales in.
            GhostLogo(
                size = 90.dp,
                strokeColor = GhostNeonGreen,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = logoProgress
                        val s = 0.86f + (0.14f * logoProgress)
                        scaleX = s
                        scaleY = s
                    }
            )

            Spacer(Modifier.height(24.dp))

            // "GhostLink" (Ghost white, Link green)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 32.sp)) {
                        append("Ghost")
                    }
                    withStyle(SpanStyle(color = GhostNeonGreen, fontWeight = FontWeight.Bold, fontSize = 32.sp)) {
                        append("Link")
                    }
                },
                letterSpacing = 1.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = textProgress
                    translationY = (1f - textProgress) * 14f
                }
            )

            Spacer(Modifier.height(8.dp))

            // Tagline: "Private. Anonymous. Free."
            Text(
                text = "Private. Anonymous. Free.",
                style = GhostStatusStyle.copy(
                    fontSize = 13.sp,
                    letterSpacing = 1.sp
                ),
                color = GhostTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = textProgress }
            )

            Spacer(Modifier.height(28.dp))

            // Small divider
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(1.dp)
                    .background(GhostBorderSubtle)
            )

            Spacer(Modifier.height(28.dp))

            // Three lines in monospace: "No accounts." / "No tracking." / "Just people."
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.graphicsLayer {
                    alpha = claimsProgress
                    translationY = (1f - claimsProgress) * 10f
                }
            ) {
                Text(
                    text = "No accounts.",
                    fontFamily = GhostMono,
                    fontSize = 13.sp,
                    color = GhostTextSecondary
                )
                Text(
                    text = "No tracking.",
                    fontFamily = GhostMono,
                    fontSize = 13.sp,
                    color = GhostTextSecondary
                )
                Text(
                    text = "Just people.",
                    fontFamily = GhostMono,
                    fontSize = 13.sp,
                    color = GhostNeonGreen
                )
            }
        }

        // Bottom progress & status line
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 40.dp, end = 40.dp)
                .graphicsLayer { alpha = bottomProgress },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(2.dp),
                color = GhostNeonGreen,
                trackColor = Color(0xFF0F2618)
            )
            Text(
                text = "Initializing secure environment...",
                fontFamily = GhostMono,
                fontSize = 11.sp,
                color = GhostTextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SplashScreenPreview() {
    GhostLinkTheme {
        SplashScreen(onComplete = {})
    }
}
