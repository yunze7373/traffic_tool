package com.trafficcapture

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络包转发器，负责实际的网络流量转发
 */
class NetworkForwarder {
    companion object {
        private const val TAG = "NetworkForwarder"
    }

    private val tcpConnections = ConcurrentHashMap<String, SocketChannel>()
    private val udpConnections = ConcurrentHashMap<String, DatagramChannel>()
    private val selector = Selector.open()
    private var isRunning = false
    private var cleanupThread: Thread? = null

    fun start() {
        isRunning = true
        
        // 启动清理线程
        cleanupThread = Thread {
            while (isRunning) {
                try {
                    Thread.sleep(30000) // 每30秒清理一次
                    cleanupConnections()
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        cleanupThread?.start()
        
        Log.d(TAG, "NetworkForwarder started")
    }

    fun stop() {
        isRunning = false
        
        // 停止清理线程
        cleanupThread?.interrupt()
        cleanupThread = null
        
        // 关闭所有连接
        tcpConnections.values.forEach { channel ->
            try {
                channel.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing TCP channel", e)
            }
        }
        
        udpConnections.values.forEach { channel ->
            try {
                channel.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing UDP channel", e)
            }
        }
        
        tcpConnections.clear()
        udpConnections.clear()
        
        try {
            selector.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing selector", e)
        }
        
        Log.d(TAG, "NetworkForwarder stopped")
    }

    /**
     * 转发TCP数据包
     */
    fun forwardTcp(sourceIp: String, sourcePort: Int, destIp: String, destPort: Int, payload: ByteArray): ByteArray? {
        val connectionKey = "$sourceIp:$sourcePort->$destIp:$destPort"
        
        if (!isRunning) return null
        
        try {
            var channel = tcpConnections[connectionKey]
            
            if (channel == null || !channel.isConnected) {
                // 创建新的TCP连接
                channel?.close()
                channel = SocketChannel.open()
                channel.configureBlocking(false)
                
                val connected = channel.connect(InetSocketAddress(destIp, destPort))
                if (!connected) {
                    // 连接正在进行中，等待连接完成
                    var attempts = 0
                    while (!channel.finishConnect() && attempts < 10) {
                        Thread.sleep(10)
                        attempts++
                    }
                }
                
                if (channel.isConnected) {
                    tcpConnections[connectionKey] = channel
                    Log.d(TAG, "Created new TCP connection: $connectionKey")
                } else {
                    Log.w(TAG, "Failed to connect TCP: $connectionKey")
                    channel.close()
                    return null
                }
            }
            
            if (channel?.isConnected == true && payload.isNotEmpty()) {
                // 发送数据
                val buffer = ByteBuffer.wrap(payload)
                var totalWritten = 0
                while (buffer.hasRemaining() && totalWritten < payload.size) {
                    val written = channel.write(buffer)
                    if (written > 0) {
                        totalWritten += written
                    } else {
                        break
                    }
                }
                
                // 尝试读取响应（非阻塞）
                val responseBuffer = ByteBuffer.allocate(4096)
                Thread.sleep(5) // 短暂等待响应
                val bytesRead = channel.read(responseBuffer)
                
                if (bytesRead > 0) {
                    responseBuffer.flip()
                    val response = ByteArray(bytesRead)
                    responseBuffer.get(response)
                    return response
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding TCP packet for $connectionKey", e)
            tcpConnections.remove(connectionKey)?.close()
        }
        
        return null
    }

    /**
     * 转发UDP数据包
     */
    fun forwardUdp(sourceIp: String, sourcePort: Int, destIp: String, destPort: Int, payload: ByteArray): ByteArray? {
        val connectionKey = "$sourceIp:$sourcePort->$destIp:$destPort"
        
        if (!isRunning) return null
        
        try {
            var channel = udpConnections[connectionKey]
            
            if (channel == null || !channel.isConnected) {
                // 创建新的UDP连接
                channel?.close()
                channel = DatagramChannel.open()
                channel.configureBlocking(false)
                channel.connect(InetSocketAddress(destIp, destPort))
                udpConnections[connectionKey] = channel
                Log.d(TAG, "Created new UDP connection: $connectionKey")
            }
            
            if (payload.isNotEmpty()) {
                // 发送数据
                val buffer = ByteBuffer.wrap(payload)
                channel?.write(buffer)
                
                // 尝试读取响应（非阻塞）
                val responseBuffer = ByteBuffer.allocate(4096)
                Thread.sleep(2) // UDP响应通常很快
                val bytesRead = channel?.read(responseBuffer) ?: 0
                
                if (bytesRead > 0) {
                    responseBuffer.flip()
                    val response = ByteArray(bytesRead)
                    responseBuffer.get(response)
                    return response
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding UDP packet for $connectionKey", e)
            udpConnections.remove(connectionKey)?.close()
        }
        
        return null
    }

    /**
     * 清理超时的连接
     */
    fun cleanupConnections() {
        val iterator = tcpConnections.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val channel = entry.value
            if (!channel.isConnected) {
                try {
                    channel.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing disconnected TCP channel", e)
                }
                iterator.remove()
            }
        }
    }
}
