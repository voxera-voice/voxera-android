package com.rocs.demo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rocs.demo.ChatViewModel
import com.rocs.demo.Config

// ── Colors (matching rocs.tsx) ───────────────────────────────────────────────
private val BgDark     = Color(0xFF0F172A)
private val CardBg     = Color(0xFF1E293B)
private val InputBg    = Color(0xFF0F172A)
private val BorderClr  = Color(0xFF334155)
private val GreenStart = Color(0xFF10B981)
private val BlueSel    = Color(0xFF1D4ED8)
private val PinkF      = Color(0xFFEC4899)
private val BlueM      = Color(0xFF3B82F6)
private val TextPrimary   = Color(0xFFE2E8F0)
private val TextSecondary = Color(0xFF94A3B8)
private val TextMuted     = Color(0xFF64748B)
private val TextDim       = Color(0xFF475569)

// ── SetupScreen ──────────────────────────────────────────────────────────────

@Composable
fun SetupScreen(viewModel: ChatViewModel) {

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var serverExpanded by remember { mutableStateOf(false) }

    val actualUrl = viewModel.actualServerUrl
    val isValidUrl = actualUrl.startsWith("http://") || actualUrl.startsWith("https://")
    val canStart = isValidUrl

    Box(Modifier.fillMaxSize().background(BgDark)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            // ── Header ──
            Spacer(Modifier.height(8.dp))
            Text("ROCS", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = (-1).sp)
            Text("Real-time AI voice conversations", fontSize = 14.sp, color = TextMuted, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(24.dp))

            // ── Error banner ──
            state.errorMessage?.let { err ->
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Red.copy(alpha = 0.15f))
                        .border(1.dp, Color.Red, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) { Text("⚠️ $err", color = Color(0xFFFCA5A5), fontSize = 13.sp) }
                Spacer(Modifier.height(16.dp))
            }

            // ── Server card ──
            SetupCard {
                CardTitle("🌐 Server")

                PickerButton(
                    text = Config.servers.firstOrNull { it.id == state.selectedServerId }?.label ?: "Select…",
                    expanded = serverExpanded,
                    onClick = { serverExpanded = !serverExpanded },
                )

                AnimatedVisibility(visible = serverExpanded) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(InputBg)
                            .border(1.dp, BorderClr, RoundedCornerShape(10.dp))
                    ) {
                        Config.servers.forEachIndexed { i, server ->
                            val sel = server.id == state.selectedServerId
                            Box(
                                Modifier.fillMaxWidth()
                                    .then(if (sel) Modifier.background(BlueSel) else Modifier)
                                    .clickable { viewModel.setSelectedServer(server.id); serverExpanded = false }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Text(server.label, fontSize = 13.sp,
                                    color = if (sel) Color.White else TextPrimary,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                            }
                            if (i < Config.servers.size - 1) HorizontalDivider(color = CardBg, thickness = 1.dp)
                        }
                    }
                }

                AnimatedVisibility(visible = state.selectedServerId == "custom") {
                    Column(Modifier.padding(top = 8.dp)) {
                        SetupInput(
                            value = state.customServerUrl,
                            onValueChange = viewModel::setCustomServerUrl,
                            placeholder = "https://your-server.example.com",
                            keyboardType = KeyboardType.Uri,
                        )
                        if (state.customServerUrl.isNotEmpty() && !isValidUrl)
                            Text("⚠️ Enter a valid http/https URL", color = Color(0xFFF87171), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }

                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("URL: ", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(actualUrl.ifEmpty { "—" }, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Voice card ──
            SetupCard {
                CardTitle("🎙 Voice")

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.voices.forEach { voice ->
                        val sel = voice.voiceId == state.selectedVoiceId
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (sel) BlueSel else InputBg,
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, if (sel) BlueM else BorderClr, RoundedCornerShape(10.dp))
                                .clickable { viewModel.setSelectedVoice(voice.voiceId) },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(voice.name, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                    color = if (sel) Color.White else TextPrimary)
                                Text(voice.description, fontSize = 11.sp,
                                    color = if (sel) Color.White.copy(alpha = 0.75f) else TextMuted,
                                    modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Session config card ──
            SetupCard {
                CardTitle("🔑 Session")

                Text("App Key", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextMuted)
                Spacer(Modifier.height(4.dp))
                SetupInput(
                    value = state.appKey,
                    onValueChange = viewModel::setAppKey,
                    placeholder = "App Key",
                )

                Spacer(Modifier.height(12.dp))
                Text("User ID", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextMuted)
                Spacer(Modifier.height(4.dp))
                SetupInput(
                    value = state.userId,
                    onValueChange = viewModel::setUserId,
                    placeholder = "User ID",
                )

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Thread ID", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextMuted)
                    Surface(
                        shape = RoundedCornerShape(8.dp), color = Color.Transparent,
                        modifier = Modifier.clickable { viewModel.regenerateThreadId() },
                    ) {
                        Text("↻ New", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3B82F6), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                SetupInput(
                    value = state.threadId,
                    onValueChange = viewModel::setThreadId,
                    placeholder = "Thread ID",
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Start button ──
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (canStart) GreenStart else CardBg,
                modifier = Modifier.fillMaxWidth().height(56.dp)
                    .then(if (canStart) Modifier.clickable { viewModel.startCall() } else Modifier),
            ) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("📞", fontSize = 22.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("Start Call", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Text(
                when {
                    !isValidUrl -> "Select a valid server to start"
                    else -> "Tap Start Call to connect"
                },
                fontSize = 12.sp, color = TextDim,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).wrapContentWidth(Alignment.CenterHorizontally),
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

@Composable
private fun SetupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun PickerButton(text: String, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp), color = InputBg,
        modifier = Modifier.fillMaxWidth().border(1.dp, BorderClr, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 14.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SetupInput(value: String, onValueChange: (String) -> Unit, placeholder: String, keyboardType: KeyboardType = KeyboardType.Text) {
    Surface(
        shape = RoundedCornerShape(10.dp), color = InputBg,
        modifier = Modifier.fillMaxWidth().border(1.dp, BorderClr, RoundedCornerShape(10.dp)),
    ) {
        TextField(
            value = value, onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = TextSecondary) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = TextPrimary,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done, autoCorrect = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
