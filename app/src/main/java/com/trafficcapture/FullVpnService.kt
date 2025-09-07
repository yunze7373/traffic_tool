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
import com.trafficcapture.tun2socks.Tun2SocksBridge
import com.trafficcapture.mitm.MitmProxyManager
import com.trafficcapture.mitm.MitmEvent
import java.io.Serializable

/**
 * [DNS FIX] Implements an internal/external DNS separation to resolve the
 * `err_name_not_resolved` issue by preventing a DNS routing loop.
 */
class FullVpnService : VpnService() {

    companion object {
        private const val TAG = "FullVpnService"
        private const val VPN_ADDRESS = "10.0.0.2"
    // 改回直接使用上游DNS，避免虚拟 10.0.0.1 需要本地转发导致无响应
    // 若后续需要自建DNS拦截再开启虚拟DNS模式
        private const val UPSTREAM_DNS = "8.8.8.8"
        
        const val BROADCAST_VPN_STATE = "com.trafficcapture.FULL_VPN_STATE_CHANGED"
        const val BROADCAST_PACKET_CAPTURED = "com.trafficcapture.FULL_PACKET_CAPTURED"
        const val BROADCAST_MITM_EVENT = "com.trafficcapture.MITM_EVENT"
        const val EXTRA_RUNNING = "extra_running"
        const val EXTRA_PACKET_INFO = "extra_packet_info"
        const val EXTRA_MITM_EVENT = "extra_mitm_event"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private lateinit var broadcaster: LocalBroadcastManager
    
    private var mitmProxy: MitmProxyManager? = null
    private lateinit var httpsDecryptor: HttpsDecryptor

    override fun onCreate() {
        super.onCreate()
        broadcaster = LocalBroadcastManager.getInstance(this)
        httpsDecryptor = HttpsDecryptor(this)
        Log.d(TAG, "FullVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            "com.trafficcapture.START_FULL_VPN" -> {
                startFullVpn()
                START_STICKY
            }
            "com.trafficcapture.STOP_FULL_VPN" -> {
                stopFullVpn()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startFullVpn() {
        if (isRunning) {
            Log.d(TAG, "Full VPN is already running")
            return
        }

        vpnInterface = establishFullVpn()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface. Cannot start Full VPN.")
            stopSelf()
            return
        }

        isRunning = true
        
        startForeground(4, createNotification())
        startMitmProxy()

        // Give the proxy a moment to initialize before starting the native engine
        Thread.sleep(500)
        startNativeEngine(vpnInterface!!)
            
        broadcaster.sendBroadcast(Intent(BROADCAST_VPN_STATE).putExtra(EXTRA_RUNNING, true))
        Log.i(TAG, "Full VPN started successfully")
    }

    private fun stopFullVpn() {
        if (!isRunning) return
        Log.d(TAG, "Stopping Full VPN...")
        isRunning = false

        stopNativeEngine()
        stopMitmProxy()

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        } finally {
            vpnInterface = null
        }

        broadcaster.sendBroadcast(Intent(BROADCAST_VPN_STATE).putExtra(EXTRA_RUNNING, false))
        stopForeground(true)
        stopSelf()
        Log.i(TAG, "Full VPN stopped")
    }

    private fun establishFullVpn(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Full Traffic Capture")
                .addAddress(VPN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                // 直接使用真实上游DNS，系统解析不再依赖虚拟 10.0.0.1
                .addDnsServer(UPSTREAM_DNS)
                .addDnsServer("1.1.1.1")
                // 移除应用排除以确保所有流量都被捕获
                // .addDisallowedApplication(packageName)  // 注释掉自我排除
                .setMtu(1500)
                .setBlocking(false)  // 非阻塞模式
            
            // 注意：不排除VPN应用自身，而是通过protect机制避免循环
            // try {
            //     builder.addDisallowedApplication(packageName)
            //     Log.i(TAG, "Excluded VPN app itself from VPN routing")
            // } catch (e: Exception) {
            //     Log.w(TAG, "Could not exclude VPN app: ${e.message}")
            // }
            
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot establish Full VPN", e)
            null
        }
    }

    private fun startNativeEngine(pfd: ParcelFileDescriptor) {
        try {
            val fd = pfd.detachFd()
            val proxyAddress = "127.0.0.1:8889"
            
            // 初始化 native 核心
            if (!Tun2SocksBridge.nativeInit(fd, 1500, proxyAddress, UPSTREAM_DNS)) {
                Log.e(TAG, "Native tun2socks engine initialization failed.")
                return
            }

            // 传入 VpnService 实例供 native protect(fd)
            Tun2SocksBridge.attachVpnService(this)
            // 安装 protect 回调，使底层 TCP/UDP socket 不走 TUN 防止回环
            Tun2SocksBridge.nativeInstallProtectCallback()

            Tun2SocksBridge.setListener(object: Tun2SocksBridge.PacketListener {
                override fun onPacket(direction: Int, proto: Int, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, payload: ByteArray?) {
                    val protocolName = when(proto) { 6 -> "TCP"; 17 -> "UDP"; 1 -> "ICMP"; else -> "P$proto" }
                    val packetInfo = PacketInfo(
                        protocol = protocolName,
                        sourceIp = srcIp,
                        sourcePort = srcPort,
                        destIp = dstIp,
                        destPort = dstPort,
                        size = payload?.size ?: 0,
                        direction = if (direction == 0) PacketInfo.Direction.OUTBOUND else PacketInfo.Direction.INBOUND,
                        payload = payload
                    )
                    broadcaster.sendBroadcast(Intent(BROADCAST_PACKET_CAPTURED).apply {
                        putExtra(EXTRA_PACKET_INFO, packetInfo)
                    })
                }
            })
            
            Tun2SocksBridge.nativeSetLogLevel(0) // 0=DEBUG
            Tun2SocksBridge.nativeStart()
            Log.i(TAG, "Native tun2socks engine started. Version: ${Tun2SocksBridge.nativeVersion()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start native engine", e)
        }
    }

    private fun stopNativeEngine() {
        try {
            Tun2SocksBridge.nativeStop()
            Tun2SocksBridge.setListener(null)
            Log.i(TAG, "Native tun2socks engine stopped.")
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping native engine", e)
        }
    }

    private fun startMitmProxy() {
        if (mitmProxy != null) return
        try {
            mitmProxy = MitmProxyManager(
                vpnService = this,
                decryptor = httpsDecryptor,
                eventCallback = { event ->
                    broadcaster.sendBroadcast(Intent(BROADCAST_MITM_EVENT).putExtra(EXTRA_MITM_EVENT, event as Serializable))
                }
            )
            mitmProxy?.start(8889)
            Log.i(TAG, "MITM proxy started on port 8889")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MITM proxy", e)
        }
    }

    private fun stopMitmProxy() {
        mitmProxy?.stop()
        mitmProxy = null
    }
    
    private fun createNotification(): Notification {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "full_vpn_channel")
            .setContentTitle("Full Traffic Capture Active")
            .setContentText("Forwarding all network traffic via native engine.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "full_vpn_channel",
                "Full VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFullVpn()
        Log.d(TAG, "FullVpnService destroyed")
    }
}
