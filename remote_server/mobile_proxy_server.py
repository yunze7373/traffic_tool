#!/usr/bin/env python3
"""
移动抓包远程代理服务器
为 bigjj.site 域名定制版本
"""

import asyncio
import json
import sqlite3
import websockets
import threading
from datetime import datetime
from mitmproxy import http
from mitmproxy.tools.main import mitmdump
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse
import ssl

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
    
    def request(self, flow: http.HTTPFlow):
        # 记录请求开始时间
        flow.metadata['start_time'] = datetime.now()
    
    def response(self, flow: http.HTTPFlow):
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
            
            # 保存到数据库
            self.db.save_traffic(traffic_data)
            
            # 推送到WebSocket客户端
            if self.websocket_clients:
                asyncio.create_task(self.broadcast_to_clients(traffic_data))
            
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

# 全局实例
proxy_addon = MobileProxyAddon()

class APIHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            parsed_path = urllib.parse.urlparse(self.path)
            
            if parsed_path.path == '/api/traffic':
                # 获取流量数据
                query_params = urllib.parse.parse_qs(parsed_path.query)
                device_id = query_params.get('device_id', [''])[0]
                limit = int(query_params.get('limit', ['100'])[0])
                
                results = proxy_addon.db.get_traffic(device_id, limit)
                
                self.send_response(200)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                
                self.wfile.write(json.dumps(results, ensure_ascii=False).encode('utf-8'))
                print(f"📊 API请求: 返回 {len(results)} 条记录给设备 {device_id}")
            
            elif parsed_path.path == '/api/status':
                # 服务器状态
                status = {
                    'status': 'running',
                    'domain': 'bigjj.site',
                    'active_connections': len(proxy_addon.websocket_clients),
                    'total_traffic': proxy_addon.traffic_count,
                    'timestamp': datetime.now().isoformat()
                }
                
                self.send_response(200)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                
                self.wfile.write(json.dumps(status, ensure_ascii=False).encode('utf-8'))
                print("📊 状态查询请求")
            
            elif parsed_path.path == '/':
                # 简单的状态页面
                html = f"""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>bigjj.site 移动抓包代理服务器</title>
                    <meta charset="utf-8">
                </head>
                <body>
                    <h1>🚀 bigjj.site 移动抓包代理服务器</h1>
                    <p>✅ 服务器正在运行</p>
                    <p>📱 活跃连接: {len(proxy_addon.websocket_clients)}</p>
                    <p>🌐 总流量: {proxy_addon.traffic_count}</p>
                    <p>⏰ 时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>
                    
                    <h2>配置信息</h2>
                    <ul>
                        <li>代理地址: bigjj.site:8888</li>
                        <li>WebSocket: wss://bigjj.site:8765</li>
                        <li>API接口: https://bigjj.site:5010</li>
                    </ul>
                    
                    <h2>Android配置步骤</h2>
                    <ol>
                        <li>WiFi设置 → 修改网络 → 高级选项</li>
                        <li>代理: 手动</li>
                        <li>主机名: bigjj.site</li>
                        <li>端口: 8888</li>
                    </ol>
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
    
    proxy_addon.add_websocket_client(websocket)
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
        proxy_addon.remove_websocket_client(websocket)
        print(f"📱 WebSocket断开: {client_info}")

def start_api_server(port=5010):
    """启动HTTP API服务器"""
    try:
        server = HTTPServer(('0.0.0.0', port), APIHandler)
        print(f"🔗 HTTP API服务器启动在端口 {port}")
        server.serve_forever()
    except Exception as e:
        print(f"❌ API服务器启动失败: {e}")

def start_websocket_server(port=8765):
    """启动WebSocket服务器"""
    try:
        print(f"📱 WebSocket服务器启动在端口 {port}")
        
        # 创建新的事件循环
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        
        async def run_server():
            server = await websockets.serve(websocket_handler, "0.0.0.0", port)
            print(f"✅ WebSocket服务器成功绑定到 0.0.0.0:{port}")
            await server.wait_closed()
        
        loop.run_until_complete(run_server())
    except Exception as e:
        print(f"❌ WebSocket服务器启动失败: {e}")
        import traceback
        traceback.print_exc()

def main():
    print("🚀 bigjj.site 移动抓包远程代理服务器")
    print("=" * 60)
    
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
    print("📱 WebSocket: wss://bigjj.site:8765")
    print("🔗 API接口: https://bigjj.site:5010")
    print("🌐 状态页面: https://bigjj.site:5010")
    print("=" * 60)
    print("✅ 所有服务启动完成！")
    print("📱 请在Android应用中选择'远程代理'模式并配置WiFi代理。")
    print("🔍 访问 https://bigjj.site:5010 查看服务器状态")
    print("=" * 60)
    
    try:
        # 启动mitmproxy (主线程)
        print("🔄 启动mitmproxy代理服务器...")
        mitmdump([
            "-s", __file__, 
            "--listen-port", "8888",
            "--set", "web_host=0.0.0.0",
            "--set", "web_port=8010",
            "--set", "confdir=~/.mitmproxy"
        ])
    except KeyboardInterrupt:
        print("\n🛑 服务器正在关闭...")
    except Exception as e:
        print(f"❌ 代理服务器启动失败: {e}")
        import traceback
        traceback.print_exc()

if __name__ == '__main__':
    main()
