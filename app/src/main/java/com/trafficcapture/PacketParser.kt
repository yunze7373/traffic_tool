package com.trafficcapture

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * 增强的数据包解析器，能够解析详细的包信息和应用信息
 */
object PacketParser {
    private const val TAG = "PacketParser"
    
    fun parse(packetData: ByteArray, length: Int, context: Context? = null): PacketInfo? {
        if (length < 20) return null // IPv4 header is at least 20 bytes

        val buffer = ByteBuffer.wrap(packetData, 0, length)
        
        // Read the first byte to get version and header length
        val versionAndHeaderLength = buffer.get().toInt() and 0xFF
        val version = versionAndHeaderLength shr 4
        if (version != 4) return null // Only support IPv4

        // Header length in bytes
        val headerLength = (versionAndHeaderLength and 0x0F) * 4
        
        // Protocol (TCP, UDP, ICMP, etc.)
        val protocol = buffer.get(9).toInt() and 0xFF

        // Source and Destination IP addresses
        val sourceIp = readIpAddress(buffer, 12)
        val destIp = readIpAddress(buffer, 16)

        // Determine direction (简单判断：本地地址为出站，否则为入站)
        val direction = if (isLocalAddress(sourceIp)) {
            PacketInfo.Direction.OUTBOUND
        } else {
            PacketInfo.Direction.INBOUND
        }

        // Ports for TCP/UDP
        var sourcePort = 0
        var destPort = 0
        var httpInfo: PacketInfo.HttpInfo? = null
        var payload: ByteArray? = null
        
        if (protocol == 6 /* TCP */ || protocol == 17 /* UDP */) {
            if (length >= headerLength + 4) {
                try {
                    sourcePort = buffer.getShort(headerLength).toInt() and 0xFFFF
                    destPort = buffer.getShort(headerLength + 2).toInt() and 0xFFFF
                } catch (e: Exception) {
                    Log.d(TAG, "Port parse error: ${e.message}")
                }

                if (protocol == 6 && length >= headerLength + 20) { // 至少存在最小TCP头
                    val tcpHeaderLength = ((buffer.get(headerLength + 12).toInt() and 0xF0) shr 4) * 4
                    val dataOffset = headerLength + tcpHeaderLength
                    if (dataOffset in 0 until length && dataOffset < length) {
                        val payloadSize = length - dataOffset
                        if (payloadSize > 0) {
                            payload = ByteArray(payloadSize)
                            System.arraycopy(packetData, dataOffset, payload, 0, payloadSize)
                            // 尝试HTTP探测: 根据首行特征而不仅仅是端口
                            if (looksLikeHttp(payload)) {
                                httpInfo = parseHttpContent(payload)
                            }
                        }
                    }
                } else if (protocol == 17) { // UDP可根据端口判断是否DNS/QUIC等
                    val udpHeaderLen = 8
                    val dataOffset = headerLength + udpHeaderLen
                    if (dataOffset < length) {
                        val payloadSize = length - dataOffset
                        if (payloadSize > 0 && payloadSize <= 4096) { // 限制大小
                            payload = ByteArray(payloadSize)
                            System.arraycopy(packetData, dataOffset, payload, 0, payloadSize)
                        }
                    }
                }
            }
        }
        
        val protocolName = when (protocol) {
            1 -> "ICMP"
            6 -> "TCP"
            17 -> "UDP"
            else -> "Protocol($protocol)"
        }

        // 尝试获取应用信息（简化版本，实际需要更复杂的实现）
        val appInfo = getAppInfo(sourcePort, destPort, context)

        return PacketInfo(
            protocol = protocolName,
            sourceIp = sourceIp,
            sourcePort = sourcePort,
            destIp = destIp,
            destPort = destPort,
            size = length,
            appPackage = appInfo.first,
            appName = appInfo.second,
            direction = direction,
            payload = payload,
            httpInfo = httpInfo
        )
    }

    private fun readIpAddress(buffer: ByteBuffer, offset: Int): String {
        return "${buffer.get(offset).toInt() and 0xFF}." +
               "${buffer.get(offset + 1).toInt() and 0xFF}." +
               "${buffer.get(offset + 2).toInt() and 0xFF}." +
               "${buffer.get(offset + 3).toInt() and 0xFF}"
    }
    
    private fun isLocalAddress(ip: String): Boolean {
        return ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172.")
    }
    
    private fun parseHttpContent(payload: ByteArray): PacketInfo.HttpInfo? {
        try {
            val content = String(payload, StandardCharsets.UTF_8)
            val lines = content.split("\r\n", "\n")
            if (lines.isEmpty()) return null
            
            val firstLine = lines[0]
            val headers = mutableMapOf<String, String>()
            
            // 解析头部
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) break
                
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[key] = value
                }
            }
            
            // 判断是请求还是响应
            return if (firstLine.contains("HTTP/")) {
                // HTTP响应
                val parts = firstLine.split(" ", limit = 3)
                val statusCode = if (parts.size >= 2) parts[1].toIntOrNull() else null
                PacketInfo.HttpInfo(
                    statusCode = statusCode,
                    headers = headers,
                    contentType = headers["Content-Type"]
                )
            } else {
                // HTTP请求
                val parts = firstLine.split(" ", limit = 3)
                val method = if (parts.isNotEmpty()) parts[0] else null
                val url = if (parts.size >= 2) parts[1] else null
                PacketInfo.HttpInfo(
                    method = method,
                    url = url,
                    headers = headers,
                    contentType = headers["Content-Type"]
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse HTTP content: ${e.message}")
            return null
        }
    }

    private fun looksLikeHttp(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val prefix = String(payload, 0, minOf(payload.size, 16), StandardCharsets.US_ASCII)
        // 常见HTTP方法 或 HTTP响应头
        return prefix.startsWith("GET ") || prefix.startsWith("POST ") || prefix.startsWith("HTTP/") ||
               prefix.startsWith("PUT ") || prefix.startsWith("HEAD ") || prefix.startsWith("OPTIONS ") ||
               prefix.startsWith("DELETE ") || prefix.startsWith("TRACE ") || prefix.startsWith("PATCH ")
    }
    
    private fun getAppInfo(sourcePort: Int, destPort: Int, context: Context?): Pair<String?, String?> {
        // 这是一个简化的实现
        // 实际上需要通过端口和连接信息来确定应用
        // 在Android中，这需要root权限或其他复杂的方法
        return when {
            sourcePort == 80 || destPort == 80 -> "browser" to "浏览器"
            sourcePort == 443 || destPort == 443 -> "https" to "HTTPS应用"
            sourcePort == 53 || destPort == 53 -> "dns" to "DNS查询"
            else -> null to null
        }
    }
}
