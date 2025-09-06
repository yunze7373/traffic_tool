package com.trafficcapture.mitm

import java.io.Serializable

data class MitmEvent(
    val type: Type,
    val direction: Direction,
    val host: String?,
    val method: String? = null,
    val url: String? = null,
    val statusCode: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val payloadPreview: String? = null,
    val rawPayload: ByteArray? = null,
    val protocol: String = "HTTP"
) : Serializable {
    enum class Type { REQUEST, RESPONSE, ERROR }
    enum class Direction { OUTBOUND, INBOUND }
}
