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

/**
 * 监控模式VPN - 只捕获数据包信息，不阻断网络
 * 这个版本专注于数据包监控而不是完全的网络代理
 */
class MonitorVpnService : VpnService() {
    companion object {
        private const val TAG = "MonitorVpnService"
        private const val VPN_ADDRESS = "172.19.0.1"
        const val BROADCAST_VPN_STATE = "com.trafficcapture.MONITOR_VPN_STATE_CHANGED"
        const val BROADCAST_PACKET_CAPTURED = "com.trafficcapture.MONITOR_PACKET_CAPTURED"
        const val EXTRA_RUNNING = "extra_running"
        const val EXTRA_PACKET_INFO = "extra_packet_info"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var workerThread: Thread? = null
    private lateinit var broadcaster: LocalBroadcastManager

    override fun onCreate() {
        super.onCreate()
        broadcaster = LocalBroadcastManager.getInstance(this)
        Log.d(TAG, "MonitorVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            "com.trafficcapture.START_MONITOR_VPN" -> {
                startMonitorVpn()
                START_STICKY
            }
            "com.trafficcapture.STOP_MONITOR_VPN" -> {
                stopMonitorVpn()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startMonitorVpn() {
        if (isRunning) {
            Log.d(TAG, "Monitor VPN is already running")
            return
        }

        // 创建前台通知
        createNotificationChannel()
        val notification = createNotification()
        startForeground(2, notification)

        // 建立VPN连接
        vpnInterface = establishMonitorVpn()
        if (vpnInterface != null) {
            isRunning = true
            workerThread = Thread(MonitorWorker(vpnInterface!!))
            workerThread?.start()
            
            // 发送状态广播
            broadcaster.sendBroadcast(Intent(BROADCAST_VPN_STATE).apply {
                putExtra(EXTRA_RUNNING, true)
            })
            
            Log.d(TAG, "Monitor VPN started successfully")
        } else {
            Log.e(TAG, "Failed to start Monitor VPN")
            stopSelf()
        }
    }

    private fun stopMonitorVpn() {
        Log.d(TAG, "Stopping Monitor VPN...")
        isRunning = false

        workerThread?.interrupt()
        workerThread = null

        vpnInterface?.close()
        vpnInterface = null

        // 发送状态广播
        broadcaster.sendBroadcast(Intent(BROADCAST_VPN_STATE).apply {
            putExtra(EXTRA_RUNNING, false)
        })

        stopForeground(true)
        stopSelf()
        
        Log.d(TAG, "Monitor VPN stopped")
    }

    private fun establishMonitorVpn(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setSession("Traffic Monitor")
                .addAddress(VPN_ADDRESS, 32)
                // 使用更保守的路由策略，避免完全劫持网络
                // 只监控特定的网络段，让主要流量通过默认路由
                .addRoute("172.19.0.0", 16)  // 只路由我们的VPN地址段
                // 为了演示目的，添加一些常见的本地网络段
                .addRoute("10.0.2.0", 24)   // Android模拟器网络
                .addRoute("192.168.1.0", 24) // 常见WiFi网络
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                // 排除我们自己的应用，避免循环
                .addDisallowedApplication(packageName)
                .setMtu(1500)
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot establish Monitor VPN", e)
            null
        }
    }

    private inner class MonitorWorker(private val vpnInterface: ParcelFileDescriptor) : Runnable {
        override fun run() {
            Log.d(TAG, "MonitorWorker started")
            val vpnInput = FileInputStream(vpnInterface.fileDescriptor)
            val vpnOutput = FileOutputStream(vpnInterface.fileDescriptor)
            val buffer = ByteArray(32767)

            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    val length = vpnInput.read(buffer)
                    if (length > 0) {
                        // 监控并透传数据包
                        monitorAndForwardPacket(buffer, length, vpnOutput)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error in MonitorWorker", e)
                    }
                    break
                }
            }
            
            vpnInput.close()
            vpnOutput.close()
            Log.d(TAG, "MonitorWorker finished")
        }

        private fun monitorAndForwardPacket(buffer: ByteArray, length: Int, vpnOutput: FileOutputStream) {
            try {
                // 1. 解析并记录数据包信息
                val packetInfo = PacketParser.parse(buffer, length, this@MonitorVpnService)
                if (packetInfo != null) {
                    broadcaster.sendBroadcast(Intent(BROADCAST_PACKET_CAPTURED).apply {
                        putExtra(EXTRA_PACKET_INFO, packetInfo)
                    })
                    
                    Log.d(TAG, "Monitored packet: ${packetInfo.protocol} ${packetInfo.sourceIp}:${packetInfo.sourcePort} -> ${packetInfo.destIp}:${packetInfo.destPort}")
                }

                // 2. 透传数据包 - 使用一个简单但有效的方法
                // 我们简单地将数据包写回VPN接口，让系统处理路由
                // 由于我们使用了addDisallowedApplication，应该不会形成循环
                vpnOutput.write(buffer, 0, length)
                vpnOutput.flush()
                
                Log.d(TAG, "Packet forwarded: $length bytes")
                
            } catch (e: Exception) {
                Log.w(TAG, "Error monitoring/forwarding packet", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "monitor_vpn_channel",
                "Monitor VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Traffic monitoring VPN service"
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

        return NotificationCompat.Builder(this, "monitor_vpn_channel")
            .setContentTitle("Traffic Monitor")
            .setContentText("Monitoring network traffic (limited scope)")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitorVpn()
        Log.d(TAG, "MonitorVpnService destroyed")
    }
}
