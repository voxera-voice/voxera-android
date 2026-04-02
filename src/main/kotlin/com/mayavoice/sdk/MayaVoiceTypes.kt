package com.mayavoice.sdk

import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

// ---------------------------------------------------------------------------
// Status enums
// ---------------------------------------------------------------------------

enum class ConnectionStatus {
    IDLE, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR
}

enum class ConversationStatus {
    IDLE, STARTING, ACTIVE, ENDING, ENDED
}

enum class SpeakingStatus {
    USER, AI, NONE
}

// ---------------------------------------------------------------------------
// Message
// ---------------------------------------------------------------------------

data class ConversationMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,  // epoch milliseconds
    val metadata: Map<String, Any>? = null
) {
    enum class MessageRole { USER, ASSISTANT, SYSTEM }
}

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

data class WebRTCStats(
    val bytesReceived: Long = 0,
    val bytesSent: Long = 0,
    val packetsReceived: Long = 0,
    val packetsSent: Long = 0,
    val roundTripTime: Double? = null,
    val jitter: Double? = null,
    val audioLevel: Double? = null
)

// ---------------------------------------------------------------------------
// Error
// ---------------------------------------------------------------------------

enum class MayaVoiceErrorCode(val code: String) {
    CONNECTION_FAILED("CONNECTION_FAILED"),
    AUTHENTICATION_FAILED("AUTHENTICATION_FAILED"),
    WEBRTC_ERROR("WEBRTC_ERROR"),
    MEDIA_ACCESS_DENIED("MEDIA_ACCESS_DENIED"),
    TIMEOUT("TIMEOUT"),
    SERVER_ERROR("SERVER_ERROR"),
    INVALID_CONFIG("INVALID_CONFIG"),
    NETWORK_ERROR("NETWORK_ERROR"),
    UNKNOWN_ERROR("UNKNOWN_ERROR")
}

class MayaVoiceException(
    message: String,
    val code: MayaVoiceErrorCode,
    val details: Map<String, Any>? = null
) : Exception(message)

// ---------------------------------------------------------------------------
// Meeting types
// ---------------------------------------------------------------------------

enum class RoomMode(val value: String) {
    AI_MEETING("ai-meeting"),
    NORMAL_MEETING("normal-meeting");

    companion object {
        fun fromValue(value: String): RoomMode =
            entries.firstOrNull { it.value == value } ?: AI_MEETING
    }
}

data class RoomParticipant(
    val id: String,
    val name: String,
    val userId: String? = null,
    val isMuted: Boolean = false,
    val isSpeaking: Boolean = false,
    val sessionId: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): RoomParticipant = RoomParticipant(
            id = json.optString("clientId", json.optString("id", "")),
            name = json.optString("displayName", json.optString("name", "")),
            userId = json.optString("userId", null),
            isMuted = json.optBoolean("isMuted", false),
            isSpeaking = json.optBoolean("isSpeaking", false),
            sessionId = json.optString("sessionId", null),
        )
    }
}

data class TranscriptionEntry(
    val clientId: String,
    val displayName: String,
    val text: String,
    val timestamp: Long,
) {
    companion object {
        fun fromJson(json: JSONObject): TranscriptionEntry = TranscriptionEntry(
            clientId = json.optString("clientId", ""),
            displayName = json.optString("displayName", ""),
            text = json.optString("text", ""),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
        )
    }
}

data class WaitingRoomEntry(
    val clientId: String,
    val socketId: String,
    val displayName: String,
    val joinedAt: Long,
) {
    companion object {
        fun fromJson(json: JSONObject): WaitingRoomEntry = WaitingRoomEntry(
            clientId = json.optString("clientId", ""),
            socketId = json.optString("socketId", ""),
            displayName = json.optString("displayName", ""),
            joinedAt = json.optLong("joinedAt", System.currentTimeMillis()),
        )
    }
}

data class MeetingBookmark(
    val id: String,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        fun fromJson(json: JSONObject): MeetingBookmark = MeetingBookmark(
            id = json.optString("id", json.optString("bookmarkId", "")),
            note = json.optString("note", ""),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
        )
    }
}

data class MeetingSummary(
    val id: String = "",
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        fun fromJson(json: JSONObject): MeetingSummary = MeetingSummary(
            id = json.optString("id", ""),
            content = json.optString("content", json.optString("summary", "")),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
        )
    }
}

data class MeetingMinutesActionItem(
    val task: String = "",
    val assignee: String? = null,
    val deadline: String? = null,
)

data class MeetingMinutesSection(
    val title: String = "",
    val content: String = "",
    val actionItems: List<MeetingMinutesActionItem> = emptyList(),
)

data class MeetingMinutes(
    val id: String = "",
    val sections: List<MeetingMinutesSection> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        fun fromJson(json: JSONObject): MeetingMinutes {
            val sectionsArr = json.optJSONArray("sections")
            val sections = mutableListOf<MeetingMinutesSection>()
            if (sectionsArr != null) {
                for (i in 0 until sectionsArr.length()) {
                    val s = sectionsArr.optJSONObject(i) ?: continue
                    val aiArr = s.optJSONArray("actionItems")
                    val actionItems = mutableListOf<MeetingMinutesActionItem>()
                    if (aiArr != null) {
                        for (j in 0 until aiArr.length()) {
                            val a = aiArr.optJSONObject(j) ?: continue
                            actionItems += MeetingMinutesActionItem(
                                task = a.optString("task", ""),
                                assignee = a.optString("assignee", null),
                                deadline = a.optString("deadline", null),
                            )
                        }
                    }
                    sections += MeetingMinutesSection(
                        title = s.optString("title", ""),
                        content = s.optString("content", ""),
                        actionItems = actionItems,
                    )
                }
            }
            return MeetingMinutes(
                id = json.optString("id", ""),
                sections = sections,
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Meeting callbacks
// ---------------------------------------------------------------------------

open class MeetingCallbacks {
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
}
