package com.rocs.sdk.mediasoup

import org.webrtc.MediaStreamTrack

/**
 * Wraps an RTCMediaStreamTrack for receiving.
 * Port of iOS mediasoup-client-swift Consumer.swift.
 */
class Consumer internal constructor(
    val id: String,
    val localId: String,
    val producerId: String,
    val track: MediaStreamTrack,
    val kind: MediaKind,
    val rtpParameters: String,
    val appData: String,
) {
    var listener: ConsumerListener? = null
    var closed: Boolean = false; private set
    var paused: Boolean = false; private set

    internal var onClose: ((String) -> Unit)? = null

    fun pause() {
        if (closed) return
        paused = true
        track.setEnabled(false)
    }

    fun resume() {
        if (closed) return
        paused = false
        track.setEnabled(true)
    }

    fun close() {
        if (closed) return
        closed = true
        track.setEnabled(false)
        onClose?.invoke(localId)
    }

    internal fun transportClosed() {
        if (closed) return
        closed = true
        track.setEnabled(false)
        listener?.onTransportClose(this)
    }
}
