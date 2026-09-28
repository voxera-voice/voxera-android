package com.rocs.sdk.mediasoup

import org.webrtc.MediaStreamTrack

/**
 * Wraps an RTCMediaStreamTrack for sending.
 * Port of iOS mediasoup-client-swift Producer.swift.
 */
class Producer internal constructor(
    val id: String,
    val localId: String,
    var track: MediaStreamTrack,
    val kind: MediaKind,
    val rtpParameters: String,
    val appData: String,
) {
    var listener: ProducerListener? = null
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

    fun replaceTrack(newTrack: MediaStreamTrack) {
        if (closed) throw MediasoupError.InvalidState("Producer closed")
        track = newTrack
    }

    internal fun transportClosed() {
        if (closed) return
        closed = true
        track.setEnabled(false)
        listener?.onTransportClose(this)
    }
}
