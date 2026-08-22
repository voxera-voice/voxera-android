package com.rocs.sdk

import android.media.AudioManager
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
 * val config = RocsConfig(
 *     appKey = "YOUR_APP_KEY",
 *     serverUrl = "wss://media.example.com",
 *     chatConfig = ChatConfig(systemPrompt = "You are a helpful assistant.")
 * )
 * val client = RocsClient(context, config)
 * client.listener = object : RocsListener {
 *     override fun onConnectionStatusChanged(status: ConnectionStatus) { /* ... */ }
 *     override fun onMessage(message: ConversationMessage) { /* ... */ }
 * }
 * client.connect()
 * ```
 */
class RocsClient(
    private val context: Context,
    private val config: RocsConfig,
) {
    // -----------------------------------------------------------------------
    // Public state
    // -----------------------------------------------------------------------

    var listener: RocsListener? = null

    var connectionStatus:   ConnectionStatus   = ConnectionStatus.IDLE;   private set
    var conversationStatus: ConversationStatus = ConversationStatus.IDLE; private set
    var speakingStatus:     SpeakingStatus     = SpeakingStatus.NONE;     private set
    val messages:           MutableList<ConversationMessage> = mutableListOf()
    var sessionId:          String? = null; private set

    var audioLevel:   Float = 0f; private set
    var aiAudioLevel: Float = 0f; private set

    val isConnected          get() = connectionStatus == ConnectionStatus.CONNECTED
    val isConversationActive get() = conversationStatus == ConversationStatus.ACTIVE
    var isMuted: Boolean = false; private set

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
    private var routerRtpCapabilities: JSONObject? = null
    private var manualDisconnect = false
    private var previousAudioMode: Int? = null

    // -----------------------------------------------------------------------
    // Connect
    // -----------------------------------------------------------------------

    fun connect() {
        if (connectionStatus == ConnectionStatus.CONNECTED ||
            connectionStatus == ConnectionStatus.CONNECTING) return

        manualDisconnect = false
        setConnectionStatus(ConnectionStatus.CONNECTING)
        scope.launch {
            try {
                ensureCommunicationAudioMode()

                // Generate the session ID client-side; no REST bootstrap is required.
                sessionId = config.sessionId ?: java.util.UUID.randomUUID().toString()

                signaling = SocketSignaling(config.serverUrl)
                setupSignalingCallbacks()
                setupMeetingSignalingCallbacks()
                signaling.connect()

                // Initialize the managed voice-session connection.
                signaling.emitWithAck("init-session-connection", buildSessionPayload())

                // RTP capabilities
                val rtpCaps = signaling.emitWithAck("getRtpCapabilities")
                // Patch: add opus PT-101 for AI audio compatibility (matches web/iOS clients)
                routerRtpCapabilities = patchRtpCapabilities(rtpCaps)

                // ICE servers — server returns a JSONArray directly
                val iceResult = runCatching { signaling.emitWithAck("getIceServers") }.getOrNull()
                val iceServers = iceResult?.optJSONArray("_array")
                    ?: iceResult?.optJSONArray("servers")
                android.util.Log.d("RocsClient", "ICE servers: $iceServers")

                // WebRTC (mediasoup-client)
                webRTC = WebRTCManager(context, signaling)
                webRTC.init()
                webRTC.onRemoteAudioTrack = { track -> handleRemoteAudioTrack(track) }
                webRTC.onRemoteVideoTrack = { track -> handleRemoteVideoTrack(track) }
                webRTC.onError = { err ->
                    handleError(RocsException(err.message ?: "WebRTC error", RocsErrorCode.WEBRTC_ERROR))
                }

                // Set ICE servers + load mediasoup Device
                webRTC.setIceServers(iceServers)
                webRTC.loadDevice(routerRtpCapabilities!!.toString())

                // Send transport
                val sendParams = signaling.emitWithAck("createTransport")
                webRTC.createSendTransport(sendParams)

                // Produce audio
                webRTC.produceAudio()

                // Recv transport
                val recvParams = signaling.emitWithAck("createTransport")
                webRTC.createRecvTransport(recvParams)

                // new-producer events are handled via setupSignalingCallbacks

                setConnectionStatus(ConnectionStatus.CONNECTED)
            } catch (e: Exception) {
                handleError(e as? RocsException ?: RocsException(e.message ?: "Unknown error", RocsErrorCode.UNKNOWN_ERROR))
                setConnectionStatus(ConnectionStatus.ERROR)
            }
        }
    }

    fun disconnect() {
        if (connectionStatus == ConnectionStatus.IDLE ||
            connectionStatus == ConnectionStatus.DISCONNECTED) return
        manualDisconnect = true
        if (conversationStatus == ConversationStatus.ACTIVE) endConversation()
        setMuted(true)
        setSpeakingStatus(SpeakingStatus.NONE)
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
        setMuted(true)
        setSpeakingStatus(SpeakingStatus.NONE)
        signaling.emit("conversation:end")
        setConversationStatus(ConversationStatus.IDLE)
    }

    fun sendMessage(content: String) {
        if (!isConnected) throw RocsException("Not connected", RocsErrorCode.CONNECTION_FAILED)
        val message = ConversationMessage(
            id        = "msg_${System.currentTimeMillis()}_${(Math.random() * 9999).toInt()}",
            role      = ConversationMessage.MessageRole.USER,
            content   = content,
            timestamp = System.currentTimeMillis(),
            isFinal   = true,
        )
        messages += message
        listener?.onMessage(message)
        config.listener?.onMessage(message)
        signaling.emit("send-message", JSONObject().put("message", content))
    }

    fun selectAction(message: String, actionId: String) {
        if (!isConnected) throw RocsException("Not connected", RocsErrorCode.CONNECTION_FAILED)
        signaling.emit("select-actions", JSONObject().apply {
            put("message", message)
            put("event", "justin_action_output")
            put("action_id", actionId)
        })
    }

    // -----------------------------------------------------------------------
    // Mute
    // -----------------------------------------------------------------------

    fun setMuted(muted: Boolean) {
        isMuted = muted
        runCatching { webRTC }.getOrNull()?.setAudioEnabled(!muted)
        listener?.onMuteChanged(muted)
        config.listener?.onMuteChanged(muted)
    }

    fun toggleMute() = setMuted(!isMuted)

    // -----------------------------------------------------------------------
    // Video
    // -----------------------------------------------------------------------

    /** Whether the local camera is currently active. */
    val isVideoEnabled: Boolean get() = runCatching { webRTC.localVideoTrack != null }.getOrDefault(false)

    /** The local camera VideoTrack (null when camera is off). */
    val localVideoTrack: VideoTrack? get() = runCatching { webRTC.localVideoTrack }.getOrNull()

    /** Whether the front camera is currently selected. */
    val isFrontCamera: Boolean get() = runCatching { webRTC.isFrontCamera }.getOrDefault(true)

    /** Shared EglBase for video rendering — pass to SurfaceViewRenderer.init(). */
    val eglBase: org.webrtc.EglBase? get() = runCatching { webRTC.eglBase }.getOrNull()

    fun startCamera(front: Boolean = true) {
        scope.launch {
            try {
                webRTC.startCamera(front)
                scope.launch(Dispatchers.Main) {
                    listener?.onLocalVideoTrack(webRTC.localVideoTrack)
                    config.listener?.onLocalVideoTrack(webRTC.localVideoTrack)
                }
            } catch (e: Exception) {
                handleError(RocsException("Video failed: ${e.message}", RocsErrorCode.MEDIA_ACCESS_DENIED))
            }
        }
    }

    fun stopCamera() {
        webRTC.stopCamera()
        scope.launch(Dispatchers.Main) {
            listener?.onLocalVideoTrack(null)
            config.listener?.onLocalVideoTrack(null)
        }
    }

    fun switchCamera() {
        runCatching { webRTC.switchCamera() }
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
                    put("agentId", config.agentId ?: config.configurationId ?: "")
                    put("roomMode", this@RocsClient.roomMode?.value ?: "ai-meeting")
                    put("isHost", this@RocsClient.isHost)
                })

                setConnectionStatus(ConnectionStatus.CONNECTED)
            } catch (e: Exception) {
                handleError(e as? RocsException ?: RocsException(e.message ?: "Unknown error", RocsErrorCode.UNKNOWN_ERROR))
                setConnectionStatus(ConnectionStatus.ERROR)
            }
        }
    }

    fun setupRoomWebRTC() {
        if (!::signaling.isInitialized) throw RocsException("Socket not connected", RocsErrorCode.CONNECTION_FAILED)
        scope.launch {
            try {
                ensureCommunicationAudioMode()

                val rtpCaps = signaling.emitWithAck("getRtpCapabilities")
                routerRtpCapabilities = patchRtpCapabilities(rtpCaps)

                webRTC = WebRTCManager(context, signaling)
                webRTC.init()
                webRTC.onRemoteAudioTrack = { track -> handleRemoteAudioTrack(track) }
                webRTC.onRemoteVideoTrack = { track -> handleRemoteVideoTrack(track) }
                webRTC.onError = { err ->
                    handleError(RocsException(err.message ?: "WebRTC error", RocsErrorCode.WEBRTC_ERROR))
                }

                val iceResult = runCatching { signaling.emitWithAck("getIceServers") }.getOrNull()
                val iceServers = iceResult?.optJSONArray("_array")
                    ?: iceResult?.optJSONArray("servers")

                webRTC.setIceServers(iceServers)
                webRTC.loadDevice(routerRtpCapabilities!!.toString())

                val sendParams = signaling.emitWithAck("createTransport")
                webRTC.createSendTransport(sendParams)

                val recvParams = signaling.emitWithAck("createTransport")
                webRTC.createRecvTransport(recvParams)

                webRTC.produceAudio()
            } catch (e: Exception) {
                handleError(RocsException("WebRTC setup failed: ${e.message}", RocsErrorCode.WEBRTC_ERROR))
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
                "searching"     -> SpeakingStatus.SEARCHING
                else            -> SpeakingStatus.NONE
            }
            setSpeakingStatus(mapped)
        }

        signaling.onConversationMessage = { sessionId, role, content, timestamp ->
            val roleEnum = ConversationMessage.MessageRole.values().firstOrNull {
                it.name.equals(role, ignoreCase = true)
            } ?: ConversationMessage.MessageRole.ASSISTANT
            val msg = ConversationMessage(
                id        = "${role}_$timestamp",
                messageId = "${role}_$timestamp",
                role      = roleEnum,
                content   = content,
                timestamp = timestamp,
                isFinal   = true,
            )
            messages += msg
            if (speakingStatus == SpeakingStatus.SEARCHING) setSpeakingStatus(SpeakingStatus.NONE)
            scope.launch(Dispatchers.Main) {
                listener?.onMessage(msg)
                config.listener?.onMessage(msg)
            }
        }

        signaling.onNewProducer = { producerId, source ->
            scope.launch {
                try {
                    val consumeParams = signaling.emitWithAck("consume", JSONObject().apply {
                        put("producerId", producerId)
                        put("transportId", webRTC.getRecvTransportId() ?: "")
                        put("rtpCapabilities", routerRtpCapabilities ?: JSONObject())
                    })
                    webRTC.consumeAI(consumeParams)
                } catch (e: Exception) {
                    handleError(RocsException("Consume failed: ${e.message}", RocsErrorCode.WEBRTC_ERROR))
                }
            }
        }

        // Streaming and final conversation messages share the 'message' event.
        signaling.onMessage = { d ->
            val roleStr = d.optString("role", "assistant")
            val role = ConversationMessage.MessageRole.values().firstOrNull {
                it.name.equals(roleStr, ignoreCase = true)
            } ?: ConversationMessage.MessageRole.ASSISTANT
            val content = if (d.has("content")) d.optString("content") else d.optString("text", "")
            val isFinal = d.optBoolean("isFinal", false) || d.has("output")
            val fullText = if (d.has("fullText")) d.optString("fullText") else null
            val messageId = d.optString("messageId", "msg_${System.currentTimeMillis()}")
            val msg = ConversationMessage(
                id        = d.optString("id", "msg_${System.currentTimeMillis()}"),
                messageId = messageId,
                role      = role,
                content   = content,
                timestamp = System.currentTimeMillis(),
                fullText  = fullText,
                isFinal   = isFinal,
            )
            messages += msg
            if (speakingStatus == SpeakingStatus.SEARCHING) setSpeakingStatus(SpeakingStatus.NONE)
            scope.launch(Dispatchers.Main) {
                listener?.onMessage(msg)
                config.listener?.onMessage(msg)
            }
        }

        signaling.onVolumeChanged = { volume, isAI ->
            // Server sends dBFS (range ~ -100…0). Normalize to 0…1 for UI.
            val clamped = maxOf(minOf(volume, 0.0), -100.0).toFloat()
            val level = (clamped + 100f) / 100f
            if (isAI) {
                aiAudioLevel = level
                scope.launch(Dispatchers.Main) {
                    listener?.onAIAudioLevel(level)
                    config.listener?.onAIAudioLevel(level)
                }
            } else {
                audioLevel = level
                scope.launch(Dispatchers.Main) {
                    listener?.onAudioLevel(level)
                    config.listener?.onAudioLevel(level)
                }
            }
        }

        signaling.onServerError = { message ->
            handleError(RocsException(message, RocsErrorCode.UNKNOWN_ERROR))
        }

        signaling.onDisconnect = {
            val opts = config.connectionOptions
            if (!manualDisconnect && opts.autoReconnect && reconnectAttempts < opts.reconnectAttempts) {
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
        signaling.onJustinAction = { d ->
            val content = d.optJSONObject("content")
            if (content != null) {
                val actionId = content.optString("action_id", "")
                val name = content.optString("name", "")
                val args = content.optJSONObject("arguments") ?: JSONObject()
                val tool = ToolCall(
                    id = actionId,
                    type = "function",
                    function = ToolCallFunction(name = name, arguments = args)
                )
                scope.launch(Dispatchers.Main) {
                    listener?.onToolCalls(listOf(tool), "")
                    config.listener?.onToolCalls(listOf(tool), "")
                }
            }
        }
        signaling.onClearAction = { actionIds, sessionId ->
            android.util.Log.d("RocsClient", "🔵 clear_action – ids: $actionIds, sessionId: $sessionId")
            scope.launch(Dispatchers.Main) {
                listener?.onClearAction(actionIds, sessionId)
                config.listener?.onClearAction(actionIds, sessionId)
            }
        }

        signaling.onSearching = { d ->
            val query = d.optString("query", "")
            scope.launch(Dispatchers.Main) {
                listener?.onSearching(query)
                config.listener?.onSearching(query)
            }
        }
    }

    private suspend fun initSession(): JSONObject {
        // Kept for backward compatibility; no longer called from connect().
        return JSONObject()
    }

    private fun buildSessionPayload(): JSONObject = JSONObject().apply {
        put("protocol",     "voxera")
        put("protocolVersion", "1.0")
        put("sessionId",    sessionId ?: "")
        put("threadId",     config.threadId ?: sessionId ?: "")
        put("chatProfile",  config.chatProfile ?: "")
        put("userId",       config.userId ?: "android-user")
        put("clientType",   "android")
        put("appKey",       config.appKey)
        put("agentId",      config.agentId ?: config.configurationId ?: "")
        config.user?.let          { put("user", JSONObject(it)) }
        config.ttsConfig?.let     { put("ttsConfig", JSONObject(it)) }
        config.transcriptionConfig?.let { put("transcriptionConfig", JSONObject(it)) }
        config.modelConfig?.let { put("modelConfig", JSONObject(it)) }
        config.tools?.let { put("tools", org.json.JSONArray(it.map { tool -> JSONObject(tool) })) }
        config.additionalSystemPrompts?.let { put("additionalSystemPrompts", org.json.JSONArray(it)) }
        config.initialMessages?.let { put("initialMessages", org.json.JSONArray(it.map { m -> JSONObject(m) })) }
        config.selectedModel?.let { put("selectedModel", it) }
        config.selectedVoice?.let { put("selectedVoice", it) }
        config.workspaceId?.let   { put("workspaceId", it) }
        config.username?.let      { put("username", it) }
        config.userInfo?.let      { put("userInfo", JSONObject(it)) }
        config.metadata?.takeIf { it.isNotEmpty() }?.let { put("metadata", JSONObject(it)) }
        val iso8601 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        iso8601.timeZone = java.util.TimeZone.getDefault()
        put("clientDateTime", iso8601.format(java.util.Date()))
    }

    /**
     * Patch RTP capabilities: add opus PT-101 copy for AI audio compatibility.
     * Mirrors the web and iOS patchRtpCapabilities logic.
     */
    private fun patchRtpCapabilities(rtpCaps: JSONObject): JSONObject {
        val codecs = rtpCaps.optJSONArray("codecs") ?: return rtpCaps
        // Check if PT-101 already exists
        for (i in 0 until codecs.length()) {
            if (codecs.getJSONObject(i).optInt("preferredPayloadType", -1) == 101) return rtpCaps
        }
        // Find first opus codec and add a copy with PT-101
        for (i in 0 until codecs.length()) {
            val c = codecs.getJSONObject(i)
            if (c.optString("mimeType", "").equals("audio/opus", ignoreCase = true)) {
                val copy = JSONObject(c.toString())
                copy.put("preferredPayloadType", 101)
                codecs.put(copy)
                break
            }
        }
        return rtpCaps
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

    private fun handleError(e: RocsException) {
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

    private fun cleanup() {
        runCatching { webRTC.cleanup() }
        runCatching { signaling.disconnect() }
        restorePreviousAudioMode()
        reconnectAttempts = 0
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

    private fun ensureCommunicationAudioMode() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (previousAudioMode == null) {
            previousAudioMode = audioManager.mode
        }
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        logAudioMode("ensureCommunicationAudioMode")
    }

    private fun restorePreviousAudioMode() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = previousAudioMode ?: return
        audioManager.mode = mode
        previousAudioMode = null
        logAudioMode("restorePreviousAudioMode")
    }

    private fun logAudioMode(label: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        android.util.Log.d(
            "RocsClient",
            "[$label] mode=${audioManager.mode}, speakerOn=${audioManager.isSpeakerphoneOn}, btScoOn=${audioManager.isBluetoothScoOn}"
        )
    }
}
