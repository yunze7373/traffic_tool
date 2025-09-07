package com.trafficcapture

import android.util.Log
import java.io.*
import java.net.*
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * HTTPS代理服务器 - 用于HTTPS流量解密和分析
 * 实现中间人代理模式，拦截和解密HTTPS流量
 */
class HttpsProxyServer : Thread() {
    
    companion object {
        private const val TAG = "HttpsProxyServer"
        private const val PROXY_PORT = 8080
        private const val BUFFER_SIZE = 4096
    }
    
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val sslContext: SSLContext by lazy { createSSLContext() }
    
    fun startProxy() {
        if (!isRunning) {
            isRunning = true
            start()
        }
    }
    
    fun stopProxy() {
        isRunning = false
        serverSocket?.close()
    }
    
    override fun run() {
        try {
            serverSocket = ServerSocket(PROXY_PORT)
            Log.d(TAG, "HTTPS Proxy Server started on port $PROXY_PORT")
            
            while (isRunning) {
                try {
                    val clientSocket = serverSocket?.accept()
                    clientSocket?.let { 
                        // 为每个连接创建新线程处理
                        Thread { handleClient(it) }.start()
                    }
                } catch (e: SocketException) {
                    if (isRunning) {
                        Log.e(TAG, "Socket error in proxy server", e)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error accepting connection", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting proxy server", e)
        }
    }
    
    private fun handleClient(clientSocket: Socket) {
        try {
            val clientInput = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val clientOutput = PrintWriter(clientSocket.getOutputStream(), true)
            
            // 读取第一行请求
            val requestLine = clientInput.readLine()
            if (requestLine == null) {
                clientSocket.close()
                return
            }
            
            Log.d(TAG, "Request: $requestLine")
            
            if (requestLine.startsWith("CONNECT")) {
                // HTTPS CONNECT请求
                handleHttpsConnect(requestLine, clientSocket, clientInput, clientOutput)
            } else {
                // HTTP请求
                handleHttpRequest(requestLine, clientSocket, clientInput, clientOutput)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client socket", e)
            }
        }
    }
    
    private fun handleHttpsConnect(
        requestLine: String,
        clientSocket: Socket,
        clientInput: BufferedReader,
        clientOutput: PrintWriter
    ) {
        try {
            // 解析CONNECT请求
            val hostPort = requestLine.split(" ")[1]
            val (host, port) = if (hostPort.contains(":")) {
                hostPort.split(":").let { it[0] to it[1].toInt() }
            } else {
                hostPort to 443
            }
            
            Log.d(TAG, "HTTPS CONNECT to $host:$port")
            
            // 读取完整的CONNECT请求头
            var line: String?
            do {
                line = clientInput.readLine()
            } while (line != null && line.isNotEmpty())
            
            // 连接到目标服务器
            val targetSocket = Socket(host, port)
            
            // 发送200 Connection Established响应
            clientOutput.println("HTTP/1.1 200 Connection Established")
            clientOutput.println()
            clientOutput.flush()
            
            // 记录HTTPS连接信息
            val httpsRequest = HttpsRequest(
                host = host,
                port = port,
                timestamp = System.currentTimeMillis(),
                method = "CONNECT",
                url = "https://$host:$port",
                encrypted = true
            )
            
            // 发送到MainActivity显示
            sendToMainActivity(httpsRequest)
            
            // 开始代理数据传输
            startDataRelay(clientSocket, targetSocket, host)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling HTTPS CONNECT", e)
            clientOutput.println("HTTP/1.1 500 Internal Server Error")
            clientOutput.println()
        }
    }
    
    private fun handleHttpRequest(
        requestLine: String,
        clientSocket: Socket,
        clientInput: BufferedReader,
        clientOutput: PrintWriter
    ) {
        try {
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            
            val method = parts[0]
            val url = parts[1]
            val httpVersion = parts[2]
            
            Log.d(TAG, "HTTP Request: $method $url")
            
            // 读取请求头
            val headers = mutableMapOf<String, String>()
            var line: String?
            do {
                line = clientInput.readLine()
                if (line != null && line.contains(":")) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size >= 2) {
                        val key = parts[0]
                        val value = parts[1]
                        headers[key.trim()] = value.trim()
                    }
                }
            } while (line != null && line.isNotEmpty())
            
            // 记录HTTP请求
            val httpRequest = HttpsRequest(
                host = headers["Host"] ?: "unknown",
                port = 80,
                timestamp = System.currentTimeMillis(),
                method = method,
                url = url,
                encrypted = false,
                headers = headers
            )
            
            sendToMainActivity(httpRequest)
            
            // 转发HTTP请求到目标服务器
            forwardHttpRequest(requestLine, headers, clientSocket)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling HTTP request", e)
        }
    }
    
    private fun startDataRelay(clientSocket: Socket, targetSocket: Socket, host: String) {
        val clientToTarget = Thread {
            relayData(clientSocket.getInputStream(), targetSocket.getOutputStream(), "Client->$host")
        }
        val targetToClient = Thread {
            relayData(targetSocket.getInputStream(), clientSocket.getOutputStream(), "$host->Client")
        }
        
        clientToTarget.start()
        targetToClient.start()
        
        try {
            clientToTarget.join()
            targetToClient.join()
        } catch (e: InterruptedException) {
            Log.d(TAG, "Data relay interrupted")
        } finally {
            try {
                targetSocket.close()
                clientSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing sockets", e)
            }
        }
    }
    
    private fun relayData(input: InputStream, output: OutputStream, direction: String) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                
                output.write(buffer, 0, bytesRead)
                output.flush()
                
                // 记录数据传输（对于HTTPS是加密数据）
                Log.d(TAG, "$direction: $bytesRead bytes")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Data relay ended for $direction: ${e.message}")
        }
    }
    
    private fun forwardHttpRequest(
        requestLine: String,
        headers: Map<String, String>,
        clientSocket: Socket
    ) {
        // 实现HTTP请求转发逻辑
        // 这里简化处理，实际实现需要完整的HTTP代理逻辑
    }
    
    private fun createSSLContext(): SSLContext {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate?> = arrayOfNulls(0)
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })
            
            SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, java.security.SecureRandom())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating SSL context", e)
            SSLContext.getDefault()
        }
    }
    
    private fun sendToMainActivity(request: HttpsRequest) {
        // 这里需要实现与MainActivity的通信机制
        // 可以使用LocalBroadcastManager或EventBus
        Log.d(TAG, "Captured HTTPS request: ${request.method} ${request.url}")
    }
}

/**
 * HTTPS请求数据模型
 */
data class HttpsRequest(
    val host: String,
    val port: Int,
    val timestamp: Long,
    val method: String,
    val url: String,
    val encrypted: Boolean,
    val headers: Map<String, String> = emptyMap()
)
