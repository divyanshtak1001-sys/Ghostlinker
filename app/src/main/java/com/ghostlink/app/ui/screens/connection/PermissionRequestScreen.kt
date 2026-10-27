package com.ghostlink.app.ui.screens.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.ui.components.*
import com.ghostlink.app.ui.theme.*

@Composable
fun PermissionRequestScreen(
    onAllowWhileUsing: () -> Unit,
    onAllowOnlyThisTime: () -> Unit,
    onDontAllow: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GhostMainBackground),
        contentAlignment = Alignment.Center
    ) {
        GhostCard(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                // Location pin icon in circle
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0F2618))
                        .border(1.dp, GhostBorderSubtle, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = GhostNeonGreen,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = "Allow GhostLink to access your location?",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = GhostTextPrimary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Location permission is required for Wi-Fi Direct device discovery and connection.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = GhostTextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                // Three buttons
                GhostPrimaryButton(
                    text = "Allow while using the app",
                    onClick = onAllowWhileUsing
                )

                GhostOutlinedButton(
                    text = "Only this time",
                    onClick = onAllowOnlyThisTime
                )

                GhostDestructiveButton(
                    text = "Don't allow",
                    onClick = onDontAllow
                )

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PermissionRequestScreenPreview() {
    GhostLinkTheme {
        PermissionRequestScreen(
            onAllowWhileUsing = {},
            onAllowOnlyThisTime = {},
            onDontAllow = {}
        )
    }
}
