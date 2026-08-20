package com.rocs.sdk.mediasoup

/**
 * ORTC capability negotiation.
 * Port of iOS mediasoup-client-swift ORTC.swift.
 */
internal object Ortc {

    /**
     * Match local RTP capabilities against remote (router) capabilities.
     */
    fun getExtendedRtpCapabilities(
        localCaps: RtpCapabilities,
        remoteCaps: RtpCapabilities,
    ): ExtendedRtpCapabilities {
        val extended = ExtendedRtpCapabilities()

        // Match media codecs (order preferred by remote/router)
        for (remoteCodec in remoteCaps.codecs ?: emptyList()) {
            if (isRtxCodec(remoteCodec.mimeType)) continue

            val matchingLocal = (localCaps.codecs ?: emptyList()).firstOrNull { localCodec ->
                matchCodecs(localCodec, remoteCodec, strict = true)
            } ?: continue

            extended.codecs.add(
                ExtendedRtpCodec(
                    kind = remoteCodec.kind ?: matchingLocal.kind ?: "audio",
                    mimeType = remoteCodec.mimeType,
                    clockRate = remoteCodec.clockRate,
                    channels = remoteCodec.channels,
                    localPayloadType = matchingLocal.preferredPayloadType,
                    remotePayloadType = remoteCodec.preferredPayloadType,
                    localParameters = matchingLocal.parameters,
                    remoteParameters = remoteCodec.parameters,
                    rtcpFeedback = reduceRtcpFeedback(
                        matchingLocal.rtcpFeedback ?: emptyList(),
                        remoteCodec.rtcpFeedback ?: emptyList(),
                    ),
                )
            )
        }

        // Match RTX codecs
        for (i in extended.codecs.indices) {
            val extCodec = extended.codecs[i]

            val matchingLocalRtx = (localCaps.codecs ?: emptyList()).firstOrNull { c ->
                isRtxCodec(c.mimeType) && c.parameters?.get("apt")?.intValue == extCodec.localPayloadType
            }
            val matchingRemoteRtx = (remoteCaps.codecs ?: emptyList()).firstOrNull { c ->
                isRtxCodec(c.mimeType) && c.parameters?.get("apt")?.intValue == extCodec.remotePayloadType
            }

            if (matchingLocalRtx != null && matchingRemoteRtx != null) {
                extended.codecs[i] = extCodec.copy(
                    localRtxPayloadType = matchingLocalRtx.preferredPayloadType,
                    remoteRtxPayloadType = matchingRemoteRtx.preferredPayloadType,
                )
            }
        }

        // Match header extensions
        for (remoteExt in remoteCaps.headerExtensions ?: emptyList()) {
            val matchingLocal = (localCaps.headerExtensions ?: emptyList()).firstOrNull { localExt ->
                matchHeaderExtensions(localExt, remoteExt)
            } ?: continue

            val direction = when (remoteExt.direction ?: "sendrecv") {
                "sendrecv" -> "sendrecv"
                "recvonly" -> "sendonly"
                "sendonly" -> "recvonly"
                "inactive" -> "inactive"
                else -> "sendrecv"
            }

            extended.headerExtensions.add(
                ExtendedRtpHeaderExtension(
                    kind = remoteExt.kind,
                    uri = remoteExt.uri,
                    sendId = matchingLocal.preferredId,
                    recvId = remoteExt.preferredId,
                    encrypt = matchingLocal.preferredEncrypt ?: false,
                    direction = direction,
                )
            )
        }

        return extended
    }

    /**
     * Get recv RTP capabilities (for device.rtpCapabilities()).
     */
    fun getRecvRtpCapabilities(extendedRtpCapabilities: ExtendedRtpCapabilities): RtpCapabilities {
        val codecs = mutableListOf<RtpCodecCapability>()
        val headerExtensions = mutableListOf<RtpHeaderExtensionCapability>()

        for (ext in extendedRtpCapabilities.codecs) {
            codecs.add(
                RtpCodecCapability(
                    kind = ext.kind,
                    mimeType = ext.mimeType,
                    preferredPayloadType = ext.remotePayloadType,
                    clockRate = ext.clockRate,
                    channels = ext.channels,
                    parameters = ext.localParameters,
                    rtcpFeedback = ext.rtcpFeedback.toMutableList(),
                )
            )

            ext.remoteRtxPayloadType?.let { remoteRtxPt ->
                codecs.add(
                    RtpCodecCapability(
                        kind = ext.kind,
                        mimeType = "${ext.kind}/rtx",
                        preferredPayloadType = remoteRtxPt,
                        clockRate = ext.clockRate,
                        channels = null,
                        parameters = mapOf("apt" to JsonValue.Int(ext.remotePayloadType)),
                        rtcpFeedback = mutableListOf(),
                    )
                )
            }
        }

        for (ext in extendedRtpCapabilities.headerExtensions) {
            if (ext.direction != "sendrecv" && ext.direction != "recvonly") continue

            headerExtensions.add(
                RtpHeaderExtensionCapability(
                    kind = ext.kind,
                    uri = ext.uri,
                    preferredId = ext.recvId,
                    preferredEncrypt = ext.encrypt,
                    direction = ext.direction,
                )
            )
        }

        return RtpCapabilities(codecs = codecs, headerExtensions = headerExtensions)
    }

    /**
     * Generate RTP parameters for sending a given media kind.
     */
    fun getSendingRtpParameters(
        kind: String,
        extendedRtpCapabilities: ExtendedRtpCapabilities,
    ): MsRtpParameters {
        val codecs = mutableListOf<RtpCodecParameters>()
        val headerExtensions = mutableListOf<RtpHeaderExtensionParam>()

        for (ext in extendedRtpCapabilities.codecs) {
            if (ext.kind != kind) continue

            codecs.add(
                RtpCodecParameters(
                    mimeType = ext.mimeType,
                    payloadType = ext.localPayloadType,
                    clockRate = ext.clockRate,
                    channels = ext.channels,
                    parameters = ext.localParameters,
                    rtcpFeedback = ext.rtcpFeedback,
                )
            )

            ext.localRtxPayloadType?.let { localRtxPt ->
                codecs.add(
                    RtpCodecParameters(
                        mimeType = "${ext.kind}/rtx",
                        payloadType = localRtxPt,
                        clockRate = ext.clockRate,
                        channels = null,
                        parameters = mapOf("apt" to JsonValue.Int(ext.localPayloadType)),
                        rtcpFeedback = emptyList(),
                    )
                )
            }
        }

        for (ext in extendedRtpCapabilities.headerExtensions) {
            if (ext.kind != null && ext.kind != kind) continue
            if (ext.direction != "sendrecv" && ext.direction != "sendonly") continue

            headerExtensions.add(
                RtpHeaderExtensionParam(uri = ext.uri, id = ext.sendId, encrypt = ext.encrypt)
            )
        }

        return MsRtpParameters(
            mid = null,
            codecs = codecs,
            headerExtensions = headerExtensions,
            encodings = emptyList(),
            rtcp = RtcpParam(),
        )
    }

    /**
     * Generate RTP parameters suitable for the remote SDP answer.
     */
    fun getSendingRemoteRtpParameters(
        kind: String,
        extendedRtpCapabilities: ExtendedRtpCapabilities,
    ): MsRtpParameters {
        val codecs = mutableListOf<RtpCodecParameters>()
        val headerExtensions = mutableListOf<RtpHeaderExtensionParam>()

        for (ext in extendedRtpCapabilities.codecs) {
            if (ext.kind != kind) continue

            codecs.add(
                RtpCodecParameters(
                    mimeType = ext.mimeType,
                    payloadType = ext.localPayloadType,
                    clockRate = ext.clockRate,
                    channels = ext.channels,
                    parameters = ext.remoteParameters,
                    rtcpFeedback = ext.rtcpFeedback,
                )
            )

            ext.localRtxPayloadType?.let { localRtxPt ->
                codecs.add(
                    RtpCodecParameters(
                        mimeType = "${ext.kind}/rtx",
                        payloadType = localRtxPt,
                        clockRate = ext.clockRate,
                        channels = null,
                        parameters = mapOf("apt" to JsonValue.Int(ext.localPayloadType)),
                        rtcpFeedback = emptyList(),
                    )
                )
            }
        }

        for (ext in extendedRtpCapabilities.headerExtensions) {
            if (ext.kind != null && ext.kind != kind) continue
            if (ext.direction != "sendrecv" && ext.direction != "sendonly") continue

            headerExtensions.add(
                RtpHeaderExtensionParam(uri = ext.uri, id = ext.sendId, encrypt = ext.encrypt)
            )
        }

        // Reduce RTCP feedback: prefer transport-cc over goog-remb
        val hasTransportCC = headerExtensions.any {
            it.uri == "http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01"
        }
        val hasAbsSendTime = headerExtensions.any {
            it.uri == "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time"
        }

        for (i in codecs.indices) {
            codecs[i] = codecs[i].copy(
                rtcpFeedback = codecs[i].rtcpFeedback?.filter { fb ->
                    when {
                        hasTransportCC -> fb.type != "goog-remb"
                        hasAbsSendTime -> fb.type != "transport-cc"
                        else -> fb.type != "transport-cc" && fb.type != "goog-remb"
                    }
                }
            )
        }

        return MsRtpParameters(
            mid = null,
            codecs = codecs,
            headerExtensions = headerExtensions,
            encodings = emptyList(),
            rtcp = RtcpParam(),
        )
    }

    /**
     * Reduce codecs to only include the first one (and its RTX) or a matching codec.
     */
    fun reduceCodecs(
        codecs: List<RtpCodecParameters>,
        capCodec: RtpCodecCapability? = null,
    ): List<RtpCodecParameters> {
        if (codecs.isEmpty()) return emptyList()

        if (capCodec != null) {
            for (idx in codecs.indices) {
                if (matchCodecParams(codecs[idx], capCodec)) {
                    val result = mutableListOf(codecs[idx])
                    if (idx + 1 < codecs.size && isRtxCodec(codecs[idx + 1].mimeType)) {
                        result.add(codecs[idx + 1])
                    }
                    return result
                }
            }
            return listOf(codecs[0])
        } else {
            val result = mutableListOf(codecs[0])
            if (codecs.size > 1 && isRtxCodec(codecs[1].mimeType)) {
                result.add(codecs[1])
            }
            return result
        }
    }

    fun canSend(kind: String, extendedCaps: ExtendedRtpCapabilities): Boolean =
        extendedCaps.codecs.any { it.kind == kind }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun isRtxCodec(mimeType: String): Boolean =
        mimeType.lowercase().endsWith("/rtx")

    private fun matchCodecs(a: RtpCodecCapability, b: RtpCodecCapability, strict: Boolean): Boolean {
        if (a.mimeType.lowercase() != b.mimeType.lowercase()) return false
        if (a.clockRate != b.clockRate) return false
        if (a.channels != b.channels) return false

        if (strict) {
            when (a.mimeType.lowercase()) {
                "video/h264" -> {
                    val aPM = a.parameters?.get("packetization-mode")?.intValue ?: 0
                    val bPM = b.parameters?.get("packetization-mode")?.intValue ?: 0
                    if (aPM != bPM) return false
                    val aPLI = (a.parameters?.get("profile-level-id")?.stringValue ?: "42e01f").lowercase()
                    val bPLI = (b.parameters?.get("profile-level-id")?.stringValue ?: "42e01f").lowercase()
                    if (aPLI.take(4) != bPLI.take(4)) return false
                }
                "video/vp9" -> {
                    val aPI = a.parameters?.get("profile-id")?.intValue ?: 0
                    val bPI = b.parameters?.get("profile-id")?.intValue ?: 0
                    if (aPI != bPI) return false
                }
            }
        }
        return true
    }

    private fun matchCodecParams(a: RtpCodecParameters, b: RtpCodecCapability): Boolean {
        if (a.mimeType.lowercase() != b.mimeType.lowercase()) return false
        if (a.clockRate != b.clockRate) return false
        return true
    }

    private fun matchHeaderExtensions(
        a: RtpHeaderExtensionCapability,
        b: RtpHeaderExtensionCapability,
    ): Boolean {
        if (a.kind != null && b.kind != null && a.kind != b.kind) return false
        if (a.uri != b.uri) return false
        return true
    }

    private fun reduceRtcpFeedback(a: List<RtcpFeedback>, b: List<RtcpFeedback>): List<RtcpFeedback> {
        return a.filter { aFb ->
            b.any { bFb ->
                bFb.type == aFb.type && (bFb.parameter ?: "") == (aFb.parameter ?: "")
            }
        }
    }
}
