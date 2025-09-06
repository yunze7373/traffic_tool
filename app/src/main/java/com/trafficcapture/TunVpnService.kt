package com.trafficcapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 简化的VPN服务，实现基本的透传功能
 * 这个版本专注于捕获流量并转发到真实网络
 */
class TunVpnService : VpnService() {
    companion object {
        private const val TAG = "TunVpnService"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        const val BROADCAST_VPN_STATE = "com.trafficcapture.VPN_STATE_CHANGED"
        const val BROADCAST_PACKET_CAPTURED = "com.trafficcapture.PACKET_CAPTURED"
        const val EXTRA_RUNNING = "extra_running"
        const val EXTRA_PACKET_INFO = "extra_packet_info"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()
    private val connections = ConcurrentHashMap<String, Socket>()
    private lateinit var broadcaster: LocalBroadcastManager

    override fun onCreate() {
        super.onCreate()
        broadcaster = LocalBroadcastManager.getInstance(this)
        Log.d(TAG, "TunVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            "com.trafficcapture.START_VPN" -> {
                startVpn()
                START_STICKY
            }
            "com.trafficcapture.STOP_VPN" -> {
                stopVpn()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startVpn() {
        if (isRunning) {
            Log.d(TAG, "VPN is already running")
            return
        }

        // 创建前台通知
        createNotificationChannel()
        val notification = createNotification()
        startForeground(1, notification)

        // 建立VPN连接
        vpnInterface = establishVpn()
        if (vpnInterface != null) {
            isRunning = true
            executor.submit(VpnWorker(vpnInterface!!))
            
            // 发送状态广播
            broadcaster.sendBroadcast(Intent(BROADCAST_VPN_STATE).apply {
                putExtra(EXTRA_RUNNING, true)
            })
            
            Log.d(TAG, "VPN started successfully")
        } else {
            Log.e(TAG, "Failed to start VPN")
            stopSelf()
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN...")
        isRunning = false

        // 关闭连接
        connections.values.forEach { 
            try { it.close() } catch (e: Exception) { /* ignore */ }
        }
        connections.clear()

        // 关闭VPN接口
        vpnInterface?.close()
        vpnInterface = null

        // 关闭线程池
        executor.shutdown()

        // 发送状态广播
        broadcaster.sendBroadcast(Intent(BROADCAST_VPN_STATE).apply {
            putExtra(EXTRA_RUNNING, false)
        })

        // 停止前台服务
        stopForeground(true)
        stopSelf()
        
        Log.d(TAG, "VPN stopped")
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setSession("Traffic Capture VPN")
                .addAddress(VPN_ADDRESS, 32)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                // 重要：排除我们自己的应用，避免循环
                .addDisallowedApplication(packageName)
                .setMtu(1500)
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot establish VPN", e)
            null
        }
    }

    private inner class VpnWorker(private val vpnInterface: ParcelFileDescriptor) : Runnable {
        private val realSocket = DatagramSocket()
        
        override fun run() {
            Log.d(TAG, "VpnWorker started")
            val vpnInput = FileInputStream(vpnInterface.fileDescriptor)
            val vpnOutput = FileOutputStream(vpnInterface.fileDescriptor)
            val buffer = ByteArray(32767)

            // 保护真实socket，避免被VPN路由
            protect(realSocket)

            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    val length = vpnInput.read(buffer)
                    if (length > 0) {
                        handlePacket(buffer, length, vpnOutput)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error in VpnWorker", e)
                    }
                    break
                }
            }
            
            realSocket.close()
            vpnInput.close()
            vpnOutput.close()
            Log.d(TAG, "VpnWorker finished")
        }

        private fun handlePacket(buffer: ByteArray, length: Int, vpnOutput: FileOutputStream) {
            try {
                // 1. 解析并记录数据包信息（用于UI显示）
                val packetInfo = PacketParser.parse(buffer, length, this@TunVpnService)
                if (packetInfo != null) {
                    broadcaster.sendBroadcast(Intent(BROADCAST_PACKET_CAPTURED).apply {
                        putExtra(EXTRA_PACKET_INFO, packetInfo)
                    })
                }

                // 2. 尝试转发UDP数据包（最常见的情况）
                if (forwardUdpPacket(buffer, length, vpnOutput)) {
                    Log.d(TAG, "UDP packet forwarded successfully")
                } else {
                    // 对于其他类型的数据包，简单记录
                    Log.d(TAG, "Packet captured (not forwarded): $length bytes")
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Error handling packet", e)
            }
        }

        private fun forwardUdpPacket(buffer: ByteArray, length: Int, vpnOutput: FileOutputStream): Boolean {
            try {
                // 检查是否为IPv4 UDP数据包
                val version = (buffer[0].toInt() and 0xF0) shr 4
                if (version != 4) return false
                
                val protocol = buffer[9].toInt() and 0xFF
                if (protocol != 17) return false // 只处理UDP
                
                // 提取目标信息
                val destIpBytes = buffer.copyOfRange(16, 20)
                val destIp = InetAddress.getByAddress(destIpBytes)
                val destPort = ((buffer[22].toInt() and 0xFF) shl 8) or (buffer[23].toInt() and 0xFF)
                
                // 提取UDP载荷
                val ipHeaderLength = (buffer[0].toInt() and 0x0F) * 4
                val udpDataStart = ipHeaderLength + 8
                val udpDataLength = length - udpDataStart
                
                if (udpDataLength > 0) {
                    // 发送到真实网络
                    val udpData = buffer.copyOfRange(udpDataStart, length)
                    val packet = DatagramPacket(udpData, udpDataLength, destIp, destPort)
                    realSocket.send(packet)
                    
                    Log.d(TAG, "Forwarded UDP to $destIp:$destPort")
                    return true
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "UDP forward failed: ${e.message}")
            }
            return false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "vpn_channel",
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Traffic capture VPN service"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "vpn_channel")
            .setContentTitle("Traffic Capture")
            .setContentText("VPN is running and capturing traffic")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        Log.d(TAG, "TunVpnService destroyed")
    }
}
