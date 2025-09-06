package com.trafficcapture

import java.nio.ByteBuffer

/**
 * Utility to construct IP packets to be written back to the VPN interface.
 */
object PacketBuilder {

    fun buildTcpResponse(originalPacket: Packet, payload: ByteBuffer, payloadSize: Int): ByteBuffer {
        // This is a simplified builder. A real implementation would need to handle
        // TCP sequence/acknowledgment numbers, flags, etc.
        // For now, we are just swapping source and destination and wrapping the payload.

        val originalIpHeader = originalPacket.ipHeader
        val originalTcpHeader = originalPacket.transportHeader as TcpHeader

        val totalLength = 20 + 20 + payloadSize // IPv4 header + TCP header + payload
        val buffer = ByteBuffer.allocate(totalLength)

        // IP Header
        buffer.put((4 shl 4 or 5).toByte()) // Version 4, Header length 5 words (20 bytes)
        buffer.put(0.toByte()) // Type of service
        buffer.putShort(totalLength.toShort()) // Total length
        buffer.putShort(0) // Identification
        buffer.putShort(0) // Flags and Fragment Offset
        buffer.put(64.toByte()) // TTL
        buffer.put(6.toByte()) // Protocol (TCP)
        buffer.putShort(0) // Header checksum (kernel will fill it)
        buffer.put(originalIpHeader.destinationAddress.address) // Source address (was original destination)
        buffer.put(originalIpHeader.sourceAddress.address) // Destination address (was original source)

        // TCP Header
        buffer.putShort(originalTcpHeader.destinationPort.toShort()) // Source port
        buffer.putShort(originalTcpHeader.sourcePort.toShort()) // Destination port
        buffer.putInt(0) // Sequence number (dummy)
        buffer.putInt(0) // Acknowledgment number (dummy)
        buffer.putShort((5 shl 12).toShort()) // Header length 5 words (20 bytes) + flags
        buffer.putShort(0) // Window size
        buffer.putShort(0) // Checksum (kernel will fill it)
        buffer.putShort(0) // Urgent pointer

        // Payload
        buffer.put(payload)

        buffer.flip()
        return buffer
    }

    fun buildUdpResponse(originalPacket: Packet, payload: ByteBuffer, payloadSize: Int): ByteBuffer {
        val originalIpHeader = originalPacket.ipHeader
        val originalUdpHeader = originalPacket.transportHeader as UdpHeader

        val totalLength = 20 + 8 + payloadSize // IPv4 header + UDP header + payload
        val buffer = ByteBuffer.allocate(totalLength)

        // IP Header
        buffer.put((4 shl 4 or 5).toByte())
        buffer.put(0.toByte())
        buffer.putShort(totalLength.toShort())
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.put(64.toByte())
        buffer.put(17.toByte()) // Protocol (UDP)
        buffer.putShort(0)
        buffer.put(originalIpHeader.destinationAddress.address)
        buffer.put(originalIpHeader.sourceAddress.address)

        // UDP Header
        buffer.putShort(originalUdpHeader.destinationPort.toShort()) // Source port
        buffer.putShort(originalUdpHeader.sourcePort.toShort()) // Destination port
        buffer.putShort((8 + payloadSize).toShort()) // Length
        buffer.putShort(0) // Checksum

        // Payload
        buffer.put(payload)

        buffer.flip()
        return buffer
    }
}
