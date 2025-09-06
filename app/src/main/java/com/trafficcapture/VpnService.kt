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
import java.io.IOException
import java.nio.ByteBuffer

/**
 * [REBUILT] A functional VpnService that captures all traffic and forwards it.
 */
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
        }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        if (isRunning) {
            Log.d(TAG, "VPN is already running.")
            return
        }

        Log.d(TAG, "Starting VPN...")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        vpnInterface = establishVpn()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface.")
            stopVpn()
            return
        }

        isRunning = true
        vpnThread = Thread(VpnRunnable(vpnInterface!!), "VpnTrafficHandler")
        vpnThread?.start()
        Log.d(TAG, "VPN Started Successfully.")
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN...")
        isRunning = false
        vpnThread?.interrupt()
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        stopForeground(true)
        stopSelf()
        Log.d(TAG, "VPN Stopped.")
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(VPN_ADDRESS, 32)
                // [FIX] Route all traffic through the VPN
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot establish VPN", e)
            null
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

    private inner class VpnRunnable(private val vpnInterface: ParcelFileDescriptor) : Runnable {
        override fun run() {
            val vpnInput = FileInputStream(vpnInterface.fileDescriptor)
            val vpnOutput = FileOutputStream(vpnInterface.fileDescriptor)
            val buffer = ByteBuffer.allocate(32767)

            Log.d(TAG, "VPN Runnable started.")
            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    val bytesRead = vpnInput.read(buffer.array())
                    if (bytesRead > 0) {
                        buffer.limit(bytesRead)
                        
                        // [NEW] Parse packet and broadcast info
                        val packetInfo = PacketParser.parse(buffer.array(), bytesRead)
                        if (packetInfo != null) {
                            sendPacketInfoBroadcast(packetInfo)
                        }
                        
                        // [FIX] Write the packet back to allow traffic to flow
                        vpnOutput.write(buffer.array(), 0, bytesRead)
                        buffer.clear()
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "VPN I/O Error", e)
                    break
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            Log.d(TAG, "VPN Runnable finished.")
        }

        private fun sendPacketInfoBroadcast(info: String) {
            val intent = Intent(BROADCAST_PACKET_CAPTURED).apply {
                putExtra(EXTRA_PACKET_INFO, info)
            }
            broadcaster.sendBroadcast(intent)
        }
    }
}
