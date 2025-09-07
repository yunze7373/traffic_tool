package com.trafficcapture

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.app.AlertDialog
import android.widget.Toast
import android.util.Log
import okhttp3.*
import okio.ByteString
import kotlinx.coroutines.*
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.concurrent.TimeUnit

data class RemoteTrafficData(
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("method") val method: String,
    @SerializedName("url") val url: String,
    @SerializedName("host") val host: String,
    @SerializedName("request_headers") val requestHeaders: Map<String, String>,
    @SerializedName("request_body") val requestBody: String,
    @SerializedName("response_status") val responseStatus: Int,
    @SerializedName("response_headers") val responseHeaders: Map<String, String>,
    @SerializedName("response_body") val responseBody: String,
    @SerializedName("device_id") val deviceId: String
)

class RemoteProxyManager(private val context: Context) {
    
    companion object {
        private const val TAG = "RemoteProxyManager"
        private const val SERVER_HOST = "bigjj.site"  // 您的服务器域名
        private const val PROXY_PORT = 8888
        private const val WEBSOCKET_PORT = 8765
        private const val API_PORT = 5010
    }
    
    private val websocketUrl = "wss://$SERVER_HOST:$WEBSOCKET_PORT"
    private val apiUrl = "https://$SERVER_HOST:$API_PORT/api"
    
    private var webSocket: WebSocket? = null
    private var isConnected = false
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)  // 增加连接超时
        .readTimeout(60, TimeUnit.SECONDS)     // 增加读取超时
        .writeTimeout(30, TimeUnit.SECONDS)    // 增加写入超时
        .proxy(java.net.Proxy.NO_PROXY)       // 绕过系统代理设置
        .retryOnConnectionFailure(true)        // 连接失败时重试
        .build()
    
    // 回调接口
    interface TrafficCallback {
        fun onNewTraffic(traffic: RemoteTrafficData)
        fun onConnectionStateChanged(connected: Boolean)
        fun onError(error: String)
    }
    
    private var callback: TrafficCallback? = null
    
    fun setCallback(callback: TrafficCallback) {
        this.callback = callback
    }
    
    fun startRemoteCapture() {
        // 先建立WebSocket连接，再显示配置对话框
        GlobalScope.launch {
            try {
                val url = "$apiUrl/status"
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "服务器状态检查成功，开始连接WebSocket")
                    connectWebSocket()
                    
                    // 延迟显示配置对话框，让用户先看到连接状态
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        showProxyConfigDialog()
                    }, 2000)
                } else {
                    Log.e(TAG, "服务器状态检查失败: ${response.code}")
                    callback?.onError("服务器不可用，状态码: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "服务器连接检查失败", e)
                callback?.onError("无法连接到服务器: ${e.message}")
            }
        }
    }
    
    fun stopRemoteCapture() {
        disconnectWebSocket()
    }
    
    private fun showProxyConfigDialog() {
        val connectionStatus = if (isConnected) "✅ 已连接" else "❌ 未连接"
        val message = """
            🌐 远程代理服务器状态: $connectionStatus
            
            ⚠️ 重要提示：
            请在看到"已连接"状态后，再配置手机代理！
            
            📱 配置步骤：
            
            1️⃣ 打开设置 → WiFi
            2️⃣ 长按当前连接的WiFi网络
            3️⃣ 选择"修改网络"
            4️⃣ 展开"高级选项"
            5️⃣ 代理设置选择"手动"
            6️⃣ 输入代理信息：
               主机名: $SERVER_HOST
               端口: $PROXY_PORT
            7️⃣ 保存设置
            
            🔄 配置完成后，所有网络流量将通过远程服务器，
            您可以在应用中实时查看抓包数据！
            
            💡 提示：如需HTTPS明文解密，请访问：
            https://$SERVER_HOST:5010/cert.pem
            下载并安装证书。
        """.trimIndent()
        
        AlertDialog.Builder(context)
            .setTitle("🌐 配置远程代理")
            .setMessage(message)
            .setPositiveButton("复制服务器地址") { _, _ ->
                copyToClipboard(SERVER_HOST)
                Toast.makeText(context, "服务器地址已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("复制端口") { _, _ ->
                copyToClipboard(PROXY_PORT.toString())
                Toast.makeText(context, "端口号已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("确定", null)
            .show()
    }
    
    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("proxy_config", text)
        clipboard.setPrimaryClip(clip)
    }
    
    private fun connectWebSocket() {
        if (isConnected) return
        
        Log.d(TAG, "连接到远程服务器: $websocketUrl")
        
        val request = Request.Builder()
            .url(websocketUrl)
            .addHeader("User-Agent", "TrafficCapture-Android/1.0")
            .addHeader("Origin", "http://bigjj.site")
            .addHeader("Connection", "Upgrade")
            .addHeader("Upgrade", "websocket")
            .build()
        
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket连接成功")
                isConnected = true
                callback?.onConnectionStateChanged(true)
                
                // 发送设备注册信息
                val deviceInfo = mapOf(
                    "type" to "device_register",
                    "device_id" to getDeviceId(),
                    "device_model" to android.os.Build.MODEL,
                    "android_version" to android.os.Build.VERSION.RELEASE,
                    "app_version" to "1.0",
                    "timestamp" to System.currentTimeMillis()
                )
                val registrationJson = Gson().toJson(deviceInfo)
                Log.d(TAG, "发送设备注册信息: $registrationJson")
                webSocket.send(registrationJson)
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    Log.d(TAG, "收到消息: ${text.take(200)}...")
                    
                    // 尝试解析为流量数据
                    val trafficData = Gson().fromJson(text, RemoteTrafficData::class.java)
                    callback?.onNewTraffic(trafficData)
                } catch (e: Exception) {
                    Log.d(TAG, "收到非流量数据消息: $text")
                    // 可能是心跳或其他控制消息，不作为错误处理
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket连接失败", t)
                Log.e(TAG, "Response: ${response?.toString()}")
                isConnected = false
                callback?.onConnectionStateChanged(false)
                
                val errorMsg = when {
                    t.message?.contains("timeout") == true -> 
                        "连接超时，请检查网络或稍后重试"
                    t.message?.contains("unexpected end of stream") == true -> 
                        "服务器WebSocket服务可能未启动"
                    t.message?.contains("failed to connect") == true -> 
                        "无法连接到服务器，请检查网络"
                    t.message?.contains("refused") == true -> 
                        "服务器拒绝连接，可能正在维护"
                    else -> "连接失败: ${t.message}"
                }
                callback?.onError(errorMsg)
                
                // 延长重连间隔，避免频繁重试
                GlobalScope.launch {
                    delay(10000) // 10秒后重试
                    if (!isConnected) {
                        Log.d(TAG, "尝试重新连接...")
                        connectWebSocket()
                    }
                }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket连接关闭: $code $reason")
                isConnected = false
                callback?.onConnectionStateChanged(false)
            }
        })
    }
    
    private fun disconnectWebSocket() {
        webSocket?.close(1000, "用户主动断开")
        webSocket = null
        isConnected = false
        callback?.onConnectionStateChanged(false)
    }
    
    suspend fun getHistoryTraffic(limit: Int = 100): List<RemoteTrafficData> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$apiUrl/traffic?device_id=${getDeviceId()}&limit=$limit"
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val jsonData = response.body?.string() ?: "[]"
                    val dataArray = Gson().fromJson(jsonData, Array<RemoteTrafficData>::class.java)
                    dataArray.toList()
                } else {
                    Log.e(TAG, "获取历史数据失败: ${response.code}")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取历史数据异常", e)
                emptyList()
            }
        }
    }
    
    suspend fun getServerStatus(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$apiUrl/status"
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                val response = httpClient.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                Log.e(TAG, "检查服务器状态失败", e)
                false
            }
        }
    }
    
    private fun getDeviceId(): String {
        // 生成唯一设备ID
        val sharedPref = context.getSharedPreferences("traffic_capture", Context.MODE_PRIVATE)
        var deviceId = sharedPref.getString("device_id", null)
        
        if (deviceId == null) {
            deviceId = "device_${System.currentTimeMillis()}_${(1000..9999).random()}"
            sharedPref.edit().putString("device_id", deviceId).apply()
        }
        
        return deviceId
    }
    
    fun isConnected(): Boolean = isConnected
    
    fun getServerInfo(): String {
        return "代理地址: $SERVER_HOST:$PROXY_PORT\nWebSocket: ${if (isConnected) "已连接" else "未连接"}"
    }
}
