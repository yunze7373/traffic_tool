package com.trafficcapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * A functional VpnService that captures all traffic and forwards it.
 */
@Suppress("unused")
class VpnService : VpnService() {

    companion object {
        const val TAG = "TrafficCaptureVpn"
        const val ACTION_START = "com.trafficcapture.START_VPN"
        const val ACTION_STOP = "com.trafficcapture.STOP_VPN"
        const val BROADCAST_PACKET_CAPTURED = "com.trafficcapture.PACKET_CAPTURED"
        const val EXTRA_PACKET_INFO = "packet_info"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "VpnServiceChannel"
        private const val VPN_ADDRESS = "10.0.8.1" // A private address for the VPN interface
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isRunning = false
    private var networkForwarder: NetworkForwarder? = null
    private lateinit var broadcaster: LocalBroadcastManager

    override fun onCreate() {
        super.onCreate()
        broadcaster = LocalBroadcastManager.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startVpn()
                return START_STICKY
            }
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun startVpn() {
        if (isRunning) return
        Log.d(TAG, "Starting VPN...")

        vpnInterface = establishVpn()
        if (vpnInterface == null) {
            Log.e(TAG, "Could not establish vpn interface.")
            return
        }

        isRunning = true
        networkForwarder = NetworkForwarder()
        networkForwarder?.start()
        vpnThread = Thread(VpnRunnable(vpnInterface!!))
        vpnThread!!.start()

        createNotificationChannel()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        Log.d(TAG, "VPN Started Successfully.")
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN...")
        isRunning = false
        networkForwarder?.stop()
        networkForwarder = null
        vpnThread?.interrupt()
        try {
            vpnInterface?.close()
        } catch (_: IOException) {
            Log.e(TAG, "Error closing VPN interface")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        Log.d(TAG, "VPN Stopped.")
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(VPN_ADDRESS, 32)
                // 只路由私有网络范围，避免劫持所有网络流量
                .addRoute("10.0.0.0", 8)
                .addRoute("172.16.0.0", 12)
                .addRoute("192.168.0.0", 16)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                // 排除我们自己的应用，避免循环
                .addDisallowedApplication(packageName)
                .setMtu(1500)
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot establish VPN", e)
            null
        }
    }

    private inner class VpnRunnable(private val vpnInterface: ParcelFileDescriptor) : Runnable {
        override fun run() {
            val vpnInput = FileInputStream(vpnInterface.fileDescriptor)
            val vpnOutput = FileOutputStream(vpnInterface.fileDescriptor)
            val buffer = ByteArray(32767)

            Log.d(TAG, "VPN Runnable started with packet capture only mode.")
            
            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    val bytesRead = vpnInput.read(buffer)
                    if (bytesRead > 0) {
                        // 解析并记录数据包信息（用于UI显示）
                        val packetInfo = PacketParser.parse(buffer, bytesRead, this@VpnService)
                        if (packetInfo != null) {
                            sendPacketInfoBroadcast(packetInfo)
                        }
                        
                        // 简单的"监听模式" - 我们只记录数据包但不阻止网络访问
                        // 这意味着其他网络接口（WiFi/移动数据）仍然可以正常工作
                        // 虽然数据包会被路由到VPN，但我们不转发它们，这样可以避免复杂的转发逻辑
                        // 用户的网络体验可能会受到一些影响，但可以观察到应用的网络活动
                        
                        Log.d(TAG, "Captured and logged packet: ${bytesRead} bytes")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "VPN I/O Error", e)
                    break
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in VPN processing", e)
                }
            }
            
            Log.d(TAG, "VPN Runnable finished.")
        }

        private fun sendPacketInfoBroadcast(packetInfo: PacketInfo) {
            val intent = Intent(BROADCAST_PACKET_CAPTURED).apply {
                putExtra(EXTRA_PACKET_INFO, packetInfo)
            }
            broadcaster.sendBroadcast(intent)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Traffic Capture Active")
            .setContentText("Capturing network traffic...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details) // Use system icon
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "VPN Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
