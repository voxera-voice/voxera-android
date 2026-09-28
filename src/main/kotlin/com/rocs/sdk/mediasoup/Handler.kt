package com.rocs.sdk.mediasoup

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages a single RTCPeerConnection for either send or recv direction.
 * Port of iOS mediasoup-client-swift Handler.swift.
 */
internal class Handler(
    private val direction: Direction,
    pcFactory: PeerConnectionFactory,
    iceParameters: IceParametersJSON,
    iceCandidates: List<IceCandidateJSON>,
    dtlsParameters: DtlsParametersJSON,
    iceServers: List<PeerConnection.IceServer>,
    iceTransportPolicy: PeerConnection.IceTransportsType,
    private val extendedRtpCapabilities: ExtendedRtpCapabilities,
) {
    enum class Direction { SEND, RECV }

    data class SendResult(val localId: String, val rtpParameters: MsRtpParameters)
    data class RecvResult(val localId: String, val track: MediaStreamTrack)

    private val pc: PeerConnection
    private val remoteSdp: RemoteSdp
    private var transportReady = false
    private val mapMidTransceiver = mutableMapOf<String, RtpTransceiver>()

    var onConnect: (suspend (String) -> Unit)? = null
    var onConnectionStateChange: ((TransportConnectionState) -> Unit)? = null

    companion object {
        private const val TAG = "Mediasoup.Handler"
    }

    // Must be declared before init{} so it's initialized by the time createPeerConnection runs.
    private val pcObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Log.d(TAG, "Signaling state: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ICE connection state: $state")
            val msState = when (state) {
                PeerConnection.IceConnectionState.NEW -> TransportConnectionState.NEW
                PeerConnection.IceConnectionState.CHECKING -> TransportConnectionState.CHECKING
                PeerConnection.IceConnectionState.CONNECTED -> TransportConnectionState.CONNECTED
                PeerConnection.IceConnectionState.COMPLETED -> TransportConnectionState.COMPLETED
                PeerConnection.IceConnectionState.FAILED -> TransportConnectionState.FAILED
                PeerConnection.IceConnectionState.DISCONNECTED -> TransportConnectionState.DISCONNECTED
                PeerConnection.IceConnectionState.CLOSED -> TransportConnectionState.CLOSED
            }
            onConnectionStateChange?.invoke(msState)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.d(TAG, "ICE gathering state: $state")
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(TAG, "ICE candidate: ${candidate.sdp}")
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    init {
        val config = PeerConnection.RTCConfiguration(iceServers)
        config.iceTransportsType = iceTransportPolicy
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        pc = pcFactory.createPeerConnection(config, pcObserver)
            ?: throw MediasoupError.InvalidState("Failed to create PeerConnection")

        remoteSdp = RemoteSdp(
            iceParameters = iceParameters,
            iceCandidates = iceCandidates,
            dtlsParameters = dtlsParameters,
        )
    }

    fun close() {
        pc.close()
    }

    // -----------------------------------------------------------------------
    // Send
    // -----------------------------------------------------------------------

    suspend fun send(
        track: MediaStreamTrack,
        encodings: List<RtpParameters.Encoding>?,
    ): SendResult {
        val kind = track.kind() // "audio" or "video"
        Log.d(TAG, "send() - kind: $kind")

        val transceiverInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf("mediasoup-send"),
            encodings ?: emptyList(),
        )

        val transceiver = pc.addTransceiver(track, transceiverInit)
            ?: throw MediasoupError.InvalidState("Failed to add transceiver")

        // Create offer
        val offer = createOffer()
        val localSdpStr = offer.description

        // Build sending RTP parameters
        var sendingRtpParameters = Ortc.getSendingRtpParameters(kind, extendedRtpCapabilities)
        sendingRtpParameters = sendingRtpParameters.copy(
            codecs = Ortc.reduceCodecs(sendingRtpParameters.codecs)
        )

        // Build remote answer RTP parameters
        var sendingRemoteRtpParameters = Ortc.getSendingRemoteRtpParameters(kind, extendedRtpCapabilities)
        sendingRemoteRtpParameters = sendingRemoteRtpParameters.copy(
            codecs = Ortc.reduceCodecs(sendingRemoteRtpParameters.codecs)
        )

        // Set local description first
        setLocalDescription(offer)

        // Setup transport on first send/recv (AFTER setLocalDescription)
        if (!transportReady) {
            setupTransport(localSdpStr)
        }

        // Get the mid from the transceiver
        val mid = transceiver.mid
        if (mid.isNullOrEmpty()) {
            throw MediasoupError.InvalidState("Transceiver has no mid after setLocalDescription")
        }
        val localId = mid

        // Re-parse local description to get final SDP
        val finalLocalSdp = pc.localDescription?.description
            ?: throw MediasoupError.InvalidState("No local description after setLocalDescription")

        // Set mid
        sendingRtpParameters = sendingRtpParameters.copy(mid = localId)

        // Extract CNAME
        sendingRtpParameters = sendingRtpParameters.copy(
            rtcp = RtcpParam(
                cname = SdpExtractor.extractCname(finalLocalSdp),
                reducedSize = true,
            )
        )

        // Extract encodings (SSRCs) from the media section
        val mediaIndex = pc.transceivers.indexOfFirst { it.mid == mid }.takeIf { it >= 0 } ?: 0
        val sdpEncodings = SdpExtractor.extractEncodings(finalLocalSdp, mediaIndex)

        val rtpEncodings = sdpEncodings.map { (ssrc, rtxSsrc) ->
            var enc = RtpEncodingParam(ssrc = ssrc)
            if (rtxSsrc != null && sendingRtpParameters.codecs.size > 1) {
                enc = enc.copy(rtx = RtxSsrc(ssrc = rtxSsrc))
            }
            enc
        }
        sendingRtpParameters = sendingRtpParameters.copy(encodings = rtpEncodings)

        // Extract extmap lines from offer for matching in the answer
        val offerExtmapLines = extractExtmapLines(finalLocalSdp, mediaIndex)
        val offerPayloads = extractPayloads(finalLocalSdp, mediaIndex)

        // Build remote answer SDP
        remoteSdp.addSendMediaSection(
            mid = localId,
            kind = kind,
            offerPayloads = offerPayloads,
            answerRtpParameters = sendingRemoteRtpParameters,
            offerExtmapLines = offerExtmapLines,
        )

        val answerSdp = remoteSdp.getSdp()
        Log.d(TAG, "Answer SDP:\n$answerSdp")
        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        setRemoteDescription(answer)

        Log.d(TAG, "send() complete - mid=$localId")

        mapMidTransceiver[localId] = transceiver

        return SendResult(localId = localId, rtpParameters = sendingRtpParameters)
    }

    // -----------------------------------------------------------------------
    // Receive
    // -----------------------------------------------------------------------

    suspend fun receive(
        trackId: String,
        kind: String,
        rtpParameters: MsRtpParameters,
    ): RecvResult {
        val localId = rtpParameters.mid ?: mapMidTransceiver.size.toString()
        val streamId = rtpParameters.rtcp?.cname ?: "-"

        remoteSdp.addRecvMediaSection(
            mid = localId,
            kind = kind,
            offerRtpParameters = rtpParameters,
            streamId = streamId,
            trackId = trackId,
        )

        val offerSdp = remoteSdp.getSdp()
        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

        setRemoteDescription(offer)

        val answer = createAnswer()

        if (!transportReady) {
            setupTransport(answer.description)
        }

        setLocalDescription(answer)

        // Find the transceiver for this mid
        val transceiver = pc.transceivers.firstOrNull { it.mid == localId }
            ?: throw MediasoupError.InvalidState("Transceiver not found for mid $localId")

        mapMidTransceiver[localId] = transceiver

        val track = transceiver.receiver.track()
            ?: throw MediasoupError.InvalidState("Receiver track is null for mid $localId")

        return RecvResult(localId = localId, track = track)
    }

    // -----------------------------------------------------------------------
    // Stop sending/receiving
    // -----------------------------------------------------------------------

    suspend fun stopSending(localId: String) {
        val transceiver = mapMidTransceiver[localId] ?: return
        transceiver.sender.setTrack(null, false)
        pc.removeTrack(transceiver.sender)
        remoteSdp.closeMediaSection(localId)

        val offer = createOffer()
        setLocalDescription(offer)

        val answer = SessionDescription(SessionDescription.Type.ANSWER, remoteSdp.getSdp())
        setRemoteDescription(answer)
        mapMidTransceiver.remove(localId)
    }

    suspend fun stopReceiving(localId: String) {
        remoteSdp.closeMediaSection(localId)

        val offer = SessionDescription(SessionDescription.Type.OFFER, remoteSdp.getSdp())
        setRemoteDescription(offer)
        val answer = createAnswer()
        setLocalDescription(answer)
        mapMidTransceiver.remove(localId)
    }

    // -----------------------------------------------------------------------
    // ICE servers update
    // -----------------------------------------------------------------------

    fun updateIceServers(iceServers: List<PeerConnection.IceServer>) {
        val config = PeerConnection.RTCConfiguration(iceServers)
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        pc.setConfiguration(config)
    }

    // -----------------------------------------------------------------------
    // Private: WebRTC operations
    // -----------------------------------------------------------------------

    private suspend fun createOffer(): SessionDescription =
        suspendCancellableCoroutine { cont ->
            val constraints = MediaConstraints()
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { cont.resume(sdp) }
                override fun onCreateFailure(error: String) {
                    cont.resumeWithException(MediasoupError.Unknown(Exception("createOffer: $error")))
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, constraints)
        }

    private suspend fun createAnswer(): SessionDescription =
        suspendCancellableCoroutine { cont ->
            val constraints = MediaConstraints()
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { cont.resume(sdp) }
                override fun onCreateFailure(error: String) {
                    cont.resumeWithException(MediasoupError.Unknown(Exception("createAnswer: $error")))
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, constraints)
        }

    private suspend fun setLocalDescription(sdp: SessionDescription) =
        suspendCancellableCoroutine { cont ->
            pc.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onSetFailure(error: String) {
                    cont.resumeWithException(MediasoupError.Unknown(Exception("setLocalDescription: $error")))
                }
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            }, sdp)
        }

    private suspend fun setRemoteDescription(sdp: SessionDescription) =
        suspendCancellableCoroutine { cont ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onSetFailure(error: String) {
                    cont.resumeWithException(MediasoupError.Unknown(Exception("setRemoteDescription: $error")))
                }
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            }, sdp)
        }

    // -----------------------------------------------------------------------
    // Private: Transport setup
    // -----------------------------------------------------------------------

    private suspend fun setupTransport(localSdp: String) {
        val dtlsParams = SdpExtractor.extractDtlsParameters(localSdp)
        Log.d(TAG, "setupTransport - fingerprints: ${dtlsParams.fingerprints.size}, role: ${dtlsParams.role}")

        val connDtls = dtlsParams.copy(role = "client")
        remoteSdp.updateDtlsRole("server")

        val dtlsJson = JsonHelpers.stringifyDtlsParameters(connDtls)
        Log.d(TAG, "setupTransport - calling onConnect")
        onConnect?.invoke(dtlsJson)
        Log.d(TAG, "setupTransport - onConnect completed")

        transportReady = true
    }

    // -----------------------------------------------------------------------
    // Private: SDP extraction helpers
    // -----------------------------------------------------------------------

    private fun extractPayloads(sdp: String, mediaIndex: Int): String {
        val normalized = sdp.replace("\r\n", "\n")
        val mLines = normalized.split("\n").filter { it.startsWith("m=") }
        if (mediaIndex >= mLines.size) return ""
        val parts = mLines[mediaIndex].split(" ")
        return if (parts.size > 3) parts.drop(3).joinToString(" ") else ""
    }

    private fun extractExtmapLines(sdp: String, mediaIndex: Int): List<String> {
        val sections = splitMediaSections(sdp)
        if (mediaIndex >= sections.size) return emptyList()
        val section = sections[mediaIndex]

        return section.split("\n")
            .map { it.trim() }
            .filter { it.startsWith("a=extmap:") }
            .map { it.removePrefix("a=extmap:") }
    }

    private fun splitMediaSections(sdp: String): List<String> {
        val normalized = sdp.replace("\r\n", "\n")
        val sections = mutableListOf<String>()
        val current = StringBuilder()
        var inMedia = false

        for (line in normalized.split("\n")) {
            if (line.startsWith("m=")) {
                if (inMedia) sections.add(current.toString())
                current.clear()
                current.appendLine(line)
                inMedia = true
            } else if (inMedia) {
                current.appendLine(line)
            }
        }
        if (inMedia) sections.add(current.toString())
        return sections
    }

}
