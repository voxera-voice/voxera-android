package com.rocs.sdk

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handles all Socket.IO signaling with the Rocs media server.
 * Implements the same JSON protocol as the web SDK.
 */
class SocketSignaling(private val serverUrl: String) {

    // -----------------------------------------------------------------------
    // Callbacks
    // -----------------------------------------------------------------------

    var onNewProducer: ((producerId: String, source: String?) -> Unit)? = null
    var onConversationMessage: ((sessionId: String, role: String, content: String, timestamp: Long) -> Unit)? = null
    var onMessage: ((JSONObject) -> Unit)? = null
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
    /** The assistant called a tool. Named `onJustinAction` until the wire
     *  event `justin_action` gained its canonical name `tool-triggered`. */
    var onToolTriggered:        ((JSONObject) -> Unit)? = null

    /**
     * Action ids already delivered, so the canonical tool event and its legacy
     * alias do not both reach the client for one call.
     *
     * Synchronised because Socket.IO dispatches on its own IO thread and the
     * two copies of a call arrive back to back — the exact case an unguarded
     * check would let through.
     */
    private val seenToolActionIds = LinkedHashSet<String>()
    private val seenToolActionLimit = 256

    /** True the first time an id is seen. An empty id is always allowed: there
     *  is nothing to match on, and dropping it would lose a real tool call. */
    @Synchronized
    private fun isNewToolAction(id: String): Boolean {
        if (id.isEmpty()) return true
        if (!seenToolActionIds.add(id)) return false
        while (seenToolActionIds.size > seenToolActionLimit) {
            val oldest = seenToolActionIds.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }
    var onVolumeChanged:          ((volume: Double, isAI: Boolean) -> Unit)? = null
    var onSearching:             ((JSONObject) -> Unit)? = null
    var onClearAction:           ((List<String>, String) -> Unit)? = null

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
            .setReconnection(true)
            .setReconnectionAttempts(3)
            .setReconnectionDelay(1000)
            .setReconnectionDelayMax(20000)
            .build()

        socket = IO.socket(serverUrl, opts)

        socket.once(Socket.EVENT_CONNECT) {
            if (cont.isActive) cont.resume(Unit)
        }

        socket.once(Socket.EVENT_CONNECT_ERROR) { args ->
            val msg = args.firstOrNull()?.toString() ?: "Socket connection error"
            if (cont.isActive) cont.resumeWithException(
                RocsException(msg, RocsErrorCode.CONNECTION_FAILED)
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
                    is org.json.JSONArray -> if (cont.isActive) cont.resume(JSONObject().put("_array", response))
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
            // Current servers send "id"; older servers send "producerId".
            val producerId = d.optString("id").ifEmpty { d.optString("producerId") }
            val source = if (d.has("source")) d.optString("source") else null
            onNewProducer?.invoke(producerId, source)
        }

        // Streaming and final conversation messages share the 'message' event.
        socket.on("message") { args ->
            val d = args.firstOrNull() as? JSONObject ?: return@on
            onMessage?.invoke(d)
        }

        // producer-closed event
        socket.on("producer-closed") { _ -> /* handled internally */ }

        socket.on("server-error") { args ->
            val msg = (args.firstOrNull() as? JSONObject)?.optString("message") ?: "Unknown error"
            onServerError?.invoke(msg)
        }

        socket.on("volume-changed") { args ->
            val d = args.firstOrNull() as? JSONObject ?: return@on
            val volume = d.optDouble("volume", -100.0)
            val isAI   = d.optBoolean("isAi", false)
            onVolumeChanged?.invoke(volume, isAI)
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
            "searching"             to { d -> onSearching?.invoke(d) },
        )
        for ((event, handler) in events) {
            socket.on(event) { args ->
                val d = args.firstOrNull() as? JSONObject ?: JSONObject()
                handler?.invoke(d)
            }
        }

        // Tool calls arrive under the canonical name and the legacy alias, so
        // that clients built before the rename keep working. This SDK sees
        // every call twice and drops the second copy: a duplicate would be
        // answered with a second tool output for an action the server has
        // already had an answer for.
        for (toolEvent in listOf("tool-triggered", "justin_action")) {
            socket.on(toolEvent) { args ->
                val d = args.firstOrNull() as? JSONObject ?: JSONObject()
                val actionId = d.optJSONObject("content")?.optString("action_id").orEmpty()
                if (isNewToolAction(actionId)) onToolTriggered?.invoke(d)
            }
        }

        // clear_action has a different signature — handled separately
        socket.on("clear_action") { args ->
            val d = args.firstOrNull() as? JSONObject ?: return@on
            val jsonArray = d.optJSONArray("action_ids")
            val actionIds = (0 until (jsonArray?.length() ?: 0)).map { jsonArray!!.optString(it) }
            val sessionId = d.optString("sessionId", "")
            android.util.Log.d("[Signaling]", "🔵 clear_action received – ids: $actionIds")
            onClearAction?.invoke(actionIds, sessionId)
        }
    }
}
