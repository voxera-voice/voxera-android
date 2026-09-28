package com.rocs.sdk.mediasoup

/**
 * Extracts structured data from SDP text.
 * Port of iOS mediasoup-client-swift SdpExtractor.swift.
 */
internal object SdpExtractor {

    /**
     * Extract native RTP capabilities from a local offer SDP.
     */
    fun extractRtpCapabilities(sdp: String): RtpCapabilities {
        val sections = splitMediaSections(sdp)
        val codecsMap = mutableMapOf<Int, RtpCodecCapability>()
        val headerExtMap = mutableMapOf<Int, RtpHeaderExtensionCapability>()

        for (section in sections) {
            val kind = mediaKind(section) ?: continue

            // Parse a=rtpmap lines
            for (line in lines(section, "a=rtpmap:")) {
                val parts = line.split(" ", limit = 2)
                if (parts.size != 2) continue
                val pt = parts[0].toIntOrNull() ?: 0
                val codecParts = parts[1].split("/")
                val codecName = codecParts[0]
                val clockRate = codecParts.getOrNull(1)?.toIntOrNull() ?: 0
                val channels = codecParts.getOrNull(2)?.toIntOrNull()

                codecsMap[pt] = RtpCodecCapability(
                    kind = kind,
                    mimeType = "$kind/$codecName",
                    preferredPayloadType = pt,
                    clockRate = clockRate,
                    channels = channels,
                    parameters = mutableMapOf(),
                    rtcpFeedback = mutableListOf(),
                )
            }

            // Parse a=fmtp lines
            for (line in lines(section, "a=fmtp:")) {
                val parts = line.split(" ", limit = 2)
                if (parts.size != 2) continue
                val pt = parts[0].toIntOrNull() ?: continue
                val params = parseFormatParams(parts[1])
                codecsMap[pt]?.parameters = params
            }

            // Parse a=rtcp-fb lines
            for (line in lines(section, "a=rtcp-fb:")) {
                val parts = line.split(" ", limit = 3)
                if (parts.isEmpty()) continue
                val fbType = parts.getOrNull(1) ?: ""
                val fbParam = parts.getOrNull(2)

                val feedback = RtcpFeedback(type = fbType, parameter = fbParam)

                if (parts[0] == "*") {
                    for (pt in codecsMap.keys) {
                        val codec = codecsMap[pt] ?: continue
                        if (codec.kind == kind && !isRtxCodec(codec.mimeType)) {
                            codec.rtcpFeedback?.add(feedback)
                        }
                    }
                } else {
                    val pt = parts[0].toIntOrNull()
                    pt?.let { codecsMap[it]?.rtcpFeedback?.add(feedback) }
                }
            }

            // Parse a=extmap lines
            for (line in lines(section, "a=extmap:")) {
                val parts = line.split(" ", limit = 2)
                if (parts.size != 2) continue
                val idStr = parts[0].split("/").firstOrNull() ?: parts[0]
                val id = idStr.toIntOrNull() ?: continue
                val uri = parts[1]

                val existing = headerExtMap[id]
                if (existing != null) {
                    if (existing.kind != kind) {
                        headerExtMap[id] = existing.copy(kind = null)
                    }
                } else {
                    headerExtMap[id] = RtpHeaderExtensionCapability(
                        kind = kind,
                        uri = uri,
                        preferredId = id,
                        preferredEncrypt = false,
                        direction = "sendrecv",
                    )
                }
            }
        }

        return RtpCapabilities(
            codecs = codecsMap.values.sortedBy { it.preferredPayloadType },
            headerExtensions = headerExtMap.values.sortedBy { it.preferredId },
        )
    }

    /**
     * Extract DTLS parameters from a local SDP.
     */
    fun extractDtlsParameters(sdp: String): DtlsParametersJSON {
        val normalized = sdp.replace("\r\n", "\n")
        var setup: String? = null
        var fingerprint: Pair<String, String>? = null

        for (line in normalized.split("\n")) {
            val l = line.trim()
            if (l.startsWith("a=setup:") && setup == null) {
                setup = l.removePrefix("a=setup:")
            }
            if (l.startsWith("a=fingerprint:") && fingerprint == null) {
                val parts = l.removePrefix("a=fingerprint:").split(" ", limit = 2)
                if (parts.size == 2) {
                    fingerprint = parts[0] to parts[1]
                }
            }
        }

        val role = when (setup) {
            "active" -> "client"
            "passive" -> "server"
            else -> "auto"
        }

        return DtlsParametersJSON(
            fingerprints = fingerprint?.let { listOf(FingerprintJSON(it.first, it.second)) } ?: emptyList(),
            role = role,
        )
    }

    /**
     * Extract CNAME from the first a=ssrc line with cname attribute.
     */
    fun extractCname(sdp: String): String {
        val normalized = sdp.replace("\r\n", "\n")
        for (line in normalized.split("\n")) {
            val l = line.trim()
            if (l.startsWith("a=ssrc:") && l.contains(" cname:")) {
                val idx = l.indexOf(" cname:")
                return l.substring(idx + " cname:".length)
            }
        }
        return ""
    }

    /**
     * Extract SSRCs and RTX SSRCs from a media section of the local offer SDP.
     */
    fun extractEncodings(sdp: String, mediaIndex: Int): List<Pair<Long, Long?>> {
        val sections = splitMediaSections(sdp)
        if (mediaIndex >= sections.size) return emptyList()
        val section = sections[mediaIndex]

        val ssrcs = mutableSetOf<Long>()
        val ssrcToRtx = mutableListOf<Pair<Long, Long?>>()

        // Collect all SSRCs
        for (line in lines(section, "a=ssrc:")) {
            line.split(" ").firstOrNull()?.toLongOrNull()?.let { ssrcs.add(it) }
        }

        // Check FID groups for RTX
        for (line in lines(section, "a=ssrc-group:FID ")) {
            val parts = line.split(" ")
            if (parts.size >= 2) {
                val ssrc = parts[0].toLongOrNull()
                val rtxSsrc = parts[1].toLongOrNull()
                if (ssrc != null && rtxSsrc != null) {
                    ssrcs.remove(ssrc)
                    ssrcs.remove(rtxSsrc)
                    ssrcToRtx.add(ssrc to rtxSsrc)
                }
            }
        }

        // Remaining SSRCs without RTX
        for (ssrc in ssrcs.sorted()) {
            ssrcToRtx.add(ssrc to null)
        }

        return ssrcToRtx
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

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

    private fun lines(section: String, prefix: String): List<String> {
        val result = mutableListOf<String>()
        for (line in section.split("\n")) {
            val l = line.trim()
            if (l.startsWith(prefix)) {
                result.add(l.removePrefix(prefix))
            }
        }
        return result
    }

    private fun parseFormatParams(str: String): Map<String, JsonValue> {
        val params = mutableMapOf<String, JsonValue>()
        for (part in str.split(";")) {
            val kv = part.split("=", limit = 2)
            val key = kv[0].trim()
            val value = if (kv.size > 1) kv[1].trim() else ""
            val intVal = value.toIntOrNull()
            params[key] = if (intVal != null) JsonValue.Int(intVal) else JsonValue.Str(value)
        }
        return params
    }

    private fun mediaKind(section: String): String? = when {
        section.startsWith("m=audio") -> "audio"
        section.startsWith("m=video") -> "video"
        else -> null
    }

    private fun isRtxCodec(mimeType: String): Boolean =
        mimeType.lowercase().endsWith("/rtx")
}
