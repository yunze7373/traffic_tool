package com.trafficcapture

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * [REBUILT] A more advanced parser that converts a raw byte buffer into a structured Packet object.
 */
object PacketParser {

    private const val IPV4_VERSION = 4
    private const val TCP_PROTOCOL = 6
    private const val UDP_PROTOCOL = 17

    fun parse(buffer: ByteBuffer): Packet? {
        if (buffer.remaining() < 20) return null

        buffer.mark()
        val versionAndHeaderLength = buffer.get().toInt() and 0xFF
        val version = versionAndHeaderLength shr 4
        if (version != IPV4_VERSION) {
            buffer.reset()
            return null
        }
        
        val headerLength = (versionAndHeaderLength and 0x0F) * 4
        val totalLength = buffer.getShort(2).toInt() and 0xFFFF
        val protocol = buffer.get(9).toInt() and 0xFF
        val sourceAddress = readIpAddress(buffer, 12)
        val destinationAddress = readIpAddress(buffer, 16)

        val ipHeader = IpHeader(version, headerLength, totalLength, protocol, sourceAddress, destinationAddress)

        buffer.position(headerLength)

        val transportHeader: TransportHeader? = when (protocol) {
            UDP_PROTOCOL -> parseUdpHeader(buffer)
            TCP_PROTOCOL -> parseTcpHeader(buffer)
            else -> null
        }
        
        buffer.reset() // Reset buffer to original position for the payload

        return if (transportHeader != null) {
            // Correctly calculate payload position and length
            val transportHeaderLength = if (transportHeader is UdpHeader) 8 else if (transportHeader is TcpHeader) 20 else 0
            val payloadPosition = ipHeader.headerLength + transportHeaderLength
            val payloadSize = ipHeader.totalLength - payloadPosition
            
            // Ensure payload size is not negative
            if (payloadSize >= 0) {
                val payload = ByteBuffer.wrap(buffer.array(), payloadPosition, payloadSize)
                Packet(ipHeader, transportHeader, payload)
            } else {
                null
            }
        } else {
            null
        }
    }

    private fun parseUdpHeader(buffer: ByteBuffer): UdpHeader? {
        if (buffer.remaining() < 8) return null
        val sourcePort = buffer.short.toInt() and 0xFFFF
        val destinationPort = buffer.short.toInt() and 0xFFFF
        val length = buffer.short.toInt() and 0xFFFF
        buffer.short // Checksum
        return UdpHeader(sourcePort, destinationPort, length)
    }

    private fun parseTcpHeader(buffer: ByteBuffer): TcpHeader? {
        if (buffer.remaining() < 20) return null
        val sourcePort = buffer.short.toInt() and 0xFFFF
        val destinationPort = buffer.short.toInt() and 0xFFFF
        val sequenceNumber = buffer.int.toLong() and 0xFFFFFFFFL
        val acknowledgmentNumber = buffer.int.toLong() and 0xFFFFFFFFL
        buffer.short // Header length and flags
        val flags = buffer.get().toInt() and 0xFF
        val windowSize = buffer.short.toInt() and 0xFFFF
        return TcpHeader(sourcePort, destinationPort, sequenceNumber, acknowledgmentNumber, flags, windowSize)
    }

    /**
     * [FIXED] Reads a 4-byte IP address from the buffer at a specific offset
     * in a way that is compatible with all Android API levels.
     */
    private fun readIpAddress(buffer: ByteBuffer, offset: Int): InetAddress {
        val address = ByteArray(4)
        // Save the buffer's current position before changing it
        val originalPosition = buffer.position()
        try {
            // Move to the specified offset to read the address
            buffer.position(offset)
            // Read the bytes into the 'address' array
            buffer.get(address)
        } finally {
            // Always restore the original position, even if an error occurs
            buffer.position(originalPosition)
        }
        return InetAddress.getByAddress(address)
    }
}
