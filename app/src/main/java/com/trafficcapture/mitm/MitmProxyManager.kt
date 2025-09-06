package com.trafficcapture.mitm

import com.trafficcapture.HttpsDecryptor
import com.trafficcapture.PacketInfo
import kotlinx.coroutines.*
import java.net.*
import java.io.*
import android.util.Log
import javax.net.ssl.*

class MitmProxyManager(
    private val decryptor: HttpsDecryptor,
    private val eventCallback: (MitmEvent) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object { private const val TAG = "MitmProxyManager" }

    private var server: ServerSocket? = null
    private var running = false
    var listenPort: Int = 0; private set

    fun start(port: Int = 8889) {
        if (running) return
        running = true
        server = ServerSocket(port)
        listenPort = port
        scope.launch {
            Log.i(TAG, "MITM proxy listening on $port")
            while (running) {
                try { handleClient(server!!.accept()) } catch (e: Exception) { if (running) Log.w(TAG, "Accept error: ${e.message}") }
            }
        }
    }

    private fun handleClient(client: Socket) {
        scope.launch {
            client.soTimeout = 20000
            val inBuf = BufferedInputStream(client.getInputStream())
            val out = BufferedOutputStream(client.getOutputStream())
            var targetHost: String? = null
            var targetPort = 443
            try {
                inBuf.mark(8192)
                val peek = ByteArray(8192)
                val read = inBuf.read(peek)
                if (read <= 0) return@launch
                val header = String(peek, 0, read)
                if (header.startsWith("CONNECT ")) {
                    // CONNECT host:port HTTP/1.1
                    val line = header.lineSequence().firstOrNull() ?: ""
                    val hostPort = line.split(" ").getOrNull(1) ?: ""
                    val hp = hostPort.split(":")
                    targetHost = hp.getOrNull(0)
                    targetPort = hp.getOrNull(1)?.toIntOrNull() ?: 443
                    out.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: TrafficTool\r\n\r\n".toByteArray())
                    out.flush()
                    // 重新读后续 TLS ClientHello
                    inBuf.reset()
                    inBuf.mark(8192)
                    val again = inBuf.read(peek)
                    if (again <= 0) return@launch
                } else {
                    // 直接 HTTP 明文
                    val parsed = HttpStreamParser.parseMessage(ByteArrayInputStream(peek,0,read))
                    eventCallback(
                        MitmEvent.Legacy(
                            type = MitmEvent.Type.REQUEST,
                            direction = MitmEvent.Direction.OUTBOUND,
                            hostname = extractHost(parsed?.headers, targetHost),
                            method = parsed?.method,
                            url = parsed?.url,
                            headers = parsed?.headers ?: emptyMap(),
                            payloadPreview = parsed?.bodyPreview
                        )
                    )
                    // 透传：简单实现（不转发上游，这里可拓展）
                    return@launch
                }

                // TLS 检测 & SNI
                val sniff = TlsSniffer.peekClientHello(peek.copyOf(read))
                if (sniff.isTls) {
                    targetHost = sniff.sni ?: targetHost
                    if (targetHost == null) { Log.w(TAG, "No SNI, abort TLS MITM"); return@launch }
                    // 回退指针，让 SSLSocket 自行读取 ClientHello
                    inBuf.reset()
                    val serverCtx = decryptor.buildServerSSLContextForHost(targetHost!!)
                    if (serverCtx == null) { Log.w(TAG, "Server SSLContext null, abort"); return@launch }
                    val sslSocket = serverCtx.socketFactory.createSocket(client, client.inetAddress.hostAddress, client.port, false) as SSLSocket
                    try { sslSocket.useClientMode = false } catch (_: Exception) {}
                    try { sslSocket.startHandshake() } catch (e: Exception) { Log.w(TAG, "Client TLS handshake fail: ${e.message}"); return@launch }

                    // 上游真实 TLS 连接
                    val upstreamFactory = decryptor.createSSLSocketFactory()
                    val upstream = upstreamFactory.createSocket(targetHost, targetPort) as SSLSocket
                    try { upstream.startHandshake() } catch (e: Exception) { Log.w(TAG, "Upstream handshake fail: ${e.message}"); return@launch }

                    val clientIn = BufferedInputStream(sslSocket.inputStream)
                    val clientOut = BufferedOutputStream(sslSocket.outputStream)
                    val upstreamIn = BufferedInputStream(upstream.inputStream)
                    val upstreamOut = BufferedOutputStream(upstream.outputStream)

                    // 解析首个请求
                    val reqParsed = HttpStreamParser.parseMessage(clientIn)
                    reqParsed?.let {
                        eventCallback(
                            MitmEvent.Legacy(
                                type = MitmEvent.Type.REQUEST,
                                direction = MitmEvent.Direction.OUTBOUND,
                                hostname = targetHost,
                                method = it.method,
                                url = it.url,
                                headers = it.headers,
                                payloadPreview = it.bodyPreview
                            )
                        )
                        // 重建并转发首个请求头+体
                        val builder = StringBuilder()
                        builder.append(it.startLine).append("\r\n")
                        it.headers.forEach { (k,v) -> builder.append(k).append(": ").append(v).append("\r\n") }
                        builder.append("\r\n")
                        upstreamOut.write(builder.toString().toByteArray())
                        it.bodyPreview?.let { bodyPrev -> upstreamOut.write(bodyPrev.toByteArray()) }
                        upstreamOut.flush()
                    }

                    // 解析首个响应
                    val respParsed = HttpStreamParser.parseMessage(upstreamIn)
                    respParsed?.let {
                        eventCallback(
                            MitmEvent.Legacy(
                                type = MitmEvent.Type.RESPONSE,
                                direction = MitmEvent.Direction.INBOUND,
                                hostname = targetHost,
                                statusCode = it.statusCode,
                                headers = it.headers,
                                payloadPreview = it.bodyPreview
                            )
                        )
                        val builder = StringBuilder()
                        builder.append(it.startLine).append("\r\n")
                        it.headers.forEach { (k,v) -> builder.append(k).append(": ").append(v).append("\r\n") }
                        builder.append("\r\n")
                        clientOut.write(builder.toString().toByteArray())
                        it.bodyPreview?.let { bodyPrev -> clientOut.write(bodyPrev.toByteArray()) }
                        clientOut.flush()
                    }

                    // 后续数据直接 relay (不再解析，留待扩展)
                    val job1 = launch { relay(clientIn, upstreamOut, targetHost) }
                    val job2 = launch { relay(upstreamIn, clientOut, targetHost) }
                    joinAll(job1, job2)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client error: ${e.message}")
                eventCallback(
                    MitmEvent.Error(
                        hostname = targetHost,
                        message = e.message ?: "Unknown error"
                    )
                )
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun relay(src: InputStream, dst: OutputStream, host: String? = null) = withContext(Dispatchers.IO) {
        val buf = ByteArray(8192)
        while (true) {
            val r = try { src.read(buf) } catch (_: Exception) { -1 }
            if (r <= 0) break
            try {
                dst.write(buf,0,r)
                dst.flush()
            } catch (_: Exception) { break }
        }
    }

    private fun extractHost(headers: Map<String,String>?, fallback: String?): String? =
        headers?.get("Host") ?: fallback

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        scope.cancel()
    }
}


