package com.rocs.sdk

import android.content.Context
import android.util.Log
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import com.rocs.sdk.mediasoup.*
import com.rocs.sdk.mediasoup.Device as MsDevice
import com.rocs.sdk.mediasoup.SendTransport as MsSendTransport
import com.rocs.sdk.mediasoup.ReceiveTransport as MsReceiveTransport
import com.rocs.sdk.mediasoup.Producer as MsProducer
import com.rocs.sdk.mediasoup.Consumer as MsConsumer

/**
 * Manages WebRTC media via a pure-Kotlin mediasoup-client library.
 *
 * Uses mediasoup Device / SendTransport / RecvTransport / Producer / Consumer
 * which handle all SDP negotiation, ICE, and DTLS internally — matching the
 * same architecture as the iOS SDK.
 *
 * No native C++ mediasoup code — only WebRTC's public Java API (org.webrtc.*).
 */
class WebRTCManager(private val context: Context, private val signaling: SocketSignaling) {

    companion object {
        private const val TAG = "WebRTCManager"
    }

    // -----------------------------------------------------------------------
    // Callbacks
    // -----------------------------------------------------------------------

    var onRemoteAudioTrack: ((AudioTrack) -> Unit)? = null
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    // -----------------------------------------------------------------------
    // mediasoup objects
    // -----------------------------------------------------------------------

    private var device: MsDevice? = null
    private var sendTransport: MsSendTransport? = null
    private var recvTransport: MsReceiveTransport? = null
    private var audioProducer: MsProducer? = null
    private var videoProducer: MsProducer? = null
    private var audioConsumer: MsConsumer? = null
    private var videoConsumer: MsConsumer? = null

    // Track refs
    private var localAudioTrack: AudioTrack? = null

    /** The local camera video track. Null when camera is off. */
    var localVideoTrack: VideoTrack? = null; private set
    private var videoSource: VideoSource? = null
    private var cameraCapturer: Camera2Capturer? = null

    /** Current camera facing: true = front, false = back. */
    var isFrontCamera: Boolean = true; private set

    // PeerConnectionFactory — needed to create audio/video tracks
    private lateinit var factory: PeerConnectionFactory

    // Shared EglBase for all video rendering
    val eglBase: EglBase = EglBase.create()

    // ICE/TURN servers from the signaling server
    private var iceServers: JSONArray? = null

    // Transport IDs (needed for signaling)
    private var sendTransportId: String? = null
    private var recvTransportId: String? = null

    /** Public accessor for recv transport ID (needed for consume calls). */
    fun getRecvTransportId(): String? = recvTransportId

    // -----------------------------------------------------------------------
    // Initialization
    // -----------------------------------------------------------------------

    fun init() {
        // Initialise PeerConnectionFactory — loads the host-app's WebRTC native
        // lib (libjingle_peerconnection_so.so).  No mediasoup native code needed.
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        Log.d(
            TAG,
            "Audio effects availability - AEC=${AcousticEchoCanceler.isAvailable()}, NS=${NoiseSuppressor.isAvailable()}, AGC=${AutomaticGainControl.isAvailable()}"
        )

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    /**
     * Load the mediasoup Device with the router's RTP capabilities.
     * Must be called after init() and before creating transports.
     */
    suspend fun loadDevice(routerRtpCapabilities: String) {
        val dev = MsDevice(factory)
        dev.load(routerRtpCapabilities)
        device = dev
        Log.d(TAG, "Device loaded, canProduce audio=${dev.canProduce("audio")}")
    }

    /**
     * Store ICE/TURN servers received from the signaling server.
     * Must be called before creating transports.
     */
    fun setIceServers(servers: JSONArray?) {
        iceServers = servers
        Log.d(TAG, "ICE servers set: $servers")
    }

    // -----------------------------------------------------------------------
    // Transport Creation
    // -----------------------------------------------------------------------

    fun createSendTransport(params: JSONObject) {
        val dev = device ?: throw RocsException("Device not loaded", RocsErrorCode.WEBRTC_ERROR)
        sendTransportId = params.optString("id")

        Log.d(TAG, "createSendTransport params: id=${params.optString("id")}, " +
            "iceParameters=${params.optJSONObject("iceParameters")}, " +
            "iceCandidates=${params.optJSONArray("iceCandidates")}, " +
            "dtlsParameters=${params.optJSONObject("dtlsParameters")}")

        val transport = dev.createSendTransport(
            id = params.optString("id"),
            iceParameters = params.optJSONObject("iceParameters")?.toString() ?: "{}",
            iceCandidates = params.optJSONArray("iceCandidates")?.toString() ?: "[]",
            dtlsParameters = params.optJSONObject("dtlsParameters")?.toString() ?: "{}",
            iceServers = buildIceServers(),
        )
        transport.listener = sendTransportListener
        sendTransport = transport
        Log.d(TAG, "Send transport created: id=$sendTransportId")
    }

    fun createRecvTransport(params: JSONObject) {
        val dev = device ?: throw RocsException("Device not loaded", RocsErrorCode.WEBRTC_ERROR)
        recvTransportId = params.optString("id")

        val transport = dev.createRecvTransport(
            id = params.optString("id"),
            iceParameters = params.optJSONObject("iceParameters")?.toString() ?: "{}",
            iceCandidates = params.optJSONArray("iceCandidates")?.toString() ?: "[]",
            dtlsParameters = params.optJSONObject("dtlsParameters")?.toString() ?: "{}",
            iceServers = buildIceServers(),
        )
        transport.listener = recvTransportListener
        recvTransport = transport
        Log.d(TAG, "Recv transport created: id=$recvTransportId")
    }

    // -----------------------------------------------------------------------
    // Produce Audio
    // -----------------------------------------------------------------------

    suspend fun produceAudio() {
        val transport = sendTransport ?: throw RocsException("Send transport not created", RocsErrorCode.WEBRTC_ERROR)
        val audioConstraints = MediaConstraints().apply {
            // Hint WebRTC to use the communication audio path to improve AEC behavior.
            mandatory.add(MediaConstraints.KeyValuePair("googAudioSource", "7"))
            mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
        }
        Log.d(TAG, "Audio constraints applied: ${audioConstraints.mandatory}")
        val src = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("audio0", src)
        localAudioTrack!!.setEnabled(true)

        audioProducer = transport.produce(track = localAudioTrack!!)
        audioProducer?.resume()
        Log.d(TAG, "Audio producer created: id=${audioProducer?.id}")
    }

    // -----------------------------------------------------------------------
    // Camera / Video
    // -----------------------------------------------------------------------

    /**
     * Start the camera and produce a video track to the server.
     * @param front true for front camera, false for back camera.
     */
    suspend fun startCamera(front: Boolean = true) {
        val transport = sendTransport ?: throw RocsException("Send transport not created", RocsErrorCode.WEBRTC_ERROR)
        isFrontCamera = front
        val cameraId = getCameraId(front)

        videoSource = factory.createVideoSource(false)
        val capturer = Camera2Capturer(context, cameraId, null)
        capturer.initialize(
            SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext),
            context, videoSource!!.capturerObserver
        )
        capturer.startCapture(1280, 720, 30)
        cameraCapturer = capturer

        localVideoTrack = factory.createVideoTrack("rocs-video-0", videoSource)
        localVideoTrack!!.setEnabled(true)

        videoProducer = transport.produce(track = localVideoTrack!!)
        videoProducer?.resume()
        Log.d(TAG, "Video producer created: id=${videoProducer?.id}")
    }

    /** Stop the camera and close the video producer. */
    fun stopCamera() {
        cameraCapturer?.stopCapture()
        cameraCapturer?.dispose()
        cameraCapturer = null
        videoProducer?.close()
        videoProducer = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        Log.d(TAG, "Camera stopped")
    }

    /** Switch between front and back camera. */
    fun switchCamera() {
        val capturer = cameraCapturer ?: return
        // Stop current capture
        capturer.stopCapture()
        capturer.dispose()

        isFrontCamera = !isFrontCamera
        val newCameraId = getCameraId(isFrontCamera)

        val newCapturer = Camera2Capturer(context, newCameraId, null)
        newCapturer.initialize(
            SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext),
            context, videoSource!!.capturerObserver
        )
        newCapturer.startCapture(1280, 720, 30)
        cameraCapturer = newCapturer
        Log.d(TAG, "Camera switched to ${if (isFrontCamera) "front" else "back"}")
    }

    private fun getCameraId(front: Boolean): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val targetFacing = if (front)
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        else
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == targetFacing) return id
        }
        return manager.cameraIdList.firstOrNull() ?: throw RocsException("No camera found", RocsErrorCode.MEDIA_ACCESS_DENIED)
    }

    // -----------------------------------------------------------------------
    // Consume
    // -----------------------------------------------------------------------

    suspend fun consumeAI(consumeParams: JSONObject) {
        val transport = recvTransport ?: throw RocsException("Recv transport not created", RocsErrorCode.WEBRTC_ERROR)
        val kindStr = consumeParams.optString("kind", "audio")

        val consumer = transport.consume(
            consumerId = consumeParams.optString("id"),
            producerId = consumeParams.optString("producerId"),
            kind = MediaKind.from(kindStr),
            rtpParameters = consumeParams.optJSONObject("rtpParameters")?.toString() ?: "{}",
        )

        val track = consumer.track
        Log.d(TAG, "Consumer created: id=${consumer.id}, kind=${consumer.kind}, trackKind=${track.kind()}")

        when (kindStr) {
            "audio" -> {
                audioConsumer = consumer
                (track as? AudioTrack)?.let { onRemoteAudioTrack?.invoke(it) }
            }
            "video" -> {
                videoConsumer = consumer
                (track as? VideoTrack)?.let { onRemoteVideoTrack?.invoke(it) }
            }
        }
        consumer.resume()
    }

    // -----------------------------------------------------------------------
    // Transport Listeners
    // -----------------------------------------------------------------------

    private val sendTransportListener = object : SendTransportListener {
        override suspend fun onConnect(transport: com.rocs.sdk.mediasoup.Transport, dtlsParameters: String) {
            Log.d(TAG, "Send transport onConnect")
            signaling.emit("connectTransport", JSONObject().apply {
                put("transportId", transport.id)
                put("dtlsParameters", JSONObject(dtlsParameters))
            })
        }

        override fun onConnectionStateChange(transport: com.rocs.sdk.mediasoup.Transport, connectionState: TransportConnectionState) {
            Log.d(TAG, "Send transport state: ${connectionState.value}")
            if (connectionState == TransportConnectionState.FAILED) {
                onError?.invoke(RocsException("Send transport connection failed", RocsErrorCode.WEBRTC_ERROR))
            }
        }

        override suspend fun onProduce(
            transport: com.rocs.sdk.mediasoup.Transport,
            kind: MediaKind,
            rtpParameters: String,
            appData: String,
        ): String? {
            Log.d(TAG, "Send transport onProduce: kind=${kind.value}")
            return try {
                val response = signaling.emitWithAck("produce", JSONObject().apply {
                    put("kind", kind.value)
                    put("rtpParameters", JSONObject(rtpParameters))
                    put("transportId", transport.id)
                })
                val producerId = response.optString("id", "")
                Log.d(TAG, "Produce ack: producerId=$producerId")
                producerId
            } catch (e: Exception) {
                Log.e(TAG, "Produce signaling failed", e)
                ""
            }
        }
    }

    private val recvTransportListener = object : ReceiveTransportListener {
        override suspend fun onConnect(transport: com.rocs.sdk.mediasoup.Transport, dtlsParameters: String) {
            Log.d(TAG, "Recv transport onConnect")
            signaling.emit("connectTransport", JSONObject().apply {
                put("transportId", transport.id)
                put("dtlsParameters", JSONObject(dtlsParameters))
            })
        }

        override fun onConnectionStateChange(transport: com.rocs.sdk.mediasoup.Transport, connectionState: TransportConnectionState) {
            Log.d(TAG, "Recv transport state: ${connectionState.value}")
            if (connectionState == TransportConnectionState.FAILED) {
                onError?.invoke(RocsException("Recv transport connection failed", RocsErrorCode.WEBRTC_ERROR))
            }
        }
    }

    // -----------------------------------------------------------------------
    // ICE server helpers
    // -----------------------------------------------------------------------

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        iceServers?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val urls = when (val u = s.opt("urls")) {
                    is String -> listOf(u)
                    is JSONArray -> (0 until u.length()).map { idx -> u.getString(idx) }
                    else -> emptyList()
                }
                if (urls.isNotEmpty()) {
                    val builder = PeerConnection.IceServer.builder(urls)
                    s.optString("username").takeIf { it.isNotEmpty() }?.let { builder.setUsername(it) }
                    s.optString("credential").takeIf { it.isNotEmpty() }?.let { builder.setPassword(it) }
                    servers += builder.createIceServer()
                }
            }
        }
        return servers
    }

    // -----------------------------------------------------------------------
    // Audio control
    // -----------------------------------------------------------------------

    fun setAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    fun cleanup() {
        audioProducer?.close(); audioProducer = null
        videoProducer?.close(); videoProducer = null
        audioConsumer?.close(); audioConsumer = null
        videoConsumer?.close(); videoConsumer = null
        sendTransport?.close(); sendTransport = null
        recvTransport?.close(); recvTransport = null
        localAudioTrack?.dispose(); localAudioTrack = null
        cameraCapturer?.stopCapture()
        cameraCapturer?.dispose(); cameraCapturer = null
        localVideoTrack?.dispose(); localVideoTrack = null
        videoSource?.dispose(); videoSource = null
        device = null
        if (::factory.isInitialized) factory.dispose()
        eglBase.release()
    }
}
