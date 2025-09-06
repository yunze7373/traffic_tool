package com.trafficcapture.mitm

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * 轻量HTTP解析：解析首行+头部，返回(body截断)。不处理分块传输全部细节（可拓展）。
 */
object HttpStreamParser {
    data class Parsed(val startLine: String, val headers: Map<String,String>, val bodyPreview: String?, val statusCode: Int?, val method: String?, val url: String?)

    fun parseMessage(input: InputStream, maxBody: Int = 2048): Parsed? {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(8192)
        val headerBytes = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = buffered.read()
            if (b == -1) break
            headerBytes.write(b)
            last4 = ((last4 shl 8) or b) and 0xFFFFFFFF.toInt()
            if (last4 == 0x0D0A0D0A) break // CRLFCRLF
            if (headerBytes.size() > 32_768) return null
        }
        val headerText = headerBytes.toString(Charset.forName("UTF-8"))
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val start = lines.first()
        val headers = mutableMapOf<String,String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(":")
            if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        var statusCode: Int? = null
        var method: String? = null
        var url: String? = null
        if (start.startsWith("HTTP/")) {
            val parts = start.split(" ")
            if (parts.size >= 2) statusCode = parts[1].toIntOrNull()
        } else {
            val parts = start.split(" ")
            if (parts.size >= 2) {
                method = parts[0]
                url = parts[1]
            }
        }
        // Body preview (best effort, not handling chunked fully)
        val cl = headers["Content-Length"]?.toIntOrNull()
        val bodyPreview = if (cl != null && cl > 0) {
            val toRead = cl.coerceAtMost(maxBody)
            val buf = ByteArray(toRead)
            val read = buffered.read(buf)
            if (read > 0) String(buf, 0, read, Charset.forName("UTF-8")).replace("\r\n", "\\n") else null
        } else null
        return Parsed(start, headers, bodyPreview, statusCode, method, url)
    }
}
