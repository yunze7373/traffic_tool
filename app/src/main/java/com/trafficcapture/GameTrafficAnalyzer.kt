package com.trafficcapture

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * 游戏流量分析器
 * 专门用于分析和解码游戏应用的网络流量
 */
class GameTrafficAnalyzer {
    
    companion object {
        private const val TAG = "GameTrafficAnalyzer"
    }
    
    // 游戏服务器连接信息缓存
    private val gameConnections = ConcurrentHashMap<String, GameConnection>()
    
    // 已知游戏协议特征
    private val knownGamePatterns = mapOf(
        "Unity游戏" to byteArrayOf(0x00, 0x01, 0x02, 0x03), // Unity网络特征
        "Unreal游戏" to byteArrayOf(0x7F.toByte(), 0x7E.toByte(), 0x7D.toByte(), 0x7C.toByte()), // Unreal网络特征
        "王者荣耀" to byteArrayOf(0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()), // 示例特征
        "和平精英" to byteArrayOf(0x12, 0x34, 0x56, 0x78), // 示例特征
        "原神" to byteArrayOf(0x4B.toByte(), 0x4D.toByte(), 0x4F.toByte(), 0x01), // 示例特征
    )
    
    /**
     * 分析游戏数据包
     */
    fun analyzeGamePacket(packet: ByteArray, length: Int, sourceIp: String, destIp: String, protocol: Int): GamePacketInfo? {
        try {
            // 检查是否是游戏流量
            val gameType = detectGameType(packet, length, destIp)
            if (gameType != null) {
                return parseGamePacket(packet, length, sourceIp, destIp, protocol, gameType)
            }
            
            // 检查是否是游戏服务器通信
            if (isGameServerCommunication(destIp, protocol)) {
                return analyzeGameServerPacket(packet, length, sourceIp, destIp, protocol)
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing game packet", e)
            return null
        }
    }
    
    /**
     * 检测游戏类型
     */
    private fun detectGameType(packet: ByteArray, length: Int, destIp: String): String? {
        // 1. 通过数据包特征检测
        for ((gameName, pattern) in knownGamePatterns) {
            if (length >= pattern.size && packet.take(pattern.size).toByteArray().contentEquals(pattern)) {
                Log.d(TAG, "Detected game: $gameName by packet pattern")
                return gameName
            }
        }
        
        // 2. 通过目标IP地址检测（已知游戏服务器IP段）
        val gameByIp = detectGameByServerIp(destIp)
        if (gameByIp != null) {
            Log.d(TAG, "Detected game: $gameByIp by server IP")
            return gameByIp
        }
        
        // 3. 通过端口和协议特征检测
        return detectGameByNetworkBehavior(packet, length, destIp)
    }
    
    /**
     * 通过服务器IP检测游戏
     */
    private fun detectGameByServerIp(ip: String): String? {
        return when {
            ip.startsWith("203.205.") -> "腾讯游戏服务器"
            ip.startsWith("42.186.") -> "网易游戏服务器"  
            ip.startsWith("47.254.") -> "阿里云游戏服务器"
            ip.startsWith("52.") || ip.startsWith("54.") -> "AWS游戏服务器"
            ip.startsWith("35.") -> "Google Cloud游戏服务器"
            else -> null
        }
    }
    
    /**
     * 通过网络行为检测游戏
     */
    private fun detectGameByNetworkBehavior(packet: ByteArray, length: Int, destIp: String): String? {
        // 分析数据包大小和频率模式
        if (length in 50..200) {
            return "可能是实时游戏心跳包"
        }
        
        if (length in 500..2000) {
            return "可能是游戏状态同步"
        }
        
        if (length > 5000) {
            return "可能是游戏资源下载"
        }
        
        return null
    }
    
    /**
     * 检查是否是游戏服务器通信
     */
    private fun isGameServerCommunication(destIp: String, protocol: Int): Boolean {
        // 常见游戏端口
        val commonGamePorts = listOf(7777, 8080, 9001, 10001, 12345, 54321)
        
        // 检查是否是实时游戏协议（通常使用UDP）
        if (protocol == 17) { // UDP
            return true // 大多数实时游戏使用UDP
        }
        
        // 检查是否是已知游戏服务器IP
        return detectGameByServerIp(destIp) != null
    }
    
    /**
     * 解析游戏数据包
     */
    private fun parseGamePacket(
        packet: ByteArray, 
        length: Int, 
        sourceIp: String, 
        destIp: String, 
        protocol: Int, 
        gameType: String
    ): GamePacketInfo {
        val buffer = ByteBuffer.wrap(packet, 0, length)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        return when (gameType) {
            "Unity游戏" -> parseUnityPacket(buffer, sourceIp, destIp)
            "王者荣耀" -> parseHonorOfKingsPacket(buffer, sourceIp, destIp)
            "和平精英" -> parsePUBGMobilePacket(buffer, sourceIp, destIp)
            "原神" -> parseGenshinImpactPacket(buffer, sourceIp, destIp)
            else -> parseGenericGamePacket(buffer, sourceIp, destIp, gameType)
        }
    }
    
    /**
     * 解析Unity游戏数据包
     */
    private fun parseUnityPacket(buffer: ByteBuffer, sourceIp: String, destIp: String): GamePacketInfo {
        try {
            // Unity网络包通常有固定的头部结构
            val messageType = buffer.get().toInt()
            val sequenceNumber = buffer.getInt()
            val payloadLength = buffer.getShort().toInt()
            
            return GamePacketInfo(
                gameType = "Unity游戏",
                messageType = messageType,
                sequenceNumber = sequenceNumber,
                payloadSize = payloadLength,
                sourceIp = sourceIp,
                destIp = destIp,
                timestamp = System.currentTimeMillis(),
                rawData = buffer.array().copyOfRange(buffer.position(), buffer.limit()),
                decodedInfo = mapOf(
                    "MessageType" to when(messageType) {
                        1 -> "Player Movement"
                        2 -> "Game State Update"
                        3 -> "Chat Message"
                        4 -> "Heartbeat"
                        else -> "Unknown($messageType)"
                    }
                )
            )
        } catch (e: Exception) {
            return createGenericGamePacketInfo(sourceIp, destIp, "Unity游戏", buffer.array())
        }
    }
    
    /**
     * 解析王者荣耀数据包（示例）
     */
    private fun parseHonorOfKingsPacket(buffer: ByteBuffer, sourceIp: String, destIp: String): GamePacketInfo {
        return try {
            // 腾讯游戏通常使用自定义的协议格式
            val packetId = buffer.getShort().toInt()
            val userId = buffer.getInt()
            val actionType = buffer.get().toInt()
            
            GamePacketInfo(
                gameType = "王者荣耀",
                messageType = actionType,
                sequenceNumber = packetId,
                payloadSize = buffer.remaining(),
                sourceIp = sourceIp,
                destIp = destIp,
                timestamp = System.currentTimeMillis(),
                rawData = buffer.array(),
                decodedInfo = mapOf(
                    "UserId" to userId.toString(),
                    "Action" to when(actionType) {
                        0x01 -> "英雄移动"
                        0x02 -> "技能释放"
                        0x03 -> "购买装备"
                        0x04 -> "聊天消息"
                        else -> "未知动作($actionType)"
                    }
                )
            )
        } catch (e: Exception) {
            createGenericGamePacketInfo(sourceIp, destIp, "王者荣耀", buffer.array())
        }
    }
    
    /**
     * 解析和平精英数据包（示例）
     */
    private fun parsePUBGMobilePacket(buffer: ByteBuffer, sourceIp: String, destIp: String): GamePacketInfo {
        return try {
            val commandId = buffer.getInt()
            val playerId = buffer.getLong()
            
            GamePacketInfo(
                gameType = "和平精英",
                messageType = commandId,
                sequenceNumber = 0,
                payloadSize = buffer.remaining(),
                sourceIp = sourceIp,
                destIp = destIp,
                timestamp = System.currentTimeMillis(),
                rawData = buffer.array(),
                decodedInfo = mapOf(
                    "PlayerId" to playerId.toString(),
                    "Command" to when(commandId) {
                        1001 -> "玩家位置更新"
                        1002 -> "开火射击"
                        1003 -> "物品拾取"
                        1004 -> "语音通话"
                        else -> "未知指令($commandId)"
                    }
                )
            )
        } catch (e: Exception) {
            createGenericGamePacketInfo(sourceIp, destIp, "和平精英", buffer.array())
        }
    }
    
    /**
     * 解析原神数据包（示例）
     */
    private fun parseGenshinImpactPacket(buffer: ByteBuffer, sourceIp: String, destIp: String): GamePacketInfo {
        return try {
            val opcode = buffer.getShort().toInt()
            val uid = buffer.getInt()
            
            GamePacketInfo(
                gameType = "原神",
                messageType = opcode,
                sequenceNumber = 0,
                payloadSize = buffer.remaining(),
                sourceIp = sourceIp,
                destIp = destIp,
                timestamp = System.currentTimeMillis(),
                rawData = buffer.array(),
                decodedInfo = mapOf(
                    "UID" to uid.toString(),
                    "Operation" to when(opcode) {
                        101 -> "角色移动"
                        102 -> "元素战技"
                        103 -> "宝箱开启"
                        104 -> "多人匹配"
                        else -> "未知操作($opcode)"
                    }
                )
            )
        } catch (e: Exception) {
            createGenericGamePacketInfo(sourceIp, destIp, "原神", buffer.array())
        }
    }
    
    /**
     * 解析通用游戏数据包
     */
    private fun parseGenericGamePacket(buffer: ByteBuffer, sourceIp: String, destIp: String, gameType: String): GamePacketInfo {
        return GamePacketInfo(
            gameType = gameType,
            messageType = 0,
            sequenceNumber = 0,
            payloadSize = buffer.remaining(),
            sourceIp = sourceIp,
            destIp = destIp,
            timestamp = System.currentTimeMillis(),
            rawData = buffer.array(),
            decodedInfo = mapOf(
                "PacketSize" to "${buffer.remaining()} bytes",
                "HexDump" to buffer.array().take(32).joinToString(" ") { "%02X".format(it) }
            )
        )
    }
    
    /**
     * 分析游戏服务器数据包
     */
    private fun analyzeGameServerPacket(
        packet: ByteArray, 
        length: Int, 
        sourceIp: String, 
        destIp: String, 
        protocol: Int
    ): GamePacketInfo {
        val protocolName = when(protocol) {
            6 -> "TCP"
            17 -> "UDP"
            else -> "Protocol($protocol)"
        }
        
        return GamePacketInfo(
            gameType = "游戏服务器通信",
            messageType = protocol,
            sequenceNumber = 0,
            payloadSize = length,
            sourceIp = sourceIp,
            destIp = destIp,
            timestamp = System.currentTimeMillis(),
            rawData = packet.copyOf(length),
            decodedInfo = mapOf(
                "Protocol" to protocolName,
                "Direction" to if (sourceIp.startsWith("192.168.") || sourceIp.startsWith("10.")) "Outgoing" else "Incoming",
                "ServerType" to (detectGameByServerIp(destIp) ?: "Unknown Game Server"),
                "DataSize" to "$length bytes"
            )
        )
    }
    
    private fun createGenericGamePacketInfo(sourceIp: String, destIp: String, gameType: String, data: ByteArray): GamePacketInfo {
        return GamePacketInfo(
            gameType = gameType,
            messageType = 0,
            sequenceNumber = 0,
            payloadSize = data.size,
            sourceIp = sourceIp,
            destIp = destIp,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            decodedInfo = mapOf("Status" to "Parsing failed, showing raw data")
        )
    }
}

/**
 * 游戏数据包信息
 */
data class GamePacketInfo(
    val gameType: String,
    val messageType: Int,
    val sequenceNumber: Int,
    val payloadSize: Int,
    val sourceIp: String,
    val destIp: String,
    val timestamp: Long,
    val rawData: ByteArray,
    val decodedInfo: Map<String, String>
)

/**
 * 游戏连接信息
 */
data class GameConnection(
    val gameType: String,
    val serverIp: String,
    val serverPort: Int,
    val protocol: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val packetCount: Int
)
