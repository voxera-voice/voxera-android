package com.rocs.demo.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rocs.demo.ChatUiState
import com.rocs.demo.ChatViewModel
import com.rocs.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

// ── Colors ───────────────────────────────────────────────────────────────────
private val BgDark  = Color(0xFF0F172A)
private val Green   = Color(0xFF10B981)
private val Blue    = Color(0xFF3B82F6)
private val Red     = Color(0xFFEF4444)
private val Yellow  = Color(0xFFF59E0B)
private val Purple  = Color(0xFF8B5CF6)
private val Indigo  = Color(0xFF6366F1)
private val SlateLight = Color(0xFF94A3B8)

// ── Status config ────────────────────────────────────────────────────────────
private data class StatusCfg(val icon: String, val color: Color, val label: String, val pulse: Boolean)

private fun statusCfgFor(convStatus: ConversationStatus, speaking: SpeakingStatus): StatusCfg {
    if (speaking == SpeakingStatus.AI)   return StatusCfg("●", Green, "AI Speaking", true)
    if (speaking == SpeakingStatus.USER) return StatusCfg("◉", Blue, "Listening", true)
    return when (convStatus) {
        ConversationStatus.ACTIVE  -> StatusCfg("◉", Blue, "Listening", true)
        else                       -> StatusCfg("○", SlateLight, "Idle", false)
    }
}

// ── Entry Point ──────────────────────────────────────────────────────────────

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (!state.isSetupDone) {
        SetupScreen(viewModel)
        return
    }

    val isConnected  = state.connectionStatus == ConnectionStatus.CONNECTED
    val isConnecting = state.connectionStatus == ConnectionStatus.CONNECTING ||
            state.connectionStatus == ConnectionStatus.RECONNECTING

    ActiveCallScreen(
        state = state,
        isConnected = isConnected,
        isConnecting = isConnecting,
        eglBase = viewModel.eglBase,
        onToggleMute = viewModel::toggleMute,
        onToggleListenMode = viewModel::toggleListenMode,
        onToggleSpeaker = viewModel::toggleSpeaker,
        onToggleCamera = viewModel::toggleCamera,
        onSwitchCamera = viewModel::switchCamera,
        onEnd = viewModel::backToSetup,
        onSelectAction = viewModel::selectAction,
        onSendMessage = viewModel::sendMessage,
    )
}

// ── Active Call Screen (matches rocs.tsx) ─────────────────────────────────────

@Composable
private fun ActiveCallScreen(
    state: ChatUiState,
    isConnected: Boolean,
    isConnecting: Boolean,
    eglBase: EglBase?,
    onToggleMute: () -> Unit,
    onToggleListenMode: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onEnd: () -> Unit,
    onSelectAction: (message: String, actionId: String) -> Unit,
    onSendMessage: (String) -> Unit,
) {
    val statusCfg = statusCfgFor(state.conversationStatus, state.speakingStatus)
    var textInput by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── Background / remote video ──
        if (state.remoteVideoTrack != null && eglBase != null) {
            VideoRenderer(
                videoTrack = state.remoteVideoTrack,
                eglBase = eglBase,
                mirror = false,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(BgDark),
                contentAlignment = Alignment.Center,
            ) { /* dark background behind chat */ }
        }

        // ── Main UI column (status bar → chat → input → dock) ──
        Column(Modifier.fillMaxSize()) {

            // Listen mode banner
            if (state.isListenMode) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Purple.copy(alpha = 0.85f))
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("👂", fontSize = 16.sp)
                        Text("LISTEN MODE — Microphone muted", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }
                }
            }

            // Top status bar
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) Green else Yellow)
                    )
                    Text(
                        if (isConnecting) "Connecting…" else "Live",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.border(1.dp, statusCfg.color, RoundedCornerShape(20.dp)),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (statusCfg.pulse) { PulseDot(color = statusCfg.color) }
                            Text(statusCfg.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = statusCfg.color)
                        }
                    }
                    if (state.roomParticipants.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.15f),
                        ) {
                            Text(
                                "👤 ${state.roomParticipants.size}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("🎤", fontSize = 14.sp)
                    VolumeBar(value = state.audioLevel, color = Blue, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text("🤖", fontSize = 14.sp)
                    VolumeBar(value = state.aiAudioLevel, color = Green, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text("✅${state.iFinalCount}", fontSize = 12.sp, color = Color.White)
                }
                state.errorMessage?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Red.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        Text("⚠️ $err", color = Color.White, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(8.dp))
                    }
                }
            }

            // ── Chat messages list ──
            val chatMessages = state.messages.filter {
                it.content.isNotBlank() && it.role != ConversationMessage.MessageRole.SYSTEM
            }
            val listState = rememberLazyListState()
            LaunchedEffect(chatMessages.size) {
                if (chatMessages.isNotEmpty()) listState.animateScrollToItem(chatMessages.lastIndex)
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                if (chatMessages.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("🤖", fontSize = 40.sp)
                            Text("Start a conversation", color = Color(0xFF475569), fontSize = 14.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(chatMessages, key = { it.id }) { msg ->
                            ChatBubble(msg)
                        }
                    }
                }
            }

            // ── Live transcript strip ──
            if (state.currentTranscript.isNotEmpty()) {
                Text(
                    state.currentTranscript,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            // ── Searching indicator ──
            if (state.speakingStatus == SpeakingStatus.SEARCHING) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Purple, strokeWidth = 2.dp)
                    Text("Searching…", color = Purple, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Tool calls / actions ──
            if (state.toolCalls.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Actions", color = SlateLight, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    val toolCallJson = remember(state.toolCalls, state.toolCallsMessageId) {
                        toolCallsToJson(state.toolCalls, state.toolCallsMessageId)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 100.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(8.dp)
                    ) {
                        SelectionContainer {
                            Text(toolCallJson, color = Green, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                    state.toolCalls.forEach { tool ->
                        Surface(
                            onClick = { onSelectAction(tool.function.arguments.toString(), tool.id) },
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("⚡", fontSize = 16.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(tool.function.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    if (state.lastSelectActionsJson.isNotEmpty()) {
                        Text("select-actions payload:", color = Yellow, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(8.dp)
                        ) {
                            SelectionContainer {
                                Text(state.lastSelectActionsJson, color = Blue, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            // ── Text input row ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Message…", color = Color(0xFF64748B), fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Purple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Purple,
                        focusedContainerColor = Color.White.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                    ),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        val t = textInput.trim()
                        if (t.isNotEmpty()) { onSendMessage(t); textInput = "" }
                    }),
                )
                Surface(
                    onClick = {
                        val t = textInput.trim()
                        if (t.isNotEmpty()) { onSendMessage(t); textInput = "" }
                    },
                    shape = CircleShape,
                    color = if (textInput.isNotBlank()) Purple else Color.White.copy(alpha = 0.12f),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }

            // ── Bottom dock ──
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .navigationBarsPadding()
            ) {
                Column {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DockButton(icon = if (state.isMuted) "🔇" else "🎤", label = if (state.isMuted) "Unmute" else "Mute", bgColor = if (state.isMuted) Blue else Color.White.copy(alpha = 0.12f), onClick = onToggleMute)
                        DockButton(icon = if (state.isListenMode) "👂" else "🎧", label = if (state.isListenMode) "Listening" else "Listen", bgColor = if (state.isListenMode) Purple else Color.White.copy(alpha = 0.12f), onClick = onToggleListenMode)
                        DockButton(icon = if (state.isSpeakerOn) "🔊" else "🔈", label = if (state.isSpeakerOn) "Speaker" else "Earpiece", bgColor = if (state.isSpeakerOn) Indigo else Color.White.copy(alpha = 0.12f), onClick = onToggleSpeaker)
                        DockButton(icon = if (state.isVideoEnabled) "📹" else "📷", label = if (state.isVideoEnabled) "Cam On" else "Cam Off", bgColor = if (state.isVideoEnabled) Green else Color.White.copy(alpha = 0.12f), onClick = onToggleCamera)
                        DockButton(icon = "📞", label = "End", bgColor = Red, onClick = onEnd)
                    }
                }
            }
        }

        // ── Local camera PIP (top z-order, overlaid) ──
        if (state.localVideoTrack != null && eglBase != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 80.dp, end = 12.dp)
                    .size(120.dp, 160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            ) {
                VideoRenderer(
                    videoTrack = state.localVideoTrack,
                    eglBase = eglBase,
                    mirror = state.isFrontCamera,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable { onSwitchCamera() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🔄", fontSize = 14.sp)
                }
            }
        }
    }
}

// ── Chat Bubble ──────────────────────────────────────────────────────────────

@Composable
private fun ChatBubble(msg: ConversationMessage) {
    val isUser = msg.role == ConversationMessage.MessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (isUser) Spacer(Modifier.weight(0.15f))
        if (!isUser) {
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center,
            ) { Text("🤖", fontSize = 12.sp) }
        }
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Text(
                if (isUser) "You" else "Rocs",
                color = if (isUser) Color(0xFF34D399) else Color(0xFFA78BFA),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isUser) Color(0xFF134E4A) else Color(0xFF1E293B))
                    .border(
                        1.dp,
                        if (isUser) Color(0xFF10B981).copy(alpha = 0.3f) else Color(0xFF334155),
                        RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(msg.content, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        if (isUser) {
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF134E4A)),
                contentAlignment = Alignment.Center,
            ) { Text("👤", fontSize = 12.sp) }
        }
        if (!isUser) Spacer(Modifier.weight(0.15f))
    }
}

// ── Pulsing Dot ──────────────────────────────────────────────────────────────

@Composable
private fun PulseDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    Box(
        Modifier
            .size(8.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color)
    )
}

// ── Volume Bar ───────────────────────────────────────────────────────────────

@Composable
private fun VolumeBar(value: Float, color: Color, modifier: Modifier = Modifier) {
    val pct = value.coerceIn(0f, 1f)
    Box(
        modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.15f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(pct)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
    }
}

// ── Dock Button ──────────────────────────────────────────────────────────────

@Composable
private fun DockButton(
    icon: String,
    label: String,
    bgColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(bgColor)
            .clickable(enabled = enabled) { onClick() }
            .then(if (!enabled) Modifier.background(bgColor.copy(alpha = 0.35f)) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(icon, fontSize = 24.sp)
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Video Renderer ───────────────────────────────────────────────────────────

@Composable
private fun VideoRenderer(
    videoTrack: VideoTrack?,
    eglBase: EglBase,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    val trackState = rememberUpdatedState(videoTrack)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(mirror)
                setEnableHardwareScaler(true)
            }
        },
        update = { renderer ->
            renderer.setMirror(mirror)
            // Remove old sink, add new one
            trackState.value?.addSink(renderer)
        },
        onRelease = { renderer ->
            trackState.value?.removeSink(renderer)
            renderer.release()
        },
    )
}

// ── Helper: format tool calls as JSON ────────────────────────────────────────

private fun toolCallsToJson(tools: List<ToolCall>, messageId: String): String {
    return try {
        val tool = tools.firstOrNull() ?: return "{}"
        val parsedArgs = tool.function.arguments
        JSONObject().apply {
            put("type", "tool-triggered")
            put("content", JSONObject().apply {
                put("action_id", tool.id)
                put("name", tool.function.name)
                put("arguments", parsedArgs)
            })
        }.toString(2)
    } catch (_: Exception) { "{}" }
}
