package com.rocs.sdk.mediasoup

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*

/**
 * SendTransport — creates producers to send media to the server.
 * Port of iOS mediasoup-client-swift Transport.swift.
 */
class SendTransport internal constructor(
    override val id: String,
    private val handler: Handler,
    private val extendedRtpCapabilities: ExtendedRtpCapabilities,
    override val appData: String,
) : Transport {

    companion object {
        private const val TAG = "Mediasoup.SendTransport"
    }

    override var closed: Boolean = false; private set
    override var connectionState: TransportConnectionState = TransportConnectionState.NEW; private set

    var listener: SendTransportListener? = null

    private val producers = mutableMapOf<String, Producer>()
    private var nextProducerId = 0

    init {
        handler.onConnect = { dtlsParameters ->
            listener?.onConnect(this, dtlsParameters)
        }
        handler.onConnectionStateChange = { state ->
            connectionState = state
            listener?.onConnectionStateChange(this, state)
        }
    }

    /**
     * Create a Producer for the given track.
     */
    suspend fun produce(
        track: MediaStreamTrack,
        encodings: List<RtpParameters.Encoding>? = null,
        appData: String = "",
    ): Producer {
        if (closed) throw MediasoupError.InvalidState("Transport closed")

        val sendResult = handler.send(track = track, encodings = encodings)

        val kind = if (track.kind() == "audio") MediaKind.AUDIO else MediaKind.VIDEO
        val rtpParamsJson = JsonHelpers.stringifyMsRtpParameters(sendResult.rtpParameters)

        Log.d(TAG, "Calling onProduce delegate for $kind track...")

        val producerId = listener?.onProduce(
            transport = this,
            kind = kind,
            rtpParameters = rtpParamsJson,
            appData = appData,
        )

        val producer = Producer(
            id = producerId ?: "producer-${nextProducerId}",
            localId = sendResult.localId,
            track = track,
            kind = kind,
            rtpParameters = rtpParamsJson,
            appData = appData,
        )
        nextProducerId++

        producer.onClose = { localId ->
            CoroutineScope(Dispatchers.IO).launch {
                try { handler.stopSending(localId) } catch (_: Exception) {}
            }
        }

        producers[producer.id] = producer
        Log.d(TAG, "Producer created - id: ${producer.id}")
        return producer
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.close()
        for (p in producers.values) p.transportClosed()
        producers.clear()
    }
}

/**
 * ReceiveTransport — creates consumers to receive media from the server.
 */
class ReceiveTransport internal constructor(
    override val id: String,
    private val handler: Handler,
    private val extendedRtpCapabilities: ExtendedRtpCapabilities,
    override val appData: String,
) : Transport {

    companion object {
        private const val TAG = "Mediasoup.RecvTransport"
    }

    override var closed: Boolean = false; private set
    override var connectionState: TransportConnectionState = TransportConnectionState.NEW; private set

    var listener: ReceiveTransportListener? = null

    private val consumers = mutableMapOf<String, Consumer>()

    init {
        handler.onConnect = { dtlsParameters ->
            listener?.onConnect(this, dtlsParameters)
        }
        handler.onConnectionStateChange = { state ->
            connectionState = state
            listener?.onConnectionStateChange(this, state)
        }
    }

    /**
     * Consume a remote producer.
     */
    suspend fun consume(
        consumerId: String,
        producerId: String,
        kind: MediaKind,
        rtpParameters: String,
        appData: String = "",
    ): Consumer {
        if (closed) throw MediasoupError.InvalidState("Transport closed")

        val rtpParams = JsonHelpers.parseMsRtpParameters(rtpParameters)

        val recvResult = handler.receive(
            trackId = consumerId,
            kind = kind.value,
            rtpParameters = rtpParams,
        )

        val consumer = Consumer(
            id = consumerId,
            localId = recvResult.localId,
            producerId = producerId,
            track = recvResult.track,
            kind = kind,
            rtpParameters = rtpParameters,
            appData = appData,
        )

        consumer.onClose = { localId ->
            CoroutineScope(Dispatchers.IO).launch {
                try { handler.stopReceiving(localId) } catch (_: Exception) {}
            }
        }

        consumers[consumer.id] = consumer
        return consumer
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.close()
        for (c in consumers.values) c.transportClosed()
        consumers.clear()
    }
}
