package com.rocs.sdk.mediasoup

import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// JSON value wrapper — handles mixed string/number codec parameters
// ---------------------------------------------------------------------------

sealed class JsonValue {
    data class Str(val v: String) : JsonValue()
    data class Int(val v: kotlin.Int) : JsonValue()
    data class Dbl(val v: Double) : JsonValue()
    data class Bool(val v: Boolean) : JsonValue()

    val intValue: kotlin.Int? get() = when (this) {
        is Int -> v
        is Dbl -> v.toInt()
        is Str -> v.toIntOrNull()
        is Bool -> if (v) 1 else 0
    }

    val stringValue: String? get() = when (this) {
        is Str -> v
        is Int -> v.toString()
        is Dbl -> v.toString()
        is Bool -> v.toString()
    }

    fun toAny(): Any = when (this) {
        is Str -> v
        is Int -> v
        is Dbl -> v
        is Bool -> v
    }

    companion object {
        fun from(value: Any?): JsonValue? = when (value) {
            null -> null
            is kotlin.Int -> Int(value)
            is Long -> Int(value.toInt())
            is Double -> Dbl(value)
            is Float -> Dbl(value.toDouble())
            is String -> Str(value)
            is Boolean -> Bool(value)
            is Number -> Int(value.toInt())
            else -> Str(value.toString())
        }
    }
}

// ---------------------------------------------------------------------------
// RTP Capabilities (from router / device)
// ---------------------------------------------------------------------------

data class RtpCodecCapability(
    var kind: String? = null,
    val mimeType: String,
    val preferredPayloadType: Int,
    val clockRate: Int,
    val channels: Int? = null,
    var parameters: Map<String, JsonValue>? = null,
    var rtcpFeedback: MutableList<RtcpFeedback>? = null,
)

data class RtpHeaderExtensionCapability(
    var kind: String? = null,
    val uri: String,
    val preferredId: Int,
    val preferredEncrypt: Boolean? = false,
    val direction: String? = "sendrecv",
)

data class RtpCapabilities(
    val codecs: List<RtpCodecCapability>? = null,
    val headerExtensions: List<RtpHeaderExtensionCapability>? = null,
)

// ---------------------------------------------------------------------------
// RTP Parameters (for produce / consume)
// ---------------------------------------------------------------------------

data class RtpCodecParameters(
    val mimeType: String,
    val payloadType: Int,
    val clockRate: Int,
    val channels: Int? = null,
    val parameters: Map<String, JsonValue>? = null,
    var rtcpFeedback: List<RtcpFeedback>? = null,
)

data class RtpHeaderExtensionParam(
    val uri: String,
    val id: Int,
    val encrypt: Boolean? = null,
)

data class RtpEncodingParam(
    val ssrc: Long? = null,
    val rid: String? = null,
    var rtx: RtxSsrc? = null,
)

data class RtxSsrc(val ssrc: Long)

data class RtcpParam(
    var cname: String? = null,
    val reducedSize: Boolean? = null,
)

data class RtcpFeedback(
    val type: String,
    val parameter: String? = null,
)

data class MsRtpParameters(
    var mid: String? = null,
    var codecs: List<RtpCodecParameters>,
    val headerExtensions: List<RtpHeaderExtensionParam>? = null,
    var encodings: List<RtpEncodingParam>? = null,
    var rtcp: RtcpParam? = null,
)

// ---------------------------------------------------------------------------
// Transport parameters (ICE / DTLS)
// ---------------------------------------------------------------------------

data class IceParametersJSON(
    val usernameFragment: String,
    val password: String,
    val iceLite: Boolean? = null,
)

data class IceCandidateJSON(
    val foundation: String,
    val priority: Long,
    val ip: String? = null,
    val address: String? = null,
    val port: Int,
    val type: String,
    val protocol: String,
    val tcpType: String? = null,
) {
    val effectiveIP: String get() = address ?: ip ?: "0.0.0.0"
}

data class DtlsParametersJSON(
    val fingerprints: List<FingerprintJSON>,
    var role: String? = null,
)

data class FingerprintJSON(
    val algorithm: String,
    val value: String,
)

// ---------------------------------------------------------------------------
// Extended RTP Capabilities (internal)
// ---------------------------------------------------------------------------

data class ExtendedRtpCodec(
    val kind: String,
    val mimeType: String,
    val clockRate: Int,
    val channels: Int? = null,
    val localPayloadType: Int,
    var localRtxPayloadType: Int? = null,
    val remotePayloadType: Int,
    var remoteRtxPayloadType: Int? = null,
    val localParameters: Map<String, JsonValue>? = null,
    val remoteParameters: Map<String, JsonValue>? = null,
    val rtcpFeedback: List<RtcpFeedback>,
)

data class ExtendedRtpHeaderExtension(
    val kind: String?,
    val uri: String,
    val sendId: Int,
    val recvId: Int,
    val encrypt: Boolean,
    val direction: String,
)

data class ExtendedRtpCapabilities(
    val codecs: MutableList<ExtendedRtpCodec> = mutableListOf(),
    val headerExtensions: MutableList<ExtendedRtpHeaderExtension> = mutableListOf(),
)

// ---------------------------------------------------------------------------
// JSON parsing helpers
// ---------------------------------------------------------------------------

internal object JsonHelpers {

    fun parseRtpCapabilities(json: String): RtpCapabilities {
        val obj = JSONObject(json)
        return RtpCapabilities(
            codecs = obj.optJSONArray("codecs")?.let { parseCodecCapabilities(it) },
            headerExtensions = obj.optJSONArray("headerExtensions")?.let { parseHeaderExtCaps(it) },
        )
    }

    fun parseIceParameters(json: String): IceParametersJSON {
        val o = JSONObject(json)
        return IceParametersJSON(
            usernameFragment = o.getString("usernameFragment"),
            password = o.getString("password"),
            iceLite = if (o.has("iceLite")) o.getBoolean("iceLite") else null,
        )
    }

    fun parseIceCandidates(json: String): List<IceCandidateJSON> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            IceCandidateJSON(
                foundation = o.getString("foundation"),
                priority = o.getLong("priority"),
                ip = o.optString("ip", null),
                address = o.optString("address", null),
                port = o.getInt("port"),
                type = o.getString("type"),
                protocol = o.getString("protocol"),
                tcpType = o.optString("tcpType", null),
            )
        }
    }

    fun parseDtlsParameters(json: String): DtlsParametersJSON {
        val o = JSONObject(json)
        val fps = o.optJSONArray("fingerprints") ?: JSONArray()
        return DtlsParametersJSON(
            fingerprints = (0 until fps.length()).map { i ->
                val fp = fps.getJSONObject(i)
                FingerprintJSON(fp.getString("algorithm"), fp.getString("value"))
            },
            role = o.optString("role", null),
        )
    }

    fun parseMsRtpParameters(json: String): MsRtpParameters {
        val o = JSONObject(json)
        return MsRtpParameters(
            mid = o.optString("mid", null),
            codecs = parseCodecParameters(o.optJSONArray("codecs") ?: JSONArray()),
            headerExtensions = o.optJSONArray("headerExtensions")?.let { parseHeaderExtParams(it) },
            encodings = o.optJSONArray("encodings")?.let { parseEncodings(it) },
            rtcp = o.optJSONObject("rtcp")?.let {
                RtcpParam(it.optString("cname", null), it.optBoolean("reducedSize", true))
            },
        )
    }

    fun stringifyMsRtpParameters(params: MsRtpParameters): String {
        val o = JSONObject()
        params.mid?.let { o.put("mid", it) }
        o.put("codecs", JSONArray().apply {
            for (c in params.codecs) put(codecParamToJson(c))
        })
        params.headerExtensions?.let { exts ->
            o.put("headerExtensions", JSONArray().apply {
                for (e in exts) put(JSONObject().apply {
                    put("uri", e.uri)
                    put("id", e.id)
                    e.encrypt?.let { put("encrypt", it) }
                })
            })
        }
        params.encodings?.let { encs ->
            o.put("encodings", JSONArray().apply {
                for (e in encs) put(JSONObject().apply {
                    e.ssrc?.let { put("ssrc", it) }
                    e.rid?.let { put("rid", it) }
                    e.rtx?.let { put("rtx", JSONObject().put("ssrc", it.ssrc)) }
                })
            })
        }
        params.rtcp?.let { rtcp ->
            o.put("rtcp", JSONObject().apply {
                rtcp.cname?.let { put("cname", it) }
                rtcp.reducedSize?.let { put("reducedSize", it) }
            })
        }
        return o.toString()
    }

    fun stringifyRtpCapabilities(caps: RtpCapabilities): String {
        val o = JSONObject()
        caps.codecs?.let { codecs ->
            o.put("codecs", JSONArray().apply {
                for (c in codecs) put(codecCapToJson(c))
            })
        }
        caps.headerExtensions?.let { exts ->
            o.put("headerExtensions", JSONArray().apply {
                for (e in exts) put(JSONObject().apply {
                    e.kind?.let { put("kind", it) }
                    put("uri", e.uri)
                    put("preferredId", e.preferredId)
                    put("preferredEncrypt", e.preferredEncrypt ?: false)
                    e.direction?.let { put("direction", it) }
                })
            })
        }
        return o.toString()
    }

    fun stringifyDtlsParameters(params: DtlsParametersJSON): String {
        val o = JSONObject()
        o.put("fingerprints", JSONArray().apply {
            for (fp in params.fingerprints)
                put(JSONObject().put("algorithm", fp.algorithm).put("value", fp.value))
        })
        params.role?.let { o.put("role", it) }
        return o.toString()
    }

    // --- private helpers ---

    private fun parseCodecCapabilities(arr: JSONArray): List<RtpCodecCapability> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RtpCodecCapability(
                kind = o.optString("kind", null),
                mimeType = o.getString("mimeType"),
                preferredPayloadType = o.getInt("preferredPayloadType"),
                clockRate = o.getInt("clockRate"),
                channels = if (o.has("channels")) o.getInt("channels") else null,
                parameters = parseParams(o.optJSONObject("parameters")),
                rtcpFeedback = parseRtcpFeedbacks(o.optJSONArray("rtcpFeedback")),
            )
        }

    private fun parseCodecParameters(arr: JSONArray): List<RtpCodecParameters> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RtpCodecParameters(
                mimeType = o.getString("mimeType"),
                payloadType = o.getInt("payloadType"),
                clockRate = o.getInt("clockRate"),
                channels = if (o.has("channels")) o.getInt("channels") else null,
                parameters = parseParams(o.optJSONObject("parameters")),
                rtcpFeedback = parseRtcpFeedbacks(o.optJSONArray("rtcpFeedback"))?.toList(),
            )
        }

    private fun parseHeaderExtCaps(arr: JSONArray): List<RtpHeaderExtensionCapability> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RtpHeaderExtensionCapability(
                kind = o.optString("kind", null),
                uri = o.getString("uri"),
                preferredId = o.getInt("preferredId"),
                preferredEncrypt = o.optBoolean("preferredEncrypt", false),
                direction = o.optString("direction", "sendrecv"),
            )
        }

    private fun parseHeaderExtParams(arr: JSONArray): List<RtpHeaderExtensionParam> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RtpHeaderExtensionParam(
                uri = o.getString("uri"),
                id = o.getInt("id"),
                encrypt = if (o.has("encrypt")) o.getBoolean("encrypt") else null,
            )
        }

    private fun parseEncodings(arr: JSONArray): List<RtpEncodingParam> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RtpEncodingParam(
                ssrc = if (o.has("ssrc")) o.getLong("ssrc") else null,
                rid = o.optString("rid", null),
                rtx = o.optJSONObject("rtx")?.let { RtxSsrc(it.getLong("ssrc")) },
            )
        }

    private fun parseParams(obj: JSONObject?): Map<String, JsonValue>? {
        obj ?: return null
        val map = mutableMapOf<String, JsonValue>()
        for (key in obj.keys()) {
            JsonValue.from(obj.get(key))?.let { map[key] = it }
        }
        return map
    }

    private fun parseRtcpFeedbacks(arr: JSONArray?): MutableList<RtcpFeedback>? {
        arr ?: return null
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RtcpFeedback(
                type = o.getString("type"),
                parameter = o.optString("parameter", null).takeIf { !it.isNullOrEmpty() },
            )
        }.toMutableList()
    }

    private fun codecCapToJson(c: RtpCodecCapability): JSONObject = JSONObject().apply {
        c.kind?.let { put("kind", it) }
        put("mimeType", c.mimeType)
        put("preferredPayloadType", c.preferredPayloadType)
        put("clockRate", c.clockRate)
        c.channels?.let { put("channels", it) }
        c.parameters?.let { p ->
            put("parameters", JSONObject().apply {
                for ((k, v) in p) put(k, v.toAny())
            })
        }
        c.rtcpFeedback?.let { fbs ->
            put("rtcpFeedback", JSONArray().apply {
                for (fb in fbs) put(JSONObject().apply {
                    put("type", fb.type)
                    put("parameter", fb.parameter ?: "")
                })
            })
        }
    }

    private fun codecParamToJson(c: RtpCodecParameters): JSONObject = JSONObject().apply {
        put("mimeType", c.mimeType)
        put("payloadType", c.payloadType)
        put("clockRate", c.clockRate)
        c.channels?.let { put("channels", it) }
        c.parameters?.let { p ->
            put("parameters", JSONObject().apply {
                for ((k, v) in p) put(k, v.toAny())
            })
        }
        c.rtcpFeedback?.let { fbs ->
            put("rtcpFeedback", JSONArray().apply {
                for (fb in fbs) put(JSONObject().apply {
                    put("type", fb.type)
                    put("parameter", fb.parameter ?: "")
                })
            })
        }
    }
}
