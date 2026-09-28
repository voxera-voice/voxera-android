package com.rocs.sdk.mediasoup

/**
 * Constructs remote SDP strings for mediasoup signaling.
 * - For sending: builds a remote ANSWER SDP in response to the local PC's OFFER.
 * - For receiving: builds a remote OFFER SDP that the local PC will ANSWER.
 *
 * Port of iOS mediasoup-client-swift RemoteSdp.swift.
 */
internal class RemoteSdp(
    private val iceParameters: IceParametersJSON,
    private val iceCandidates: List<IceCandidateJSON>,
    private val dtlsParameters: DtlsParametersJSON,
) {
    private var dtlsRole: String = dtlsParameters.role ?: "auto"
    private val mediaSections = mutableListOf<MediaSectionSdp>()
    private var sessionVersion = 0

    fun updateDtlsRole(role: String) {
        dtlsRole = role
    }

    // -- Send (build answer for local offer) --

    fun addSendMediaSection(
        mid: String,
        kind: String,
        offerPayloads: String,
        answerRtpParameters: MsRtpParameters,
        offerExtmapLines: List<String>,
        extmapAllowMixed: Boolean = true,
    ) {
        mediaSections.add(
            buildAnswerMediaSection(
                mid = mid,
                kind = kind,
                payloads = filterPayloads(offerPayloads, answerRtpParameters),
                direction = "recvonly",
                rtpParameters = answerRtpParameters,
                offerExtmapLines = offerExtmapLines,
                extmapAllowMixed = extmapAllowMixed,
            )
        )
    }

    // -- Receive (build offer for local answer) --

    fun addRecvMediaSection(
        mid: String,
        kind: String,
        offerRtpParameters: MsRtpParameters,
        streamId: String,
        trackId: String,
    ) {
        mediaSections.add(
            buildOfferMediaSection(
                mid = mid,
                kind = kind,
                rtpParameters = offerRtpParameters,
                streamId = streamId,
                trackId = trackId,
            )
        )
    }

    fun closeMediaSection(mid: String) {
        val idx = mediaSections.indexOfFirst { it.mid == mid }
        if (idx >= 0) mediaSections[idx] = mediaSections[idx].copy(closed = true)
    }

    fun getSdp(): String {
        sessionVersion++

        val fingerprint = dtlsParameters.fingerprints.lastOrNull()
        val setup = when (dtlsRole) {
            "client" -> "active"
            "server" -> "passive"
            else -> "actpass"
        }

        val bundleMids = mediaSections.filter { !it.closed }.joinToString(" ") { it.mid }

        val sb = StringBuilder()
        sb.append("v=0\r\n")
        sb.append("o=- 10000 $sessionVersion IN IP4 0.0.0.0\r\n")
        sb.append("s=-\r\n")
        sb.append("t=0 0\r\n")
        sb.append("a=ice-options:ice2\r\n")
        if (iceParameters.iceLite == true) sb.append("a=ice-lite\r\n")
        fingerprint?.let { sb.append("a=fingerprint:${it.algorithm} ${it.value}\r\n") }
        sb.append("a=msid-semantic: WMS *\r\n")
        sb.append("a=group:BUNDLE $bundleMids\r\n")

        for (section in mediaSections) {
            if (section.closed) {
                sb.append(buildClosedSection(section.mid, section.kind))
            } else {
                sb.append(buildActiveSection(section, setup))
            }
        }

        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // Private
    // -----------------------------------------------------------------------

    private fun buildActiveSection(section: MediaSectionSdp, setup: String): String {
        val sb = StringBuilder()
        sb.append("m=${section.kind} 7 UDP/TLS/RTP/SAVPF ${section.payloads}\r\n")
        sb.append("c=IN IP4 127.0.0.1\r\n")
        sb.append("a=mid:${section.mid}\r\n")
        sb.append("a=ice-ufrag:${iceParameters.usernameFragment}\r\n")
        sb.append("a=ice-pwd:${iceParameters.password}\r\n")

        for (cand in iceCandidates) {
            var line = "a=candidate:${cand.foundation} 1 ${cand.protocol} ${cand.priority} ${cand.effectiveIP} ${cand.port} typ ${cand.type}"
            if (cand.protocol.lowercase() == "tcp" && cand.tcpType != null) {
                line += " tcptype ${cand.tcpType}"
            }
            sb.append("$line\r\n")
        }
        sb.append("a=end-of-candidates\r\n")
        sb.append("a=ice-options:renomination\r\n")
        sb.append("a=setup:$setup\r\n")
        sb.append("a=${section.direction}\r\n")
        sb.append(section.codecLines)
        sb.append("a=rtcp-mux\r\n")
        sb.append("a=rtcp-rsize\r\n")

        return sb.toString()
    }

    private fun buildClosedSection(mid: String, kind: String): String {
        val sb = StringBuilder()
        sb.append("m=$kind 0 UDP/TLS/RTP/SAVPF 0\r\n")
        sb.append("c=IN IP4 127.0.0.1\r\n")
        sb.append("a=mid:$mid\r\n")
        sb.append("a=inactive\r\n")
        return sb.toString()
    }

    private fun buildAnswerMediaSection(
        mid: String,
        kind: String,
        payloads: String,
        direction: String,
        rtpParameters: MsRtpParameters,
        offerExtmapLines: List<String>,
        extmapAllowMixed: Boolean,
    ): MediaSectionSdp {
        val sb = StringBuilder()

        for (codec in rtpParameters.codecs) {
            val codecName = codecShortName(codec.mimeType)
            if (codec.channels != null && codec.channels > 1) {
                sb.append("a=rtpmap:${codec.payloadType} $codecName/${codec.clockRate}/${codec.channels}\r\n")
            } else {
                sb.append("a=rtpmap:${codec.payloadType} $codecName/${codec.clockRate}\r\n")
            }

            val fmtpStr = formatParams(codec.parameters)
            if (fmtpStr.isNotEmpty()) {
                sb.append("a=fmtp:${codec.payloadType} $fmtpStr\r\n")
            }

            for (fb in codec.rtcpFeedback ?: emptyList()) {
                if (!fb.parameter.isNullOrEmpty()) {
                    sb.append("a=rtcp-fb:${codec.payloadType} ${fb.type} ${fb.parameter}\r\n")
                } else {
                    sb.append("a=rtcp-fb:${codec.payloadType} ${fb.type}\r\n")
                }
            }
        }

        // Request 20ms Opus frames
        if (kind == "audio") {
            sb.append("a=ptime:20\r\n")
        }

        // Use extension IDs from the offer (must match what local PC offered)
        val offerUris = (rtpParameters.headerExtensions ?: emptyList()).map { it.uri }.toSet()
        for (extLine in offerExtmapLines) {
            val parts = extLine.split(" ", limit = 2)
            if (parts.size == 2 && offerUris.contains(parts[1])) {
                sb.append("a=extmap:$extLine\r\n")
            }
        }

        if (extmapAllowMixed) {
            sb.append("a=extmap-allow-mixed\r\n")
        }

        return MediaSectionSdp(
            mid = mid,
            kind = kind,
            payloads = payloads,
            direction = direction,
            codecLines = sb.toString(),
            closed = false,
        )
    }

    private fun buildOfferMediaSection(
        mid: String,
        kind: String,
        rtpParameters: MsRtpParameters,
        streamId: String,
        trackId: String,
    ): MediaSectionSdp {
        val sb = StringBuilder()

        for (codec in rtpParameters.codecs) {
            val codecName = codecShortName(codec.mimeType)
            if (codec.channels != null && codec.channels > 1) {
                sb.append("a=rtpmap:${codec.payloadType} $codecName/${codec.clockRate}/${codec.channels}\r\n")
            } else {
                sb.append("a=rtpmap:${codec.payloadType} $codecName/${codec.clockRate}\r\n")
            }

            val fmtpStr = formatParams(codec.parameters)
            if (fmtpStr.isNotEmpty()) {
                sb.append("a=fmtp:${codec.payloadType} $fmtpStr\r\n")
            }

            for (fb in codec.rtcpFeedback ?: emptyList()) {
                if (!fb.parameter.isNullOrEmpty()) {
                    sb.append("a=rtcp-fb:${codec.payloadType} ${fb.type} ${fb.parameter}\r\n")
                } else {
                    sb.append("a=rtcp-fb:${codec.payloadType} ${fb.type}\r\n")
                }
            }
        }

        // Header extensions
        for (ext in rtpParameters.headerExtensions ?: emptyList()) {
            sb.append("a=extmap:${ext.id} ${ext.uri}\r\n")
        }

        sb.append("a=extmap-allow-mixed\r\n")

        // MSID
        sb.append("a=msid:$streamId $trackId\r\n")

        // SSRCs
        val encodings = rtpParameters.encodings ?: emptyList()
        if (encodings.isNotEmpty()) {
            val enc = encodings[0]
            enc.ssrc?.let { ssrc ->
                val cname = rtpParameters.rtcp?.cname ?: "mediasoup-client"
                sb.append("a=ssrc:$ssrc cname:$cname\r\n")
                sb.append("a=ssrc:$ssrc msid:$streamId $trackId\r\n")

                enc.rtx?.ssrc?.let { rtxSsrc ->
                    sb.append("a=ssrc:$rtxSsrc cname:$cname\r\n")
                    sb.append("a=ssrc:$rtxSsrc msid:$streamId $trackId\r\n")
                    sb.append("a=ssrc-group:FID $ssrc $rtxSsrc\r\n")
                }
            }
        }

        val payloads = rtpParameters.codecs.joinToString(" ") { it.payloadType.toString() }

        return MediaSectionSdp(
            mid = mid,
            kind = kind,
            payloads = payloads,
            direction = "sendonly",
            codecLines = sb.toString(),
            closed = false,
        )
    }

    private fun filterPayloads(offerPayloads: String, params: MsRtpParameters): String {
        val allowed = params.codecs.map { it.payloadType }.toSet()
        val filtered = offerPayloads.split(" ")
            .filter { (it.toIntOrNull() ?: -1) in allowed }
            .joinToString(" ")
        return filtered.ifEmpty { offerPayloads }
    }

    private fun codecShortName(mimeType: String): String {
        val parts = mimeType.split("/")
        return if (parts.size > 1) parts[1] else mimeType
    }

    private fun formatParams(params: Map<String, JsonValue>?): String {
        if (params.isNullOrEmpty()) return ""
        return params.entries.sortedBy { it.key }.joinToString(";") { (key, value) ->
            when (value) {
                is JsonValue.Int -> "$key=${value.v}"
                is JsonValue.Dbl -> "$key=${value.v}"
                is JsonValue.Str -> "$key=${value.v}"
                is JsonValue.Bool -> "$key=${if (value.v) 1 else 0}"
            }
        }
    }

    private data class MediaSectionSdp(
        val mid: String,
        val kind: String,
        val payloads: String,
        val direction: String,
        val codecLines: String,
        val closed: Boolean,
    )
}
