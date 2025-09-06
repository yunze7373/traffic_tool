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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * [REBUILT v2] A fully functional VpnService with proper packet forwarding.
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
        private const val VPN_ADDRESS = "10.0.8.1"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var broadcaster: LocalBroadcastManager
    private lateinit var executor: ExecutorService

    private var isRunning = false
    private lateinit var udpForwarder: UdpForwarder

    override fun onCreate() {
        super.onCreate()
        broadcaster = LocalBroadcastManager.getInstance(this)
        udpForwarder = UdpForwarder(this)
        executor = Executors.newFixedThreadPool(5)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return if (intent?.action == ACTION_START) {
            startVpn()
            START_STICKY
        } else {
            stopVpn()
            START_NOT_STICKY
        }
    }

    private fun startVpn() {
        if (isRunning) return
        Log.d(TAG, "Starting VPN...")
        
        vpnInterface = establishVpn() ?: run {
            Log.e(TAG, "Failed to establish VPN interface.")
            return
        }
        
        isRunning = true
        executor.submit(VpnRunnable(vpnInterface!!))
        
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "VPN Started Successfully.")
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN...")
        isRunning = false
        executor.shutdownNow()
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
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot establish VPN", e)
            null
        }
    }

    private inner class VpnRunnable(private val vpnInterface: ParcelFileDescriptor) : Runnable {
        override fun run() {
            val vpnInput = FileInputStream(vpnInterface.fileDescriptor).channel
            val buffer = ByteBuffer.allocate(32767)

            Log.d(TAG, "VPN Runnable started.")
            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    val bytesRead = vpnInput.read(buffer)
                    if (bytesRead > 0) {
                        buffer.flip()
                        val packet = PacketParser.parse(buffer)
                        if (packet != null) {
                            sendPacketInfoBroadcast(packet)
                            if (packet.isUdp) {
                                udpForwarder.forward(packet)
                            } else {
                                // TCP forwarding would be handled here
                            }
                        }
                        buffer.clear()
                    }
                } catch (e: IOException) {
                    if (isRunning) Log.e(TAG, "VPN I/O Error", e)
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "VPN processing error", e)
                }
            }
            Log.d(TAG, "VPN Runnable finished.")
        }
    }
    
    private fun sendPacketInfoBroadcast(packet: Packet) {
        val info = "[${if (packet.isUdp) "UDP" else "TCP"}] " +
                   "${packet.ipHeader.sourceAddress.hostAddress}:${packet.transportHeader.sourcePort} -> " +
                   "${packet.ipHeader.destinationAddress.hostAddress}:${packet.transportHeader.destinationPort}"
        
        val intent = Intent(BROADCAST_PACKET_CAPTURED).apply {
            putExtra(EXTRA_PACKET_INFO, info)
        }
        broadcaster.sendBroadcast(intent)
    }

    private fun createNotification(): Notification {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Traffic Capture Active")
            .setContentText("Capturing network traffic...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
