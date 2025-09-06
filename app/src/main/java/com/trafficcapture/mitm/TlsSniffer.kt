package com.trafficcapture.mitm

import java.io.InputStream

/**
 * 简单 TLS ClientHello SNI 解析 (仅支持常规扩展结构)。
 */
object TlsSniffer {
    data class Result(val isTls: Boolean, val sni: String?)

    fun peekClientHello(peek: ByteArray): Result {
        if (peek.size < 5) return Result(false, null)
        // TLS record header: 0x16 handshake, version 0x03 0x01+ , length 2 bytes
        if (peek[0].toInt() != 0x16) return Result(false, null)
        if (peek[1].toInt() != 0x03) return Result(false, null)
        // Handshake type 0x01 (ClientHello) at byte 5
        if (peek.size < 6) return Result(true, null)
        if (peek[5].toInt() != 0x01) return Result(true, null)
        // We attempt a naive parse to locate extensions and SNI
        try {
            var index = 5 + 4 // handshake header (type + length3)
            if (index + 2 > peek.size) return Result(true, null)
            index += 2 // client version
            index += 32 // random
            if (index + 1 > peek.size) return Result(true, null)
            val sessionIdLen = peek[index].toInt() and 0xFF
            index += 1 + sessionIdLen
            if (index + 2 > peek.size) return Result(true, null)
            val cipherLen = ((peek[index].toInt() and 0xFF) shl 8) or (peek[index + 1].toInt() and 0xFF)
            index += 2 + cipherLen
            if (index + 1 > peek.size) return Result(true, null)
            val compLen = peek[index].toInt() and 0xFF
            index += 1 + compLen
            if (index + 2 > peek.size) return Result(true, null)
            val extTotalLen = ((peek[index].toInt() and 0xFF) shl 8) or (peek[index + 1].toInt() and 0xFF)
            index += 2
            var extProcessed = 0
            while (extProcessed < extTotalLen && index + 4 <= peek.size) {
                val type = ((peek[index].toInt() and 0xFF) shl 8) or (peek[index + 1].toInt() and 0xFF)
                val len = ((peek[index + 2].toInt() and 0xFF) shl 8) or (peek[index + 3].toInt() and 0xFF)
                index += 4
                if (index + len > peek.size) break
                if (type == 0x00) { // server_name
                    var p = index + 2 // list length skip
                    while (p + 3 < index + len) {
                        val nameType = peek[p].toInt() and 0xFF
                        val nameLen = ((peek[p + 1].toInt() and 0xFF) shl 8) or (peek[p + 2].toInt() and 0xFF)
                        p += 3
                        if (p + nameLen > index + len) break
                        if (nameType == 0) {
                            val sni = String(peek, p, nameLen)
                            return Result(true, sni)
                        }
                        p += nameLen
                    }
                }
                index += len
                extProcessed += 4 + len
            }
        } catch (_: Exception) { }
        return Result(true, null)
    }
}
