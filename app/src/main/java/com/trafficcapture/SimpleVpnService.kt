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
import com.trafficcapture.tun2socks.SimpleTun2socks

/**
 * Simplified VPN service based on successful open source projects
 * Focus: Reliable packet forwarding without complex proxy chains
 */
class SimpleVpnService : VpnService() {

    companion object {
        private const val TAG = "SimpleVpnService"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val DNS_SERVER = "8.8.8.8"
        private const val NOTIFICATION_CHANNEL_ID = "simple_vpn_channel"
        private const val NOTIFICATION_ID = 5
        
        const val BROADCAST_VPN_STATE = "com.trafficcapture.SIMPLE_VPN_STATE_CHANGED"
        const val EXTRA_RUNNING = "extra_running"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private lateinit var broadcaster: LocalBroadcastManager

    override fun onCreate() {
        super.onCreate()
        broadcaster = LocalBroadcastManager.getInstance(this)
        createNotificationChannel()
        Log.d(TAG, "SimpleVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            "com.trafficcapture.START_SIMPLE_VPN" -> {
                startSimpleVpn()
                START_STICKY
            }
            "com.trafficcapture.STOP_SIMPLE_VPN" -> {
                stopSimpleVpn()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startSimpleVpn() {
        if (isRunning) {
            Log.d(TAG, "Simple VPN is already running")
            return
        }

        if (!SimpleTun2socks.isAvailable()) {
            Log.e(TAG, "Simple proxy library not available")
            stopSelf()
            return
        }

        vpnInterface = establishVpnInterface()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }

        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Start the simple proxy
        startSimpleProxy()
        
        broadcaster.sendBroadcast(Intent(BROADCAST_VPN_STATE).putExtra(EXTRA_RUNNING, true))
        Log.i(TAG, "Simple VPN started successfully")
    }

    private fun stopSimpleVpn() {
        if (!isRunning) return
        
        Log.d(TAG, "Stopping Simple VPN...")
        isRunning = false

        // Stop the simple proxy
        stopSimpleProxy()

        vpnInterface?.close()
        vpnInterface = null

        stopForeground(true)
        broadcaster.sendBroadcast(Intent(BROADCAST_VPN_STATE).putExtra(EXTRA_RUNNING, false))
        Log.i(TAG, "Simple VPN stopped")
    }

    private fun establishVpnInterface(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("SimpleVPN")
                .addAddress(VPN_ADDRESS, 32)  // Single host route
                .addRoute("0.0.0.0", 0)       // Route all traffic
                .addDnsServer(DNS_SERVER)     // Use Google DNS directly
                .setMtu(1500)
                .setBlocking(false)
            
            // Allow some system apps to bypass VPN (commented out to ensure all traffic goes through VPN)
            /*
            try {
                builder.addDisallowedApplication("com.android.vending") // Play Store
                builder.addDisallowedApplication("com.google.android.gms") // Google Play Services
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add disallowed applications: ${e.message}")
            }
            */

            val vpnInterface = builder.establish()
            if (vpnInterface != null) {
                Log.i(TAG, "VPN interface established successfully: $VPN_ADDRESS")
                Log.i(TAG, "VPN file descriptor: ${vpnInterface.fd}")
            } else {
                Log.e(TAG, "Failed to establish VPN interface")
            }
            vpnInterface
        } catch (e: Exception) {
            Log.e(TAG, "Error establishing VPN interface", e)
            null
        }
    }

    private fun startSimpleProxy() {
        val tunFd = vpnInterface?.fd ?: return
        
        try {
            Log.i(TAG, "Starting simple proxy with TUN fd: $tunFd")
            
            // Set protect callback
            SimpleTun2socks.setProtectCallback(object : SimpleTun2socks.ProtectCallback {
                override fun protect(fd: Int): Boolean {
                    val result = this@SimpleVpnService.protect(fd)
                    Log.d(TAG, "Protected socket fd=$fd, result=$result")
                    return result
                }
            })
            
            // Initialize and start the proxy
            val initResult = SimpleTun2socks.init(tunFd, "", DNS_SERVER, 1500)
            if (initResult != 0) {
                Log.e(TAG, "Failed to initialize simple proxy: $initResult")
                return
            }
            
            val startResult = SimpleTun2socks.start()
            if (startResult != 0) {
                Log.e(TAG, "Failed to start simple proxy: $startResult")
                return
            }
            
            Log.i(TAG, "Simple proxy started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting simple proxy", e)
        }
    }

    private fun stopSimpleProxy() {
        try {
            SimpleTun2socks.stop()
            Log.i(TAG, "Simple proxy stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping simple proxy", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Simple VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Simple VPN is running"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Simple VPN Active")
            .setContentText("Capturing network traffic")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSimpleVpn()
        Log.d(TAG, "SimpleVpnService destroyed")
    }
}
