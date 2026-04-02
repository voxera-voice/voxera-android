package com.mayavoice.sdk

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handles all Socket.IO signaling with the Maya Voice media server.
 * Implements the same JSON protocol as the web SDK.
 */
class SocketSignaling(private val serverUrl: String) {

    // -----------------------------------------------------------------------
    // Callbacks
    // -----------------------------------------------------------------------

    var onNewProducer: ((producerId: String, source: String?) -> Unit)? = null
    var onConversationMessage: ((sessionId: String, role: String, content: String, timestamp: Long) -> Unit)? = null
    var onSpeakingStatusChanged: ((status: String) -> Unit)? = null
    var onServerError: ((message: String) -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null

    // Meeting callbacks
    var onParticipantJoined:    ((JSONObject) -> Unit)? = null
    var onParticipantLeft:      ((JSONObject) -> Unit)? = null
    var onParticipantRemoved:   ((JSONObject) -> Unit)? = null
    var onParticipantsUpdated:  ((JSONObject) -> Unit)? = null
    var onYouWereMuted:         ((JSONObject) -> Unit)? = null
    var onYouWereRemoved:       ((JSONObject) -> Unit)? = null
    var onAllMuted:             ((JSONObject) -> Unit)? = null
    var onAllUnmuted:           ((JSONObject) -> Unit)? = null
    var onHostChanged:          ((JSONObject) -> Unit)? = null
    var onMeetingEnded:         ((JSONObject) -> Unit)? = null
    var onRoomLockedChanged:    ((JSONObject) -> Unit)? = null
    var onTranscriptionToggled: ((JSONObject) -> Unit)? = null
    var onLiveTranscription:    ((JSONObject) -> Unit)? = null
    var onAskAiStarted:         ((JSONObject) -> Unit)? = null
    var onAskAiProcessing:      ((JSONObject) -> Unit)? = null
    var onAskAiCancelled:       ((JSONObject) -> Unit)? = null
    var onAskAiTextStarted:     ((JSONObject) -> Unit)? = null
    var onAskAiTextChunk:       ((JSONObject) -> Unit)? = null
    var onAskAiTextResponse:    ((JSONObject) -> Unit)? = null
    var onAskAiTextError:       ((JSONObject) -> Unit)? = null
    var onWaitingRoom:          ((JSONObject) -> Unit)? = null
    var onAdmitted:             ((JSONObject) -> Unit)? = null
    var onDenied:               ((JSONObject) -> Unit)? = null
    var onWaitingRoomUpdated:   ((JSONObject) -> Unit)? = null
    var onWaitingRoomToggled:   ((JSONObject) -> Unit)? = null
    var onSummaryGenerating:    ((JSONObject) -> Unit)? = null
    var onSummaryGenerated:     ((JSONObject) -> Unit)? = null
    var onMinutesGenerating:    ((JSONObject) -> Unit)? = null
    var onMinutesGenerated:     ((JSONObject) -> Unit)? = null
    var onBookmarkAdded:        ((JSONObject) -> Unit)? = null
    var onBookmarkRemoved:      ((JSONObject) -> Unit)? = null

    // -----------------------------------------------------------------------
    // Internal state
    // -----------------------------------------------------------------------

    private lateinit var socket: Socket

    // -----------------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------------

    suspend fun connect() = suspendCancellableCoroutine<Unit> { cont ->
        val opts = IO.Options.builder()
            .setTransports(arrayOf("websocket"))
            .setReconnection(false)
            .build()

        socket = IO.socket(serverUrl, opts)

        socket.once(Socket.EVENT_CONNECT) {
            if (cont.isActive) cont.resume(Unit)
        }

        socket.once(Socket.EVENT_CONNECT_ERROR) { args ->
            val msg = args.firstOrNull()?.toString() ?: "Socket connection error"
            if (cont.isActive) cont.resumeWithException(
                MayaVoiceException(msg, MayaVoiceErrorCode.CONNECTION_FAILED)
            )
        }

        socket.on(Socket.EVENT_DISCONNECT) { onDisconnect?.invoke() }

        registerListeners()
        socket.connect()

        cont.invokeOnCancellation { socket.disconnect() }
    }

    fun disconnect() {
        if (::socket.isInitialized) socket.disconnect()
    }

    // -----------------------------------------------------------------------
    // Emit helpers
    // -----------------------------------------------------------------------

    fun emit(event: String, data: JSONObject = JSONObject()) {
        socket.emit(event, data)
    }

    suspend fun emitWithAck(event: String, data: JSONObject = JSONObject()): JSONObject =
        suspendCancellableCoroutine { cont ->
            socket.emit(event, arrayOf(data)) { args ->
                val response = args.firstOrNull()
                when (response) {
                    is JSONObject -> if (cont.isActive) cont.resume(response)
                    null          -> if (cont.isActive) cont.resume(JSONObject())
                    else          -> if (cont.isActive) cont.resume(JSONObject().put("raw", response.toString()))
                }
            }
        }

    // -----------------------------------------------------------------------
    // Listener registration
    // -----------------------------------------------------------------------

    private fun registerListeners() {
        socket.on("speaking-status-changed") { args ->
            val status = (args.firstOrNull() as? JSONObject)?.optString("status") ?: return@on
            onSpeakingStatusChanged?.invoke(status)
        }

        socket.on("conversation-message") { args ->
            val d = args.firstOrNull() as? JSONObject ?: return@on
            onConversationMessage?.invoke(
                d.optString("sessionId"),
                d.optString("role"),
                d.optString("content"),
                d.optLong("timestamp")
            )
        }

        socket.on("new-producer") { args ->
            val d = args.firstOrNull() as? JSONObject ?: return@on
            val producerId = d.optString("producerId")
            val source = if (d.has("source")) d.optString("source") else null
            onNewProducer?.invoke(producerId, source)
        }

        socket.on("server-error") { args ->
            val msg = (args.firstOrNull() as? JSONObject)?.optString("message") ?: "Unknown error"
            onServerError?.invoke(msg)
        }

        registerMeetingListeners()
    }

    private fun registerMeetingListeners() {
        val events = mapOf<String, ((JSONObject) -> Unit)?>(
            "participant-joined"    to { d -> onParticipantJoined?.invoke(d) },
            "participant-left"      to { d -> onParticipantLeft?.invoke(d) },
            "participant-removed"   to { d -> onParticipantRemoved?.invoke(d) },
            "participants-updated"  to { d -> onParticipantsUpdated?.invoke(d) },
            "you-were-muted"        to { d -> onYouWereMuted?.invoke(d) },
            "you-were-removed"      to { d -> onYouWereRemoved?.invoke(d) },
            "all-muted"             to { d -> onAllMuted?.invoke(d) },
            "all-unmuted"           to { d -> onAllUnmuted?.invoke(d) },
            "host-changed"          to { d -> onHostChanged?.invoke(d) },
            "meeting-ended"         to { d -> onMeetingEnded?.invoke(d) },
            "room-locked-changed"   to { d -> onRoomLockedChanged?.invoke(d) },
            "transcription-toggled" to { d -> onTranscriptionToggled?.invoke(d) },
            "live-transcription"    to { d -> onLiveTranscription?.invoke(d) },
            "ask-ai-started"        to { d -> onAskAiStarted?.invoke(d) },
            "ask-ai-processing"     to { d -> onAskAiProcessing?.invoke(d) },
            "ask-ai-cancelled"      to { d -> onAskAiCancelled?.invoke(d) },
            "ask-ai-text-started"   to { d -> onAskAiTextStarted?.invoke(d) },
            "ask-ai-text-chunk"     to { d -> onAskAiTextChunk?.invoke(d) },
            "ask-ai-text-response"  to { d -> onAskAiTextResponse?.invoke(d) },
            "ask-ai-text-error"     to { d -> onAskAiTextError?.invoke(d) },
            "waiting-room"          to { d -> onWaitingRoom?.invoke(d) },
            "admitted"              to { d -> onAdmitted?.invoke(d) },
            "denied"                to { d -> onDenied?.invoke(d) },
            "waiting-room-updated"  to { d -> onWaitingRoomUpdated?.invoke(d) },
            "waiting-room-toggled"  to { d -> onWaitingRoomToggled?.invoke(d) },
            "summary-generating"    to { d -> onSummaryGenerating?.invoke(d) },
            "summary-generated"     to { d -> onSummaryGenerated?.invoke(d) },
            "minutes-generating"    to { d -> onMinutesGenerating?.invoke(d) },
            "minutes-generated"     to { d -> onMinutesGenerated?.invoke(d) },
            "bookmark-added"        to { d -> onBookmarkAdded?.invoke(d) },
            "bookmark-removed"      to { d -> onBookmarkRemoved?.invoke(d) },
        )
        for ((event, handler) in events) {
            socket.on(event) { args ->
                val d = args.firstOrNull() as? JSONObject ?: JSONObject()
                handler?.invoke(d)
            }
        }
    }
}
