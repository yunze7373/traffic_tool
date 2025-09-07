package com.trafficcapture.mitm

import android.net.VpnService
import com.trafficcapture.HttpsDecryptor
import kotlinx.coroutines.*
import java.net.*
import java.io.*
import android.util.Log
import java.io.Serializable
import javax.net.ssl.*

/**
 * [FINAL FIX] Resolves the type mismatch error by ensuring the hostname is not null
 * before proceeding with the TLS handling logic.
 */
class MitmProxyManager(
    private val vpnService: VpnService,
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
            client.soTimeout = 120000
            val inBuf = BufferedInputStream(client.getInputStream())
            val out = BufferedOutputStream(client.getOutputStream())
            var targetHost: String? = null
            var targetPort = 443
            try {
                val initialRequest = readInitialRequest(inBuf)
                if (initialRequest.isBlank()) {
                    client.close()
                    return@launch
                }
                
                if (initialRequest.startsWith("CONNECT ")) {
                    val hostPort = initialRequest.split(" ").getOrNull(1)
                    val parts = hostPort?.split(":")
                    targetHost = parts?.getOrNull(0)
                    targetPort = parts?.getOrNull(1)?.toIntOrNull() ?: 443
                    
                    if (targetHost == null) {
                        Log.e(TAG, "Could not parse host from CONNECT request: '$initialRequest'")
                        client.close()
                        return@launch
                    }
                    
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    out.flush()
                    
                    handleTls(client, inBuf, targetHost, targetPort)
                } else {
                    Log.d(TAG, "Plain HTTP request received, not forwarding.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client error: ${e.message}", e)
                eventCallback(MitmEvent.Error(hostname = targetHost, message = e.message ?: "Unknown error"))
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleTls(clientSocket: Socket, clientIn: InputStream, host: String, port: Int) {
        val serverSslContext = decryptor.buildServerSSLContextForHost(host)
        if (serverSslContext == null) {
            Log.w(TAG, "Could not create SSLContext for $host, aborting.");
            return
        }
        
        val sslSocket = serverSslContext.socketFactory.createSocket(clientSocket, clientSocket.inetAddress.hostAddress, clientSocket.port, true) as SSLSocket
        sslSocket.useClientMode = false
        sslSocket.startHandshake()

        val upstreamSocket = createProtectedUpstreamSocket(host, port) ?: return
        
        val clientRequestStream = BufferedInputStream(sslSocket.inputStream)
        val clientResponseStream = BufferedOutputStream(sslSocket.outputStream)
        val upstreamRequestStream = BufferedOutputStream(upstreamSocket.outputStream)
        val upstreamResponseStream = BufferedInputStream(upstreamSocket.inputStream)

        scope.launch { relay(clientRequestStream, upstreamRequestStream, "Client->Server") }
        scope.launch { relay(upstreamResponseStream, clientResponseStream, "Server->Client") }
    }

    private fun createProtectedUpstreamSocket(host: String, port: Int): SSLSocket? {
        try {
            val plainSocket = Socket()
            vpnService.protect(plainSocket)
            plainSocket.connect(InetSocketAddress(host, port), 10000)
            
            val upstreamFactory = decryptor.createSSLSocketFactory()
            val sslSocket = upstreamFactory.createSocket(plainSocket, host, port, true) as SSLSocket
            sslSocket.startHandshake()
            Log.d(TAG, "Protected upstream connection to $host:$port established.")
            return sslSocket
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create protected upstream socket to $host:$port", e)
            return null
        }
    }

    private fun readInitialRequest(inputStream: InputStream): String {
        val reader = BufferedReader(InputStreamReader(inputStream))
        return reader.readLine() ?: ""
    }

    private suspend fun relay(src: InputStream, dst: OutputStream, direction: String) = withContext(Dispatchers.IO) {
        val buf = ByteArray(8192)
        try {
            while (isActive) {
                val bytesRead = src.read(buf)
                if (bytesRead == -1) break
                dst.write(buf, 0, bytesRead)
                dst.flush()
            }
        } catch (e: IOException) {
            Log.d(TAG, "Relay($direction) finished: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Relay($direction) error", e)
        }
    }
    
    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        scope.cancel()
    }
}
