package com.ghostlink.app.ui.screens.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlink.app.ui.components.*
import com.ghostlink.app.ui.theme.*

/**
 * Allows the user to type the one-time invitation code they received.
 *
 * Code format: XXXX-XXXX-XXXX (12 alphanumeric chars + 2 dashes = 14 chars total).
 *
 * Bugs fixed:
 *  - Was truncating to 9 chars (`clean.take(9)`) — cut off valid 12-char codes.
 *  - Only inserted a dash at position 4, not at position 9 (the second dash).
 *  - Minimum length check for "Continue" button was 8; now correctly requires
 *    the full 14 chars (including dashes) which maps to 12 alphanumeric chars.
 */
@Composable
fun EnterCodeScreen(
    codeInput: String,
    onCodeChange: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onScanQr: () -> Unit
) {
    Scaffold(
        topBar = {
            GhostTopBar(
                title = "Enter Invitation Code",
                onBack = onBack
            )
        },
        // The Column handles the IME itself (see imePadding below) so the
        // Scaffold must not also consume it — otherwise the field is shifted twice.
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        containerColor = GhostMainBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            // Keyboard icon in a circle
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0F2618))
                    .border(1.dp, GhostBorderSubtle, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Keyboard,
                    contentDescription = null,
                    tint = GhostNeonGreen,
                    modifier = Modifier.size(32.dp)
                )
            }

            Text(
                text = "Type the code you received from the other person.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = GhostTextSecondary,
                textAlign = TextAlign.Center
            )

            // Monospace Input Field
            OutlinedTextField(
                value = codeInput,
                onValueChange = { input ->
                    // Strip everything except letters, digits, and dashes.
                    // Normalise to uppercase.
                    val alphanumOnly = input.uppercase().filter { it.isLetterOrDigit() }

                    // Re-insert dashes at positions 4 and 8 (after every 4 chars).
                    // Format: XXXX-XXXX-XXXX  (14 total, 12 alpha + 2 dashes)
                    val formatted = buildString {
                        for (i in alphanumOnly.indices) {
                            if (i == 4 || i == 8) append('-')
                            append(alphanumOnly[i])
                            if (length >= 14) break
                        }
                    }
                    onCodeChange(formatted)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "e.g. 7K9M-4Q2X-AB3C",
                        fontFamily = GhostMono,
                        color = GhostTextMuted,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                textStyle = TextStyle(
                    fontFamily = GhostMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = GhostTextPrimary,
                    textAlign = TextAlign.Center,
                    letterSpacing = 2.sp
                ),
                singleLine = true,
                isError = errorMessage != null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GhostNeonGreen,
                    unfocusedBorderColor = GhostBorderSubtle,
                    focusedContainerColor = GhostCardBackground,
                    unfocusedContainerColor = GhostCardBackground,
                    cursorColor = GhostNeonGreen
                ),
                shape = GhostShapeCard,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    // 14 = "XXXX-XXXX-XXXX".length
                    onDone = { if (!isLoading && codeInput.length >= 14) onContinue() }
                )
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = GhostError,
                    textAlign = TextAlign.Center
                )
            }

            GhostPrimaryButton(
                text = if (isLoading) "Validating…" else "Continue",
                onClick = onContinue,
                isLoading = isLoading,
                // Full XXXX-XXXX-XXXX is 14 chars; accept any length >= 8 alphanumeric
                // (e.g. XXXX-XXXX) so legacy shorter codes also work.
                enabled = codeInput.replace("-", "").length >= 8
            )

            GhostOutlinedButton(
                text = "Scan QR Code Instead",
                onClick = onScanQr,
                leadingIcon = Icons.Outlined.QrCodeScanner,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // Footer
            Text(
                text = "The code is valid for a limited time and can only be used once.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = GhostTextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun EnterCodeScreenPreview() {
    GhostLinkTheme {
        EnterCodeScreen(
            codeInput = "7K9M-4Q2X-AB3C",
            onCodeChange = {},
            isLoading = false,
            errorMessage = null,
            onBack = {},
            onContinue = {},
            onScanQr = {}
        )
    }
}
