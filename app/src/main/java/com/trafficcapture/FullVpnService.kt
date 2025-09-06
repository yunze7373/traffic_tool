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
import java.io.File
import java.io.RandomAccessFile
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.trafficcapture.tun2socks.Tun2SocksBridge

/**
 * 完整的VPN服务 - 既能正常上网又能抓取所有数据包
 * 实现真正的网络转发机制
 */
class FullVpnService : VpnService() {
    // TCP连接键定义（外部提升避免内部类声明限制问题）
    private data class TcpKey(val srcIp: String, val srcPort: Int, val dstIp: String, val dstPort: Int)
    companion object {
        private const val TAG = "FullVpnService"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_DNS = "8.8.8.8"
        const val BROADCAST_VPN_STATE = "com.trafficcapture.FULL_VPN_STATE_CHANGED"
        const val BROADCAST_PACKET_CAPTURED = "com.trafficcapture.FULL_PACKET_CAPTURED"
        const val EXTRA_RUNNING = "extra_running"
        const val EXTRA_PACKET_INFO = "extra_packet_info"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var workerThread: Thread? = null
    private lateinit var broadcaster: LocalBroadcastManager
    
    // 网络转发器
    // 引擎模式：native tun2socks 或 fallback Kotlin
    private val useNativeTun2Socks = true // 后续可通过设置切换

    // 分离的执行器: 抓包、UDP、TCP (仅在Kotlin fallback时使用)
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val udpExecutor = Executors.newFixedThreadPool(2)
    private val tcpExecutor = Executors.newFixedThreadPool(4)
    private val udpForwarder = UdpForwarder()
    private val tcpForwarder = TcpForwarder()

    // PCAP文件写入支持
    private var pcapFile: RandomAccessFile? = null
    @Volatile private var pcapEnabled = true

    override fun onCreate() {
        super.onCreate()
        broadcaster = LocalBroadcastManager.getInstance(this)
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

        createNotificationChannel()
        val notification = createNotification()
        startForeground(4, notification)

        vpnInterface = establishFullVpn()
        if (vpnInterface != null) {
            isRunning = true
            initPcap()
            if (useNativeTun2Socks) {
                startNativeEngine(vpnInterface!!)
            } else {
                workerThread = Thread(FullVpnWorker(vpnInterface!!), "FullVpn-Worker")
                workerThread?.start()
            }
            
            broadcaster.sendBroadcast(Intent(BROADCAST_VPN_STATE).apply {
                putExtra(EXTRA_RUNNING, true)
            })
            
            Log.d(TAG, "Full VPN started successfully")
        } else {
            Log.e(TAG, "Failed to start Full VPN")
            stopSelf()
        }
    }

    private fun stopFullVpn() {
        Log.d(TAG, "Stopping Full VPN...")
        isRunning = false

        if (useNativeTun2Socks) {
            stopNativeEngine()
        } else {
            workerThread?.interrupt()
            workerThread = null
            udpForwarder.close()
            tcpForwarder.close()
            captureExecutor.shutdownNow()
            udpExecutor.shutdownNow()
            tcpExecutor.shutdownNow()
        }
        closePcap()

        vpnInterface?.close()
        vpnInterface = null

        broadcaster.sendBroadcast(Intent(BROADCAST_VPN_STATE).apply {
            putExtra(EXTRA_RUNNING, false)
        })

        stopForeground(true)
        stopSelf()
        
        Log.d(TAG, "Full VPN stopped")
    }

    private fun establishFullVpn(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setSession("Full Traffic Capture")
                .addAddress(VPN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)  // 路由所有流量
                .addDnsServer(VPN_DNS)
                .addDnsServer("8.8.4.4")
                // 排除我们自己，避免循环
                .addDisallowedApplication(packageName)
                .setMtu(1500)
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot establish Full VPN", e)
            null
        }
    }

    private inner class FullVpnWorker(private val vpnInterface: ParcelFileDescriptor) : Runnable {
        override fun run() {
            Log.d(TAG, "FullVpnWorker started")
            val vpnInput = FileInputStream(vpnInterface.fileDescriptor)
            val vpnOutput = FileOutputStream(vpnInterface.fileDescriptor)
            val buffer = ByteArray(32767)

            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    val length = vpnInput.read(buffer)
                    if (length > 0) {
                        handlePacketWithForwarding(buffer.copyOf(length), length, vpnOutput)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error in FullVpnWorker", e)
                    }
                    break
                }
            }
            
            vpnInput.close()
            vpnOutput.close()
            Log.d(TAG, "FullVpnWorker finished")
        }

        private fun handlePacketWithForwarding(buffer: ByteArray, length: Int, vpnOutput: FileOutputStream) {
            try {
                // 1. 先解析并记录数据包（抓包功能）
                val packetInfo = PacketParser.parse(buffer, length, this@FullVpnService)
                if (packetInfo != null) {
                    broadcaster.sendBroadcast(Intent(BROADCAST_PACKET_CAPTURED).apply {
                        putExtra(EXTRA_PACKET_INFO, packetInfo)
                    })
                    writePcapPacket(buffer, length)
                    Log.d(TAG, "Captured: ${packetInfo.protocol} ${packetInfo.sourceIp}:${packetInfo.sourcePort} -> ${packetInfo.destIp}:${packetInfo.destPort}")
                }

                // 2. 进行真正的网络转发（保证上网功能）
                when (buffer[9].toInt() and 0xFF) {
                    17 -> udpExecutor.submit { forwardPacket(buffer, length, vpnOutput) }
                    6 -> tcpExecutor.submit { forwardPacket(buffer, length, vpnOutput) }
                    else -> captureExecutor.submit { forwardPacket(buffer, length, vpnOutput) }
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Error handling packet with forwarding", e)
            }
        }

        private fun forwardPacket(buffer: ByteArray, length: Int, vpnOutput: FileOutputStream) {
            try {
                // 检查IP版本
                val version = (buffer[0].toInt() and 0xF0) shr 4
                if (version != 4) {
                    Log.d(TAG, "Non-IPv4 packet, version: $version")
                    return
                }

                val protocol = buffer[9].toInt() and 0xFF
                
                when (protocol) {
                    17 -> {  // UDP
                        udpForwarder.forwardUdp(buffer, length)?.let { response ->
                            sendResponse(vpnOutput, response)
                        }
                    }
                    6 -> {   // TCP
                        tcpForwarder.forwardTcp(buffer, length)?.let { response ->
                            sendResponse(vpnOutput, response)
                        }
                    }
                    1 -> {   // ICMP
                        Log.d(TAG, "ICMP packet received")
                        // ICMP通常不需要转发，直接忽略
                    }
                    else -> {
                        Log.d(TAG, "Unsupported protocol: $protocol")
                    }
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Error forwarding packet", e)
            }
        }

        private fun sendResponse(vpnOutput: FileOutputStream, data: ByteArray) {
            synchronized(vpnOutput) {
                vpnOutput.write(data)
                vpnOutput.flush()
            }
            writePcapPacket(data, data.size)
        }
    }

    // UDP转发器
    private inner class UdpForwarder {
        private val socket = DatagramSocket()
        
        init {
            // 保护UDP socket，避免被VPN路由
            protect(socket)
        }

    fun forwardUdp(packet: ByteArray, length: Int): ByteArray? {
            try {
                // 解析UDP包
                val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
                val destIpBytes = packet.copyOfRange(16, 20)
                val destIp = InetAddress.getByAddress(destIpBytes)
                val destPort = ((packet[22].toInt() and 0xFF) shl 8) or (packet[23].toInt() and 0xFF)
                val sourcePort = ((packet[20].toInt() and 0xFF) shl 8) or (packet[21].toInt() and 0xFF)
                
                val udpDataStart = ipHeaderLength + 8
                val udpDataLength = length - udpDataStart
                
                if (udpDataLength > 0) {
                    // 发送UDP数据
                    val udpData = packet.copyOfRange(udpDataStart, length)
                    val sendPacket = DatagramPacket(udpData, udpDataLength, destIp, destPort)
                    socket.send(sendPacket)
                    
                    // 尝试接收响应（非阻塞尝试）
                    socket.soTimeout = 150
                    return try {
                        val responseData = ByteArray(1500)
                        val receivePacket = DatagramPacket(responseData, responseData.size)
                        socket.receive(receivePacket)
                        buildUdpResponsePacket(packet, receivePacket.data, receivePacket.length, sourcePort, destPort)
                    } catch (te: SocketTimeoutException) {
                        null // 无响应也不算错误
                    }
                }
                
            } catch (e: SocketTimeoutException) {
                Log.d(TAG, "UDP timeout - normal for some requests")
            } catch (e: Exception) {
                Log.w(TAG, "UDP forward error: ${e.message}")
            }
            return null
        }

        private fun buildUdpResponsePacket(originalPacket: ByteArray, responseData: ByteArray, responseLength: Int, srcPort: Int, dstPort: Int): ByteArray {
            val ipHeaderLen = 20
            val udpHeaderLen = 8
            val totalLen = ipHeaderLen + udpHeaderLen + responseLength
            val out = ByteArray(totalLen)

            // IPv4 Header
            out[0] = 0x45
            out[1] = 0
            out[2] = (totalLen shr 8).toByte()
            out[3] = (totalLen and 0xFF).toByte()
            out[4] = 0; out[5] = 0 // Identification (0简化)
            out[6] = 0; out[7] = 0 // Flags + Fragment offset
            out[8] = 64 // TTL
            out[9] = 17 // UDP
            // 源/目标IP交换
            System.arraycopy(originalPacket, 16, out, 12, 4)
            System.arraycopy(originalPacket, 12, out, 16, 4)
            // 计算IP头校验和
            val ipChecksum = ipChecksum(out, 0, ipHeaderLen)
            out[10] = (ipChecksum shr 8).toByte()
            out[11] = (ipChecksum and 0xFF).toByte()

            // UDP Header
            out[20] = (dstPort shr 8).toByte() // 源端口: 对端端口
            out[21] = (dstPort and 0xFF).toByte()
            out[22] = (srcPort shr 8).toByte() // 目标端口: 原始源端口
            out[23] = (srcPort and 0xFF).toByte()
            val udpLen = udpHeaderLen + responseLength
            out[24] = (udpLen shr 8).toByte(); out[25] = (udpLen and 0xFF).toByte()
            out[26] = 0; out[27] = 0 // 校验和暂置0 (可选计算)

            // Payload
            System.arraycopy(responseData, 0, out, 28, responseLength)
            return out
        }

        fun close() {
            socket.close()
        }
    }

    // TCP转发器（简化版）
    private inner class TcpForwarder {
        private val connections = ConcurrentHashMap<TcpKey, Socket>()

        fun forwardTcp(packet: ByteArray, length: Int): ByteArray? {
            try {
                val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
                if (length < ipHeaderLen + 20) return null
                val srcIp = InetAddress.getByAddress(packet.copyOfRange(12, 16)).hostAddress
                val dstIp = InetAddress.getByAddress(packet.copyOfRange(16, 20)).hostAddress
                val srcPort = ((packet[ipHeaderLen].toInt() and 0xFF) shl 8) or (packet[ipHeaderLen + 1].toInt() and 0xFF)
                val dstPort = ((packet[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (packet[ipHeaderLen + 3].toInt() and 0xFF)
                val key = TcpKey(srcIp, srcPort, dstIp, dstPort)

                val flags = packet[ipHeaderLen + 13].toInt() and 0xFF
                val syn = flags and 0x02 != 0
                val fin = flags and 0x01 != 0
                val rst = flags and 0x04 != 0
                val ack = flags and 0x10 != 0

                if (rst || fin) {
                    connections.remove(key)?.let { try { it.close() } catch (_: Exception) {} }
                    return null
                }

                var socket = connections[key]
                if (socket == null && syn && !ack) {
                    // 建立到目标的直连socket
                    socket = Socket()
                    protect(socket)
                    socket.connect(InetSocketAddress(dstIp, dstPort), 1500)
                    socket.tcpNoDelay = true
                    connections[key] = socket
                    return null // 等待后续ACK + 数据
                }

                // 提取payload
                val dataOffset = ((packet[ipHeaderLen + 12].toInt() and 0xF0) shr 4) * 4
                val payloadOffset = ipHeaderLen + dataOffset
                if (payloadOffset > length) return null
                val payloadLen = length - payloadOffset
                if (payloadLen > 0 && socket != null && socket.isConnected) {
                    try {
                        socket.getOutputStream().write(packet, payloadOffset, payloadLen)
                    } catch (e: Exception) {
                        connections.remove(key)
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
                // 非阻塞：暂不读取返回（需要单独读取线程）；返回null防止伪造响应污染
                return null
            } catch (e: Exception) {
                Log.w(TAG, "TCP forward error: ${e.message}")
                return null
            }
        }

        fun close() {
            connections.values.forEach {
                try { it.close() } catch (_: Exception) {}
            }
            connections.clear()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "full_vpn_channel",
                "Full VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Full traffic capture VPN with forwarding"
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

        return NotificationCompat.Builder(this, "full_vpn_channel")
            .setContentTitle("Full Traffic Capture")
            .setContentText("Capturing all packets + Network forwarding active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFullVpn()
        Log.d(TAG, "FullVpnService destroyed")
    }

    // ================= Native tun2socks 集成 =================
    private fun startNativeEngine(pfd: ParcelFileDescriptor) {
        try {
            val fd = pfd.detachFd()
            Tun2SocksBridge.setListener(object: Tun2SocksBridge.PacketListener {
                override fun onPacket(direction: Int, proto: Int, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, payload: ByteArray?) {
                    // 将native回调转换为 PacketInfo 并广播
                    val protocolName = when(proto) { 6 -> "TCP"; 17 -> "UDP"; 1 -> "ICMP"; else -> "P$proto" }
                    val info = PacketInfo(
                        protocol = protocolName,
                        sourceIp = srcIp,
                        sourcePort = srcPort,
                        destIp = dstIp,
                        destPort = dstPort,
                        size = (payload?.size ?: 0),
                        direction = if (direction == 0) PacketInfo.Direction.OUTBOUND else PacketInfo.Direction.INBOUND,
                        payload = payload
                    )
                    broadcaster.sendBroadcast(Intent(BROADCAST_PACKET_CAPTURED).apply {
                        putExtra(EXTRA_PACKET_INFO, info)
                    })
                    payload?.let { writePcapPacket(buildRawIpStub(proto, srcIp, srcPort, dstIp, dstPort, it), it.size) }
                }
            })
            if (!Tun2SocksBridge.nativeInit(fd, 1500, null, VPN_DNS)) {
                Log.e(TAG, "nativeInit failed, fallback to Kotlin forwarding")
                // fallback
                useNativeFallback()
                return
            }
            Tun2SocksBridge.nativeSetLogLevel(2)
            Tun2SocksBridge.nativeStart()
            Log.i(TAG, "tun2socks engine started: ${Tun2SocksBridge.nativeVersion()}")
        } catch (e: Exception) {
            Log.e(TAG, "Start native engine error: ${e.message}")
            useNativeFallback()
        }
    }

    private fun stopNativeEngine() {
        try { Tun2SocksBridge.nativeStop() } catch (_: Throwable) {}
        Tun2SocksBridge.setListener(null)
    }

    private fun useNativeFallback() {
        // 若失败回退到Kotlin实现
        workerThread = Thread(FullVpnWorker(vpnInterface!!), "FullVpn-Worker")
        workerThread?.start()
    }

    private fun buildRawIpStub(proto: Int, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, payload: ByteArray): ByteArray {
        // 仅用于PCAP记录的最小化伪IP包（不回写TUN），不保证全部字段正确
        val ipHeaderLen = 20
        val transportHeaderLen = if (proto == 17) 8 else 20
        val totalLen = ipHeaderLen + transportHeaderLen + payload.size
        val out = ByteArray(totalLen)
        out[0] = 0x45
        out[2] = (totalLen shr 8).toByte(); out[3] = (totalLen and 0xFF).toByte()
        out[8] = 64
        out[9] = proto.toByte()
        val sip = srcIp.split('.')
        val dip = dstIp.split('.')
        for (i in 0 until 4) {
            out[12 + i] = sip[i].toInt().toByte()
            out[16 + i] = dip[i].toInt().toByte()
        }
        // 伪校验和
        val csum = ipChecksum(out, 0, ipHeaderLen)
        out[10] = (csum shr 8).toByte(); out[11] = (csum and 0xFF).toByte()
        // 传输头（简化）
        out[ipHeaderLen] = (srcPort shr 8).toByte(); out[ipHeaderLen + 1] = (srcPort and 0xFF).toByte()
        out[ipHeaderLen + 2] = (dstPort shr 8).toByte(); out[ipHeaderLen + 3] = (dstPort and 0xFF).toByte()
        System.arraycopy(payload, 0, out, ipHeaderLen + transportHeaderLen, payload.size)
        return out
    }

    // ================= PCAP 支持 =================
    private fun initPcap() {
        if (!pcapEnabled) return
        try {
            val dir = File(getExternalFilesDir(null), "captures")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "capture_${System.currentTimeMillis()}.pcap")
            pcapFile = RandomAccessFile(file, "rw")
            // 写入PCAP全局头 (标准pcap, 链路类型: Raw IP = 101)
            val header = ByteBuffer.allocate(24)
            header.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.putInt(0xa1b2c3d4.toInt()) // magic
            header.putShort(2) // major
            header.putShort(4) // minor
            header.putInt(0) // thiszone
            header.putInt(0) // sigfigs
            header.putInt(65535) // snaplen
            header.putInt(101) // network (LINKTYPE_RAW)
            pcapFile?.write(header.array())
        } catch (e: Exception) {
            Log.w(TAG, "PCAP init failed: ${e.message}")
            pcapEnabled = false
        }
    }

    private fun writePcapPacket(data: ByteArray, length: Int) {
        if (!pcapEnabled) return
        try {
            val ts = System.currentTimeMillis()
            val sec = (ts / 1000).toInt()
            val usec = ((ts % 1000) * 1000).toInt()
            val packetHeader = ByteBuffer.allocate(16)
            packetHeader.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            packetHeader.putInt(sec)
            packetHeader.putInt(usec)
            packetHeader.putInt(length)
            packetHeader.putInt(length)
            pcapFile?.write(packetHeader.array())
            pcapFile?.write(data, 0, length)
        } catch (e: Exception) {
            Log.w(TAG, "Write pcap failed: ${e.message}")
        }
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length) {
            if (i == offset + 10) { // 跳过校验和字段
                i += 2; continue
            }
            val value = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += value
            if (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum shr 16)
            i += 2
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun closePcap() {
        try { pcapFile?.close() } catch (_: Exception) {}
        pcapFile = null
    }
}
