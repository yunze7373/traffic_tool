package com.trafficcapture

import android.util.Log
import java.nio.ByteBuffer

/**
 * IP数据包处理器，负责解析和重构IP数据包
 */
object IpPacketProcessor {
    private const val TAG = "IpPacketProcessor"
    
    data class IpPacket(
        val version: Int,
        val headerLength: Int,
        val protocol: Int,
        val sourceIp: String,
        val destIp: String,
        val sourcePort: Int,
        val destPort: Int,
        val payload: ByteArray,
        val originalPacket: ByteArray
    )

    /**
     * 解析IP数据包
     */
    fun parsePacket(data: ByteArray, length: Int): IpPacket? {
        if (length < 20) {
            Log.d(TAG, "Packet too short: $length bytes")
            return null
        }

        val buffer = ByteBuffer.wrap(data, 0, length)
        
        // 版本和头部长度
        val versionAndHeaderLength = buffer.get().toInt() and 0xFF
        val version = versionAndHeaderLength shr 4
        val headerLength = (versionAndHeaderLength and 0x0F) * 4
        
        Log.d(TAG, "Parsing packet: length=$length, version=$version, headerLength=$headerLength")
        
        if (version != 4) {
            Log.d(TAG, "Not IPv4 packet, version=$version")
            return null
        }
        
        // 跳过服务类型、总长度、标识、标志和片偏移
        buffer.position(buffer.position() + 7)
        
        // 协议
        val protocol = buffer.get().toInt() and 0xFF
        
        // 跳过头部校验和
        buffer.position(buffer.position() + 1)
        
        // 源IP和目标IP
        val sourceIp = readIpAddress(buffer)
        val destIp = readIpAddress(buffer)
        
        Log.d(TAG, "Packet: protocol=$protocol, $sourceIp -> $destIp")
        
        var sourcePort = 0
        var destPort = 0
        var payload = ByteArray(0)
        
        // 如果是TCP或UDP，解析端口
        if ((protocol == 6 || protocol == 17) && length > headerLength + 4) {
            buffer.position(headerLength)
            sourcePort = buffer.short.toInt() and 0xFFFF
            destPort = buffer.short.toInt() and 0xFFFF
            
            // 获取载荷数据
            val payloadStart = if (protocol == 6) {
                // TCP: 需要计算TCP头部长度
                val tcpHeaderLength = ((buffer.get(headerLength + 12).toInt() and 0xFF) shr 4) * 4
                headerLength + tcpHeaderLength
            } else {
                // UDP: 固定8字节头部
                headerLength + 8
            }
            
            if (payloadStart < length) {
                val payloadSize = length - payloadStart
                payload = ByteArray(payloadSize)
                System.arraycopy(data, payloadStart, payload, 0, payloadSize)
            }
        }
        
        return IpPacket(
            version = version,
            headerLength = headerLength,
            protocol = protocol,
            sourceIp = sourceIp,
            destIp = destIp,
            sourcePort = sourcePort,
            destPort = destPort,
            payload = payload,
            originalPacket = data.copyOf(length)
        ).also {
            Log.d(TAG, "Successfully parsed ${it.protocol} packet: ${it.sourceIp}:${it.sourcePort} -> ${it.destIp}:${it.destPort}, payload=${it.payload.size} bytes")
        }
    }

    /**
     * 构造响应IP数据包
     */
    fun createResponsePacket(originalPacket: IpPacket, responsePayload: ByteArray): ByteArray? {
        return try {
            when (originalPacket.protocol) {
                6 -> createTcpResponsePacket(originalPacket, responsePayload)
                17 -> createUdpResponsePacket(originalPacket, responsePayload)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating response packet", e)
            null
        }
    }

    private fun createTcpResponsePacket(originalPacket: IpPacket, responsePayload: ByteArray): ByteArray {
        val ipHeaderSize = 20
        val tcpHeaderSize = 20
        val totalSize = ipHeaderSize + tcpHeaderSize + responsePayload.size
        
        val packet = ByteBuffer.allocate(totalSize)
        
        // IP头部
        packet.put((0x45).toByte()) // 版本4, 头部长度5*4=20
        packet.put(0) // 服务类型
        packet.putShort(totalSize.toShort()) // 总长度
        packet.putShort(0) // 标识
        packet.putShort(0) // 标志和片偏移
        packet.put(64) // TTL
        packet.put(6) // 协议 TCP
        packet.putShort(0) // 头部校验和（暂时为0）
        
        // 交换源和目标IP
        putIpAddress(packet, originalPacket.destIp)
        putIpAddress(packet, originalPacket.sourceIp)
        
        // TCP头部
        packet.putShort(originalPacket.destPort.toShort()) // 源端口
        packet.putShort(originalPacket.sourcePort.toShort()) // 目标端口
        packet.putInt(0) // 序列号
        packet.putInt(0) // 确认号
        packet.put((0x50).toByte()) // 头部长度和标志
        packet.put(0x18) // 标志 PSH+ACK
        packet.putShort(8192) // 窗口大小
        packet.putShort(0) // 校验和（暂时为0）
        packet.putShort(0) // 紧急指针
        
        // 载荷
        packet.put(responsePayload)
        
        return packet.array()
    }

    private fun createUdpResponsePacket(originalPacket: IpPacket, responsePayload: ByteArray): ByteArray {
        val ipHeaderSize = 20
        val udpHeaderSize = 8
        val totalSize = ipHeaderSize + udpHeaderSize + responsePayload.size
        
        val packet = ByteBuffer.allocate(totalSize)
        
        // IP头部
        packet.put((0x45).toByte()) // 版本4, 头部长度5*4=20
        packet.put(0) // 服务类型
        packet.putShort(totalSize.toShort()) // 总长度
        packet.putShort(0) // 标识
        packet.putShort(0) // 标志和片偏移
        packet.put(64) // TTL
        packet.put(17) // 协议 UDP
        packet.putShort(0) // 头部校验和（暂时为0）
        
        // 交换源和目标IP
        putIpAddress(packet, originalPacket.destIp)
        putIpAddress(packet, originalPacket.sourceIp)
        
        // UDP头部
        packet.putShort(originalPacket.destPort.toShort()) // 源端口
        packet.putShort(originalPacket.sourcePort.toShort()) // 目标端口
        packet.putShort((udpHeaderSize + responsePayload.size).toShort()) // UDP长度
        packet.putShort(0) // 校验和（暂时为0）
        
        // 载荷
        packet.put(responsePayload)
        
        return packet.array()
    }

    private fun readIpAddress(buffer: ByteBuffer): String {
        return "${buffer.get().toInt() and 0xFF}." +
               "${buffer.get().toInt() and 0xFF}." +
               "${buffer.get().toInt() and 0xFF}." +
               "${buffer.get().toInt() and 0xFF}"
    }

    private fun putIpAddress(buffer: ByteBuffer, ip: String) {
        val parts = ip.split(".")
        parts.forEach { part ->
            buffer.put(part.toInt().toByte())
        }
    }
}
