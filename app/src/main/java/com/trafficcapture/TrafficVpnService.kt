package com.trafficcapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

class TrafficVpnService : VpnService() {
    
    companion object {
        private const val TAG = "TrafficVpnService"
        private const val VPN_ADDRESS = "10.66.66.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_DNS = "8.8.8.8"
        private const val SERVER_HOST = "bigjj.site"
        private const val API_PORT = 5010
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vpn_service_channel"
    }
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var trafficJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "VPN服务启动")
        
        if (!isRunning) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            startVpn()
        }
        
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "VPN服务停止")
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        try {
            // 创建VPN配置
            val builder = Builder()
                .setSession("TrafficCapture VPN")
                .addAddress(VPN_ADDRESS, 24)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer(VPN_DNS)
                .setMtu(1500)
                .setBlocking(false)

            // 建立VPN接口
            vpnInterface = builder.establish()
            
            if (vpnInterface != null) {
                isRunning = true
                Log.d(TAG, "VPN隧道建立成功")
                
                // 启动流量处理
                startTrafficHandling()
            } else {
                Log.e(TAG, "无法建立VPN隧道")
                stopSelf()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "启动VPN失败", e)
            stopSelf()
        }
    }

    private fun stopVpn() {
        isRunning = false
        
        trafficJob?.cancel()
        trafficJob = null
        
        vpnInterface?.close()
        vpnInterface = null
        
        serviceScope.cancel()
        
        Log.d(TAG, "VPN服务已停止")
    }

    private fun startTrafficHandling() {
        trafficJob = serviceScope.launch {
            val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
            val vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
            
            Log.d(TAG, "开始处理VPN流量")
            
            try {
                val buffer = ByteArray(32767)
                
                while (isRunning && !currentCoroutineContext().isActive.not()) {
                    val length = vpnInput.read(buffer)
                    
                    if (length > 0) {
                        // 解析IP包
                        val packet = parseIPPacket(buffer, length)
                        
                        if (packet != null) {
                            // 上报流量数据到服务器
                            reportTrafficData(packet)
                            
                            // 转发数据包（简化实现）
                            handlePacket(packet, vpnOutput)
                        }
                    }
                    
                    delay(1) // 避免CPU占用过高
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "处理VPN流量时出错", e)
            } finally {
                vpnInput.close()
                vpnOutput.close()
            }
        }
    }

    private fun parseIPPacket(buffer: ByteArray, length: Int): IPPacket? {
        try {
            if (length < 20) return null // IP头最小20字节
            
            val packet = IPPacket()
            
            // 解析IP头
            packet.version = (buffer[0].toInt() shr 4) and 0xF
            packet.headerLength = (buffer[0].toInt() and 0xF) * 4
            packet.totalLength = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
            packet.protocol = buffer[9].toInt() and 0xFF
            
            // 源IP和目标IP
            packet.sourceIP = String.format("%d.%d.%d.%d",
                buffer[12].toInt() and 0xFF,
                buffer[13].toInt() and 0xFF,
                buffer[14].toInt() and 0xFF,
                buffer[15].toInt() and 0xFF)
            
            packet.destIP = String.format("%d.%d.%d.%d",
                buffer[16].toInt() and 0xFF,
                buffer[17].toInt() and 0xFF,
                buffer[18].toInt() and 0xFF,
                buffer[19].toInt() and 0xFF)
            
            // 如果是TCP或UDP，解析端口
            if (packet.protocol == 6 || packet.protocol == 17) { // TCP或UDP
                if (length >= packet.headerLength + 4) {
                    packet.sourcePort = ((buffer[packet.headerLength].toInt() and 0xFF) shl 8) or 
                                       (buffer[packet.headerLength + 1].toInt() and 0xFF)
                    packet.destPort = ((buffer[packet.headerLength + 2].toInt() and 0xFF) shl 8) or 
                                     (buffer[packet.headerLength + 3].toInt() and 0xFF)
                }
            }
            
            packet.data = buffer.copyOfRange(0, length)
            
            return packet
            
        } catch (e: Exception) {
            Log.w(TAG, "解析IP包失败", e)
            return null
        }
    }

    private suspend fun reportTrafficData(packet: IPPacket) {
        try {
            val trafficData = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("client_ip", VPN_ADDRESS)
                put("protocol", when(packet.protocol) {
                    6 -> "TCP"
                    17 -> "UDP"
                    1 -> "ICMP"
                    else -> "OTHER"
                })
                put("src_ip", packet.sourceIP)
                put("dst_ip", packet.destIP)
                put("src_port", packet.sourcePort)
                put("dst_port", packet.destPort)
                put("bytes", packet.data.size)
                
                // 尝试解析域名（简化版）
                if (packet.destPort == 80 || packet.destPort == 443) {
                    put("url", "http${if(packet.destPort == 443) "s" else ""}://${packet.destIP}")
                    put("method", "UNKNOWN")
                }
            }
            
            // 发送到服务器API
            val request = Request.Builder()
                .url("https://$SERVER_HOST:$API_PORT/api/traffic/report")
                .post(RequestBody.create(
                    MediaType.parse("application/json"), 
                    trafficData.toString()
                ))
                .build()
            
            withContext(Dispatchers.IO) {
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "上报流量数据失败: ${response.code}")
                }
                response.close()
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "上报流量数据时出错", e)
        }
    }

    private suspend fun handlePacket(packet: IPPacket, vpnOutput: FileOutputStream) {
        try {
            // 这里是一个简化的包转发实现
            // 实际环境中需要更复杂的路由和NAT逻辑
            
            // 对于HTTP/HTTPS流量，我们可以记录但不阻断
            if (packet.destPort == 80 || packet.destPort == 443) {
                Log.d(TAG, "检测到HTTP${if(packet.destPort == 443) "S" else ""}流量: ${packet.destIP}:${packet.destPort}")
            }
            
            // 简单转发（实际应该通过真实网络接口）
            // 这里只是示例代码
            withContext(Dispatchers.IO) {
                vpnOutput.write(packet.data)
                vpnOutput.flush()
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "处理数据包时出错", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN流量监控服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "监控网络流量的VPN服务"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) 
                PendingIntent.FLAG_IMMUTABLE 
            else 
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("流量监控VPN")
            .setContentText("正在监控网络流量...")
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // 数据包结构
    data class IPPacket(
        var version: Int = 0,
        var headerLength: Int = 0,
        var totalLength: Int = 0,
        var protocol: Int = 0,
        var sourceIP: String = "",
        var destIP: String = "",
        var sourcePort: Int = 0,
        var destPort: Int = 0,
        var data: ByteArray = byteArrayOf()
    )
}
