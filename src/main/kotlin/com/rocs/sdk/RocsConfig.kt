package com.rocs.sdk

// ---------------------------------------------------------------------------
// Chat / Voice / Video config
// ---------------------------------------------------------------------------

data class ChatConfig(
    val systemPrompt: String? = null,
    val welcomeMessage: String? = null,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null
)

data class VoiceConfig(
    val voiceId: String? = null,
    val language: String? = null,
    val stability: Double? = null,
    val similarityBoost: Double? = null
)

data class VideoConfig(
    val enabled: Boolean = false,
    val width: Int = 1280,
    val height: Int = 720,
    val frameRate: Int = 30,
    /** Enable server-side AI frame analysis */
    val enableVideoAI: Boolean = false,
    /** Use front-facing camera */
    val useFrontCamera: Boolean = true
)

data class ConnectionOptions(
    val autoReconnect: Boolean = true,
    val reconnectAttempts: Int = 3,
    val reconnectDelayMs: Long = 1_000,
    val timeoutMs: Long = 30_000
)

// ---------------------------------------------------------------------------
// Listener interface (delegate pattern)
// ---------------------------------------------------------------------------

interface RocsListener {
    fun onConnectionStatusChanged(status: ConnectionStatus) {}
    fun onConversationStatusChanged(status: ConversationStatus) {}
    fun onSpeakingStatusChanged(status: SpeakingStatus) {}
    fun onMessage(message: ConversationMessage) {}
    fun onTranscript(text: String, isFinal: Boolean) {}
    fun onError(error: RocsException) {}
    fun onAudioLevel(level: Float) {}
    fun onAIAudioLevel(level: Float) {}
    /**
     * Provides the local camera VideoTrack.
     * Attach it to a `SurfaceViewRenderer` to display:
     *   track?.addSink(mySurfaceViewRenderer)
     */
    fun onLocalVideoTrack(track: org.webrtc.VideoTrack?) {}
    /** Provides the remote AI audio track. */
    fun onRemoteAudioTrack(track: org.webrtc.AudioTrack?) {}
    fun onRemoteVideoTrack(track: org.webrtc.VideoTrack?) {}
    fun onMuteChanged(muted: Boolean) {}

    // Meeting delegate methods
    fun onRoomParticipantsUpdated(participants: List<RoomParticipant>) {}
    fun onTranscriptionsUpdated(transcriptions: List<TranscriptionEntry>) {}
    fun onWaitingRoomUpdated(waitingRoom: List<WaitingRoomEntry>) {}
    fun onBookmarksUpdated(bookmarks: List<MeetingBookmark>) {}
    fun onSummariesUpdated(summaries: List<MeetingSummary>) {}
    fun onMinutesUpdated(minutes: MeetingMinutes?) {}
    fun onRoomModeChanged(mode: RoomMode) {}
    fun onIsHostChanged(isHost: Boolean) {}
    fun onMeetingEvent(event: String, data: org.json.JSONObject) {}
    fun onToolCalls(tools: List<ToolCall>, messageId: String) {}
    fun onClearAction(actionIds: List<String>, sessionId: String) {}
    fun onSearching(query: String) {}
}

// ---------------------------------------------------------------------------
// Main SDK configuration
// ---------------------------------------------------------------------------

data class RocsConfig(
    val appKey: String,
    val serverUrl: String,
    @Deprecated("Use agentId instead") val configurationId: String? = null,
    val agentId: String? = null,
    val userId: String? = null,
    val metadata: Map<String, String>? = null,
    val videoConfig: VideoConfig? = null,
    val connectionOptions: ConnectionOptions = ConnectionOptions(),
    var listener: RocsListener? = null,
    var meetingCallbacks: MeetingCallbacks? = null,

    // ── Voice-session fields ──
    /** Pre-set sessionId; if null one is generated client-side via UUID. */
    val sessionId: String? = null,
    /** Thread identifier for conversation continuity. */
    val threadId: String? = null,
    /** Chat profile name configured on the server. */
    val chatProfile: String? = null,
    /** Arbitrary user metadata dict sent with init-session-connection. */
    val user: Map<String, Any>? = null,
    /** TTS configuration (provider, voiceId, language, etc.). */
    val ttsConfig: Map<String, Any>? = null,
    /** Transcription / STT configuration. */
    val transcriptionConfig: Map<String, Any>? = null,
    /** Text model provider/endpoint configuration. */
    val modelConfig: Map<String, Any>? = null,
    /** Function-tool definitions made available to the model. */
    val tools: List<Map<String, Any>>? = null,
    /** Extra system instructions appended after the server prompt. */
    val additionalSystemPrompts: List<String>? = null,
    /** Messages to pre-load into the conversation. */
    val initialMessages: List<Map<String, Any>>? = null,
    /** AI model identifier (e.g. "gpt-4o"). */
    val selectedModel: String? = null,
    /** Voice identifier for TTS. */
    val selectedVoice: String? = null,
    /** Workspace / tenant identifier. */
    val workspaceId: String? = null,
    /** Display name of the person speaking — passed to the AI in the system prompt. */
    val username: String? = null,
    /** Additional context/metadata about the user — appended to the AI system prompt. */
    val userInfo: Map<String, String>? = null,
)
