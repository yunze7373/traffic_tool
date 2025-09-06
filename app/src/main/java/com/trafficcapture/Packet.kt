package com.trafficcapture

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Represents a parsed IP packet with its header and payload.
 */
data class Packet(
    val ipHeader: IpHeader,
    val transportHeader: TransportHeader,
    val payload: ByteBuffer
) {
    val isUdp: Boolean get() = transportHeader is UdpHeader
    val isTcp: Boolean get() = transportHeader is TcpHeader
}

/**
 * Base class for different transport layer headers (TCP, UDP).
 */sealed class TransportHeader {
    abstract val sourcePort: Int
    abstract val destinationPort: Int
}

/**
 * Represents a TCP header.
 */
data class TcpHeader(
    override val sourcePort: Int,
    override val destinationPort: Int,
    val sequenceNumber: Long,
    val acknowledgmentNumber: Long,
    val flags: Int,
    val windowSize: Int
) : TransportHeader()

/**
 * Represents a UDP header.
 */
data class UdpHeader(
    override val sourcePort: Int,
    override val destinationPort: Int,
    val length: Int
) : TransportHeader()

/**
 * Represents an IPv4 header.
 */
data class IpHeader(
    val version: Int,
    val headerLength: Int,
    val totalLength: Int,
    val protocol: Int,
    val sourceAddress: InetAddress,
    val destinationAddress: InetAddress
)
