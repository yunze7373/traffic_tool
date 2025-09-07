package com.trafficcapture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class SimpleProxyService : Service() {
    
    private val binder = LocalBinder()
    private var proxyServer: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var isRunning = false
    
    companion object {
        private const val TAG = "SimpleProxyService"
        const val PROXY_PORT = 8080
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): SimpleProxyService = this@SimpleProxyService
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    fun startProxy() {
        if (isRunning) return
        
        try {
            proxyServer = ServerSocket(PROXY_PORT)
            isRunning = true
            
            Log.d(TAG, "HTTP代理服务器启动在端口: $PROXY_PORT")
            
            // 启动服务器线程
            executor.execute {
                try {
                    while (isRunning && proxyServer?.isClosed == false) {
                        val clientSocket = proxyServer?.accept()
                        clientSocket?.let { socket ->
                            executor.execute { handleClient(socket) }
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "代理服务器错误", e)
                    }
                }
            }
            
            // 显示代理配置信息
            showProxyInfo()
            
        } catch (e: Exception) {
            Log.e(TAG, "启动代理服务器失败", e)
        }
    }
    
    fun stopProxy() {
        isRunning = false
        try {
            proxyServer?.close()
            proxyServer = null
            Log.d(TAG, "HTTP代理服务器已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止代理服务器错误", e)
        }
    }
    
    private fun handleClient(clientSocket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val output = clientSocket.getOutputStream()
            
            // 读取HTTP请求头
            val requestLine = input.readLine() ?: return
            Log.d(TAG, "请求: $requestLine")
            
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (input.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val colonIndex = line!!.indexOf(':')
                if (colonIndex > 0) {
                    val name = line!!.substring(0, colonIndex).trim()
                    val value = line!!.substring(colonIndex + 1).trim()
                    headers[name.lowercase()] = value
                }
            }
            
            // 解析请求
            val parts = requestLine.split(" ")
            if (parts.size >= 3) {
                val method = parts[0]
                val url = parts[1]
                
                when (method.uppercase()) {
                    "CONNECT" -> handleHttpsConnect(url, output, clientSocket)
                    else -> handleHttpRequest(requestLine, headers, output, input)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理客户端请求错误", e)
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                // 忽略关闭错误
            }
        }
    }
    
    private fun handleHttpsConnect(hostPort: String, output: OutputStream, clientSocket: Socket) {
        try {
            val parts = hostPort.split(":")
            val host = parts[0]
            val port = if (parts.size > 1) parts[1].toInt() else 443
            
            Log.d(TAG, "HTTPS CONNECT: $host:$port")
            
            // 连接到目标服务器
            val targetSocket = Socket(host, port)
            
            // 发送200连接建立响应
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            output.flush()
            
            // 开始双向数据转发
            val clientToServer = executor.submit {
                try {
                    clientSocket.getInputStream().copyTo(targetSocket.getOutputStream())
                } catch (e: Exception) {
                    Log.d(TAG, "客户端到服务器传输结束")
                }
            }
            
            val serverToClient = executor.submit {
                try {
                    targetSocket.getInputStream().copyTo(clientSocket.getOutputStream())
                } catch (e: Exception) {
                    Log.d(TAG, "服务器到客户端传输结束")
                }
            }
            
            // 等待任一方向传输结束
            try {
                clientToServer.get()
                serverToClient.get()
            } catch (e: Exception) {
                // 传输结束
            } finally {
                targetSocket.close()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "HTTPS CONNECT处理错误", e)
            try {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                output.flush()
            } catch (writeError: Exception) {
                // 忽略写入错误
            }
        }
    }
    
    private fun handleHttpRequest(requestLine: String, headers: Map<String, String>, output: OutputStream, input: BufferedReader) {
        try {
            val parts = requestLine.split(" ")
            val method = parts[0]
            val url = parts[1]
            
            Log.d(TAG, "HTTP请求: $method $url")
            
            // 解析URL
            val uri = if (url.startsWith("http://")) {
                URI(url)
            } else {
                // 相对URL，从Host头获取主机名
                val host = headers["host"] ?: "localhost"
                URI("http://$host$url")
            }
            
            val host = uri.host
            val port = if (uri.port > 0) uri.port else 80
            val path = if (uri.path.isEmpty()) "/" else uri.path + (uri.query?.let { "?$it" } ?: "")
            
            // 连接到目标服务器
            val targetSocket = Socket(host, port)
            val targetOutput = targetSocket.getOutputStream()
            val targetInput = targetSocket.getInputStream()
            
            // 转发请求
            targetOutput.write("$method $path HTTP/1.1\r\n".toByteArray())
            for ((name, value) in headers) {
                if (name != "proxy-connection") {
                    targetOutput.write("$name: $value\r\n".toByteArray())
                }
            }
            targetOutput.write("\r\n".toByteArray())
            
            // 如果有请求体，转发请求体
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                input.read(buffer)
                targetOutput.write(String(buffer).toByteArray())
            }
            
            targetOutput.flush()
            
            // 转发响应
            targetInput.copyTo(output)
            
            targetSocket.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "HTTP请求处理错误", e)
            try {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                output.flush()
            } catch (writeError: Exception) {
                // 忽略写入错误
            }
        }
    }
    
    private fun showProxyInfo() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress
            
            val ip = String.format(
                "%d.%d.%d.%d",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff,
                ipAddress shr 24 and 0xff
            )
            
            Log.i(TAG, "=== HTTP代理配置信息 ===")
            Log.i(TAG, "代理地址: $ip")
            Log.i(TAG, "代理端口: $PROXY_PORT")
            Log.i(TAG, "配置方法：")
            Log.i(TAG, "1. 设置 -> WiFi -> 长按当前WiFi -> 修改网络")
            Log.i(TAG, "2. 高级选项 -> 代理 -> 手动")
            Log.i(TAG, "3. 主机名: $ip")
            Log.i(TAG, "4. 端口: $PROXY_PORT")
            Log.i(TAG, "========================")
            
        } catch (e: Exception) {
            Log.e(TAG, "获取IP地址失败", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopProxy()
        executor.shutdown()
    }
}
