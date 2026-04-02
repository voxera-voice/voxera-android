package com.mayavoice.sdk

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.VideoTrack

/**
 * Main Android SDK client.
 *
 * **Quick-start:**
 * ```kotlin
 * val config = MayaVoiceConfig(
 *     appKey = "YOUR_APP_KEY",
 *     serverUrl = "wss://media.example.com",
 *     chatConfig = ChatConfig(systemPrompt = "You are a helpful assistant.")
 * )
 * val client = MayaVoiceClient(context, config)
 * client.listener = object : MayaVoiceListener {
 *     override fun onConnectionStatusChanged(status: ConnectionStatus) { /* ... */ }
 *     override fun onMessage(message: ConversationMessage) { /* ... */ }
 * }
 * client.connect()
 * ```
 */
class MayaVoiceClient(
    private val context: Context,
    private val config: MayaVoiceConfig,
) {
    // -----------------------------------------------------------------------
    // Public state
    // -----------------------------------------------------------------------

    var listener: MayaVoiceListener? = null

    var connectionStatus:   ConnectionStatus   = ConnectionStatus.IDLE;   private set
    var conversationStatus: ConversationStatus = ConversationStatus.IDLE; private set
    var speakingStatus:     SpeakingStatus     = SpeakingStatus.NONE;     private set
    val messages:           MutableList<ConversationMessage> = mutableListOf()
    var sessionId:          String? = null; private set

    val isConnected          get() = connectionStatus == ConnectionStatus.CONNECTED
    val isConversationActive get() = conversationStatus == ConversationStatus.ACTIVE

    // Meeting state
    var roomMode:               RoomMode? = null;           private set
    var isHost:                 Boolean = false;             private set
    val roomParticipants:       MutableList<RoomParticipant> = mutableListOf()
    val transcriptions:         MutableList<TranscriptionEntry> = mutableListOf()
    val waitingRoom:            MutableList<WaitingRoomEntry> = mutableListOf()
    val bookmarks:              MutableList<MeetingBookmark> = mutableListOf()
    val summaries:              MutableList<MeetingSummary> = mutableListOf()
    var currentMinutes:         MeetingMinutes? = null;     private set
    var isTranscriptionEnabled: Boolean = false;            private set
    var isAskAiActive:          Boolean = false;            private set
    var isRoomLocked:           Boolean = false;            private set
    var isWaitingRoomEnabled:   Boolean = false;            private set
    var isInWaitingRoom:        Boolean = false;            private set
    var askAiTextResponse:      String = "";                private set
    var isAskAiTextProcessing:  Boolean = false;            private set
    private var meetingCallbacks: MeetingCallbacks = MeetingCallbacks()

    // -----------------------------------------------------------------------
    // Private
    // -----------------------------------------------------------------------

    private val scope      = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var signaling: SocketSignaling
    private lateinit var webRTC: WebRTCManager

    private var reconnectAttempts = 0

    // -----------------------------------------------------------------------
    // Connect
    // -----------------------------------------------------------------------

    fun connect() {
        if (connectionStatus == ConnectionStatus.CONNECTED ||
            connectionStatus == ConnectionStatus.CONNECTING) return

        setConnectionStatus(ConnectionStatus.CONNECTING)
        scope.launch {
            try {
                val session = initSession()
                sessionId   = session.optString("sessionId").ifEmpty { null }

                signaling = SocketSignaling(config.serverUrl)
                setupSignalingCallbacks()
                setupMeetingSignalingCallbacks()
                signaling.connect()

                // init-session-connection
                signaling.emitWithAck("init-session-connection", JSONObject().apply {
                    put("sessionId", sessionId ?: "")
                    put("appKey",    config.appKey)
                    put("userId",    "android-user")
                    put("enableVideoAI", config.videoConfig?.enableVideoAI ?: false)
                })

                // RTP capabilities
                val rtpCaps = signaling.emitWithAck("getRtpCapabilities")

                // WebRTC
                webRTC = WebRTCManager(context, signaling)
                webRTC.init()
                webRTC.onRemoteAudioTrack = { track -> handleRemoteAudioTrack(track) }
                webRTC.onRemoteVideoTrack = { track -> handleRemoteVideoTrack(track) }
                webRTC.onError = { err ->
                    handleError(MayaVoiceException(err.message ?: "WebRTC error", MayaVoiceErrorCode.WEBRTC_ERROR))
                }

                // ICE servers
                val iceResult = runCatching { signaling.emitWithAck("getIceServers") }.getOrNull()
                val iceServers = iceResult?.optJSONArray("servers")

                // Send transport
                val sendParams = signaling.emitWithAck("createTransport")
                webRTC.createSendTransport(sendParams, iceServers)

                // Recv transport
                val recvParams = signaling.emitWithAck("createTransport")
                webRTC.createRecvTransport(recvParams, iceServers)

                // Produce audio
                webRTC.produceAudio()

                setConnectionStatus(ConnectionStatus.CONNECTED)
            } catch (e: Exception) {
                handleError(e as? MayaVoiceException ?: MayaVoiceException(e.message ?: "Unknown error", MayaVoiceErrorCode.UNKNOWN))
                setConnectionStatus(ConnectionStatus.ERROR)
            }
        }
    }

    fun disconnect() {
        if (connectionStatus == ConnectionStatus.IDLE ||
            connectionStatus == ConnectionStatus.DISCONNECTED) return
        if (conversationStatus == ConversationStatus.ACTIVE) endConversation()
        cleanup()
        setConnectionStatus(ConnectionStatus.DISCONNECTED)
    }

    // -----------------------------------------------------------------------
    // Conversation
    // -----------------------------------------------------------------------

    fun startConversation() {
        if (!isConnected) return
        if (conversationStatus == ConversationStatus.ACTIVE) return
        setConversationStatus(ConversationStatus.STARTING)
        setMuted(false)
        signaling.emit("conversation:start")
        setConversationStatus(ConversationStatus.ACTIVE)
    }

    fun endConversation() {
        if (conversationStatus != ConversationStatus.ACTIVE) return
        setConversationStatus(ConversationStatus.ENDING)
        signaling.emit("conversation:end")
        setConversationStatus(ConversationStatus.IDLE)
    }

    fun sendMessage(content: String) {
        if (!isConnected) throw MayaVoiceException("Not connected", MayaVoiceErrorCode.CONNECTION_FAILED)
        val message = ConversationMessage(
            id        = "msg_${System.currentTimeMillis()}_${(Math.random() * 9999).toInt()}",
            role      = "user",
            content   = content,
            timestamp = System.currentTimeMillis(),
        )
        messages += message
        listener?.onMessage(message)
        config.listener?.onMessage(message)
        signaling.emit("message:send", JSONObject().put("content", content))
    }

    // -----------------------------------------------------------------------
    // Mute
    // -----------------------------------------------------------------------

    fun setMuted(muted: Boolean) {
        // Mute/unmute the local audio track directly
        val conn = runCatching { webRTC }.getOrNull()
        // AudioTrack muting is exposed via WebRTCManager if needed; fallback: no-op
        listener?.onMuteChanged(muted)
        config.listener?.onMuteChanged(muted)
    }

    // -----------------------------------------------------------------------
    // Video
    // -----------------------------------------------------------------------

    fun enableVideo() {
        scope.launch {
            try {
                // Use Camera2Capturer for the front camera (requires camera permission)
                val capturer = org.webrtc.Camera2Capturer(context, getFrontCameraId(), null)
                val producerId = webRTC.produceVideo(capturer)
                // producerId available for logging / track management
            } catch (e: Exception) {
                handleError(MayaVoiceException("Video failed: ${e.message}", MayaVoiceErrorCode.MEDIA_ACCESS_DENIED))
            }
        }
    }

    fun disableVideo() {
        signaling.emit("track:disable", JSONObject().put("kind", "video"))
    }

    // -----------------------------------------------------------------------
    // Meeting: configuration
    // -----------------------------------------------------------------------

    fun setMeetingCallbacks(callbacks: MeetingCallbacks) {
        meetingCallbacks = callbacks
    }

    fun setRoomInfo(roomMode: RoomMode? = null, isHost: Boolean? = null) {
        if (roomMode != null) this.roomMode = roomMode
        if (isHost != null) this.isHost = isHost
    }

    // -----------------------------------------------------------------------
    // Meeting: socket-only connect (multi-room)
    // -----------------------------------------------------------------------

    fun connectSocketOnly(roomId: String, displayName: String) {
        if (connectionStatus == ConnectionStatus.CONNECTED ||
            connectionStatus == ConnectionStatus.CONNECTING) return

        setConnectionStatus(ConnectionStatus.CONNECTING)
        scope.launch {
            try {
                signaling = SocketSignaling(config.serverUrl)
                setupSignalingCallbacks()
                setupMeetingSignalingCallbacks()
                signaling.connect()

                signaling.emitWithAck("join-room", JSONObject().apply {
                    put("roomId", roomId)
                    put("displayName", displayName)
                    put("appKey", config.appKey)
                    put("configurationId", config.configurationId ?: "")
                    put("roomMode", this@MayaVoiceClient.roomMode?.value ?: "ai-meeting")
                    put("isHost", this@MayaVoiceClient.isHost)
                })

                setConnectionStatus(ConnectionStatus.CONNECTED)
            } catch (e: Exception) {
                handleError(e as? MayaVoiceException ?: MayaVoiceException(e.message ?: "Unknown error", MayaVoiceErrorCode.UNKNOWN_ERROR))
                setConnectionStatus(ConnectionStatus.ERROR)
            }
        }
    }

    fun setupRoomWebRTC() {
        if (!::signaling.isInitialized) throw MayaVoiceException("Socket not connected", MayaVoiceErrorCode.CONNECTION_FAILED)
        scope.launch {
            try {
                val rtpCaps = signaling.emitWithAck("getRtpCapabilities")
                webRTC = WebRTCManager(context, signaling)
                webRTC.init()
                webRTC.onRemoteAudioTrack = { track -> handleRemoteAudioTrack(track) }
                webRTC.onRemoteVideoTrack = { track -> handleRemoteVideoTrack(track) }
                webRTC.onError = { err ->
                    handleError(MayaVoiceException(err.message ?: "WebRTC error", MayaVoiceErrorCode.WEBRTC_ERROR))
                }
                val iceResult = runCatching { signaling.emitWithAck("getIceServers") }.getOrNull()
                val iceServers = iceResult?.optJSONArray("servers")
                val sendParams = signaling.emitWithAck("createTransport")
                webRTC.createSendTransport(sendParams, iceServers)
                val recvParams = signaling.emitWithAck("createTransport")
                webRTC.createRecvTransport(recvParams, iceServers)
                webRTC.produceAudio()
            } catch (e: Exception) {
                handleError(MayaVoiceException("WebRTC setup failed: ${e.message}", MayaVoiceErrorCode.WEBRTC_ERROR))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Meeting: host control methods
    // -----------------------------------------------------------------------

    fun muteParticipant(clientId: String) {
        scope.launch { signaling.emitWithAck("mute-participant", JSONObject().put("clientId", clientId)) }
    }

    fun muteAll() {
        scope.launch { signaling.emitWithAck("mute-all") }
    }

    fun unmuteAll() {
        scope.launch { signaling.emitWithAck("unmute-all") }
    }

    fun removeParticipant(clientId: String) {
        scope.launch { signaling.emitWithAck("remove-participant", JSONObject().put("clientId", clientId)) }
    }

    fun lockRoom(locked: Boolean) {
        scope.launch { signaling.emitWithAck("lock-room", JSONObject().put("locked", locked)) }
    }

    fun endMeeting() {
        scope.launch { signaling.emitWithAck("end-meeting") }
    }

    fun transferHost(clientId: String) {
        scope.launch { signaling.emitWithAck("transfer-host", JSONObject().put("clientId", clientId)) }
    }

    fun toggleTranscription(enabled: Boolean) {
        scope.launch { signaling.emitWithAck("toggle-transcription", JSONObject().put("enabled", enabled)) }
    }

    fun askAi(prompt: String) {
        scope.launch { signaling.emitWithAck("ask-ai", JSONObject().put("prompt", prompt)) }
    }

    fun cancelAskAi() {
        scope.launch { signaling.emitWithAck("cancel-ask-ai") }
    }

    fun askAiText(prompt: String) {
        isAskAiTextProcessing = true
        askAiTextResponse = ""
        scope.launch { signaling.emitWithAck("ask-ai-text", JSONObject().put("prompt", prompt)) }
    }

    fun enableWaitingRoom(enabled: Boolean) {
        scope.launch { signaling.emitWithAck("enable-waiting-room", JSONObject().put("enabled", enabled)) }
    }

    fun admitParticipant(clientId: String) {
        scope.launch { signaling.emitWithAck("admit-participant", JSONObject().put("clientId", clientId)) }
    }

    fun denyParticipant(clientId: String) {
        scope.launch { signaling.emitWithAck("deny-participant", JSONObject().put("clientId", clientId)) }
    }

    fun admitAll() {
        scope.launch { signaling.emitWithAck("admit-all") }
    }

    // -----------------------------------------------------------------------
    // Meeting: AI differentiator methods
    // -----------------------------------------------------------------------

    fun generateSummary(callback: ((JSONObject) -> Unit)? = null) {
        scope.launch { callback?.invoke(signaling.emitWithAck("generate-summary")) }
    }

    fun generateMinutes(callback: ((JSONObject) -> Unit)? = null) {
        scope.launch { callback?.invoke(signaling.emitWithAck("generate-minutes")) }
    }

    fun addBookmark(note: String = "", callback: ((JSONObject) -> Unit)? = null) {
        scope.launch { callback?.invoke(signaling.emitWithAck("add-bookmark", JSONObject().put("note", note))) }
    }

    fun removeBookmark(bookmarkId: String, callback: ((JSONObject) -> Unit)? = null) {
        scope.launch { callback?.invoke(signaling.emitWithAck("remove-bookmark", JSONObject().put("bookmarkId", bookmarkId))) }
    }

    fun getBookmarks(callback: ((JSONObject) -> Unit)? = null) {
        scope.launch { callback?.invoke(signaling.emitWithAck("get-bookmarks")) }
    }

    fun getTranscript(callback: ((JSONObject) -> Unit)? = null) {
        scope.launch { callback?.invoke(signaling.emitWithAck("get-transcript")) }
    }

    fun getSummaries(callback: ((JSONObject) -> Unit)? = null) {
        scope.launch { callback?.invoke(signaling.emitWithAck("get-summaries")) }
    }

    fun getMinutes(callback: ((JSONObject) -> Unit)? = null) {
        scope.launch { callback?.invoke(signaling.emitWithAck("get-minutes")) }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun setupSignalingCallbacks() {
        signaling.onSpeakingStatusChanged = { status ->
            val mapped = when (status) {
                "user-speaking" -> SpeakingStatus.USER
                "ai-speaking"   -> SpeakingStatus.AI
                else            -> SpeakingStatus.NONE
            }
            setSpeakingStatus(mapped)
        }

        signaling.onConversationMessage = { sessionId, role, content, timestamp ->
            val msg = ConversationMessage(
                id        = "${role}_$timestamp",
                role      = role,
                content   = content,
                timestamp = timestamp,
            )
            messages += msg
            scope.launch(Dispatchers.Main) {
                listener?.onMessage(msg)
                config.listener?.onMessage(msg)
            }
        }

        signaling.onNewProducer = { producerId, source ->
            if (source == "ai") {
                scope.launch {
                    try {
                        val consumeParams = signaling.emitWithAck("consume", JSONObject().apply {
                            put("producerId", producerId)
                            put("transportId", "")
                            put("rtpCapabilities", JSONObject())
                        })
                        webRTC.consumeAI(consumeParams)
                    } catch (e: Exception) {
                        handleError(MayaVoiceException("Consume failed: ${e.message}", MayaVoiceErrorCode.WEBRTC_ERROR))
                    }
                }
            }
        }

        signaling.onServerError = { message ->
            handleError(MayaVoiceException(message, MayaVoiceErrorCode.UNKNOWN))
        }

        signaling.onDisconnect = {
            val opts = config.connectionOptions
            if (opts.autoReconnect && reconnectAttempts < opts.reconnectAttempts) {
                setConnectionStatus(ConnectionStatus.RECONNECTING)
                reconnectAttempts++
                scope.launch {
                    delay(opts.reconnectDelayMs)
                    connect()
                }
            } else {
                setConnectionStatus(ConnectionStatus.DISCONNECTED)
            }
        }
    }

    private fun setupMeetingSignalingCallbacks() {
        signaling.onParticipantJoined = { d ->
            val p = RoomParticipant.fromJson(d)
            roomParticipants.add(p)
            scope.launch(Dispatchers.Main) {
                listener?.onRoomParticipantsUpdated(roomParticipants.toList())
                meetingCallbacks.onParticipantJoined?.invoke(d)
                listener?.onMeetingEvent("participant-joined", d)
            }
        }
        signaling.onParticipantLeft = { d ->
            val clientId = d.optString("clientId")
            roomParticipants.removeAll { it.id == clientId }
            scope.launch(Dispatchers.Main) {
                listener?.onRoomParticipantsUpdated(roomParticipants.toList())
                meetingCallbacks.onParticipantLeft?.invoke(d)
                listener?.onMeetingEvent("participant-left", d)
            }
        }
        signaling.onParticipantRemoved = { d ->
            val clientId = d.optString("clientId")
            roomParticipants.removeAll { it.id == clientId }
            scope.launch(Dispatchers.Main) {
                listener?.onRoomParticipantsUpdated(roomParticipants.toList())
                meetingCallbacks.onParticipantRemoved?.invoke(d)
                listener?.onMeetingEvent("participant-removed", d)
            }
        }
        signaling.onParticipantsUpdated = { d ->
            val arr = d.optJSONArray("participants")
            roomParticipants.clear()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { roomParticipants.add(RoomParticipant.fromJson(it)) }
                }
            }
            scope.launch(Dispatchers.Main) {
                listener?.onRoomParticipantsUpdated(roomParticipants.toList())
                meetingCallbacks.onParticipantsUpdated?.invoke(d)
                listener?.onMeetingEvent("participants-updated", d)
            }
        }
        signaling.onYouWereMuted = { d ->
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onYouWereMuted?.invoke(d)
                listener?.onMeetingEvent("you-were-muted", d)
            }
        }
        signaling.onYouWereRemoved = { d ->
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onYouWereRemoved?.invoke(d)
                listener?.onMeetingEvent("you-were-removed", d)
            }
        }
        signaling.onAllMuted = { d ->
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onAllMuted?.invoke(d)
                listener?.onMeetingEvent("all-muted", d)
            }
        }
        signaling.onAllUnmuted = { d ->
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onAllUnmuted?.invoke(d)
                listener?.onMeetingEvent("all-unmuted", d)
            }
        }
        signaling.onHostChanged = { d ->
            isHost = d.optBoolean("isHost", false)
            scope.launch(Dispatchers.Main) {
                listener?.onIsHostChanged(isHost)
                meetingCallbacks.onHostChanged?.invoke(d)
                listener?.onMeetingEvent("host-changed", d)
            }
        }
        signaling.onMeetingEnded = { d ->
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onMeetingEnded?.invoke(d)
                listener?.onMeetingEvent("meeting-ended", d)
            }
        }
        signaling.onRoomLockedChanged = { d ->
            isRoomLocked = d.optBoolean("locked", false)
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onRoomLockedChanged?.invoke(d)
                listener?.onMeetingEvent("room-locked-changed", d)
            }
        }
        signaling.onTranscriptionToggled = { d ->
            isTranscriptionEnabled = d.optBoolean("enabled", false)
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onTranscriptionToggled?.invoke(d)
                listener?.onMeetingEvent("transcription-toggled", d)
            }
        }
        signaling.onLiveTranscription = { d ->
            transcriptions.add(TranscriptionEntry.fromJson(d))
            scope.launch(Dispatchers.Main) {
                listener?.onTranscriptionsUpdated(transcriptions.toList())
                meetingCallbacks.onLiveTranscription?.invoke(d)
                listener?.onMeetingEvent("live-transcription", d)
            }
        }
        signaling.onAskAiStarted = { d ->
            isAskAiActive = true
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onAskAiStarted?.invoke(d)
                listener?.onMeetingEvent("ask-ai-started", d)
            }
        }
        signaling.onAskAiProcessing = { d ->
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onAskAiProcessing?.invoke(d)
                listener?.onMeetingEvent("ask-ai-processing", d)
            }
        }
        signaling.onAskAiCancelled = { d ->
            isAskAiActive = false
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onAskAiCancelled?.invoke(d)
                listener?.onMeetingEvent("ask-ai-cancelled", d)
            }
        }
        signaling.onAskAiTextStarted = { d ->
            isAskAiTextProcessing = true
            askAiTextResponse = ""
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onAskAiTextStarted?.invoke(d)
                listener?.onMeetingEvent("ask-ai-text-started", d)
            }
        }
        signaling.onAskAiTextChunk = { d ->
            askAiTextResponse += d.optString("chunk", "")
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onAskAiTextChunk?.invoke(d)
                listener?.onMeetingEvent("ask-ai-text-chunk", d)
            }
        }
        signaling.onAskAiTextResponse = { d ->
            askAiTextResponse = d.optString("response", askAiTextResponse)
            isAskAiTextProcessing = false
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onAskAiTextResponse?.invoke(d)
                listener?.onMeetingEvent("ask-ai-text-response", d)
            }
        }
        signaling.onAskAiTextError = { d ->
            isAskAiTextProcessing = false
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onAskAiTextError?.invoke(d)
                listener?.onMeetingEvent("ask-ai-text-error", d)
            }
        }
        signaling.onWaitingRoom = { d ->
            isInWaitingRoom = true
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onWaitingRoom?.invoke(d)
                listener?.onMeetingEvent("waiting-room", d)
            }
        }
        signaling.onAdmitted = { d ->
            isInWaitingRoom = false
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onAdmitted?.invoke(d)
                listener?.onMeetingEvent("admitted", d)
            }
        }
        signaling.onDenied = { d ->
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onDenied?.invoke(d)
                listener?.onMeetingEvent("denied", d)
            }
        }
        signaling.onWaitingRoomUpdated = { d ->
            val arr = d.optJSONArray("waitingRoom")
            waitingRoom.clear()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { waitingRoom.add(WaitingRoomEntry.fromJson(it)) }
                }
            }
            scope.launch(Dispatchers.Main) {
                listener?.onWaitingRoomUpdated(waitingRoom.toList())
                meetingCallbacks.onWaitingRoomUpdated?.invoke(d)
                listener?.onMeetingEvent("waiting-room-updated", d)
            }
        }
        signaling.onWaitingRoomToggled = { d ->
            isWaitingRoomEnabled = d.optBoolean("enabled", false)
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onWaitingRoomToggled?.invoke(d)
                listener?.onMeetingEvent("waiting-room-toggled", d)
            }
        }
        signaling.onSummaryGenerating = { d ->
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onSummaryGenerating?.invoke(d)
                listener?.onMeetingEvent("summary-generating", d)
            }
        }
        signaling.onSummaryGenerated = { d ->
            summaries.add(MeetingSummary.fromJson(d))
            scope.launch(Dispatchers.Main) {
                listener?.onSummariesUpdated(summaries.toList())
                meetingCallbacks.onSummaryGenerated?.invoke(d)
                listener?.onMeetingEvent("summary-generated", d)
            }
        }
        signaling.onMinutesGenerating = { d ->
            scope.launch(Dispatchers.Main) {
                meetingCallbacks.onMinutesGenerating?.invoke(d)
                listener?.onMeetingEvent("minutes-generating", d)
            }
        }
        signaling.onMinutesGenerated = { d ->
            currentMinutes = MeetingMinutes.fromJson(d)
            scope.launch(Dispatchers.Main) {
                listener?.onMinutesUpdated(currentMinutes)
                meetingCallbacks.onMinutesGenerated?.invoke(d)
                listener?.onMeetingEvent("minutes-generated", d)
            }
        }
        signaling.onBookmarkAdded = { d ->
            bookmarks.add(MeetingBookmark.fromJson(d))
            scope.launch(Dispatchers.Main) {
                listener?.onBookmarksUpdated(bookmarks.toList())
                meetingCallbacks.onBookmarkAdded?.invoke(d)
                listener?.onMeetingEvent("bookmark-added", d)
            }
        }
        signaling.onBookmarkRemoved = { d ->
            val id = d.optString("bookmarkId", d.optString("id"))
            bookmarks.removeAll { it.id == id }
            scope.launch(Dispatchers.Main) {
                listener?.onBookmarksUpdated(bookmarks.toList())
                meetingCallbacks.onBookmarkRemoved?.invoke(d)
                listener?.onMeetingEvent("bookmark-removed", d)
            }
        }
    }

    private suspend fun initSession(): JSONObject {
        val apiUrl = config.serverUrl
            .replace("wss://", "https://")
            .replace("ws://",  "http://")

        return withContext(Dispatchers.IO) {
            val url = java.net.URL("$apiUrl/api/session/init")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type",  "application/json")
            conn.setRequestProperty("X-API-Key",     config.appKey)
            conn.doOutput = true
            val body = JSONObject().apply { put("configurationId", config.configurationId ?: "") }
            conn.outputStream.write(body.toString().toByteArray())
            if (conn.responseCode != 200) {
                throw MayaVoiceException("Session init failed (${conn.responseCode})", MayaVoiceErrorCode.AUTHENTICATION_FAILED)
            }
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = JSONObject(response)
            json.optJSONObject("data") ?: json
        }
    }

    private fun handleRemoteAudioTrack(track: AudioTrack) {
        scope.launch(Dispatchers.Main) {
            listener?.onRemoteAudioTrack(track)
            config.listener?.onRemoteAudioTrack(track)
        }
    }

    private fun handleRemoteVideoTrack(track: VideoTrack) {
        scope.launch(Dispatchers.Main) {
            listener?.onRemoteVideoTrack(track)
            config.listener?.onRemoteVideoTrack(track)
        }
    }

    private fun handleError(e: MayaVoiceException) {
        scope.launch(Dispatchers.Main) {
            listener?.onError(e)
            config.listener?.onError(e)
        }
    }

    private fun setConnectionStatus(status: ConnectionStatus) {
        connectionStatus = status
        scope.launch(Dispatchers.Main) {
            listener?.onConnectionStatusChanged(status)
            config.listener?.onConnectionStatusChanged(status)
        }
    }

    private fun setConversationStatus(status: ConversationStatus) {
        conversationStatus = status
        scope.launch(Dispatchers.Main) {
            listener?.onConversationStatusChanged(status)
            config.listener?.onConversationStatusChanged(status)
        }
    }

    private fun setSpeakingStatus(status: SpeakingStatus) {
        speakingStatus = status
        scope.launch(Dispatchers.Main) {
            listener?.onSpeakingStatusChanged(status)
            config.listener?.onSpeakingStatusChanged(status)
        }
    }

    private fun getFrontCameraId(): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
            if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) return id
        }
        return manager.cameraIdList.firstOrNull() ?: throw MayaVoiceException("No camera found", MayaVoiceErrorCode.MEDIA_ACCESS_DENIED)
    }

    private fun cleanup() {
        runCatching { webRTC.cleanup() }
        runCatching { signaling.disconnect() }
        messages.clear()
        sessionId = null
        // Reset meeting state
        roomParticipants.clear()
        transcriptions.clear()
        waitingRoom.clear()
        bookmarks.clear()
        summaries.clear()
        currentMinutes = null
        isTranscriptionEnabled = false
        isAskAiActive = false
        isRoomLocked = false
        isWaitingRoomEnabled = false
        isInWaitingRoom = false
        askAiTextResponse = ""
        isAskAiTextProcessing = false
        scope.coroutineContext.cancelChildren()
    }
}
