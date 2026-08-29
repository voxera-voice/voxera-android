package com.rocs.demo

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.rocs.sdk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.util.UUID

// ── Static voice options ─────────────────────────────────────────────────
data class VoiceOption(
    val voiceId:     String,
    val name:        String,
    val description: String,
)

val STATIC_VOICES = listOf(
    VoiceOption("emma", "Emma", "Default voice · BosonAI"),
    VoiceOption("sara", "Sara",  "ElevenLabs · Multilingual"),
)

data class ChatUiState(
    // Setup (pre-call)
    val selectedServerId: String         = Config.DEFAULT_SERVER_ID,
    val customServerUrl:  String         = "",
    val appKey:           String         = "",
    val userId:           String         = Config.USER_ID,
    val threadId:         String         = UUID.randomUUID().toString(),
    val voices:           List<VoiceOption> = STATIC_VOICES,
    val selectedVoiceId:  String         = "emma",
    val voicesLoading:    Boolean        = false,
    val isSetupDone:      Boolean        = false,
    // Connection & conversation
    val connectionStatus:   ConnectionStatus   = ConnectionStatus.IDLE,
    val conversationStatus: ConversationStatus = ConversationStatus.IDLE,
    val speakingStatus:     SpeakingStatus     = SpeakingStatus.NONE,
    val messages:           List<ConversationMessage> = emptyList(),
    val errorMessage:       String?            = null,
    val isMuted:            Boolean            = false,
    val isListenMode:       Boolean            = false,
    val audioLevel:         Float              = 0f,
    val aiAudioLevel:       Float              = 0f,
    val currentTranscript:  String             = "",
    val remoteAudioTrack:   AudioTrack?        = null,
    val remoteVideoTrack:   VideoTrack?        = null,
    val localVideoTrack:    VideoTrack?        = null,
    val isVideoEnabled:     Boolean            = false,
    val isFrontCamera:      Boolean            = true,
    val systemPrompt:       String             = Config.DEFAULT_SYSTEM_PROMPT,
    val isSpeakerOn:        Boolean            = true,
    val isBluetoothConnected: Boolean          = false,
    val iFinalCount:        Int                = 0,
    // Room / meeting
    val roomMode:               RoomMode?               = null,
    val isHost:                 Boolean                  = false,
    val roomParticipants:       List<RoomParticipant>    = emptyList(),
    val transcriptions:         List<TranscriptionEntry> = emptyList(),
    val waitingRoom:            List<WaitingRoomEntry>   = emptyList(),
    val bookmarks:              List<MeetingBookmark>    = emptyList(),
    val summaries:              List<MeetingSummary>     = emptyList(),
    val currentMinutes:         MeetingMinutes?          = null,
    val isTranscriptionEnabled: Boolean                  = false,
    val isRoomLocked:           Boolean                  = false,
    val isWaitingRoomEnabled:   Boolean                  = false,
    val isInWaitingRoom:        Boolean                  = false,
    val askAiTextResponse:      String                   = "",
    val isAskAiTextProcessing:  Boolean                  = false,
    val toolCalls:              List<ToolCall>            = emptyList(),
    val toolCallsMessageId:     String                   = "",
    val lastSelectActionsJson:  String                   = "",
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var client: RocsClient? = null

    // ── Bluetooth SCO ────────────────────────────────────────────────────────

    private val audioManager: AudioManager
        get() = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    _uiState.value = _uiState.value.copy(isBluetoothConnected = true, isSpeakerOn = false)
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    _uiState.value = _uiState.value.copy(isBluetoothConnected = false)
                }
            }
        }
    }

    // Detects BT SCO device physically connecting/disconnecting — no Bluetooth permission needed
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            if (addedDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) {
                startBluetoothSco()
            }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (removedDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) {
                stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
                _uiState.value = _uiState.value.copy(isBluetoothConnected = false, isSpeakerOn = true)
            }
        }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        getApplication<Application>().registerReceiver(bluetoothReceiver, filter)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        // If a BT SCO headset is already connected when the call starts, route to it immediately
        val alreadyConnected = audioManager
            .getDevices(AudioManager.GET_DEVICES_ALL)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (alreadyConnected) startBluetoothSco()
    }

    private fun unregisterBluetoothReceiver() {
        runCatching { getApplication<Application>().unregisterReceiver(bluetoothReceiver) }
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
    }

    @Suppress("DEPRECATION")
    private fun startBluetoothSco() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
    }

    @Suppress("DEPRECATION")
    private fun stopBluetoothSco() {
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
    }

    @Suppress("DEPRECATION")
    fun selectAudioOutput(useSpeaker: Boolean, useBluetooth: Boolean = false) {
        stopBluetoothSco()
        when {
            useBluetooth -> startBluetoothSco()
            useSpeaker -> {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
            }
            else -> {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = false
            }
        }
        _uiState.value = _uiState.value.copy(
            isSpeakerOn = useSpeaker && !useBluetooth,
            isBluetoothConnected = useBluetooth,
        )
    }

    // ── Setup actions ────────────────────────────────────────────────────────

    fun setSelectedServer(id: String) {
        _uiState.value = _uiState.value.copy(selectedServerId = id)
    }
    fun setCustomServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(customServerUrl = url)
    }
    fun setAppKey(key: String)    { _uiState.value = _uiState.value.copy(appKey = key) }
    fun setUserId(id: String)     { _uiState.value = _uiState.value.copy(userId = id) }
    fun setThreadId(id: String)   { _uiState.value = _uiState.value.copy(threadId = id) }
    fun regenerateThreadId()      { _uiState.value = _uiState.value.copy(threadId = UUID.randomUUID().toString()) }
    fun setSelectedVoice(id: String) { _uiState.value = _uiState.value.copy(selectedVoiceId = id) }

    val actualServerUrl: String get() {
        val s = _uiState.value
        return if (s.selectedServerId == "custom") s.customServerUrl
        else Config.servers.firstOrNull { it.id == s.selectedServerId }?.url ?: ""
    }

    val canStart: Boolean get() {
        val url = actualServerUrl
        return url.startsWith("http://") || url.startsWith("https://")
    }

    @Suppress("DEPRECATION")
    fun startCall() {
        val s = _uiState.value
        val config = RocsConfig(
            appKey        = s.appKey.trim(),
            serverUrl     = actualServerUrl,
            userId        = s.userId.trim().ifEmpty { null },
            threadId      = s.threadId.trim().ifEmpty { null },
            selectedVoice = s.selectedVoiceId.ifEmpty { null },
            username      = "Mohammad Naim Almani",
            userInfo      = mapOf("info" to "user height is 189 cm and his weight is 105 kg"),
        )
        client = RocsClient(getApplication(), config).also { c ->
            c.listener = buildListener()
        }
        _uiState.value = _uiState.value.copy(isSetupDone = true)
        // Default to speaker mode; register receiver to detect BT headset connect/disconnect
        audioManager.isSpeakerphoneOn = true
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        registerBluetoothReceiver()
        client?.connect()
    }

    fun backToSetup() {
        stopBluetoothSco()
        unregisterBluetoothReceiver()
        client?.disconnect()
        client = null
        _uiState.value = ChatUiState(
            selectedServerId = _uiState.value.selectedServerId,
            customServerUrl  = _uiState.value.customServerUrl,
            appKey           = _uiState.value.appKey,
            userId           = _uiState.value.userId,
            threadId         = UUID.randomUUID().toString(),
            voices           = _uiState.value.voices,
            selectedVoiceId  = _uiState.value.selectedVoiceId,
        )
    }

    // ── Basic Actions ────────────────────────────────────────────────────────

    fun connect()           { client?.connect() }
    fun disconnect()        { client?.disconnect() }
    fun startConversation() { client?.startConversation() }
    fun endConversation()   { backToSetup() }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        runCatching { client?.sendMessage(text) }
            .onFailure { _uiState.value = _uiState.value.copy(errorMessage = it.message) }
    }

    fun selectAction(message: String, actionId: String) {
        val payload = JSONObject().apply {
            put("message", message)
            put("event", "tool-output")
            put("action_id", actionId)
        }.toString(2)
        client?.selectAction(message, actionId)
        _uiState.value = _uiState.value.copy(toolCalls = emptyList(), toolCallsMessageId = "", lastSelectActionsJson = payload)
    }

    fun toggleMute() {
        val next = !_uiState.value.isMuted
        _uiState.value = _uiState.value.copy(isMuted = next)
        client?.setMuted(next)
    }

    fun toggleListenMode() {
        val next = !_uiState.value.isListenMode
        _uiState.value = _uiState.value.copy(isListenMode = next)
        // client.setListenMode(next)  // Not yet in SDK
    }

    fun toggleSpeaker() {
        val s = _uiState.value
        when {
            // BT headset is active → switch back to speaker
            s.isBluetoothConnected -> selectAudioOutput(useSpeaker = true, useBluetooth = false)
            // Otherwise just toggle between speaker and earpiece
            s.isSpeakerOn -> selectAudioOutput(useSpeaker = false, useBluetooth = false)
            else -> selectAudioOutput(useSpeaker = true, useBluetooth = false)
        }
    }

    fun toggleCamera() {
        val c = client ?: return
        if (c.isVideoEnabled) {
            c.stopCamera()
            _uiState.value = _uiState.value.copy(isVideoEnabled = false, localVideoTrack = null)
        } else {
            c.startCamera()
            // State will be updated via onLocalVideoTrack callback
        }
    }

    fun switchCamera() {
        client?.switchCamera()
        _uiState.value = _uiState.value.copy(isFrontCamera = client?.isFrontCamera ?: true)
    }

    /** Shared EglBase for video rendering — pass to SurfaceViewRenderer.init(). */
    val eglBase: EglBase? get() = client?.eglBase

    fun dismissError() { _uiState.value = _uiState.value.copy(errorMessage = null) }

    fun onPermissionDenied(permissions: String) {
        _uiState.value = _uiState.value.copy(errorMessage = "Permission denied: $permissions")
    }

    fun updateSystemPrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(systemPrompt = prompt)
    }

    // ── Host Controls ────────────────────────────────────────────────────────

    fun muteParticipant(clientId: String) { client?.muteParticipant(clientId) }
    fun muteAll()   { client?.muteAll() }
    fun unmuteAll() { client?.unmuteAll() }
    fun removeParticipant(clientId: String) { client?.removeParticipant(clientId) }
    fun lockRoom(locked: Boolean) { client?.lockRoom(locked) }
    fun endMeeting() { client?.endMeeting() }
    fun transferHost(clientId: String) { client?.transferHost(clientId) }
    fun toggleTranscription(enabled: Boolean) { client?.toggleTranscription(enabled) }
    fun enableWaitingRoom(enabled: Boolean) { client?.enableWaitingRoom(enabled) }
    fun admitParticipant(clientId: String) { client?.admitParticipant(clientId) }
    fun denyParticipant(clientId: String) { client?.denyParticipant(clientId) }
    fun admitAll() { client?.admitAll() }

    // ── AI Features ──────────────────────────────────────────────────────────

    fun askAi() { client?.askAi("") }
    fun askAiText(prompt: String) { client?.askAiText(prompt) }
    fun addBookmark(label: String) { client?.addBookmark(label) }
    fun removeBookmark(bookmarkId: String) { client?.removeBookmark(bookmarkId) }
    fun generateSummary() { client?.generateSummary(null) }
    fun generateMinutes() { client?.generateMinutes(null) }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun buildListener(): RocsListener = object : RocsListener {
        override fun onConnectionStatusChanged(status: ConnectionStatus) {
            _uiState.value = _uiState.value.copy(connectionStatus = status)
            // Auto-start conversation once connected (same as rocs.tsx)
            if (status == ConnectionStatus.CONNECTED) {
                client?.startConversation()
            }
            // Return to setup on disconnect
            if (status == ConnectionStatus.DISCONNECTED) {
                backToSetup()
            }
        }
        override fun onConversationStatusChanged(status: ConversationStatus) {
            _uiState.value = _uiState.value.copy(conversationStatus = status)
        }
        override fun onSpeakingStatusChanged(status: SpeakingStatus) {
            _uiState.value = _uiState.value.copy(speakingStatus = status)
        }
        override fun onMessage(message: ConversationMessage) {
            if (!message.isFinal) return
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + message,
                iFinalCount = _uiState.value.iFinalCount + 1,
            )
        }
        override fun onTranscript(text: String, isFinal: Boolean) {
            _uiState.value = _uiState.value.copy(
                currentTranscript = if (isFinal) "" else text
            )
        }
        override fun onError(error: RocsException) {
            _uiState.value = _uiState.value.copy(errorMessage = error.message)
        }
        override fun onAudioLevel(level: Float) {
            _uiState.value = _uiState.value.copy(audioLevel = level)
        }
        override fun onAIAudioLevel(level: Float) {
            _uiState.value = _uiState.value.copy(aiAudioLevel = level)
        }
        override fun onRemoteAudioTrack(track: AudioTrack?) {
            _uiState.value = _uiState.value.copy(remoteAudioTrack = track)
        }
        override fun onLocalVideoTrack(track: VideoTrack?) {
            _uiState.value = _uiState.value.copy(
                localVideoTrack = track,
                isVideoEnabled = track != null,
                isFrontCamera = client?.isFrontCamera ?: true,
            )
        }
        override fun onRemoteVideoTrack(track: VideoTrack?) {
            _uiState.value = _uiState.value.copy(remoteVideoTrack = track)
        }
        override fun onRoomParticipantsUpdated(participants: List<RoomParticipant>) {
            _uiState.value = _uiState.value.copy(roomParticipants = participants)
        }
        override fun onTranscriptionsUpdated(transcriptions: List<TranscriptionEntry>) {
            _uiState.value = _uiState.value.copy(transcriptions = transcriptions)
        }
        override fun onWaitingRoomUpdated(waitingRoom: List<WaitingRoomEntry>) {
            _uiState.value = _uiState.value.copy(waitingRoom = waitingRoom)
        }
        override fun onBookmarksUpdated(bookmarks: List<MeetingBookmark>) {
            _uiState.value = _uiState.value.copy(bookmarks = bookmarks)
        }
        override fun onSummariesUpdated(summaries: List<MeetingSummary>) {
            _uiState.value = _uiState.value.copy(summaries = summaries)
        }
        override fun onMinutesUpdated(minutes: MeetingMinutes?) {
            _uiState.value = _uiState.value.copy(currentMinutes = minutes)
        }
        override fun onRoomModeChanged(mode: RoomMode) {
            _uiState.value = _uiState.value.copy(roomMode = mode)
        }
        override fun onIsHostChanged(isHost: Boolean) {
            _uiState.value = _uiState.value.copy(isHost = isHost)
        }
        override fun onMeetingEvent(event: String, data: org.json.JSONObject) {
            syncBooleanState()
        }
        override fun onToolCalls(tools: List<ToolCall>, messageId: String) {
            _uiState.value = _uiState.value.copy(toolCalls = tools, toolCallsMessageId = messageId)
        }
        override fun onClearAction(actionIds: List<String>, sessionId: String) {
            _uiState.value = _uiState.value.copy(
                toolCalls = _uiState.value.toolCalls.filter { it.id !in actionIds }
            )
        }
    }

    private fun syncBooleanState() {
        val c = client ?: return
        _uiState.value = _uiState.value.copy(
            isRoomLocked = c.isRoomLocked,
            isTranscriptionEnabled = c.isTranscriptionEnabled,
            isWaitingRoomEnabled = c.isWaitingRoomEnabled,
            isInWaitingRoom = c.isInWaitingRoom,
            askAiTextResponse = c.askAiTextResponse,
            isAskAiTextProcessing = c.isAskAiTextProcessing,
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopBluetoothSco()
        unregisterBluetoothReceiver()
        client?.disconnect()
    }
}