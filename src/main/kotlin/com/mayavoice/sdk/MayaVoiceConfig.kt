package com.mayavoice.sdk

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

interface MayaVoiceListener {
    fun onConnectionStatusChange(status: ConnectionStatus) {}
    fun onConversationStatusChange(status: ConversationStatus) {}
    fun onSpeakingStatusChange(status: SpeakingStatus) {}
    fun onMessage(message: ConversationMessage) {}
    fun onTranscript(text: String, isFinal: Boolean) {}
    fun onError(error: MayaVoiceException) {}
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
}

// ---------------------------------------------------------------------------
// Main SDK configuration
// ---------------------------------------------------------------------------

data class MayaVoiceConfig(
    val appKey: String,
    val serverUrl: String,
    val configurationId: String? = null,
    val chatConfig: ChatConfig? = null,
    val voiceConfig: VoiceConfig? = null,
    val videoConfig: VideoConfig? = null,
    val connectionOptions: ConnectionOptions = ConnectionOptions(),
    var listener: MayaVoiceListener? = null,
    var meetingCallbacks: MeetingCallbacks? = null,
)
