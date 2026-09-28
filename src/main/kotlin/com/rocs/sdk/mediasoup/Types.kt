package com.rocs.sdk.mediasoup

import org.webrtc.MediaStreamTrack

// ---------------------------------------------------------------------------
// Media Kind
// ---------------------------------------------------------------------------

enum class MediaKind(val value: String) {
    AUDIO("audio"),
    VIDEO("video");

    companion object {
        fun from(value: String): MediaKind = when (value) {
            "audio" -> AUDIO
            "video" -> VIDEO
            else -> throw MediasoupError.InvalidParameters("Unknown media kind: $value")
        }
    }
}

// ---------------------------------------------------------------------------
// Transport Connection State
// ---------------------------------------------------------------------------

enum class TransportConnectionState(val value: String) {
    NEW("new"),
    CHECKING("checking"),
    CONNECTED("connected"),
    COMPLETED("completed"),
    FAILED("failed"),
    DISCONNECTED("disconnected"),
    CLOSED("closed");
}

// ---------------------------------------------------------------------------
// ICE Transport Policy
// ---------------------------------------------------------------------------

enum class IceTransportPolicy {
    NONE, RELAY, NO_HOST, ALL
}

// ---------------------------------------------------------------------------
// Mediasoup Error
// ---------------------------------------------------------------------------

sealed class MediasoupError(override val message: String) : Exception(message) {
    class Unsupported(msg: String) : MediasoupError("MediasoupError.unsupported: $msg")
    class InvalidState(msg: String) : MediasoupError("MediasoupError.invalidState: $msg")
    class InvalidParameters(msg: String) : MediasoupError("MediasoupError.invalidParameters: $msg")
    class Unknown(val cause2: Throwable) : MediasoupError("MediasoupError.unknown: ${cause2.message}")
}

// ---------------------------------------------------------------------------
// Transport interface (shared by Send / Recv)
// ---------------------------------------------------------------------------

interface Transport {
    val id: String
    val closed: Boolean
    val connectionState: TransportConnectionState
    val appData: String
    fun close()
}

// ---------------------------------------------------------------------------
// Delegates / Listeners
// ---------------------------------------------------------------------------

interface TransportConnectListener {
    suspend fun onConnect(transport: Transport, dtlsParameters: String)
    fun onConnectionStateChange(transport: Transport, connectionState: TransportConnectionState)
}

interface SendTransportListener : TransportConnectListener {
    /**
     * Called when a producer needs to inform the server.
     * The implementation must signal the server and return the server-assigned producer ID.
     */
    suspend fun onProduce(
        transport: Transport,
        kind: MediaKind,
        rtpParameters: String,
        appData: String,
    ): String?
}

interface ReceiveTransportListener : TransportConnectListener

interface ProducerListener {
    fun onTransportClose(producer: Producer)
}

interface ConsumerListener {
    fun onTransportClose(consumer: Consumer)
}
