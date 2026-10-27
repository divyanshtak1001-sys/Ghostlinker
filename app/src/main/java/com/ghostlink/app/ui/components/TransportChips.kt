package com.ghostlink.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.ui.theme.*

/**
 * One row in the connection-method list: icon, label, one-line explanation and a
 * status caption that says exactly why a method is not usable right now.
 *
 * Kept separate from [GhostMenuCard] because it must render a *disabled, explained*
 * state (Wi-Fi off, permission missing, unsupported hardware) instead of looking
 * like a tappable option that silently does nothing.
 */
@Composable
fun TransportRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    statusIsWarning: Boolean = false,
) {
    val enabled = onClick != null
    val titleColor = if (enabled) GhostTextPrimary else GhostTextMuted
    GhostCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (enabled) Color(0xFF0F2618) else Color(0xFF111613))
                    .border(1.dp, GhostBorderSubtle, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) GhostNeonGreen else GhostTextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                )
                Spacer(Modifier.height(2.dp))
                Text(text = subtitle, fontSize = 12.sp, color = GhostTextSecondary)
                statusText?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        fontSize = 11.sp,
                        color = if (statusIsWarning) GhostWarning else GhostTextMuted,
                    )
                }
            }

            if (enabled) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = GhostTextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
