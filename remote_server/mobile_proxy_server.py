#!/usr/bin/env python3
"""
移动抓包远程代理服务器
为 bigjj.site 域名定制版本
"""

import asyncio
import json
import os
import sqlite3
import ssl
import traceback
import websockets
import threading
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse

# 尝试导入mitmproxy模块
try:
    from mitmproxy import http
    from mitmproxy.tools.main import mitmdump
    MITMPROXY_AVAILABLE = True
except ImportError:
    MITMPROXY_AVAILABLE = False
    print("⚠️ mitmproxy模块未安装，部分功能可能受限")

class TrafficDatabase:
    def __init__(self, db_path='mobile_traffic.db'):
        self.db_path = db_path
        self.init_database()
    
    def init_database(self):
        conn = sqlite3.connect(self.db_path)
        conn.execute('''
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
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        ''')
        conn.commit()
        conn.close()
        print("✅ 数据库初始化完成")
    
    def save_traffic(self, data):
        try:
            conn = sqlite3.connect(self.db_path)
            conn.execute('''
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
            conn.commit()
            conn.close()
        except Exception as e:
            print(f"❌ 保存流量数据失败: {e}")
    
    def get_traffic(self, device_id, limit=100):
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.execute('''
                SELECT timestamp, method, url, host, request_headers, request_body,
                       response_status, response_headers, response_body, device_id
                FROM traffic_logs 
                WHERE device_id = ? 
                ORDER BY created_at DESC 
                LIMIT ?
            ''', (device_id, limit))
            results = []
            for row in cursor.fetchall():
                results.append({
                    'timestamp': row[0],
                    'method': row[1], 
                    'url': row[2],
                    'host': row[3],
                    'request_headers': json.loads(row[4]) if row[4] else {},
                    'request_body': row[5],
                    'response_status': row[6],
                    'response_headers': json.loads(row[7]) if row[7] else {},
                    'response_body': row[8],
                    'device_id': row[9]
                })
            conn.close()
            return results
        except Exception as e:
            print(f"❌ 获取流量数据失败: {e}")
            return []

class MobileProxyAddon:
    def __init__(self):
        self.db = TrafficDatabase()
        self.websocket_clients = set()
        self.traffic_count = 0
    
    def add_websocket_client(self, websocket):
        self.websocket_clients.add(websocket)
        print(f"📱 设备连接: {len(self.websocket_clients)} 个活跃连接")
    
    def remove_websocket_client(self, websocket):
        self.websocket_clients.discard(websocket)
        print(f"📱 设备断开: {len(self.websocket_clients)} 个活跃连接")
    
    def request(self, flow):
        # 记录请求开始时间
        flow.metadata['start_time'] = datetime.now()
    
    def response(self, flow):
        try:
            # 提取设备信息
            device_id = self.get_device_id(flow)
            
            # 构造流量数据
            traffic_data = {
                'timestamp': datetime.now().isoformat(),
                'method': flow.request.method,
                'url': flow.request.pretty_url,
                'host': flow.request.pretty_host,
                'request_headers': dict(flow.request.headers),
                'request_body': self.safe_get_text(flow.request)[:4096],
                'response_status': flow.response.status_code,
                'response_headers': dict(flow.response.headers),
                'response_body': self.safe_get_text(flow.response)[:4096],
                'device_id': device_id
            }
            
            self.traffic_count += 1
            print(f"🌐 [{self.traffic_count}] [{device_id}] {flow.request.method} {flow.request.pretty_url} -> {flow.response.status_code}")
            print(f"[DEBUG] 当前WebSocket客户端数: {len(self.websocket_clients)}, 流量计数: {self.traffic_count}")
            
            # 保存到数据库
            self.db.save_traffic(traffic_data)
            
            # 推送到WebSocket客户端
            if self.websocket_clients:
                # 使用线程安全的方式发送数据
                def send_async():
                    try:
                        loop = asyncio.new_event_loop()
                        asyncio.set_event_loop(loop)
                        loop.run_until_complete(self.broadcast_to_clients(traffic_data))
                        loop.close()
                    except Exception as e:
                        print(f"📱 WebSocket广播失败: {e}")
                
                thread = threading.Thread(target=send_async)
                thread.daemon = True
                thread.start()
            
        except Exception as e:
            print(f"❌ 处理流量数据失败: {e}")
    
    def safe_get_text(self, message):
        try:
            text = message.text
            if text:
                return text
            else:
                return f'<Binary data: {len(message.raw_content)} bytes>'
        except:
            return f'<Binary data: {len(message.raw_content)} bytes>'
    
    def get_device_id(self, flow):
        # 从请求头或IP识别设备
        client_ip = flow.client_conn.address[0]
        user_agent = flow.request.headers.get('User-Agent', '')
        
        if 'TrafficCapture' in user_agent:
            return f"android_{client_ip.replace('.', '_')}"
        elif 'Android' in user_agent:
            return f"mobile_{client_ip.replace('.', '_')}"
        else:
            return f"device_{client_ip.replace('.', '_')}"
    
    async def broadcast_to_clients(self, data):
        if not self.websocket_clients:
            return
            
        message = json.dumps(data)
        disconnected = set()
        
        for client in self.websocket_clients:
            try:
                await client.send(message)
            except Exception as e:
                print(f"📱 WebSocket发送失败: {e}")
                disconnected.add(client)
        
        # 清理断开的连接
        for client in disconnected:
            self.websocket_clients.discard(client)

# 全局addon实例 (统一使用这一个)
addon_instance = None

def get_addon_instance():
    """获取或创建addon实例"""
    global addon_instance
    if addon_instance is None:
        addon_instance = MobileProxyAddon()
        print("✅ 创建MobileProxyAddon实例")
    return addon_instance

class APIHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            parsed_path = urllib.parse.urlparse(self.path)
            
            if parsed_path.path == '/api/traffic':
                # 获取流量数据
                query_params = urllib.parse.parse_qs(parsed_path.query)
                device_id = query_params.get('device_id', [''])[0]
                limit = int(query_params.get('limit', ['100'])[0])
                
                addon = get_addon_instance()
                results = addon.db.get_traffic(device_id, limit)
                
                self.send_response(200)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                
                self.wfile.write(json.dumps(results, ensure_ascii=False).encode('utf-8'))
                print(f"📊 API请求: 返回 {len(results)} 条记录给设备 {device_id}")
            
            elif parsed_path.path == '/api/status':
                # 服务器状态
                addon = get_addon_instance()
                status = {
                    'status': 'running',
                    'domain': 'bigjj.site',
                    'active_connections': len(addon.websocket_clients),
                    'total_traffic': addon.traffic_count,
                    'timestamp': datetime.now().isoformat()
                }
                
                self.send_response(200)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                
                self.wfile.write(json.dumps(status, ensure_ascii=False).encode('utf-8'))
                print("📊 状态查询请求")
            
            elif parsed_path.path == '/cert.pem':
                # 提供mitmproxy证书下载
                try:
                    cert_path = os.path.expanduser('~/.mitmproxy/mitmproxy-ca-cert.pem')
                    if os.path.exists(cert_path):
                        with open(cert_path, 'rb') as f:
                            cert_data = f.read()
                        
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/x-pem-file')
                        self.send_header('Content-Disposition', 'attachment; filename="mitmproxy-ca-cert.pem"')
                        self.send_header('Access-Control-Allow-Origin', '*')
                        self.end_headers()
                        self.wfile.write(cert_data)
                        print("📜 证书下载请求")
                    else:
                        # 如果证书不存在，提供帮助信息
                        help_html = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <title>证书下载</title>
                            <meta charset="utf-8">
                        </head>
                        <body>
                            <h1>📜 mitmproxy 证书</h1>
                            <p>❌ 证书文件未找到</p>
                            <p>请确保mitmproxy已启动并生成了证书</p>
                            <h2>替代方案：</h2>
                            <ol>
                                <li>配置代理后访问: <a href="http://mitm.it">http://mitm.it</a></li>
                                <li>选择Android选项下载证书</li>
                                <li>在设置中安装证书</li>
                            </ol>
                        </body>
                        </html>
                        """
                        self.send_response(404)
                        self.send_header('Content-Type', 'text/html; charset=utf-8')
                        self.end_headers()
                        self.wfile.write(help_html.encode('utf-8'))
                except Exception as e:
                    self.send_response(500)
                    self.send_header('Content-Type', 'text/plain')
                    self.end_headers()
                    self.wfile.write(f'证书下载失败: {e}'.encode('utf-8'))
            
            elif parsed_path.path == '/':
                # 简单的状态页面
                addon = get_addon_instance()
                
                # 获取统计信息
                websocket_count = len(addon.websocket_clients)
                traffic_count = addon.traffic_count
                
                html = f"""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>bigjj.site 移动抓包代理服务器</title>
                    <meta charset="utf-8">
                    <meta http-equiv="refresh" content="5">
                    <style>
                        body {{ font-family: Arial, sans-serif; margin: 20px; }}
                        .status {{ background: #e8f5e8; padding: 10px; border-radius: 5px; margin: 10px 0; }}
                        .cert-download {{ background: #f0f8ff; padding: 15px; border-radius: 5px; margin: 20px 0; }}
                        .cert-download a {{ color: #1e90ff; text-decoration: none; }}
                        .cert-download a:hover {{ text-decoration: underline; }}
                        .stats {{ font-size: 18px; font-weight: bold; }}
                    </style>
                </head>
                <body>
                    <h1>🚀 bigjj.site 移动抓包代理服务器</h1>
                    
                    <div class="status">
                        <p>✅ 服务器正在运行</p>
                        <div class="stats">
                            <p>📱 活跃WebSocket连接: {websocket_count}</p>
                            <p>🌐 代理流量总数: {traffic_count}</p>
                            <p>⏰ 更新时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>
                        </div>
                    </div>
                    
                    <div class="cert-download">
                        <h2>🔒 HTTPS证书下载</h2>
                        <p>要解密HTTPS流量，请下载并安装证书：</p>
                        <ul>
                            <li><a href="/cert.pem">📜 下载mitmproxy证书</a></li>
                            <li><a href="http://mitm.it" target="_blank">🌐 访问 mitm.it 获取证书</a> (需先配置代理)</li>
                        </ul>
                    </div>
                    
                    <h2>配置信息</h2>
                    <ul>
                        <li>代理地址: bigjj.site:8888</li>
                        <li>WebSocket: ws://bigjj.site:8765 (普通连接)</li>
                        <li>API接口: http://bigjj.site:5010</li>
                        <li>Web管理: http://bigjj.site:8010</li>
                    </ul>
                    
                    <h2>Android配置步骤</h2>
                    <ol>
                        <li>WiFi设置 → 修改网络 → 高级选项</li>
                        <li>代理: 手动</li>
                        <li>主机名: bigjj.site</li>
                        <li>端口: 8888</li>
                    </ol>
                    
                    <p><small>页面每5秒自动刷新</small></p>
                </body>
                </html>
                """
                
                self.send_response(200)
                self.send_header('Content-Type', 'text/html; charset=utf-8')
                self.end_headers()
                self.wfile.write(html.encode('utf-8'))
            
            else:
                self.send_response(404)
                self.send_header('Content-Type', 'text/plain')
                self.end_headers()
                self.wfile.write(b'404 Not Found')
                
        except Exception as e:
            print(f"❌ API请求处理失败: {e}")
            self.send_response(500)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(f'500 Internal Server Error: {e}'.encode())
    
    def log_message(self, format, *args):
        # 禁用默认HTTP日志，减少输出噪音
        pass

async def websocket_handler(websocket, path):
    """WebSocket连接处理"""
    client_info = f"{websocket.remote_address[0]}:{websocket.remote_address[1]}"
    print(f"📱 WebSocket连接: {client_info}")
    
    addon = get_addon_instance()
    addon.add_websocket_client(websocket)
    
    try:
        # 发送欢迎消息
        welcome = {
            'type': 'welcome',
            'server': 'bigjj.site',
            'timestamp': datetime.now().isoformat()
        }
        await websocket.send(json.dumps(welcome))
        
        # 等待连接关闭
        await websocket.wait_closed()
    except Exception as e:
        print(f"📱 WebSocket错误: {e}")
    finally:
        addon.remove_websocket_client(websocket)
        print(f"📱 WebSocket断开: {client_info}")

def start_api_server(port=5010, use_ssl=False):
    """启动HTTP API服务器"""
    try:
        server = HTTPServer(('0.0.0.0', port), APIHandler)
        
        if use_ssl:
            # 查找SSL证书文件
            cert_paths = [
                '/etc/letsencrypt/live/bigjj.site/fullchain.pem',  # Let's Encrypt
                '/etc/ssl/certs/bigjj.site.crt',                   # 自定义证书
                '/opt/mobile-proxy/cert.pem'                       # 本地证书
            ]
            key_paths = [
                '/etc/letsencrypt/live/bigjj.site/privkey.pem',    # Let's Encrypt
                '/etc/ssl/private/bigjj.site.key',                 # 自定义私钥
                '/opt/mobile-proxy/key.pem'                        # 本地私钥
            ]
            
            cert_file = None
            key_file = None
            
            for cert_path in cert_paths:
                if os.path.exists(cert_path):
                    cert_file = cert_path
                    break
                    
            for key_path in key_paths:
                if os.path.exists(key_path):
                    key_file = key_path
                    break
            
            if cert_file and key_file:
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                context.load_cert_chain(cert_file, key_file)
                server.socket = context.wrap_socket(server.socket, server_side=True)
                print(f"🔒 HTTPS API服务器启动在端口 {port} (SSL: {cert_file})")
            else:
                print(f"⚠️ SSL证书未找到，使用HTTP模式在端口 {port}")
        else:
            print(f"🔗 HTTP API服务器启动在端口 {port}")
            
        server.serve_forever()
    except Exception as e:
        print(f"❌ API服务器启动失败: {e}")
        traceback.print_exc()

def start_websocket_server(port=8765, use_ssl=False):
    """启动WebSocket服务器"""
    try:
        print(f"📱 WebSocket服务器启动在端口 {port}")
        
        # 创建新的事件循环（在独立线程中）
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        
        async def run_server():
            ssl_context = None
            
            if use_ssl:
                # 查找SSL证书文件
                cert_paths = [
                    '/etc/letsencrypt/live/bigjj.site/fullchain.pem',
                    '/etc/ssl/certs/bigjj.site.crt',
                    '/opt/mobile-proxy/cert.pem'
                ]
                key_paths = [
                    '/etc/letsencrypt/live/bigjj.site/privkey.pem',
                    '/etc/ssl/private/bigjj.site.key',
                    '/opt/mobile-proxy/key.pem'
                ]
                
                cert_file = None
                key_file = None
                
                for cert_path in cert_paths:
                    if os.path.exists(cert_path):
                        cert_file = cert_path
                        break
                
                for key_path in key_paths:
                    if os.path.exists(key_path):
                        key_file = key_path
                        break
                
                if cert_file and key_file:
                    ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                    ssl_context.load_cert_chain(cert_file, key_file)
                    print(f"🔒 WSS WebSocket服务器 (SSL: {cert_file})")
                else:
                    print(f"⚠️ SSL证书未找到，使用WS模式 (ws://bigjj.site:8765)")
            
            server = await websockets.serve(
                lambda websocket: websocket_handler(websocket, websocket.path), 
                "0.0.0.0", 
                port, 
                ssl=ssl_context,
                ping_interval=20,
                ping_timeout=10,
                close_timeout=10
            )
            print(f"✅ WebSocket服务器成功绑定到 0.0.0.0:{port}")
            await server.wait_closed()
        
        # 在独立的事件循环中运行
        loop.run_until_complete(run_server())
    except Exception as e:
        print(f"❌ WebSocket服务器启动失败: {e}")
        traceback.print_exc()

async def run_mitmproxy_async(addon, opts):
    """异步运行mitmproxy"""
    from mitmproxy.tools.dump import DumpMaster
    
    # 创建DumpMaster，现在我们在运行的事件循环中
    master = DumpMaster(opts)
    master.addons.add(addon)
    
    print("✅ Addon已注册到mitmproxy")
    
    # 运行mitmproxy（Master.run() 是一个coroutine）
    await master.run()

def main():
    print("�🚀 bigjj.site 移动抓包远程代理服务器")
    print("=" * 60)
    
    # 创建addon实例
    addon = get_addon_instance()
    print("✅ TrafficCaptureAddon 实例已创建")
    
    # 启动HTTP API服务器 (线程)
    api_thread = threading.Thread(target=start_api_server, args=(5010,))
    api_thread.daemon = True
    api_thread.start()
    
    # 启动WebSocket服务器 (线程)
    ws_thread = threading.Thread(target=start_websocket_server, args=(8765,))
    ws_thread.daemon = True
    ws_thread.start()
    
    print("🌍 域名: bigjj.site")
    print("📡 代理服务器: bigjj.site:8888")
    print("📱 WebSocket: ws://bigjj.site:8765 (普通连接)")
    print("🔗 API接口: http://bigjj.site:5010")
    print("🌐 状态页面: http://bigjj.site:5010")
    print("=" * 60)
    print("✅ 所有服务启动完成！")
    print("📱 请在Android应用中选择'远程代理'模式并配置WiFi代理。")
    print("🔍 访问 https://bigjj.site:5010 查看服务器状态")
    print("=" * 60)
    
    try:
        # 启动mitmproxy (主线程) - 允许所有连接
        print("🔄 启动mitmproxy代理服务器...")
        print(f"📄 加载Addon: {addon.__class__.__name__}")
        
        # 检查mitmproxy是否可用
        if not MITMPROXY_AVAILABLE:
            print("❌ mitmproxy未安装，无法启动代理服务器")
            print("📝 请在生产环境中安装: pip install mitmproxy")
            return
        
        try:
            from mitmproxy import options
        except ImportError as e:
            print(f"❌ 导入mitmproxy模块失败: {e}")
            print("📝 请确保已安装mitmproxy: pip install mitmproxy")
            return
        
        # 配置mitmproxy选项 - 允许所有连接
        opts = options.Options(
            listen_port=8888,
            confdir="~/.mitmproxy",
            mode=["regular@8888"],
            ssl_insecure=True,
            # 不使用block_global选项，默认情况下mitmproxy允许所有连接
            # allow_hosts和ignore_hosts默认为空，表示允许所有主机
        )
        
        # 使用asyncio.run运行异步函数，这会创建并运行事件循环
        asyncio.run(run_mitmproxy_async(addon, opts))
        
    except KeyboardInterrupt:
        print("\n🛑 服务器正在关闭...")
    except Exception as e:
        print(f"❌ 代理服务器启动失败: {e}")
        traceback.print_exc()

# mitmproxy脚本加载函数 (必须)
def addons():
    """mitmproxy会调用这个函数来获取addon"""
    addon = get_addon_instance()
    print("✅ 通过addons()函数返回TrafficCaptureAddon实例")
    return [addon]

if __name__ == '__main__':
    main()
