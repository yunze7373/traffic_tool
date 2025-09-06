package com.trafficcapture

import java.nio.ByteBuffer

/**
 * A simple utility object to parse basic information from an IP packet.
 */
object PacketParser {

    fun parse(packetData: ByteArray, length: Int): String? {
        if (length < 20) return null // IPv4 header is at least 20 bytes

        val buffer = ByteBuffer.wrap(packetData, 0, length)
        
        // Read the first byte to get version and header length
        val versionAndHeaderLength = buffer.get().toInt() and 0xFF
        val version = versionAndHeaderLength shr 4
        if (version != 4) return null // Only support IPv4

        // Protocol (TCP, UDP, ICMP, etc.)
        val protocol = buffer.get(9).toInt() and 0xFF

        // Source and Destination IP addresses
        val sourceIp = readIpAddress(buffer, 12)
        val destIp = readIpAddress(buffer, 16)

        // Ports for TCP/UDP
        var sourcePort = 0
        var destPort = 0
        if (protocol == 6 /* TCP */ || protocol == 17 /* UDP */) {
            if (buffer.limit() >= 24) {
                 // Header length in bytes
                val headerLength = (versionAndHeaderLength and 0x0F) * 4
                if(buffer.limit() >= headerLength + 4) {
                    sourcePort = buffer.getShort(headerLength).toInt() and 0xFFFF
                    destPort = buffer.getShort(headerLength + 2).toInt() and 0xFFFF
                }
            }
        }
        
        val protocolName = when (protocol) {
            1 -> "ICMP"
            6 -> "TCP"
            17 -> "UDP"
            else -> "Protocol($protocol)"
        }

        return "[$protocolName] $sourceIp:$sourcePort -> $destIp:$destPort, Size: $length bytes"
    }

    private fun readIpAddress(buffer: ByteBuffer, offset: Int): String {
        return "${buffer.get(offset).toInt() and 0xFF}." +
               "${buffer.get(offset + 1).toInt() and 0xFF}." +
               "${buffer.get(offset + 2).toInt() and 0xFF}." +
               "${buffer.get(offset + 3).toInt() and 0xFF}"
    }
}
