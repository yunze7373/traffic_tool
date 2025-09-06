package com.trafficcapture.mitm

import android.util.Log
import javax.net.ssl.SSLSocket
import java.io.IOException
import java.nio.ByteBuffer

/**
 * TLS协议检测器：解析ClientHello，提取SNI和ALPN信息
 */
object TlsProtocolDetector {
    private const val TAG = "TlsProtocolDetector"
    
    data class TlsInfo(
        val isTls: Boolean = false,
        val version: String? = null,
        val sni: String? = null,
        val alpnProtocols: List<String> = emptyList()
    )
    
    /**
     * 从ClientHello字节数组中解析TLS信息
     */
    fun parseClientHello(data: ByteArray): TlsInfo {
        if (data.size < 43) return TlsInfo() // 最小ClientHello大小
        
        try {
            val buffer = ByteBuffer.wrap(data)
            
            // TLS Record Header (5 bytes)
            val contentType = buffer.get() // Should be 0x16 for handshake
            if (contentType.toInt() != 0x16) return TlsInfo()
            
            val versionMajor = buffer.get()
            val versionMinor = buffer.get()
            val version = when (versionMajor.toInt() shl 8 or versionMinor.toInt()) {
                0x0301 -> "TLS 1.0"
                0x0302 -> "TLS 1.1" 
                0x0303 -> "TLS 1.2"
                0x0304 -> "TLS 1.3"
                else -> "Unknown TLS"
            }
            
            val length = buffer.short.toInt()
            if (length > data.size - 5) return TlsInfo(isTls = true, version = version)
            
            // Handshake Header (4 bytes)
            val handshakeType = buffer.get() // Should be 0x01 for ClientHello
            if (handshakeType.toInt() != 0x01) return TlsInfo(isTls = true, version = version)
            
            val handshakeLength = (buffer.get().toInt() shl 16) or 
                                 (buffer.get().toInt() shl 8) or 
                                 buffer.get().toInt()
            
            // ClientHello fields
            val clientVersion = buffer.short
            
            // Random (32 bytes)
            buffer.position(buffer.position() + 32)
            
            // Session ID length + session ID
            val sessionIdLength = buffer.get().toInt() and 0xFF
            buffer.position(buffer.position() + sessionIdLength)
            
            // Cipher suites length + cipher suites
            val cipherSuitesLength = buffer.short.toInt() and 0xFFFF
            buffer.position(buffer.position() + cipherSuitesLength)
            
            // Compression methods length + compression methods
            val compressionMethodsLength = buffer.get().toInt() and 0xFF
            buffer.position(buffer.position() + compressionMethodsLength)
            
            // Extensions
            if (buffer.remaining() < 2) return TlsInfo(isTls = true, version = version)
            
            val extensionsLength = buffer.short.toInt() and 0xFFFF
            val extensionsEnd = buffer.position() + extensionsLength
            
            var sni: String? = null
            val alpnProtocols = mutableListOf<String>()
            
            while (buffer.position() < extensionsEnd && buffer.remaining() >= 4) {
                val extensionType = buffer.short.toInt() and 0xFFFF
                val extensionLength = buffer.short.toInt() and 0xFFFF
                val extensionStart = buffer.position()
                
                when (extensionType) {
                    0x0000 -> { // Server Name Indication (SNI)
                        if (extensionLength >= 5) {
                            val serverNameListLength = buffer.short.toInt() and 0xFFFF
                            if (buffer.remaining() >= serverNameListLength) {
                                val nameType = buffer.get() // Should be 0x00 for hostname
                                if (nameType.toInt() == 0x00 && buffer.remaining() >= 2) {
                                    val hostnameLength = buffer.short.toInt() and 0xFFFF
                                    if (buffer.remaining() >= hostnameLength) {
                                        val hostnameBytes = ByteArray(hostnameLength)
                                        buffer.get(hostnameBytes)
                                        sni = String(hostnameBytes, Charsets.UTF_8)
                                    }
                                }
                            }
                        }
                    }
                    0x0010 -> { // Application-Layer Protocol Negotiation (ALPN)
                        if (extensionLength >= 2) {
                            val alpnListLength = buffer.short.toInt() and 0xFFFF
                            val alpnEnd = buffer.position() + alpnListLength
                            while (buffer.position() < alpnEnd && buffer.remaining() >= 1) {
                                val protocolLength = buffer.get().toInt() and 0xFF
                                if (buffer.remaining() >= protocolLength) {
                                    val protocolBytes = ByteArray(protocolLength)
                                    buffer.get(protocolBytes)
                                    alpnProtocols.add(String(protocolBytes, Charsets.UTF_8))
                                }
                            }
                        }
                    }
                }
                
                // Skip to next extension
                buffer.position(extensionStart + extensionLength)
            }
            
            return TlsInfo(
                isTls = true,
                version = version,
                sni = sni,
                alpnProtocols = alpnProtocols
            )
            
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing ClientHello: ${e.message}")
            return TlsInfo(isTls = true) // 可能是TLS但解析失败
        }
    }
    
    /**
     * 从已建立的SSLSocket中获取协商的ALPN协议
     */
    fun getNegotiatedAlpn(sslSocket: SSLSocket): String? {
        return try {
            // 反射获取ALPN协议（Android 7.0+）
            val method = sslSocket.javaClass.getMethod("getApplicationProtocol")
            method.invoke(sslSocket) as? String
        } catch (e: Exception) {
            try {
                // 备选方法：通过session获取
                val session = sslSocket.session
                val field = session.javaClass.getDeclaredField("applicationProtocol")
                field.isAccessible = true
                field.get(session) as? String
            } catch (e2: Exception) {
                Log.d(TAG, "Cannot get ALPN: ${e2.message}")
                null
            }
        }
    }
    
    /**
     * 检测是否为证书Pinning错误
     */
    fun isPinningError(exception: Throwable): Boolean {
        val message = exception.message?.lowercase() ?: ""
        val className = exception.javaClass.simpleName.lowercase()
        
        return when {
            "certificate" in message && ("pin" in message || "trust" in message) -> true
            "sslpeerunverified" in className -> true
            "certificatepinning" in className -> true
            "trustanchor" in message -> true
            "path building failed" in message -> true
            else -> false
        }
    }
    
    /**
     * 检测QUIC协议
     */
    fun isQuicPacket(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        
        val firstByte = data[0].toInt() and 0xFF
        
        // QUIC第一个字节的特征：
        // - 长头包：0x80-0xFF
        // - 短头包：0x00-0x7F
        // Version字段存在时通常为长头包
        
        return when {
            // QUIC v1 (RFC 9000)
            data.size >= 4 && firstByte >= 0x80 -> {
                val version = ByteBuffer.wrap(data, 1, 4).int
                version == 0x00000001 || version == 0x00000000 // v1 or version negotiation
            }
            // Google QUIC的特征
            data.size >= 13 && (firstByte and 0x80) != 0 -> {
                val connectionIdLength = 8 // Google QUIC默认连接ID长度
                true // 简化检测，实际可以更精确
            }
            else -> false
        }
    }
    
    /**
     * 获取协议版本信息
     */
    fun getProtocolInfo(alpn: String?): String {
        return when (alpn?.lowercase()) {
            "h2" -> "HTTP/2"
            "h3", "h3-29", "h3-32" -> "HTTP/3"
            "http/1.1" -> "HTTP/1.1"
            "http/1.0" -> "HTTP/1.0"
            "spdy/3.1" -> "SPDY/3.1"
            else -> alpn ?: "Unknown"
        }
    }
}
