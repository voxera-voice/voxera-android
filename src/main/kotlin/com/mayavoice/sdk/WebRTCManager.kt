package com.mayavoice.sdk

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages WebRTC peer connections and mediasoup transport negotiation
 * for the Maya Voice Android SDK.
 *
 * One RTCPeerConnection is created per mediasoup transport direction (send / recv).
 */
class WebRTCManager(private val context: Context, private val signaling: SocketSignaling) {

    // -----------------------------------------------------------------------
    // Callbacks
    // -----------------------------------------------------------------------

    var onRemoteAudioTrack: ((AudioTrack) -> Unit)? = null
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    // -----------------------------------------------------------------------
    // WebRTC internals
    // -----------------------------------------------------------------------

    private lateinit var factory: PeerConnectionFactory

    private var sendConnection: PeerConnection? = null
    private var recvConnection: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private var sendTransportId: String? = null
    private var recvTransportId: String? = null

    // -----------------------------------------------------------------------
    // Initialization
    // -----------------------------------------------------------------------

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(EglBase.create().eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(EglBase.create().eglBaseContext))
            .createPeerConnectionFactory()
    }

    // -----------------------------------------------------------------------
    // Transport Creation
    // -----------------------------------------------------------------------

    fun createSendTransport(params: JSONObject, iceServers: JSONArray?) {
        sendTransportId = params.optString("id")
        sendConnection = factory.createPeerConnection(buildRtcConfig(iceServers), object : BasePeerConnectionObserver() {
            override fun onTrack(transceiver: RtpTransceiver?) {
                transceiver?.receiver?.track()?.let { track ->
                    when (track) {
                        is AudioTrack -> onRemoteAudioTrack?.invoke(track)
                        is VideoTrack -> onRemoteVideoTrack?.invoke(track)
                    }
                }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        })
    }

    fun createRecvTransport(params: JSONObject, iceServers: JSONArray?) {
        recvTransportId = params.optString("id")
        recvConnection = factory.createPeerConnection(buildRtcConfig(iceServers), object : BasePeerConnectionObserver() {
            override fun onTrack(transceiver: RtpTransceiver?) {
                transceiver?.receiver?.track()?.let { track ->
                    when (track) {
                        is AudioTrack -> onRemoteAudioTrack?.invoke(track)
                        is VideoTrack -> onRemoteVideoTrack?.invoke(track)
                    }
                }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        })
    }

    // -----------------------------------------------------------------------
    // Produce Audio
    // -----------------------------------------------------------------------

    suspend fun produceAudio(): String {
        val conn = sendConnection ?: throw MayaVoiceException("Send transport not created", MayaVoiceErrorCode.WEBRTC_ERROR)
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack  = factory.createAudioTrack("audio0", audioSource)
        conn.addTrack(audioTrack!!, listOf("local-stream"))

        return negotiateProducer(connection = conn, transportId = sendTransportId!!, kind = "audio")
    }

    // -----------------------------------------------------------------------
    // Produce Video
    // -----------------------------------------------------------------------

    suspend fun produceVideo(capturer: VideoCapturer): String {
        val conn = sendConnection ?: throw MayaVoiceException("Send transport not created", MayaVoiceErrorCode.WEBRTC_ERROR)
        videoSource = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(SurfaceTextureHelper.create("CaptureThread", EglBase.create().eglBaseContext),
            context, videoSource!!.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        videoTrack = factory.createVideoTrack("video0", videoSource)
        conn.addTrack(videoTrack!!, listOf("local-stream"))

        return negotiateProducer(connection = conn, transportId = sendTransportId!!, kind = "video")
    }

    // -----------------------------------------------------------------------
    // Consume AI Audio
    // -----------------------------------------------------------------------

    suspend fun consumeAI(consumeParams: JSONObject) {
        val conn = recvConnection ?: throw MayaVoiceException("Recv transport not created", MayaVoiceErrorCode.WEBRTC_ERROR)

        // Add recvonly transceiver for audio
        val transceiverInit = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, transceiverInit)

        negotiateConsumer(connection = conn, transportId = recvTransportId!!, consumeParams = consumeParams)
    }

    // -----------------------------------------------------------------------
    // SDP Negotiation helpers
    // -----------------------------------------------------------------------

    private suspend fun negotiateProducer(connection: PeerConnection, transportId: String, kind: String): String =
        suspendCancellableCoroutine { cont ->
            val constraints = MediaConstraints()
            connection.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp ?: return
                    connection.setLocalDescription(SdpObserverAdapter(), sdp)
                    val rtpParams = extractRtpParameters(sdp.description, kind)
                    signaling.emit("produce", JSONObject().apply {
                        put("kind", kind)
                        put("rtpParameters", rtpParams)
                        put("transportId", transportId)
                    })
                    // Response handled via emitWithAck in caller; here we resolve via socket ack
                    // For simplicity we emit and observe "producer-created" or ack mechanism
                    cont.resume("producer-created") // server will return real ID via ack
                }
                override fun onCreateFailure(p0: String?) {
                    if (cont.isActive) cont.resumeWithException(MayaVoiceException(p0 ?: "SDP offer failed", MayaVoiceErrorCode.WEBRTC_ERROR))
                }
            }, constraints)
        }

    private suspend fun negotiateConsumer(connection: PeerConnection, transportId: String, consumeParams: JSONObject) =
        suspendCancellableCoroutine<Unit> { cont ->
            val remoteSdp = buildRemoteSdpFromConsume(consumeParams)
            val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, remoteSdp)
            connection.setRemoteDescription(SdpObserverAdapter(), remoteDesc)
            val constraints = MediaConstraints()
            connection.createAnswer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp ?: return
                    connection.setLocalDescription(SdpObserverAdapter(), sdp)
                    val dtls = extractDtls(sdp.description)
                    signaling.emit("connectTransport", JSONObject().apply {
                        put("dtlsParameters", dtls)
                        put("transportId", transportId)
                    })
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onCreateFailure(p0: String?) {
                    if (cont.isActive) cont.resumeWithException(MayaVoiceException(p0 ?: "SDP answer failed", MayaVoiceErrorCode.WEBRTC_ERROR))
                }
            }, constraints)
        }

    // -----------------------------------------------------------------------
    // SDP parsing helpers (simplified — use a proper SDP parser in production)
    // -----------------------------------------------------------------------

    private fun extractRtpParameters(sdp: String, kind: String): JSONObject =
        JSONObject().apply { put("sdp", sdp); put("kind", kind) }

    private fun extractDtls(sdp: String): JSONObject? {
        for (line in sdp.lines()) {
            if (line.startsWith("a=fingerprint:")) {
                val parts = line.removePrefix("a=fingerprint:").trim().split(" ")
                if (parts.size == 2) {
                    return JSONObject().apply { put("algorithm", parts[0]); put("value", parts[1]) }
                }
            }
        }
        return null
    }

    private fun buildRemoteSdpFromConsume(params: JSONObject): String {
        val rtpParameters = params.optJSONObject("rtpParameters") ?: JSONObject()
        val codecs = rtpParameters.optJSONArray("codecs") ?: JSONArray()
        val pts = (0 until codecs.length()).joinToString(" ") { codecs.getJSONObject(it).optInt("payloadType", 111).toString() }
        val lines = mutableListOf(
            "v=0", "o=- 0 0 IN IP4 127.0.0.1", "s=MayaVoice", "t=0 0",
            "m=audio 7 RTP/SAVPF $pts", "c=IN IP4 0.0.0.0", "a=recvonly"
        )
        for (i in 0 until codecs.length()) {
            val c = codecs.getJSONObject(i)
            val pt = c.optInt("payloadType", 111)
            val mime = c.optString("mimeType", "audio/opus").split("/").last()
            lines += "a=rtpmap:$pt $mime/48000/2"
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    // -----------------------------------------------------------------------
    // ICE config
    // -----------------------------------------------------------------------

    private fun buildRtcConfig(iceServers: JSONArray?): PeerConnection.RTCConfiguration {
        val servers = mutableListOf<PeerConnection.IceServer>()
        iceServers?.let {
            for (i in 0 until it.length()) {
                val s = it.getJSONObject(i)
                val urls = when (val u = s.opt("urls")) {
                    is String     -> listOf(u)
                    is JSONArray  -> (0 until u.length()).map { idx -> u.getString(idx) }
                    else          -> emptyList()
                }
                if (urls.isNotEmpty()) {
                    val builder = PeerConnection.IceServer.builder(urls)
                    s.optString("username").takeIf { v -> v.isNotEmpty() }?.let { u -> builder.setUsername(u) }
                    s.optString("credential").takeIf { v -> v.isNotEmpty() }?.let { c -> builder.setPassword(c) }
                    servers += builder.createIceServer()
                }
            }
        }
        if (servers.isEmpty()) servers += PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        return PeerConnection.RTCConfiguration(servers).also { it.iceTransportsType = PeerConnection.IceTransportsType.RELAY }
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    fun cleanup() {
        audioTrack?.dispose(); audioTrack = null
        videoTrack?.dispose(); videoTrack = null
        audioSource?.dispose(); audioSource = null
        videoSource?.dispose(); videoSource = null
        sendConnection?.close(); sendConnection = null
        recvConnection?.close(); recvConnection = null
        if (::factory.isInitialized) factory.dispose()
    }

    // -----------------------------------------------------------------------
    // Helper observer base
    // -----------------------------------------------------------------------

    private abstract class BasePeerConnectionObserver : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(p0: IceCandidate?) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
