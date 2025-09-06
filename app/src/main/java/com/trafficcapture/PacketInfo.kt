package com.trafficcapture

import android.os.Parcel
import android.os.Parcelable
import java.text.SimpleDateFormat
import java.util.*

/**
 * 数据包信息类，包含详细的包信息和应用信息
 */
data class PacketInfo(
    val timestamp: Long = System.currentTimeMillis(),
    val protocol: String,
    val sourceIp: String,
    val sourcePort: Int,
    val destIp: String,
    val destPort: Int,
    val size: Int,
    val appPackage: String? = null,
    val appName: String? = null,
    val direction: Direction,
    val payload: ByteArray? = null,
    val httpInfo: HttpInfo? = null
) : Parcelable {
    enum class Direction {
        OUTBOUND, INBOUND
    }
    
    data class HttpInfo(
        val method: String? = null,
        val url: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val statusCode: Int? = null,
        val contentType: String? = null
    ) : Parcelable {
        constructor(parcel: Parcel) : this(
            parcel.readString(),
            parcel.readString(),
            parcel.readHashMap(String::class.java.classLoader) as Map<String, String>,
            parcel.readValue(Int::class.java.classLoader) as? Int,
            parcel.readString()
        )

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeString(method)
            parcel.writeString(url)
            parcel.writeMap(headers)
            parcel.writeValue(statusCode)
            parcel.writeString(contentType)
        }

        override fun describeContents(): Int = 0

        companion object CREATOR : Parcelable.Creator<HttpInfo> {
            override fun createFromParcel(parcel: Parcel): HttpInfo {
                return HttpInfo(parcel)
            }

            override fun newArray(size: Int): Array<HttpInfo?> {
                return arrayOfNulls(size)
            }
        }
    }
    
    fun getFormattedTimestamp(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    fun getShortDescription(): String {
        val directionSymbol = if (direction == Direction.OUTBOUND) "→" else "←"
        val appInfo = appName?.let { "[$it]" } ?: ""
        return "${getFormattedTimestamp()} $appInfo [$protocol] $sourceIp:$sourcePort $directionSymbol $destIp:$destPort ($size bytes)"
    }
    
    fun getDetailedDescription(): String {
        val sb = StringBuilder()
        sb.append("时间: ${getFormattedTimestamp()}\n")
        sb.append("应用: ${appName ?: "未知"} (${appPackage ?: "未知"})\n")
        sb.append("协议: $protocol\n")
        sb.append("方向: ${if (direction == Direction.OUTBOUND) "出站" else "入站"}\n")
        sb.append("源地址: $sourceIp:$sourcePort\n")
        sb.append("目标地址: $destIp:$destPort\n")
        sb.append("大小: $size 字节\n")
        
        httpInfo?.let { http ->
            sb.append("\nHTTP信息:\n")
            http.method?.let { sb.append("方法: $it\n") }
            http.url?.let { sb.append("URL: $it\n") }
            http.statusCode?.let { sb.append("状态码: $it\n") }
            http.contentType?.let { sb.append("内容类型: $it\n") }
            if (http.headers.isNotEmpty()) {
                sb.append("头部:\n")
                http.headers.forEach { (key, value) ->
                    sb.append("  $key: $value\n")
                }
            }
        }

        // 添加Payload预览
        payload?.let { data ->
            if (data.isNotEmpty()) {
                sb.append("\n数据载荷预览 (前512字节):\n")
                sb.append(buildPayloadPreview(data, 512))
            }
        }
        
        return sb.toString()
    }

    private fun buildPayloadPreview(bytes: ByteArray, limit: Int): String {
        val len = bytes.size.coerceAtMost(limit)
        val hexBuilder = StringBuilder()
        val asciiBuilder = StringBuilder()
        for (i in 0 until len) {
            val b = bytes[i].toInt() and 0xFF
            hexBuilder.append(String.format("%02X ", b))
            val ch = if (b in 32..126) b.toChar() else '.'
            asciiBuilder.append(ch)
            if ((i + 1) % 16 == 0 || i == len - 1) {
                // 填充hex列
                val pad = 16 - ((i + 1) % 16).let { if (it == 0) 16 else it }
                if (pad > 0 && pad < 16) {
                    repeat(pad) { hexBuilder.append("   ") }
                }
                hexBuilder.append(" | ").append(asciiBuilder).append('\n')
                asciiBuilder.setLength(0)
            }
        }
        if (bytes.size > len) {
            hexBuilder.append("... (截断, 总大小=${bytes.size}字节)\n")
        }
        return hexBuilder.toString()
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PacketInfo

        if (timestamp != other.timestamp) return false
        if (protocol != other.protocol) return false
        if (sourceIp != other.sourceIp) return false
        if (sourcePort != other.sourcePort) return false
        if (destIp != other.destIp) return false
        if (destPort != other.destPort) return false
        if (size != other.size) return false
        if (appPackage != other.appPackage) return false
        if (direction != other.direction) return false
        if (payload != null) {
            if (other.payload == null) return false
            if (!payload.contentEquals(other.payload)) return false
        } else if (other.payload != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + protocol.hashCode()
        result = 31 * result + sourceIp.hashCode()
        result = 31 * result + sourcePort
        result = 31 * result + destIp.hashCode()
        result = 31 * result + destPort
        result = 31 * result + size
        result = 31 * result + (appPackage?.hashCode() ?: 0)
        result = 31 * result + direction.hashCode()
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }
    
    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readInt(),
        parcel.readString() ?: "",
        parcel.readInt(),
        parcel.readInt(),
        parcel.readString(),
        parcel.readString(),
        Direction.valueOf(parcel.readString() ?: "OUTBOUND"),
        parcel.createByteArray(),
        parcel.readParcelable(HttpInfo::class.java.classLoader)
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(timestamp)
        parcel.writeString(protocol)
        parcel.writeString(sourceIp)
        parcel.writeInt(sourcePort)
        parcel.writeString(destIp)
        parcel.writeInt(destPort)
        parcel.writeInt(size)
        parcel.writeString(appPackage)
        parcel.writeString(appName)
        parcel.writeString(direction.name)
        parcel.writeByteArray(payload)
        parcel.writeParcelable(httpInfo, flags)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<PacketInfo> {
        override fun createFromParcel(parcel: Parcel): PacketInfo {
            return PacketInfo(parcel)
        }

        override fun newArray(size: Int): Array<PacketInfo?> {
            return arrayOfNulls(size)
        }
    }
}
