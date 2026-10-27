package com.ghostlink.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.unit.sp
import com.ghostlink.app.model.*
import com.ghostlink.app.ui.components.*
import com.ghostlink.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    peer: PeerInfo,
    messages: List<ChatMessage>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onDisconnect: () -> Unit,
    localAlias: String = "Shadow7F3",
    isBlueTheme: Boolean = false
) {
    val listState = rememberLazyListState()

    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    // Color system based on active theme
    val accentColor = if (isBlueTheme) GhostBlueAccent else GhostNeonGreen
    val bubbleBgLocal = if (isBlueTheme) Color(0xFF0D1E35) else Color(0xFF0A1F13)
    val bubbleBgRemote = if (isBlueTheme) Color(0xFF071020) else Color(0xFF08160E)
    val bubbleBorder = if (isBlueTheme) GhostBlueBorder else GhostBorderSubtle
    val bgColor = if (isBlueTheme) GhostBlueBackground else GhostMainBackground
    val surfaceColor = if (isBlueTheme) GhostBlueSurface else GhostCardBackground

    // Only real sent and received messages are rendered. No sample/mock bubbles:
    // an empty list means the conversation has not started, and shows as empty.
    val displayMessages = messages

    // Auto-scroll to the newest message when one arrives, or when the keyboard
    // opens (the list gets shorter, and the last bubble must stay visible).
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(displayMessages.size, imeVisible) {
        if (displayMessages.isNotEmpty()) {
            listState.animateScrollToItem(displayMessages.lastIndex)
        }
    }

    Scaffold(
        // The chat Column below handles the IME itself (composer pinned to the
        // keyboard), so the Scaffold must not also consume the IME inset — that
        // would double-shift the composer. System bars are still handled by Scaffold.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GhostAvatar(size = 36.dp, color = accentColor)
                        Column {
                            Text(
                                text = peer.alias.display,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = GhostMono,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                ),
                                color = GhostTextPrimary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(accentColor)
                                )
                                Text(
                                    text = "Online",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontFamily = GhostMono
                                    ),
                                    color = accentColor
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDisconnect) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back / Disconnect",
                            tint = accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { /* Dropdown menu: disconnect, safety code */ }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "More options",
                            tint = GhostTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceColor,
                    titleContentColor = GhostTextPrimary
                )
            )
        },
        containerColor = bgColor
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Pin the whole column (list + composer) above the keyboard and
                // above the gesture/navigation bar. imePadding resolves to 0 when
                // the keyboard is closed, so the composer simply rests on the nav bar inset.
                .navigationBarsPadding()
                .imePadding()
        ) {
            // ── Chat Message List ──────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                // Keep the newest message above the composer when new ones arrive
                // while the user is reading further up.
                reverseLayout = false,
            ) {
                items(displayMessages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        accentColor = accentColor,
                        bubbleBgLocal = bubbleBgLocal,
                        bubbleBgRemote = bubbleBgRemote,
                        bubbleBorder = bubbleBorder
                    )
                }
            }

            // ── Input Bar ─────────────────────────────────────────────────────
            Surface(
                color = surfaceColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        bubbleBorder,
                        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attach placeholder (text/image attach — deferred to later stage)
                    IconButton(
                        onClick = { /* Attach: future feature */ },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "Attach (not yet available)",
                            tint = GhostTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    // Text input field
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(bgColor)
                            .border(1.dp, bubbleBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                text = "> Type a message...",
                                fontFamily = GhostMono,
                                fontSize = 13.sp,
                                color = GhostTextMuted
                            )
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            textStyle = TextStyle(
                                fontFamily = GhostMono,
                                fontSize = 13.sp,
                                color = GhostTextPrimary
                            ),
                            cursorBrush = SolidColor(accentColor),
                            singleLine = false,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (draft.isNotBlank()) onSend()
                            }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { focused = it.isFocused }
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // Send button
                    IconButton(
                        onClick = { if (draft.isNotBlank()) onSend() },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (isBlueTheme) Color(0xFF0F2C4C) else Color(0xFF0F2618)
                            ),
                        enabled = draft.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = "Send",
                            tint = if (draft.isNotBlank()) accentColor else GhostTextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    accentColor: Color,
    bubbleBgLocal: Color,
    bubbleBgRemote: Color,
    bubbleBorder: Color
) {
    val isLocal = message.origin == MessageOrigin.Local
    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(Date(message.timestamp))
    val bubbleBg = if (isLocal) bubbleBgLocal else bubbleBgRemote

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isLocal) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isLocal) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 12.dp, topEnd = 12.dp,
                            bottomStart = if (isLocal) 12.dp else 4.dp,
                            bottomEnd = if (isLocal) 4.dp else 12.dp
                        )
                    )
                    .background(bubbleBg)
                    .border(1.dp, bubbleBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "$timeStr  ",
                            fontFamily = GhostMono,
                            fontSize = 11.sp,
                            color = GhostTextMuted
                        )
                        Text(
                            text = message.content,
                            fontFamily = GhostMono,
                            fontSize = 13.sp,
                            color = GhostTextPrimary
                        )
                    }
                }
            }

            // Message status indicator (local messages only)
            if (isLocal) {
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = when (message.status) {
                            MessageStatus.Sending -> "Sending..."
                            MessageStatus.Sent -> "Sent"
                            MessageStatus.Delivered -> "Delivered"
                            MessageStatus.Failed -> "Failed"
                        },
                        fontFamily = GhostMono,
                        fontSize = 10.sp,
                        color = when (message.status) {
                            MessageStatus.Failed -> GhostError
                            MessageStatus.Delivered -> accentColor.copy(alpha = 0.7f)
                            else -> GhostTextMuted
                        }
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ChatScreenPreview() {
    GhostLinkTheme {
        ChatScreen(
            peer = PeerInfo(alias = GhostAlias("Lunar_8D4")),
            messages = emptyList(),
            draft = "",
            onDraftChange = {},
            onSend = {},
            onDisconnect = {},
            isBlueTheme = false
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ChatScreenBluePreview() {
    GhostLinkTheme {
        ChatScreen(
            peer = PeerInfo(alias = GhostAlias("Lunar_8D4")),
            messages = emptyList(),
            draft = "",
            onDraftChange = {},
            onSend = {},
            onDisconnect = {},
            isBlueTheme = true
        )
    }
}
