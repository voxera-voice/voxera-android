package com.rocs.sdk.mediasoup

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Mediasoup Device — manages loading router capabilities and creating transports.
 * Port of iOS mediasoup-client-swift Device.swift.
 *
 * Pure Kotlin — no native C++ mediasoup code. Uses only WebRTC's public Java API.
 */
class Device(private val pcFactory: PeerConnectionFactory) {

    companion object {
        private const val TAG = "Mediasoup.Device"
    }

    private var loaded = false
    private var extendedRtpCapabilities: ExtendedRtpCapabilities? = null
    private var recvRtpCapabilities: RtpCapabilities? = null

    fun isLoaded(): Boolean = loaded

    /**
     * Load device with router RTP capabilities (JSON string).
     */
    suspend fun load(routerRTPCapabilities: String) {
        Log.d(TAG, "load() called")
        if (loaded) throw MediasoupError.InvalidState("Device already loaded")

        // Parse router capabilities
        val routerCaps = JsonHelpers.parseRtpCapabilities(routerRTPCapabilities)
        Log.d(TAG, "Router caps parsed: ${routerCaps.codecs?.size ?: 0} codecs")

        // Get native RTP capabilities from a temporary PeerConnection
        val nativeCaps = getNativeRtpCapabilities()
        Log.d(TAG, "Native caps obtained: ${nativeCaps.codecs?.size ?: 0} codecs")

        // Compute extended RTP capabilities
        val extended = Ortc.getExtendedRtpCapabilities(
            localCaps = nativeCaps,
            remoteCaps = routerCaps,
        )
        Log.d(TAG, "Extended caps: ${extended.codecs.size} codecs, ${extended.headerExtensions.size} extensions")

        val canSendAudio = Ortc.canSend("audio", extended)
        val canSendVideo = Ortc.canSend("video", extended)
        Log.d(TAG, "canSend - audio: $canSendAudio, video: $canSendVideo")
        if (!canSendAudio && !canSendVideo) {
            throw MediasoupError.Unsupported("No matching codecs with router")
        }

        extendedRtpCapabilities = extended
        recvRtpCapabilities = Ortc.getRecvRtpCapabilities(extended)
        loaded = true
        Log.d(TAG, "Device loaded successfully")
    }

    /**
     * Get device RTP capabilities as JSON string.
     */
    fun rtpCapabilities(): String {
        if (!loaded) throw MediasoupError.InvalidState("Device not loaded")
        return JsonHelpers.stringifyRtpCapabilities(recvRtpCapabilities!!)
    }

    /**
     * Whether the device can produce the given media kind.
     */
    fun canProduce(kind: String): Boolean {
        if (!loaded) throw MediasoupError.InvalidState("Device not loaded")
        return Ortc.canSend(kind, extendedRtpCapabilities!!)
    }

    // -----------------------------------------------------------------------
    // Create Transports
    // -----------------------------------------------------------------------

    fun createSendTransport(
        id: String,
        iceParameters: String,
        iceCandidates: String,
        dtlsParameters: String,
        iceServers: List<PeerConnection.IceServer> = emptyList(),
        iceTransportPolicy: PeerConnection.IceTransportsType = PeerConnection.IceTransportsType.ALL,
        appData: String = "",
    ): SendTransport {
        if (!loaded) throw MediasoupError.InvalidState("Device not loaded")
        val extended = extendedRtpCapabilities!!

        val iceParsed = JsonHelpers.parseIceParameters(iceParameters)
        val iceCandsParsed = JsonHelpers.parseIceCandidates(iceCandidates)
        val dtlsParsed = JsonHelpers.parseDtlsParameters(dtlsParameters)

        val handler = Handler(
            direction = Handler.Direction.SEND,
            pcFactory = pcFactory,
            iceParameters = iceParsed,
            iceCandidates = iceCandsParsed,
            dtlsParameters = dtlsParsed,
            iceServers = iceServers,
            iceTransportPolicy = iceTransportPolicy,
            extendedRtpCapabilities = extended,
        )

        return SendTransport(
            id = id,
            handler = handler,
            extendedRtpCapabilities = extended,
            appData = appData,
        )
    }

    fun createRecvTransport(
        id: String,
        iceParameters: String,
        iceCandidates: String,
        dtlsParameters: String,
        iceServers: List<PeerConnection.IceServer> = emptyList(),
        iceTransportPolicy: PeerConnection.IceTransportsType = PeerConnection.IceTransportsType.ALL,
        appData: String = "",
    ): ReceiveTransport {
        if (!loaded) throw MediasoupError.InvalidState("Device not loaded")
        val extended = extendedRtpCapabilities!!

        val iceParsed = JsonHelpers.parseIceParameters(iceParameters)
        val iceCandsParsed = JsonHelpers.parseIceCandidates(iceCandidates)
        val dtlsParsed = JsonHelpers.parseDtlsParameters(dtlsParameters)

        val handler = Handler(
            direction = Handler.Direction.RECV,
            pcFactory = pcFactory,
            iceParameters = iceParsed,
            iceCandidates = iceCandsParsed,
            dtlsParameters = dtlsParsed,
            iceServers = iceServers,
            iceTransportPolicy = iceTransportPolicy,
            extendedRtpCapabilities = extended,
        )

        return ReceiveTransport(
            id = id,
            handler = handler,
            extendedRtpCapabilities = extended,
            appData = appData,
        )
    }

    // -----------------------------------------------------------------------
    // Private
    // -----------------------------------------------------------------------

    /**
     * Get native RTP capabilities by creating a temp PeerConnection and generating an offer.
     */
    private suspend fun getNativeRtpCapabilities(): RtpCapabilities {
        val config = PeerConnection.RTCConfiguration(emptyList())
        config.iceTransportsType = PeerConnection.IceTransportsType.ALL
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val tmpPc = pcFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, ss: Array<out MediaStream>) {}
        }) ?: throw MediasoupError.InvalidState("Failed to create temp PeerConnection")

        // Add audio and video transceivers to get codec info in the offer
        val audioInit = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        val videoInit = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        tmpPc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, audioInit)
        tmpPc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, videoInit)

        val sdp = suspendCancellableCoroutine { cont ->
            tmpPc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    cont.resume(sdp.description)
                }
                override fun onCreateFailure(error: String) {
                    cont.resumeWithException(MediasoupError.Unknown(Exception("createOffer: $error")))
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, MediaConstraints())
        }

        tmpPc.close()

        val caps = SdpExtractor.extractRtpCapabilities(sdp)

        // Add NACK support for OPUS if not present (like reference implementation)
        val codecs = caps.codecs?.toMutableList() ?: mutableListOf()
        for (i in codecs.indices) {
            if (codecs[i].mimeType.lowercase() == "audio/opus") {
                val hasNack = codecs[i].rtcpFeedback?.any { it.type == "nack" && it.parameter == null } ?: false
                if (!hasNack) {
                    codecs[i] = codecs[i].copy(
                        rtcpFeedback = ((codecs[i].rtcpFeedback ?: mutableListOf()) + RtcpFeedback(type = "nack")).toMutableList()
                    )
                }
            }
        }

        return RtpCapabilities(codecs = codecs, headerExtensions = caps.headerExtensions)
    }
}
