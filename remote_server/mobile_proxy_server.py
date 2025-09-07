#!/usr/bin/env python3
"""
移动抓包远程代琁E��务器
为 bigjj.site 域名定制版本
"""

import asyncio
import json
import os
import sqlite3
import ssl
import traceback
import inspect
import websockets
import threading
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse

# WebSocket/API 是否启用 SSL�E�用于页面与状态展示�E�E
WS_USE_SSL = False
API_USE_SSL = False

# 注意：不�E使用自定乁Emitmproxy�E�而是依赖现有的 mitmweb.service


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
        print("✁E数据库�E始化完�E")
    
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
            print(f"❁E保存流E��数据失败: {e}")
    
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
            print(f"❁E获取流E��数据失败: {e}")
            return []

class MobileProxyAddon:
    def __init__(self):
        self.db = TrafficDatabase()
        self.websocket_clients = set()
        self.traffic_count = 0
    
    def add_websocket_client(self, websocket):
        self.websocket_clients.add(websocket)
        print(f"📱 设夁E��接: {len(self.websocket_clients)} 个活跁E��接")
    
    def remove_websocket_client(self, websocket):
        self.websocket_clients.discard(websocket)
        print(f"📱 设夁E��开: {len(self.websocket_clients)} 个活跁E��接")
    
    def request(self, flow):
        # 记录请求开始时间
        flow.metadata['start_time'] = datetime.now()
    
    def response(self, flow):
        try:
            # 提取设夁E��息
            device_id = self.get_device_id(flow)
            
            # 极E��流E��数据
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
            print(f"[DEBUG] 当前WebSocket客户端数: {len(self.websocket_clients)}, 流E��计数: {self.traffic_count}")
            
            # 保存到数据庁E
            self.db.save_traffic(traffic_data)
            
            # 推送到WebSocket客户端
            if self.websocket_clients:
                # 使用线程安�E皁E��式发送数据
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
            print(f"❁E夁E��流E��数据失败: {e}")
    
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
        # 从请求头或IP证E��设夁E
        # 使用peername替代address以避免弁E��警呁E
        client_addr = getattr(flow.client_conn, 'peername', flow.client_conn.address)
        client_ip = client_addr[0] if client_addr else "unknown"
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
        
        # 渁E��断开皁E��接
        for client in disconnected:
            self.websocket_clients.discard(client)

# 全局addon实侁E(统一使用这一个)
addon_instance = None

def get_addon_instance():
    """获取�E创建addon实侁E""
    global addon_instance
    if addon_instance is None:
        addon_instance = MobileProxyAddon()
        print("✁E创建MobileProxyAddon实侁E)
    return addon_instance

class APIHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            parsed_path = urllib.parse.urlparse(self.path)
            
            if parsed_path.path == '/api/traffic':
                # 获取流E��数据
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
                print(f"📊 API请汁E 返回 {len(results)} 条记录给设夁E{device_id}")
            
            elif parsed_path.path == '/api/status':
                # 服务器状态E
                addon = get_addon_instance()
                status = {
                    'status': 'running',
                    'domain': 'bigjj.site',
                    'ws_scheme': 'wss' if WS_USE_SSL else 'ws',
                    'ws_url': f"{'wss' if WS_USE_SSL else 'ws'}://bigjj.site:8765",
                    'api_scheme': 'https' if API_USE_SSL else 'http',
                    'api_url': f"{'https' if API_USE_SSL else 'http'}://bigjj.site:5010",
                    'active_connections': len(addon.websocket_clients),
                    'total_traffic': addon.traffic_count,
                    'timestamp': datetime.now().isoformat()
                }
                
                self.send_response(200)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                
                self.wfile.write(json.dumps(status, ensure_ascii=False).encode('utf-8'))
                print("📊 状态查询请汁E)
            
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
                        print("📜 证书下载请汁E)
                    else:
                        # 如果证书不存在�E�提供帮助信息
                        help_html = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <title>证书下载</title>
                            <meta charset="utf-8">
                        </head>
                        <body>
                            <h1>📜 mitmproxy 证书</h1>
                            <p>❁E证书斁E��未找到</p>
                            <p>请确保mitmproxy已启动并生�E亁E��书</p>
                            <h2>替代方案！E/h2>
                            <ol>
                                <li>配置代琁E��访问: <a href="http://mitm.it">http://mitm.it</a></li>
                                <li>选择Android选项下载证书</li>
                                <li>在设置中安裁E��书</li>
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
                # 简单的状态E��面
                addon = get_addon_instance()
                
                # 获取统计信息
                websocket_count = len(addon.websocket_clients)
                traffic_count = addon.traffic_count
                
                # 根据当前WebSocket/API模式显示正确的schema
                ws_schema = 'wss' if WS_USE_SSL else 'ws'
                api_schema = 'https' if API_USE_SSL else 'http'
                html = f"""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>bigjj.site 移动抓包代琁E��务器</title>
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
                    <h1>🚀 bigjj.site 移动抓包代琁E��务器</h1>
                    
                    <div class="status">
                        <p>✁E服务器正在运衁E/p>
                        <div class="stats">
                            <p>📱 活跃WebSocket连接: {websocket_count}</p>
                            <p>🌐 代琁E��E��总数: {traffic_count}</p>
                            <p>⏰ 更新时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>
                        </div>
                    </div>
                    
                    <div class="cert-download">
                        <h2>🔒 HTTPS证书下载</h2>
                        <p>要解寁ETTPS流E���E�请下载并安裁E��书�E�E/p>
                        <ul>
                            <li><a href="/cert.pem">📜 下载mitmproxy证书</a></li>
                            <li><a href="http://mitm.it" target="_blank">🌐 访问 mitm.it 获取证书</a> (需先�E置代琁E</li>
                        </ul>
                    </div>
                    
                    <h2>配置信息</h2>
                    <ul>
                        <li>代琁E��址: bigjj.site:8080</li>  <!-- 使用 mitmweb 服务 -->
                        <li>WebSocket: {ws_schema}://bigjj.site:8765</li>
                        <li>API接口: {api_schema}://bigjj.site:5010</li>
                        <li>Web管琁E http://bigjj.site:8010</li>
                    </ul>
                    
                    <h2>Android配置步骤</h2>
                    <ol>
                        <li>WiFi设置 ↁE修改网绁EↁE高级选项</li>
                        <li>代琁E 手动</li>
                        <li>主机吁E bigjj.site</li>
                        <li>端口: 8080</li>  <!-- 使用 mitmweb 服务 -->
                    </ol>
                    
                    <p><small>页面毁E秒�E动刷新</small></p>
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
            print(f"❁EAPI请求夁E��失败: {e}")
            self.send_response(500)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(f'500 Internal Server Error: {e}'.encode())
    
    def log_message(self, format, *args):
        # 禁用默认HTTP日志，减少输�E噪音
        pass

async def websocket_handler(*args):
    """兼容websockets不同版本签吁E (websocket, path) 戁E(websocket,)"""
    if len(args) == 2:
        websocket, _ = args
    else:
        websocket = args[0]
    try:
        client_host, client_port = websocket.remote_address[:2]
        client_info = f"{client_host}:{client_port}"
    except Exception:
        client_info = "unknown"
    print(f"📱 WebSocket连接: {client_info}")

    addon = get_addon_instance()
    addon.add_websocket_client(websocket)

    try:
        welcome = {
            'type': 'welcome',
            'server': 'bigjj.site',
            'timestamp': datetime.now().isoformat()
        }
        await websocket.send(json.dumps(welcome))
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
            # 查找SSL证书斁E��
            cert_paths = [
                '/etc/letsencrypt/live/bigjj.site/fullchain.pem',  # Let's Encrypt
                '/etc/ssl/certs/bigjj.site.crt',                   # 自定义证书
                '/opt/mobile-proxy/cert.pem'                       # 本地证书
            ]
            key_paths = [
                '/etc/letsencrypt/live/bigjj.site/privkey.pem',    # Let's Encrypt
                '/etc/ssl/private/bigjj.site.key',                 # 自定义私E��
                '/opt/mobile-proxy/key.pem'                        # 本地私E��
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
                global API_USE_SSL
                API_USE_SSL = True
            else:
                print(f"⚠�E�ESSL证书未找到�E�使用HTTP模式在端口 {port}")
        else:
            print(f"🔗 HTTP API服务器启动在端口 {port}")
            
        server.serve_forever()
    except Exception as e:
        print(f"❁EAPI服务器启动失败: {e}")
        traceback.print_exc()

def start_websocket_server(port=8765, use_ssl=False):
    """启动WebSocket服务器"""
    try:
        print(f"📱 WebSocket服务器启动在端口 {port}")

        # 创建新皁E��件循环�E�在独立线程中�E�E
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        async def run_server():
            ssl_context = None

            if use_ssl:
                # 查找SSL证书斁E��
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
                    print(f"⚠�E�ESSL证书未找到�E�使用WS模弁E(ws://bigjj.site:8765)")

            # 在实际是否启用SSL皁E��果基础上更新展示用开关
            global WS_USE_SSL
            WS_USE_SSL = bool(ssl_context is not None)

            server = await websockets.serve(
                websocket_handler,
                "0.0.0.0",
                port,
                ssl=ssl_context,
                ping_interval=20,
                ping_timeout=10,
                close_timeout=10
            )
            print(f"✁EWebSocket服务器成功绑定到 0.0.0.0:{port}")
            await server.wait_closed()

        # 在独立的事件循环中运衁E
        loop.run_until_complete(run_server())
    except Exception as e:
        print(f"❁EWebSocket服务器启动失败: {e}")
        traceback.print_exc()



def main():
    # 启动横幁E
    print("🚀 bigjj.site 移动抓包远程代琁E��务器")
    print("=" * 60)
    
    # 创建addon实侁E
    addon = get_addon_instance()
    print("✁ETrafficCaptureAddon 实例已创建")
    
    # 启动HTTP API服务器 (线稁E - 优�E尝试启用HTTPS�E�若证书存在�E�E
    api_use_ssl = any([
        os.path.exists('/etc/letsencrypt/live/bigjj.site/fullchain.pem') and os.path.exists('/etc/letsencrypt/live/bigjj.site/privkey.pem'),
        os.path.exists('/etc/ssl/certs/bigjj.site.crt') and os.path.exists('/etc/ssl/private/bigjj.site.key'),
        os.path.exists('/opt/mobile-proxy/cert.pem') and os.path.exists('/opt/mobile-proxy/key.pem')
    ])
    api_thread = threading.Thread(target=start_api_server, args=(5010, api_use_ssl))
    api_thread.daemon = True
    api_thread.start()
    
    # 启动WebSocket服务器 (线稁E
    # 仁E��存在有效皁ELet's Encrypt 证书时启用 WSS�E��E签名默认禁用�E�避免移动端 TLS 失败
    le_cert = '/etc/letsencrypt/live/bigjj.site/fullchain.pem'
    le_key = '/etc/letsencrypt/live/bigjj.site/privkey.pem'
    ws_use_ssl = os.path.exists(le_cert) and os.path.exists(le_key)
    ws_thread = threading.Thread(target=start_websocket_server, args=(8765, ws_use_ssl))
    ws_thread.daemon = True
    ws_thread.start()
    
    print("🌍 域名: bigjj.site")
    print("📡 代琁E��务器: bigjj.site:8080")  # 使用 mitmweb 服务
    print(f"📱 WebSocket: {'wss' if ws_use_ssl else 'ws'}://bigjj.site:8765")
    print(f"🔗 API接口: {'https' if api_use_ssl else 'http'}://bigjj.site:5010")
    print(f"🌐 状态E��面: {'https' if api_use_ssl else 'http'}://bigjj.site:5010")
    print("=" * 60)
    print("✁E所有服务启动完�E�E�E)
    print("📱 请在Android应用中选择'远程代琁E模式并配置WiFi代琁E��E)
    print("🔍 访问 https://bigjj.site:5010 查看服务器状态E)
    print("🌐 mitmproxy Web界面: http://bigjj.site:8010")
    print("📝 代琁E��用现有的 mitmweb.service (端口8080)")
    print("=" * 60)
    
    try:
        # 不�E启动自己皁Emitmproxy�E�使用现有的 mitmweb.service
        print("ℹ�E�E使用现有的 mitmweb.service 作为代琁E��务器")
        print("ℹ�E�E代琁E��口: 8080 (由 mitmweb.service 提侁E")
        print("ℹ�E�E本服务只提侁EAPI 咁EWebSocket 功�E")
        print("🔧 如需查看代琁E��E���E�请访问: http://bigjj.site:8010")
        
        # 简单的保持运行循环
        import signal
        import time
        
        def signal_handler(sig, frame):
            print("\n🛑 收到停止信号�E�正在关闭服务器...")
            exit(0)
        
        signal.signal(signal.SIGINT, signal_handler)
        signal.signal(signal.SIGTERM, signal_handler)
        
        print("⭁E服务器运行中�E�按 Ctrl+C 停止...")
        
        # 保持服务运衁E
        while True:
            time.sleep(1)
        
    except KeyboardInterrupt:
        print("\n🛑 服务器正在关闭...")
    except Exception as e:
        print(f"❁E服务器运行失败: {e}")
        traceback.print_exc()

# 注意：不�E需要Emitmproxy addon 函数�E��E们使用现有的 mitmweb.service

if __name__ == '__main__':
    main()
