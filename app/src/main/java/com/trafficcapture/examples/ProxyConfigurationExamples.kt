package com.trafficcapture.examples

/**
 * 代理配置示例代码
 * 演示不同场景下如何配置网络代理
 */

import android.content.Context
import android.net.ConnectivityManager
import android.net.ProxyInfo
import okhttp3.OkHttpClient
import java.net.*
import java.io.*

class ProxyConfigurationExamples {

    /**
     * 示例1：OkHttp客户端配置代理
     * 适用于：自定义开发的Android应用
     */
    fun createOkHttpClientWithProxy(): OkHttpClient {
        // 创建代理对象，指向我们的HTTPS代理服务器
        val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8080))
        
        return OkHttpClient.Builder()
            .proxy(proxy)
            // 信任所有证书（仅用于测试！）
            .hostnameVerifier { hostname: String, session: javax.net.ssl.SSLSession -> true }
            .build()
    }

    /**
     * 示例2：HttpURLConnection配置代理
     * 适用于：使用Java标准网络库的应用
     */
    fun createHttpConnectionWithProxy(urlString: String): HttpURLConnection {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8080))
        val url = URL(urlString)
        return url.openConnection(proxy) as HttpURLConnection
    }

    /**
     * 示例3：系统级代理设置（需要ROOT权限）
     * 注意：普通应用无法执行此操作
     */
    fun setSystemProxy(context: Context) {
        try {
            // 这需要系统级权限，普通应用无法执行
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // Android 5.0+不再允许应用直接修改系统代理
            println("System proxy configuration requires root access")
        } catch (e: SecurityException) {
            println("Permission denied: Cannot modify system proxy settings")
        }
    }

    /**
     * 示例4：检测当前网络是否使用代理
     */
    fun getCurrentProxyInfo(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        
        return if (linkProperties?.httpProxy != null) {
            val proxy = linkProperties.httpProxy
            "Current proxy: ${proxy?.host}:${proxy?.port}"
        } else {
            "No proxy configured"
        }
    }

    /**
     * 示例5：模拟不同应用的网络行为
     */
    fun simulateAppNetworkBehavior() {
        println("=== 应用网络行为模拟 ===")
        
        // 1. 标准HTTP请求（会使用代理）
        println("1. 浏览器类应用：")
        println("   - 遵循系统代理设置")
        println("   - 可以被我们的代理拦截")
        println("   - 能够解密HTTPS内容")
        
        // 2. 绕过代理的请求
        println("\n2. 社交类应用（微信、QQ）：")
        println("   - 可能忽略系统代理设置")
        println("   - 使用直连方式访问服务器")
        println("   - 只能在VPN层看到加密数据")
        
        // 3. 证书绑定的应用
        println("\n3. 银行类应用：")
        println("   - 使用证书绑定技术")
        println("   - 检测到非原始证书会拒绝连接")
        println("   - 即使配置代理也无法解密")
    }

    /**
     * 示例6：演示配置前后的抓包效果差异
     */
    fun demonstrateCaptureDifference() {
        println("=== 抓包效果对比 ===")
        
        println("【未配置代理时 - 仅VPN层抓包】")
        println("Source: 192.168.1.100:54321")
        println("Destination: 142.250.191.46:443")
        println("Protocol: TCP")
        println("Data: [Encrypted TLS Data - 1024 bytes]")
        println("Status: ❌ 无法查看具体内容")
        
        println("\n【配置代理后 - HTTPS解密抓包】")
        println("URL: https://api.example.com/user/profile")
        println("Method: GET")
        println("Headers:")
        println("  Host: api.example.com")
        println("  Authorization: Bearer eyJhbGciOiJIUzI1NiIs...")
        println("  User-Agent: MyApp/1.0.0")
        println("Response:")
        println("  Status: 200 OK")
        println("  Content-Type: application/json")
        println("  Body: {")
        println("    \"user_id\": 12345,")
        println("    \"username\": \"testuser\",")
        println("    \"email\": \"test@example.com\"")
        println("  }")
        println("Status: ✅ 完整的明文内容")
    }

    /**
     * 示例7：常见应用的代理配置方法
     */
    fun getAppSpecificProxyMethods(): Map<String, String> {
        return mapOf(
            "Chrome浏览器" to """
                1. 打开Chrome设置
                2. 高级 → 网络
                3. 代理设置 → 手动配置
                4. HTTP/HTTPS代理: 127.0.0.1:8888
            """.trimIndent(),
            
            "Firefox浏览器" to """
                1. 打开Firefox设置  
                2. 网络设置
                3. 手动代理配置
                4. HTTP/HTTPS代理: 127.0.0.1:8888
            """.trimIndent(),
            
            "系统WiFi设置" to """
                1. 设置 → WLAN
                2. 长按WiFi网络 → 修改网络
                3. 高级选项 → 代理: 手动
                4. 主机名: 127.0.0.1, 端口: 8888
            """.trimIndent(),
            
            "自定义Android应用" to """
                在代码中配置:
                val proxy = Proxy(Type.HTTP, InetSocketAddress("127.0.0.1", 8888))
                val client = OkHttpClient.Builder().proxy(proxy).build()
            """.trimIndent(),
            
            "微信/QQ等社交应用" to """
                ❌ 通常无法配置代理
                这类应用使用自有网络栈，会绕过系统代理设置
                只能在VPN层看到加密的数据包信息
            """.trimIndent()
        )
    }
}
