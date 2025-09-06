package com.trafficcapture.mitm

import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.*

sealed class MitmEvent : Serializable {
    abstract val timestamp: Long
    abstract val hostname: String?
    
    data class HttpsPlaintext(
        override val timestamp: Long = System.currentTimeMillis(),
        override val hostname: String?,
        val sni: String? = null,
        val alpn: String? = null,
        val requestMethod: String,
        val requestUrl: String,
        val requestHeaders: Map<String, String>,
        val requestBody: String,
        val responseStatus: String,
        val responseHeaders: Map<String, String>,
        val responseBody: String,
        val requestSize: Int = 0,
        val responseSize: Int = 0,
        val transactionId: Int = 0
    ) : MitmEvent()
    
    data class PinningDetected(
        override val timestamp: Long = System.currentTimeMillis(),
        override val hostname: String,
        val errorMessage: String,
        val certificateIssuer: String? = null
    ) : MitmEvent()
    
    data class ProtocolDetected(
        override val timestamp: Long = System.currentTimeMillis(),
        override val hostname: String,
        val protocol: String, // "HTTP/2", "QUIC", "HTTP/3"
        val alpnNegotiated: String? = null,
        val version: String? = null
    ) : MitmEvent()
    
    data class Error(
        override val timestamp: Long = System.currentTimeMillis(),
        override val hostname: String? = null,
        val message: String,
        val errorType: String = "GENERAL"
    ) : MitmEvent()
    
    // 兼容旧版本
    @Deprecated("Use specific event types")
    data class Legacy(
        override val timestamp: Long = System.currentTimeMillis(),
        val type: Type,
        val direction: Direction,
        override val hostname: String?,
        val method: String? = null,
        val url: String? = null,
        val statusCode: Int? = null,
        val headers: Map<String, String> = emptyMap(),
        val payloadPreview: String? = null,
        val rawPayload: ByteArray? = null,
        val protocol: String = "HTTP"
    ) : MitmEvent()
    
    @Deprecated("Use specific event types")
    enum class Type { REQUEST, RESPONSE, ERROR }
    
    @Deprecated("Use specific event types") 
    enum class Direction { OUTBOUND, INBOUND }
    
    fun getDisplayText(): String {
        val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val timeStr = timeFormat.format(Date(timestamp))
        
        return when (this) {
            is HttpsPlaintext -> {
                val alpnStr = alpn?.let { " [$it]" } ?: ""
                val sniStr = if (sni != hostname) " (SNI: $sni)" else ""
                "$timeStr $requestMethod $requestUrl$alpnStr -> $responseStatus$sniStr"
            }
            is PinningDetected -> {
                "$timeStr [PINNING] $hostname - $errorMessage"
            }
            is ProtocolDetected -> {
                val versionStr = version?.let { " v$it" } ?: ""
                val alpnStr = alpnNegotiated?.let { " ALPN: $it" } ?: ""
                "$timeStr [PROTOCOL] $hostname - $protocol$versionStr$alpnStr"
            }
            is Error -> {
                val hostStr = hostname?.let { " [$it]" } ?: ""
                "$timeStr [ERROR]$hostStr $message"
            }
            is Legacy -> {
                val dirStr = when (direction) {
                    Direction.INBOUND -> "←"
                    Direction.OUTBOUND -> "→"
                }
                return when (type) {
                    Type.REQUEST -> "$timeStr $dirStr $method $url"
                    Type.RESPONSE -> "$timeStr $dirStr $statusCode"
                    Type.ERROR -> "$timeStr [ERROR] $payloadPreview"
                }
            }
        }
    }
}
