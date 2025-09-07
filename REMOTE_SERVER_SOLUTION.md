# 基于远程服务器的移动抓包解决方案

## 方案概述

使用您的远程服务器作为代理服务器，避开Android本地VPN的所有限制：

```
[Android App] → [远程服务器代理] → [目标网站]
      ↑              ↓
      ←── 数据分析展示 ←──
```

## 架构设计

### 1. 远程服务器组件
**功能：** HTTP/HTTPS代理服务器 + 数据存储 + API接口

```python
# 服务器端 - proxy_server.py
import asyncio
import json
import sqlite3
from datetime import datetime
from mitmproxy import http
from mitmproxy.tools.main import mitmdump
import websockets

class MobileProxyServer:
    def __init__(self):
        self.db = sqlite3.connect('traffic_capture.db')
        self.setup_database()
        self.connected_clients = set()
    
    def setup_database(self):
        self.db.execute('''
            CREATE TABLE IF NOT EXISTS traffic_logs (
                id INTEGER PRIMARY KEY,
                timestamp TEXT,
                method TEXT,
                url TEXT,
                host TEXT,
                request_headers TEXT,
                request_body TEXT,
                response_status INTEGER,
                response_headers TEXT,
                response_body TEXT,
                device_id TEXT
            )
        ''')
    
    def request(self, flow: http.HTTPFlow):
        # 记录请求开始时间
        flow.metadata['start_time'] = datetime.now()
    
    def response(self, flow: http.HTTPFlow):
        # 保存完整的请求响应数据
        traffic_data = {
            'timestamp': datetime.now().isoformat(),
            'method': flow.request.method,
            'url': flow.request.pretty_url,
            'host': flow.request.pretty_host,
            'request_headers': dict(flow.request.headers),
            'request_body': flow.request.text[:4096] if flow.request.text else '',
            'response_status': flow.response.status_code,
            'response_headers': dict(flow.response.headers),
            'response_body': flow.response.text[:4096] if flow.response.text else '',
            'device_id': self.get_device_id(flow)
        }
        
        # 存储到数据库
        self.save_to_db(traffic_data)
        
        # 实时推送到连接的设备
        asyncio.create_task(self.broadcast_to_clients(traffic_data))
    
    def save_to_db(self, data):
        self.db.execute('''
            INSERT INTO traffic_logs 
            (timestamp, method, url, host, request_headers, request_body, 
             response_status, response_headers, response_body, device_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', (
            data['timestamp'], data['method'], data['url'], data['host'],
            json.dumps(data['request_headers']), data['request_body'],
            data['response_status'], json.dumps(data['response_headers']),
            data['response_body'], data['device_id']
        ))
        self.db.commit()
    
    async def broadcast_to_clients(self, data):
        if self.connected_clients:
            message = json.dumps(data)
            await asyncio.gather(
                *[client.send(message) for client in self.connected_clients],
                return_exceptions=True
            )
    
    def get_device_id(self, flow):
        # 从请求头或IP识别设备
        return flow.client_conn.address[0]

# WebSocket服务器用于实时数据推送
async def websocket_handler(websocket, path):
    proxy_server.connected_clients.add(websocket)
    try:
        await websocket.wait_closed()
    finally:
        proxy_server.connected_clients.remove(websocket)

# 启动服务
proxy_server = MobileProxyServer()

# HTTP API服务器
from flask import Flask, jsonify, request
app = Flask(__name__)

@app.route('/api/traffic', methods=['GET'])
def get_traffic():
    device_id = request.args.get('device_id')
    limit = request.args.get('limit', 100)
    
    cursor = proxy_server.db.execute('''
        SELECT * FROM traffic_logs 
        WHERE device_id = ? 
        ORDER BY timestamp DESC 
        LIMIT ?
    ''', (device_id, limit))
    
    results = cursor.fetchall()
    return jsonify(results)

if __name__ == '__main__':
    # 启动代理服务器
    mitmdump(["-s", __file__, "--listen-port", "8888", "--set", "confdir=~/.mitmproxy"])
    
    # 启动WebSocket服务器
    start_server = websockets.serve(websocket_handler, "0.0.0.0", 8765)
    
    # 启动API服务器
    app.run(host='0.0.0.0', port=5000)
```

### 2. Android客户端修改

修改现有的Android应用，连接到远程代理：

```kotlin
// RemoteProxyManager.kt
class RemoteProxyManager(private val context: Context) {
    private val serverUrl = "wss://your-server.com:8765"
    private val apiUrl = "https://your-server.com:5000/api"
    private var webSocket: WebSocket? = null
    
    fun startRemoteCapture() {
        // 不再创建本地VPN，而是：
        // 1. 指示用户配置WiFi代理到远程服务器
        // 2. 连接WebSocket接收实时数据
        // 3. 显示代理配置信息
        
        showProxyConfigDialog()
        connectWebSocket()
    }
    
    private fun showProxyConfigDialog() {
        val dialog = AlertDialog.Builder(context)
            .setTitle("配置远程代理")
            .setMessage("""
                请在WiFi设置中配置代理：
                
                主机名: your-server.com
                端口: 8888
                
                配置完成后即可开始抓包！
            """.trimIndent())
            .setPositiveButton("复制服务器地址") { _, _ ->
                copyToClipboard("your-server.com")
            }
            .setNegativeButton("取消", null)
            .create()
        
        dialog.show()
    }
    
    private fun connectWebSocket() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(serverUrl)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                // 接收实时流量数据
                val trafficData = Gson().fromJson(text, TrafficData::class.java)
                handleNewTraffic(trafficData)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("RemoteProxy", "WebSocket连接失败", t)
            }
        })
    }
    
    private fun handleNewTraffic(data: TrafficData) {
        // 更新UI显示新的流量数据
        runOnUiThread {
            trafficList.add(0, data)
            adapter.notifyItemInserted(0)
        }
    }
    
    suspend fun getHistoryTraffic(): List<TrafficData> {
        // 从远程API获取历史数据
        val response = httpClient.get("$apiUrl/traffic?device_id=${getDeviceId()}")
        return response.body<List<TrafficData>>()
    }
}
```

## 部署步骤

### 1. 服务器端部署

```bash
# 在您的远程服务器上
# 1. 安装依赖
pip install mitmproxy flask websockets

# 2. 生成HTTPS证书
openssl req -x509 -newkey rsa:4096 -keyout key.pem -out cert.pem -days 365 -nodes

# 3. 配置防火墙
ufw allow 8888  # 代理端口
ufw allow 5000  # API端口  
ufw allow 8765  # WebSocket端口

# 4. 启动服务
python proxy_server.py
```

### 2. 域名和SSL配置

```bash
# 使用Let's Encrypt获取免费SSL证书
certbot --nginx -d your-domain.com

# 或使用Cloudflare代理（推荐）
# 1. 将域名DNS指向Cloudflare
# 2. 启用SSL/TLS加密
# 3. 配置端口转发
```

### 3. Android应用修改

修改MainActivity添加远程代理模式：

```kotlin
// 在VpnMode枚举中添加
enum class VpnMode {
    LIGHT,
    FULL, 
    SIMPLE,
    HTTP_PROXY,
    REMOTE_PROXY  // 新增
}

// 在startVpnService中添加
VpnMode.REMOTE_PROXY -> {
    val remoteManager = RemoteProxyManager(this)
    remoteManager.startRemoteCapture()
    statusText.text = "Status: Remote Proxy Active..."
}
```

## 方案优势

### ✅ 完全避开Android限制
- 无需VPN权限
- 无路由配置问题
- 无应用自我代理问题

### ✅ 功能更强大
- 多设备同时监控
- 历史数据持久化
- 实时数据推送
- 强大的服务器端分析能力

### ✅ 扩展性好
- 可以添加多种分析功能
- 支持团队协作
- 数据导出和备份
- 自定义规则和过滤

### ✅ 部署简单
- 标准的HTTP代理
- 成熟的技术栈
- 容易维护和调试

## 高级功能

### 1. 智能分析
```python
# 服务器端智能分析
def analyze_api_patterns(traffic_logs):
    # API调用频率分析
    # 异常请求检测  
    # 性能瓶颈识别
    # 安全风险评估
    pass
```

### 2. 多设备管理
```python
# 支持多个设备同时连接
device_sessions = {}

def handle_device_registration(device_id, device_info):
    device_sessions[device_id] = {
        'info': device_info,
        'last_seen': datetime.now(),
        'traffic_count': 0
    }
```

### 3. 数据可视化
```javascript
// Web管理界面
// 实时流量图表
// API调用统计
// 设备状态监控
```

这个方案完美解决了您的需求：
- 避开了所有Android VPN限制
- 利用了您的远程服务器资源
- 提供了比本地方案更强大的功能
- 易于扩展和维护

您觉得这个方案如何？我可以帮您实现任何部分！
