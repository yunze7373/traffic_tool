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

/**
 * 轻量级VPN监控服务 - 专注于监控而不是转发
 * 这个版本只捕获特定类型的流量，避免阻断正常网络连接
 */
class LightVpnService : VpnService() {
    companion object {
        private const val TAG = "LightVpnService"
        private const val VPN_ADDRESS = "10.8.0.1"
        const val BROADCAST_VPN_STATE = "com.trafficcapture.LIGHT_VPN_STATE_CHANGED"
        const val BROADCAST_PACKET_CAPTURED = "com.trafficcapture.LIGHT_PACKET_CAPTURED"
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
        Log.d(TAG, "LightVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            "com.trafficcapture.START_LIGHT_VPN" -> {
                startLightVpn()
                START_STICKY
            }
            "com.trafficcapture.STOP_LIGHT_VPN" -> {
                stopLightVpn()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startLightVpn() {
        if (isRunning) {
            Log.d(TAG, "Light VPN is already running")
            return
        }

        createNotificationChannel()
        val notification = createNotification()
        startForeground(3, notification)

        vpnInterface = establishLightVpn()
        if (vpnInterface != null) {
            isRunning = true
            workerThread = Thread(LightMonitorWorker(vpnInterface!!))
            workerThread?.start()
            
            broadcaster.sendBroadcast(Intent(BROADCAST_VPN_STATE).apply {
                putExtra(EXTRA_RUNNING, true)
            })
            
            Log.d(TAG, "Light VPN started successfully")
        } else {
            Log.e(TAG, "Failed to start Light VPN")
            stopSelf()
        }
    }

    private fun stopLightVpn() {
        Log.d(TAG, "Stopping Light VPN...")
        isRunning = false

        workerThread?.interrupt()
        workerThread = null

        vpnInterface?.close()
        vpnInterface = null

        broadcaster.sendBroadcast(Intent(BROADCAST_VPN_STATE).apply {
            putExtra(EXTRA_RUNNING, false)
        })

        stopForeground(true)
        stopSelf()
        
        Log.d(TAG, "Light VPN stopped")
    }

    private fun establishLightVpn(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setSession("Light Traffic Monitor")
                .addAddress(VPN_ADDRESS, 32)
                // 非常保守的路由策略 - 只监控很少的流量
                // 这样可以确保大部分网络流量正常运行
                .addRoute("10.8.0.0", 24)   // 只路由我们自己的地址段
                .setMtu(1500)
                // 不添加DNS服务器，让系统使用默认DNS
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot establish Light VPN", e)
            null
        }
    }

    private inner class LightMonitorWorker(private val vpnInterface: ParcelFileDescriptor) : Runnable {
        override fun run() {
            Log.d(TAG, "LightMonitorWorker started")
            val vpnInput = FileInputStream(vpnInterface.fileDescriptor)
            val buffer = ByteArray(32767)

            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    val length = vpnInput.read(buffer)
                    if (length > 0) {
                        monitorPacketLight(buffer, length)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error in LightMonitorWorker", e)
                    }
                    break
                }
            }
            
            vpnInput.close()
            Log.d(TAG, "LightMonitorWorker finished")
        }

        private fun monitorPacketLight(buffer: ByteArray, length: Int) {
            try {
                // 解析并记录数据包信息
                val packetInfo = PacketParser.parse(buffer, length, this@LightVpnService)
                if (packetInfo != null) {
                    broadcaster.sendBroadcast(Intent(BROADCAST_PACKET_CAPTURED).apply {
                        putExtra(EXTRA_PACKET_INFO, packetInfo)
                    })
                    
                    Log.d(TAG, "Light monitored: ${packetInfo.protocol} ${packetInfo.sourceIp}:${packetInfo.sourcePort} -> ${packetInfo.destIp}:${packetInfo.destPort}")
                } else {
                    Log.d(TAG, "Captured packet but could not parse: $length bytes")
                }
                
                // 注意：我们不转发这些数据包，因为这个VPN只监控很小的地址段
                // 大部分流量通过正常网络接口，保证网络连通性
                
            } catch (e: Exception) {
                Log.w(TAG, "Error monitoring light packet", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "light_vpn_channel",
                "Light VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Light traffic monitoring VPN service"
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

        return NotificationCompat.Builder(this, "light_vpn_channel")
            .setContentTitle("Light Traffic Monitor")
            .setContentText("Monitoring limited traffic (network should work normally)")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLightVpn()
        Log.d(TAG, "LightVpnService destroyed")
    }
}
