package com.trafficcapture.tun2socks

/**
 * JNI 桥接：封装 native tun2socks 引擎接口。
 * 真实实现需放置对应 .so 并实现 C/C++ 层函数：
 *  nativeInit(fd, mtu, socksServer, dnsServer)
 *  nativeStart()
 *  nativeStop()
 *  nativeSetLogLevel(level)
 *  nativeVersion()
 * 以及一个回调：onPacketCaptured(direction, proto, srcIp, srcPort, dstIp, dstPort, payload)
 */
object Tun2SocksBridge {
    init {
        try {
            System.loadLibrary("tun2socks")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    external fun nativeInit(tunFd: Int, mtu: Int, socksServer: String?, dns: String?): Boolean
    external fun nativeStart(): Boolean
    external fun nativeStop()
    external fun nativeSetLogLevel(level: Int)
    external fun nativeVersion(): String

    // Kotlin 层回调注册
    private var listener: PacketListener? = null
    fun setListener(l: PacketListener?) { listener = l }

    // 供 native 调用（必须保持 public）
    @JvmStatic
    fun onPacketCaptured(direction: Int, proto: Int,
                         srcIp: String, srcPort: Int,
                         dstIp: String, dstPort: Int,
                         payload: ByteArray?) {
        listener?.onPacket(direction, proto, srcIp, srcPort, dstIp, dstPort, payload)
    }

    interface PacketListener {
        fun onPacket(direction: Int, proto: Int, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, payload: ByteArray?)
    }
}
