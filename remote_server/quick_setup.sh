#!/bin/bash

# bigjj.site 移动抓包服务器快速配置脚本
# 适用于 Ubuntu 20.04/22.04 LTS

set -e

echo "🚀 开始配置 bigjj.site 移动抓包服务器..."

# 颜色输出函数
print_info() {
    echo -e "\e[32m[INFO]\e[0m $1"
}

print_warn() {
    echo -e "\e[33m[WARN]\e[0m $1"
}

print_error() {
    echo -e "\e[31m[ERROR]\e[0m $1"
}

# 检查root权限
if [[ $EUID -eq 0 ]]; then
    print_error "请不要使用root用户运行此脚本!"
    print_info "正确用法: ./quick_setup.sh"
    exit 1
fi

# 创建快速配置目录
QUICK_DIR="$HOME/bigjj_mobile_proxy"
mkdir -p "$QUICK_DIR"
cd "$QUICK_DIR"

print_info "工作目录: $QUICK_DIR"

# 1. 下载mitmproxy证书
print_info "下载mitmproxy证书配置..."
cat > cert_setup.py << 'EOF'
#!/usr/bin/env python3
import os
import subprocess
import sys

def setup_mitmproxy_certs():
    """设置mitmproxy证书"""
    # 创建证书目录
    cert_dir = os.path.expanduser("~/.mitmproxy")
    os.makedirs(cert_dir, exist_ok=True)
    
    # 启动mitmproxy一次以生成证书
    print("正在生成mitmproxy证书...")
    try:
        subprocess.run([
            "mitmdump", 
            "--listen-port", "8888",
            "--set", "confdir=" + cert_dir
        ], timeout=5, capture_output=True)
    except subprocess.TimeoutExpired:
        pass  # 预期的超时，证书应该已经生成
    except FileNotFoundError:
        print("错误: 未找到mitmproxy，请先安装")
        return False
    
    # 检查证书文件
    cert_file = os.path.join(cert_dir, "mitmproxy-ca-cert.pem")
    if os.path.exists(cert_file):
        print(f"证书已生成: {cert_file}")
        return True
    else:
        print("证书生成失败")
        return False

if __name__ == "__main__":
    setup_mitmproxy_certs()
EOF

# 2. 创建简化的服务器脚本
print_info "创建简化的移动代理服务器..."
cat > simple_mobile_proxy.py << 'EOF'
#!/usr/bin/env python3
"""
bigjj.site 移动抓包代理服务器 - 简化版本
支持基本的HTTPS代理和Web管理界面
"""

import asyncio
import json
import sqlite3
import threading
import time
from datetime import datetime
from pathlib import Path

import websockets
from mitmproxy import http, options
from mitmproxy.tools.dump import DumpMaster
from flask import Flask, jsonify, render_template_string

# 配置
PROXY_PORT = 8888
WEBSOCKET_PORT = 8765
WEB_PORT = 5010
DB_FILE = "mobile_traffic.db"

# 全局变量
connected_clients = set()
traffic_data = []

# 数据库初始化
def init_database():
    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()
    cursor.execute('''
    CREATE TABLE IF NOT EXISTS traffic_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp TEXT,
        method TEXT,
        url TEXT,
        host TEXT,
        request_headers TEXT,
        request_body TEXT,
        response_status INTEGER,
        response_headers TEXT,
        response_body TEXT,
        device_id TEXT,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
    ''')
    conn.commit()
    conn.close()

# mitmproxy插件
class MobileProxyAddon:
    def response(self, flow: http.HTTPFlow) -> None:
        # 记录流量数据
        traffic_item = {
            "timestamp": datetime.now().isoformat(),
            "method": flow.request.method,
            "url": flow.request.pretty_url,
            "host": flow.request.pretty_host,
            "request_headers": dict(flow.request.headers),
            "request_body": flow.request.get_text(strict=False) or "",
            "response_status": flow.response.status_code,
            "response_headers": dict(flow.response.headers),
            "response_body": flow.response.get_text(strict=False) or "",
            "device_id": "mobile_device"
        }
        
        # 保存到数据库
        save_traffic_to_db(traffic_item)
        
        # 广播给WebSocket客户端
        asyncio.create_task(broadcast_traffic(traffic_item))
        
        print(f"[{traffic_item['timestamp']}] {traffic_item['method']} {traffic_item['url']} -> {traffic_item['response_status']}")

def save_traffic_to_db(data):
    """保存流量数据到数据库"""
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute('''
        INSERT INTO traffic_logs (
            timestamp, method, url, host, request_headers, 
            request_body, response_status, response_headers, 
            response_body, device_id
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', (
            data["timestamp"], data["method"], data["url"], data["host"],
            json.dumps(data["request_headers"]), data["request_body"],
            data["response_status"], json.dumps(data["response_headers"]),
            data["response_body"], data["device_id"]
        ))
        conn.commit()
        conn.close()
    except Exception as e:
        print(f"数据库保存错误: {e}")

async def broadcast_traffic(data):
    """广播流量数据给所有连接的客户端"""
    if connected_clients:
        message = json.dumps(data)
        disconnected = []
        for client in connected_clients:
            try:
                await client.send(message)
            except websockets.exceptions.ConnectionClosed:
                disconnected.append(client)
        
        # 清理断开的连接
        for client in disconnected:
            connected_clients.discard(client)

# WebSocket服务器
async def websocket_handler(websocket, path):
    connected_clients.add(websocket)
    print(f"新的WebSocket连接: {websocket.remote_address}")
    try:
        await websocket.wait_closed()
    finally:
        connected_clients.discard(websocket)
        print(f"WebSocket连接断开: {websocket.remote_address}")

# Flask Web服务器
app = Flask(__name__)

@app.route('/')
def index():
    return render_template_string('''
<!DOCTYPE html>
<html>
<head>
    <title>bigjj.site 移动抓包服务器</title>
    <meta charset="utf-8">
    <style>
        body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }
        .container { background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        .status { padding: 15px; background: #e8f5e8; border-left: 4px solid #4caf50; margin: 20px 0; }
        .config { background: #fff3cd; padding: 15px; border-left: 4px solid #ffc107; margin: 20px 0; }
        .stats { display: flex; gap: 20px; margin: 20px 0; }
        .stat-card { flex: 1; padding: 20px; background: #f8f9fa; border-radius: 8px; text-align: center; }
        .stat-number { font-size: 2em; font-weight: bold; color: #007bff; }
        pre { background: #f8f9fa; padding: 15px; border-radius: 5px; overflow-x: auto; }
        .btn { padding: 10px 20px; background: #007bff; color: white; border: none; border-radius: 5px; cursor: pointer; }
        .btn:hover { background: #0056b3; }
    </style>
</head>
<body>
    <div class="container">
        <h1>🚀 bigjj.site 移动抓包服务器</h1>
        
        <div class="status">
            ✅ 服务器正在运行<br>
            🌐 代理端口: 8888<br>
            📡 WebSocket端口: 8765<br>
            💻 Web管理端口: 5010<br>
            📱 已连接设备: <span id="connected">{{ connected_clients }}</span>
        </div>

        <div class="config">
            <h3>📱 Android设备配置</h3>
            <ol>
                <li>连接到WiFi网络</li>
                <li>打开WiFi设置 → 高级设置 → 代理</li>
                <li>选择"手动"配置代理</li>
                <li>填入以下信息：</li>
            </ol>
            <pre>主机名: bigjj.site
端口: 8888</pre>
            <p><strong>重要:</strong> 请下载并安装HTTPS证书以支持HTTPS抓包</p>
            <button class="btn" onclick="downloadCert()">📜 下载HTTPS证书</button>
        </div>

        <div class="stats">
            <div class="stat-card">
                <div class="stat-number" id="total-requests">{{ total_requests }}</div>
                <div>总请求数</div>
            </div>
            <div class="stat-card">
                <div class="stat-number" id="active-connections">{{ connected_clients }}</div>
                <div>活跃连接</div>
            </div>
        </div>

        <h3>🔧 快速操作</h3>
        <button class="btn" onclick="clearLogs()">🗑️ 清理日志</button>
        <button class="btn" onclick="exportData()">📤 导出数据</button>
        <button class="btn" onclick="viewLogs()">📋 查看日志</button>

        <h3>📚 快速指南</h3>
        <ul>
            <li><strong>mitmproxy Web界面:</strong> <a href="http://bigjj.site:8010" target="_blank">http://bigjj.site:8010</a></li>
            <li><strong>API接口:</strong> <a href="/api/status">/api/status</a> | <a href="/api/traffic">/api/traffic</a></li>
            <li><strong>服务状态:</strong> <code>systemctl status mobile-proxy</code></li>
            <li><strong>实时日志:</strong> <code>journalctl -u mobile-proxy -f</code></li>
        </ul>
    </div>

    <script>
        function downloadCert() {
            window.open('http://bigjj.site:8888/cert.pem', '_blank');
        }
        
        function clearLogs() {
            if(confirm('确定要清理所有日志吗？')) {
                fetch('/api/clear', {method: 'POST'})
                    .then(r => r.json())
                    .then(data => alert(data.message));
            }
        }
        
        function exportData() {
            window.open('/api/export', '_blank');
        }
        
        function viewLogs() {
            window.open('/api/traffic', '_blank');
        }
        
        // 自动刷新统计数据
        setInterval(() => {
            fetch('/api/status')
                .then(r => r.json())
                .then(data => {
                    document.getElementById('total-requests').textContent = data.total_requests;
                    document.getElementById('connected').textContent = data.connected_clients;
                    document.getElementById('active-connections').textContent = data.connected_clients;
                });
        }, 5010);
    </script>
</body>
</html>
    ''', 
    connected_clients=len(connected_clients),
    total_requests=get_total_requests()
    )

@app.route('/api/status')
def api_status():
    return jsonify({
        "status": "running",
        "connected_clients": len(connected_clients),
        "total_requests": get_total_requests(),
        "proxy_port": PROXY_PORT,
        "websocket_port": WEBSOCKET_PORT,
        "web_port": WEB_PORT
    })

@app.route('/api/traffic')
def api_traffic():
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute('''
        SELECT * FROM traffic_logs 
        ORDER BY created_at DESC 
        LIMIT 100
        ''')
        
        rows = cursor.fetchall()
        columns = [description[0] for description in cursor.description]
        
        traffic = []
        for row in rows:
            traffic.append(dict(zip(columns, row)))
        
        conn.close()
        return jsonify(traffic)
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/clear', methods=['POST'])
def api_clear():
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute('DELETE FROM traffic_logs')
        deleted = cursor.rowcount
        conn.commit()
        conn.close()
        return jsonify({"message": f"已清理 {deleted} 条记录"})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

def get_total_requests():
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute('SELECT COUNT(*) FROM traffic_logs')
        count = cursor.fetchone()[0]
        conn.close()
        return count
    except:
        return 0

def run_proxy():
    """运行mitmproxy代理服务器"""
    try:
        # 配置mitmproxy选项
        opts = options.Options(
            listen_port=PROXY_PORT,
            web_port=8010,  # mitmproxy web界面端口
        )
        
        # 创建并启动master
        master = DumpMaster(opts)
        master.addons.add(MobileProxyAddon())
        
        print(f"🔄 启动代理服务器在端口 {PROXY_PORT}")
        print(f"🌐 Web界面: http://bigjj.site:8010")
        
        asyncio.run(master.run())
    except Exception as e:
        print(f"代理服务器错误: {e}")

def run_websocket():
    """运行WebSocket服务器"""
    try:
        print(f"📡 启动WebSocket服务器在端口 {WEBSOCKET_PORT}")
        asyncio.run(
            websockets.serve(websocket_handler, "0.0.0.0", WEBSOCKET_PORT)
        )
    except Exception as e:
        print(f"WebSocket服务器错误: {e}")

def run_web():
    """运行Flask Web服务器"""
    print(f"💻 启动Web管理界面在端口 {WEB_PORT}")
    app.run(host="0.0.0.0", port=WEB_PORT, debug=False)

def main():
    print("🚀 启动 bigjj.site 移动抓包服务器")
    
    # 初始化数据库
    init_database()
    
    # 启动各个服务线程
    threads = []
    
    # Web服务器线程
    web_thread = threading.Thread(target=run_web, daemon=True)
    web_thread.start()
    threads.append(web_thread)
    
    # WebSocket服务器线程  
    ws_thread = threading.Thread(target=run_websocket, daemon=True)
    ws_thread.start()
    threads.append(ws_thread)
    
    # mitmproxy在主线程运行
    try:
        run_proxy()
    except KeyboardInterrupt:
        print("\n🛑 正在停止服务器...")
    except Exception as e:
        print(f"❌ 服务器错误: {e}")

if __name__ == "__main__":
    main()
EOF

# 3. 创建requirements文件
print_info "创建Python依赖文件..."
cat > requirements.txt << 'EOF'
mitmproxy>=9.0.0
flask>=2.3.0
websockets>=11.0.0
sqlite3
asyncio
EOF

# 4. 创建安装脚本
print_info "创建一键安装脚本..."
cat > install.sh << 'EOF'
#!/bin/bash

echo "🚀 安装 bigjj.site 移动抓包服务器..."

# 更新系统包
echo "📦 更新系统包..."
sudo apt update

# 安装Python3和pip
echo "🐍 安装Python环境..."
sudo apt install -y python3 python3-pip python3-venv

# 创建虚拟环境
echo "🏗️ 创建Python虚拟环境..."
python3 -m venv venv
source venv/bin/activate

# 安装Python依赖
echo "📋 安装Python依赖..."
pip install --upgrade pip
pip install -r requirements.txt

# 生成证书
echo "🔐 生成证书..."
python3 cert_setup.py

# 创建systemd服务
echo "⚙️ 创建系统服务..."
sudo tee /etc/systemd/system/bigjj-mobile-proxy.service > /dev/null << EOL
[Unit]
Description=bigjj.site Mobile Proxy Server
After=network.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$PWD
Environment=PATH=$PWD/venv/bin
ExecStart=$PWD/venv/bin/python simple_mobile_proxy.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOL

# 启用服务
sudo systemctl daemon-reload
sudo systemctl enable bigjj-mobile-proxy

# 配置防火墙
echo "🔥 配置防火墙..."
sudo ufw allow 5010
sudo ufw allow 8010
sudo ufw allow 8765
sudo ufw allow 8888

echo "✅ 安装完成!"
echo ""
echo "🎯 下一步操作:"
echo "1. 启动服务: sudo systemctl start bigjj-mobile-proxy"
echo "2. 查看状态: sudo systemctl status bigjj-mobile-proxy"
echo "3. 访问管理页面: http://bigjj.site:5010"
echo "4. Android设备配置代理: bigjj.site:8888"
echo ""
echo "📱 Android配置步骤:"
echo "1. WiFi设置 → 高级 → 代理 → 手动"
echo "2. 主机名: bigjj.site"
echo "3. 端口: 8888"
echo "4. 下载证书: http://bigjj.site:8888/cert.pem"
EOF

chmod +x install.sh

# 5. 创建快速管理脚本
print_info "创建管理脚本..."
cat > manage.sh << 'EOF'
#!/bin/bash

SERVICE_NAME="bigjj-mobile-proxy"

case "$1" in
    start)
        echo "🚀 启动 bigjj.site 移动代理服务..."
        sudo systemctl start $SERVICE_NAME
        ;;
    stop)
        echo "🛑 停止 bigjj.site 移动代理服务..."
        sudo systemctl stop $SERVICE_NAME
        ;;
    restart)
        echo "🔄 重启 bigjj.site 移动代理服务..."
        sudo systemctl restart $SERVICE_NAME
        ;;
    status)
        echo "📊 bigjj.site 移动代理服务状态:"
        sudo systemctl status $SERVICE_NAME
        ;;
    logs)
        echo "📋 实时日志 (Ctrl+C 退出):"
        sudo journalctl -u $SERVICE_NAME -f
        ;;
    install)
        echo "📦 安装服务..."
        ./install.sh
        ;;
    test)
        echo "🧪 测试连接..."
        echo "测试代理端口 8888:"
        nc -zv bigjj.site 8888
        echo "测试Web端口 5010:"
        nc -zv bigjj.site 5010
        echo "测试WebSocket端口 8765:"
        nc -zv bigjj.site 8765
        ;;
    cert)
        echo "📜 下载证书..."
        curl -O http://bigjj.site:8888/cert.pem
        echo "证书已下载到当前目录: cert.pem"
        ;;
    clean)
        echo "🗑️ 清理数据库..."
        if [ -f "mobile_traffic.db" ]; then
            sqlite3 mobile_traffic.db "DELETE FROM traffic_logs;"
            echo "✅ 数据库已清理"
        else
            echo "❌ 未找到数据库文件"
        fi
        ;;
    *)
        echo "bigjj.site 移动抓包服务器管理工具"
        echo ""
        echo "用法: $0 {start|stop|restart|status|logs|install|test|cert|clean}"
        echo ""
        echo "命令说明:"
        echo "  start    - 启动服务"
        echo "  stop     - 停止服务"  
        echo "  restart  - 重启服务"
        echo "  status   - 查看状态"
        echo "  logs     - 查看实时日志"
        echo "  install  - 安装服务"
        echo "  test     - 测试网络连接"
        echo "  cert     - 下载HTTPS证书"
        echo "  clean    - 清理数据库"
        exit 1
esac
EOF

chmod +x manage.sh

# 6. 创建快速开始指南
cat > QUICK_START.md << 'EOF'
# bigjj.site 移动抓包服务器 - 快速开始

## 🚀 一键安装

```bash
./install.sh
```

## 📱 Android配置

1. **连接WiFi并配置代理:**
   - 设置 → WiFi → 高级设置 → 代理 → 手动
   - 主机名: `bigjj.site`
   - 端口: `8888`

2. **安装HTTPS证书:**
   ```bash
   # 下载证书到手机
   在浏览器访问: http://bigjj.site:8888/cert.pem
   
   # 或使用管理脚本下载
   ./manage.sh cert
   ```

3. **安装证书到Android:**
   - 设置 → 安全 → 加密与凭据 → 从存储设备安装
   - 选择下载的 cert.pem 文件
   - 设置为"VPN和应用"用途

## 🔧 服务管理

```bash
./manage.sh start      # 启动服务
./manage.sh stop       # 停止服务
./manage.sh restart    # 重启服务
./manage.sh status     # 查看状态
./manage.sh logs       # 查看日志
./manage.sh test       # 测试连接
```

## 🌐 访问界面

- **状态监控:** http://bigjj.site:5010
- **抓包分析:** http://bigjj.site:8010
- **API接口:** http://bigjj.site:5010/api/status

## ⚡ 常用操作

```bash
# 清理旧数据
./manage.sh clean

# 查看实时日志
./manage.sh logs

# 测试网络连通性
./manage.sh test

# 重新下载证书
./manage.sh cert
```

## 🔍 故障排除

1. **服务无法启动:**
   ```bash
   sudo systemctl status bigjj-mobile-proxy
   sudo journalctl -u bigjj-mobile-proxy
   ```

2. **端口被占用:**
   ```bash
   sudo netstat -tlnp | grep -E '(5010|8010|8765|8888)'
   ```

3. **防火墙问题:**
   ```bash
   sudo ufw status
   sudo ufw allow 8888
   ```

4. **证书问题:**
   ```bash
   curl -I http://bigjj.site:8888/cert.pem
   ```

## 📊 性能监控

```bash
# 查看数据库大小
ls -lh mobile_traffic.db

# 统计记录数量
sqlite3 mobile_traffic.db "SELECT COUNT(*) FROM traffic_logs;"

# 查看最近的请求
sqlite3 mobile_traffic.db "SELECT method, url, response_status FROM traffic_logs ORDER BY created_at DESC LIMIT 10;"
```

---

**🎉 享受您的专属移动抓包服务！**
EOF

print_info "✅ 快速配置完成!"
print_info ""
print_info "📁 配置文件位置: $QUICK_DIR"
print_info "📋 包含以下文件:"
echo "   - simple_mobile_proxy.py (简化服务器)"
echo "   - install.sh (一键安装脚本)"
echo "   - manage.sh (服务管理脚本)"
echo "   - requirements.txt (Python依赖)"
echo "   - cert_setup.py (证书配置)"
echo "   - QUICK_START.md (快速开始指南)"
print_info ""
print_info "🚀 下一步操作:"
print_info "1. 运行安装脚本: ./install.sh"
print_info "2. 启动服务: ./manage.sh start"
print_info "3. 访问管理页面: http://bigjj.site:5010"
print_info "4. 配置Android代理: bigjj.site:8888"
print_info ""
print_warn "💡 提示: 如果需要完整功能，请使用 remote_server/ 目录中的完整版本"
