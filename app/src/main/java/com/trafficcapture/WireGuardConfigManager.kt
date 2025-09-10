package com.trafficcapture

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class WireGuardConfigManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WireGuardConfig"
        private const val PREFS_NAME = "wireguard_config"
        private const val KEY_CLIENT_PRIVATE_KEY = "client_private_key"
        private const val KEY_CLIENT_PUBLIC_KEY = "client_public_key"
        private const val KEY_SERVER_PUBLIC_KEY = "server_public_key"
        private const val KEY_SERVER_ENDPOINT = "server_endpoint"
        private const val KEY_VPN_IP = "vpn_ip"
        private const val KEY_CONFIG_READY = "config_ready"
        
        private const val SERVER_HOST = "bigjj.site"
        private const val SERVER_PORT = 51820
        private const val API_PORT = 5010
        private const val VPN_IP = "10.66.66.2"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 检查WireGuard配置是否已准备好
     */
    fun isConfigReady(): Boolean {
        return prefs.getBoolean(KEY_CONFIG_READY, false) &&
               prefs.getString(KEY_CLIENT_PRIVATE_KEY, null) != null &&
               prefs.getString(KEY_SERVER_PUBLIC_KEY, null) != null
    }

    /**
     * 生成WireGuard配置文件内容
     */
    fun generateConfigFile(): String? {
        if (!isConfigReady()) {
            Log.w(TAG, "配置未准备好，无法生成配置文件")
            return null
        }
        
        val clientPrivateKey = prefs.getString(KEY_CLIENT_PRIVATE_KEY, "") ?: ""
        val serverPublicKey = prefs.getString(KEY_SERVER_PUBLIC_KEY, "") ?: ""
        val serverEndpoint = prefs.getString(KEY_SERVER_ENDPOINT, "$SERVER_HOST:$SERVER_PORT") ?: ""
        val vpnIP = prefs.getString(KEY_VPN_IP, VPN_IP) ?: ""
        
        return """
            [Interface]
            PrivateKey = $clientPrivateKey
            Address = $vpnIP/24
            DNS = 8.8.8.8, 8.8.4.4
            
            [Peer]
            PublicKey = $serverPublicKey
            Endpoint = $serverEndpoint
            AllowedIPs = 0.0.0.0/0
            PersistentKeepalive = 25
        """.trimIndent()
    }

    /**
     * 从服务器获取配置信息
     */
    suspend fun fetchConfigFromServer(): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("https://$SERVER_HOST:$API_PORT/api/status")
                    .get()
                    .build()
                
                val response = httpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body()?.string()
                    response.close()
                    
                    if (responseBody != null) {
                        parseServerConfig(responseBody)
                    } else {
                        false
                    }
                } else {
                    Log.e(TAG, "获取服务器配置失败: ${response.code}")
                    response.close()
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取服务器配置时出错", e)
            false
        }
    }

    /**
     * 解析服务器配置响应
     */
    private fun parseServerConfig(responseBody: String): Boolean {
        return try {
            val json = JSONObject(responseBody)
            
            // 检查是否为VPN模式
            if (json.optString("vpn_type") == "WireGuard") {
                val serverHost = json.optString("server_host", SERVER_HOST)
                val vpnPort = json.optInt("vpn_port", SERVER_PORT)
                val serverEndpoint = "$serverHost:$vpnPort"
                val vpnIP = json.optString("client_vpn_ip", VPN_IP)
                
                // 保存服务器配置
                prefs.edit()
                    .putString(KEY_SERVER_ENDPOINT, serverEndpoint)
                    .putString(KEY_VPN_IP, vpnIP)
                    .apply()
                
                Log.d(TAG, "服务器配置已更新: $serverEndpoint")
                true
            } else {
                Log.w(TAG, "服务器未配置为WireGuard VPN模式")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析服务器配置失败", e)
            false
        }
    }

    /**
     * 手动设置WireGuard配置（用于测试或手动配置）
     */
    fun setManualConfig(
        clientPrivateKey: String,
        serverPublicKey: String,
        serverEndpoint: String = "$SERVER_HOST:$SERVER_PORT",
        vpnIP: String = VPN_IP
    ): Boolean {
        return try {
            // 生成客户端公钥（这里简化处理，实际应该从私钥计算）
            val clientPublicKey = generatePublicKeyFromPrivate(clientPrivateKey)
            
            prefs.edit()
                .putString(KEY_CLIENT_PRIVATE_KEY, clientPrivateKey)
                .putString(KEY_CLIENT_PUBLIC_KEY, clientPublicKey)
                .putString(KEY_SERVER_PUBLIC_KEY, serverPublicKey)
                .putString(KEY_SERVER_ENDPOINT, serverEndpoint)
                .putString(KEY_VPN_IP, vpnIP)
                .putBoolean(KEY_CONFIG_READY, true)
                .apply()
            
            Log.d(TAG, "手动配置已保存")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存手动配置失败", e)
            false
        }
    }

    /**
     * 从配置字符串导入WireGuard配置
     */
    fun importConfigFromString(configString: String): Boolean {
        return try {
            val lines = configString.lines()
            var clientPrivateKey = ""
            var serverPublicKey = ""
            var serverEndpoint = ""
            var vpnIP = ""
            
            var currentSection = ""
            
            for (line in lines) {
                val trimmedLine = line.trim()
                
                when {
                    trimmedLine.startsWith("[Interface]") -> {
                        currentSection = "Interface"
                    }
                    trimmedLine.startsWith("[Peer]") -> {
                        currentSection = "Peer"
                    }
                    trimmedLine.contains("=") -> {
                        val parts = trimmedLine.split("=", limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0].trim()
                            val value = parts[1].trim()
                            
                            when (currentSection) {
                                "Interface" -> {
                                    when (key) {
                                        "PrivateKey" -> clientPrivateKey = value
                                        "Address" -> vpnIP = value.split("/")[0] // 移除子网掩码
                                    }
                                }
                                "Peer" -> {
                                    when (key) {
                                        "PublicKey" -> serverPublicKey = value
                                        "Endpoint" -> serverEndpoint = value
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // 验证必要字段
            if (clientPrivateKey.isNotEmpty() && serverPublicKey.isNotEmpty() && serverEndpoint.isNotEmpty()) {
                setManualConfig(clientPrivateKey, serverPublicKey, serverEndpoint, vpnIP)
            } else {
                Log.e(TAG, "配置文件缺少必要字段")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "导入配置失败", e)
            false
        }
    }

    /**
     * 清除所有配置
     */
    fun clearConfig() {
        prefs.edit().clear().apply()
        Log.d(TAG, "配置已清除")
    }

    /**
     * 获取配置摘要信息
     */
    fun getConfigSummary(): Map<String, String> {
        return mapOf(
            "ready" to isConfigReady().toString(),
            "server_endpoint" to (prefs.getString(KEY_SERVER_ENDPOINT, "") ?: ""),
            "vpn_ip" to (prefs.getString(KEY_VPN_IP, "") ?: ""),
            "has_private_key" to (prefs.getString(KEY_CLIENT_PRIVATE_KEY, null) != null).toString(),
            "has_server_key" to (prefs.getString(KEY_SERVER_PUBLIC_KEY, null) != null).toString()
        )
    }

    /**
     * 验证与服务器的连接
     */
    suspend fun validateServerConnection(): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("https://$SERVER_HOST:$API_PORT/api/status")
                    .get()
                    .build()
                
                val response = httpClient.newCall(request).execute()
                val isValid = response.isSuccessful
                response.close()
                
                Log.d(TAG, "服务器连接验证: ${if (isValid) "成功" else "失败"}")
                isValid
            }
        } catch (e: Exception) {
            Log.e(TAG, "验证服务器连接时出错", e)
            false
        }
    }

    /**
     * 生成公钥（简化实现，实际应该使用WireGuard库）
     */
    private fun generatePublicKeyFromPrivate(privateKey: String): String {
        // 这里是一个占位符实现
        // 实际项目中应该使用WireGuard的加密库来计算公钥
        return "PUBLIC_KEY_PLACEHOLDER_${privateKey.hashCode()}"
    }

    /**
     * 获取示例配置
     */
    fun getExampleConfig(): String {
        return """
            [Interface]
            PrivateKey = 你的客户端私钥
            Address = 10.66.66.2/24
            DNS = 8.8.8.8, 8.8.4.4
            
            [Peer]
            PublicKey = 服务器公钥
            Endpoint = bigjj.site:51820
            AllowedIPs = 0.0.0.0/0
            PersistentKeepalive = 25
        """.trimIndent()
    }
}
