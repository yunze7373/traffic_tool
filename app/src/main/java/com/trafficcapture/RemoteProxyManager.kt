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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
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
        showProxyConfigDialog()
        connectWebSocket()
    }
    
    fun stopRemoteCapture() {
        disconnectWebSocket()
    }
    
    private fun showProxyConfigDialog() {
        val message = """
            远程代理已准备就绪！
            
            请按以下步骤配置手机代理：
            
            1️⃣ 打开设置 → WiFi
            2️⃣ 长按当前连接的WiFi网络
            3️⃣ 选择"修改网络"
            4️⃣ 展开"高级选项"
            5️⃣ 代理设置选择"手动"
            6️⃣ 输入代理信息：
               主机名: $SERVER_HOST
               端口: $PROXY_PORT
            7️⃣ 保存设置
            
            配置完成后，所有网络流量将通过远程服务器，
            您可以在应用中实时查看抓包数据！
            
            💡 提示：如需HTTPS明文解密，请访问：
            http://$SERVER_HOST:$PROXY_PORT/cert.pem
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
                    "app_version" to "1.0"
                )
                webSocket.send(Gson().toJson(deviceInfo))
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    Log.d(TAG, "收到流量数据: ${text.take(100)}...")
                    val trafficData = Gson().fromJson(text, RemoteTrafficData::class.java)
                    callback?.onNewTraffic(trafficData)
                } catch (e: Exception) {
                    Log.e(TAG, "解析流量数据失败", e)
                    callback?.onError("数据解析失败: ${e.message}")
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket连接失败", t)
                isConnected = false
                callback?.onConnectionStateChanged(false)
                callback?.onError("连接失败: ${t.message}")
                
                // 自动重连
                GlobalScope.launch {
                    delay(5000)
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
