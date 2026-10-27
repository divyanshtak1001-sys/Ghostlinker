package com.ghostlink.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.ui.theme.*

// ─── Shapes ──────────────────────────────────────────────────────────────────
val GhostShapeSmall  = RoundedCornerShape(8.dp)
val GhostShapeMedium = RoundedCornerShape(12.dp)
val GhostShapeLarge  = RoundedCornerShape(16.dp)
val GhostShapeCard   = RoundedCornerShape(14.dp)

// ─── Primary Action Button (Gradient Dark Green + Neon Green Text) ───────────
@Composable
fun GhostPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    isLoading: Boolean = false
) {
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(GhostGreenPrimary, GhostGreenDarkGradient)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(GhostShapeMedium)
            .background(if (enabled) gradientBrush else Brush.linearGradient(listOf(GhostCardBackground, GhostCardBackground)))
            .border(1.dp, if (enabled) GhostBorderSubtle else GhostDividerLine, GhostShapeMedium)
            .clickable(enabled = enabled && !isLoading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = GhostNeonGreen,
                strokeWidth = 2.dp
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (enabled) GhostNeonGreen else GhostTextMuted
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = GhostMono,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        fontSize = 14.sp
                    ),
                    color = if (enabled) GhostNeonGreen else GhostTextMuted
                )
            }
        }
    }
}

// ─── Outlined Button (Transparent + Green Outline) ───────────────────────────
@Composable
fun GhostOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    dangerStyle: Boolean = false
) {
    val borderColor = if (dangerStyle) GhostError.copy(alpha = 0.7f) else GhostBorderSubtle
    val textColor   = if (dangerStyle) GhostError else GhostNeonGreen

    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = enabled,
        shape = GhostShapeMedium,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor   = textColor,
            disabledContentColor = GhostTextMuted
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = textColor
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = GhostMono,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                fontSize = 14.sp
            ),
            color = textColor
        )
    }
}

@Composable
fun GhostDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    GhostOutlinedButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        dangerStyle = true
    )
}

// ─── GhostCard (Bordered Glowing Card, Surface #0A130E, Border #1E5A3C) ───────
@Composable
fun GhostCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = GhostShapeCard,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else Modifier

    Column(
        modifier = modifier
            .clip(shape)
            .background(GhostCardBackground)
            .border(1.dp, GhostBorderSubtle, shape)
            .then(clickableModifier)
            .padding(16.dp),
        content = content
    )
}

// ─── Menu Card (With Icon Tile, Title, Subtitle, Chevron) ────────────────────
@Composable
fun GhostMenuCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GhostCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rounded dark green tile with icon
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0F2618))
                    .border(1.dp, GhostBorderSubtle, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = GhostNeonGreen,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = GhostTextPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp
                    ),
                    color = GhostTextSecondary
                )
            }

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = GhostTextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─── Ghost Top Bar ───────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GhostTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp
                    ),
                    color = GhostTextPrimary
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp
                        ),
                        color = GhostTextSecondary
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = GhostNeonGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = GhostMainBackground,
            titleContentColor = GhostTextPrimary
        ),
        modifier = modifier
    )
}

// ─── CodeField (Monospace + Copy Icon) ────────────────────────────────────────
@Composable
fun GhostCodeField(
    label: String,
    code: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = GhostTextSecondary
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GhostShapeSmall)
                .background(GhostCardBackground)
                .border(1.dp, GhostBorderSubtle, GhostShapeSmall)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = GhostMono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp
                ),
                color = GhostTextPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    tint = GhostNeonGreen,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ─── Progress Stage Row (used by the connection progress screen) ──────────────

/** Visual state of a single connection-progress stage. */
enum class StageState { Pending, Active, Done, Failed }

/**
 * One row of the connection progress list: a status glyph plus the stage label.
 * Colours follow the GhostLink palette and never invent progress of their own —
 * the caller decides the state from the real connection step.
 */
@Composable
fun GhostProgressStage(
    label: String,
    state: StageState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val (icon, tint) = when (state) {
            StageState.Done    -> Icons.Outlined.CheckCircle to GhostNeonGreen
            StageState.Active  -> Icons.Outlined.RadioButtonChecked to GhostNeonGreen
            StageState.Failed  -> Icons.Outlined.Cancel to GhostError
            StageState.Pending -> Icons.Outlined.RadioButtonUnchecked to GhostTextMuted
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            fontFamily = GhostMono,
            color = when (state) {
                StageState.Pending -> GhostTextMuted
                StageState.Failed  -> GhostError
                else               -> GhostTextPrimary
            }
        )
    }
}

// ─── Signal Bars (from a real measured RSSI) ─────────────────────────────────
/**
 * Renders signal strength derived from an actual BLE RSSI reading.
 *
 * There is no fabricated/default level: callers must pass a measured value.
 * Typical RSSI range is about -30 dBm (very close) to -100 dBm (far).
 */
@Composable
fun SignalBars(
    rssi: Int,
    modifier: Modifier = Modifier
) {
    // Map real dBm onto the 1..4 bar scale.
    val bars = when {
        rssi >= -55 -> 4
        rssi >= -70 -> 3
        rssi >= -85 -> 2
        else        -> 1
    }
    val label = when (bars) {
        4 -> "Excellent"
        3 -> "Good"
        2 -> "Fair"
        else -> "Weak"
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.height(14.dp)
        ) {
            val heights = listOf(5.dp, 8.dp, 11.dp, 14.dp)
            for (i in 0 until 4) {
                val active = i < bars
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(heights[i])
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (active) GhostNeonGreen else Color(0xFF1E3A28))
                )
            }
        }
        Text(
            text = "$label · $rssi dBm",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontFamily = GhostMono
            ),
            color = GhostSuccess
        )
    }
}

// ─── Corner Bracket Frame (Used for QR and Camera) ───────────────────────────
@Composable
fun CornerBracketFrame(
    modifier: Modifier = Modifier,
    bracketLength: Dp = 22.dp,
    strokeWidth: Dp = 3.dp,
    bracketColor: Color = GhostNeonGreen,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        content()

        Canvas(modifier = Modifier.matchParentSize()) {
            val len = bracketLength.toPx()
            val sw  = strokeWidth.toPx()
            val w   = size.width
            val h   = size.height

            // Top-Left
            drawLine(bracketColor, Offset(0f, 0f), Offset(len, 0f), sw, StrokeCap.Square)
            drawLine(bracketColor, Offset(0f, 0f), Offset(0f, len), sw, StrokeCap.Square)

            // Top-Right
            drawLine(bracketColor, Offset(w, 0f), Offset(w - len, 0f), sw, StrokeCap.Square)
            drawLine(bracketColor, Offset(w, 0f), Offset(w, len), sw, StrokeCap.Square)

            // Bottom-Left
            drawLine(bracketColor, Offset(0f, h), Offset(len, h), sw, StrokeCap.Square)
            drawLine(bracketColor, Offset(0f, h), Offset(0f, h - len), sw, StrokeCap.Square)

            // Bottom-Right
            drawLine(bracketColor, Offset(w, h), Offset(w - len, h), sw, StrokeCap.Square)
            drawLine(bracketColor, Offset(w, h), Offset(w, h - len), sw, StrokeCap.Square)
        }
    }
}

// ─── Radar View (Animated Concentric Circles with Sweeping Line) ──────────────
@Composable
fun RadarView(
    modifier: Modifier = Modifier,
    sweepDurationMs: Int = 3000
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(sweepDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepAngle"
    )

    Box(
        modifier = modifier
            .size(240.dp)
            .clip(CircleShape)
            .background(Color(0xFF061109))
            .border(1.dp, GhostBorderSubtle, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.width / 2f

            // Concentric rings
            drawCircle(GhostBorderSubtle.copy(alpha = 0.5f), radius = maxR * 0.33f, center = center, style = Stroke(1.dp.toPx()))
            drawCircle(GhostBorderSubtle.copy(alpha = 0.5f), radius = maxR * 0.66f, center = center, style = Stroke(1.dp.toPx()))
            drawCircle(GhostBorderSubtle.copy(alpha = 0.5f), radius = maxR * 0.98f, center = center, style = Stroke(1.dp.toPx()))

            // Crosshairs
            drawLine(GhostBorderSubtle.copy(alpha = 0.4f), Offset(center.x, 0f), Offset(center.x, size.height), 1.dp.toPx())
            drawLine(GhostBorderSubtle.copy(alpha = 0.4f), Offset(0f, center.y), Offset(size.width, center.y), 1.dp.toPx())

            // Sweeping line
            val rad = Math.toRadians(angle.toDouble())
            val sweepEnd = Offset(
                (center.x + maxR * Math.cos(rad)).toFloat(),
                (center.y + maxR * Math.sin(rad)).toFloat()
            )
            drawLine(GhostNeonGreen.copy(alpha = 0.8f), center, sweepEnd, 2.dp.toPx())

            // Center dot
            drawCircle(GhostNeonGreen, radius = 5.dp.toPx(), center = center)

            // Sample simulated blips
            val blip1 = Offset(center.x + maxR * 0.45f, center.y - maxR * 0.35f)
            val blip2 = Offset(center.x - maxR * 0.5f, center.y + maxR * 0.2f)
            drawCircle(GhostNeonGreen, radius = 4.dp.toPx(), center = blip1)
            drawCircle(GhostNeonGreen, radius = 3.dp.toPx(), center = blip2)
        }
    }
}

// ─── Ghost Logo & Avatar ──────────────────────────────────────────────────────
@Composable
fun GhostLogo(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    strokeColor: Color = GhostNeonGreen,
    glow: Boolean = true
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 2.4.dp.toPx()

        val path = Path().apply {
            val bodyLeft   = w * 0.20f
            val bodyRight  = w * 0.80f
            val bodyTop    = h * 0.15f
            val bodyBottom = h * 0.82f

            moveTo(bodyLeft, bodyBottom)
            lineTo(bodyLeft, h * 0.45f)
            cubicTo(bodyLeft, bodyTop, bodyRight, bodyTop, bodyRight, h * 0.45f)
            lineTo(bodyRight, bodyBottom)

            val seg = (bodyRight - bodyLeft) / 3f
            val bump = h * 0.10f
            cubicTo(bodyRight - seg * 0.2f, bodyBottom + bump, bodyRight - seg * 0.8f, bodyBottom + bump, bodyRight - seg, bodyBottom)
            cubicTo(bodyRight - seg * 1.2f, bodyBottom - bump, bodyRight - seg * 1.8f, bodyBottom - bump, bodyRight - seg * 2f, bodyBottom)
            cubicTo(bodyRight - seg * 2.2f, bodyBottom + bump, bodyLeft + seg * 0.2f, bodyBottom + bump, bodyLeft, bodyBottom)
            close()
        }

        drawPath(
            path = path,
            color = strokeColor,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Eyes
        val eyeY = h * 0.44f
        val eyeR = w * 0.05f
        drawCircle(strokeColor, radius = eyeR, center = Offset(w * 0.38f, eyeY))
        drawCircle(strokeColor, radius = eyeR, center = Offset(w * 0.62f, eyeY))
    }
}

@Composable
fun GhostAvatar(
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    color: Color = GhostNeonGreen
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF0F2618))
            .border(1.dp, GhostBorderSubtle, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        GhostLogo(size = size * 0.6f, strokeColor = color)
    }
}

// ─── Bottom Navigation Bar (Home, Chats, Devices, Profile) ───────────────────
@Composable
fun GhostBottomNav(
    selectedRoute: String = "home",
    onNavigate: (String) -> Unit
) {
    val items = listOf(
        Triple("Home", "home", Icons.Outlined.Home),
        Triple("Chats", "chats", Icons.AutoMirrored.Outlined.Chat),
        Triple("Devices", "devices", Icons.Outlined.Wifi),
        Triple("Profile", "profile", Icons.Outlined.Person)
    )

    Surface(
        color = GhostCardBackground,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GhostBorderSubtle, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (label, route, icon) ->
                val isSelected = selectedRoute == route
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onNavigate(route) }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (isSelected) GhostNeonGreen else GhostTextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isSelected) GhostNeonGreen else GhostTextMuted
                    )
                    if (isSelected) {
                        Spacer(Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .width(16.dp)
                                .height(2.dp)
                                .background(GhostNeonGreen, RoundedCornerShape(1.dp))
                        )
                    }
                }
            }
        }
    }
}

// ─── Additional Helpers ───────────────────────────────────────────────────────
@Composable
fun GhostDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = 1.dp,
        color = GhostDividerLine
    )
}

@Composable
fun GhostSpacer(height: Dp) {
    Spacer(Modifier.height(height))
}

@Composable
fun GhostStatusDot(
    active: Boolean = true,
    size: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) GhostSuccess else GhostTextMuted)
    )
}

@Composable
fun GhostSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = GhostMono,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Medium
        ),
        color = GhostTextSecondary,
        modifier = modifier.padding(vertical = 4.dp)
    )
}
