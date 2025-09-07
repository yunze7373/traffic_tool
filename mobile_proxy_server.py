#!/usr/bin/env python3
"""
移动抓包远程代理服务器
简化版本 - 适合快速部署和测试
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
    
    def save_traffic(self, data):
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
    
    def get_traffic(self, device_id, limit=100):
        conn = sqlite3.connect(self.db_path)
        cursor = conn.execute('''
            SELECT * FROM traffic_logs 
            WHERE device_id = ? 
            ORDER BY created_at DESC 
            LIMIT ?
        ''', (device_id, limit))
        results = cursor.fetchall()
        conn.close()
        return results

class MobileProxyAddon:
    def __init__(self):
        self.db = TrafficDatabase()
        self.websocket_clients = set()
    
    def add_websocket_client(self, websocket):
        self.websocket_clients.add(websocket)
        print(f"WebSocket客户端连接: {len(self.websocket_clients)} 个活跃连接")
    
    def remove_websocket_client(self, websocket):
        self.websocket_clients.discard(websocket)
        print(f"WebSocket客户端断开: {len(self.websocket_clients)} 个活跃连接")
    
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
            
            print(f"[{device_id}] {flow.request.method} {flow.request.pretty_url} -> {flow.response.status_code}")
            
            # 保存到数据库
            self.db.save_traffic(traffic_data)
            
            # 推送到WebSocket客户端
            asyncio.create_task(self.broadcast_to_clients(traffic_data))
            
        except Exception as e:
            print(f"处理流量数据失败: {e}")
    
    def safe_get_text(self, message):
        try:
            return message.text or ''
        except:
            return f'<Binary data: {len(message.raw_content)} bytes>'
    
    def get_device_id(self, flow):
        # 从请求头或IP识别设备
        client_ip = flow.client_conn.address[0]
        user_agent = flow.request.headers.get('User-Agent', '')
        
        if 'TrafficCapture' in user_agent:
            return f"android_{client_ip}"
        else:
            return f"device_{client_ip}"
    
    async def broadcast_to_clients(self, data):
        if self.websocket_clients:
            message = json.dumps(data)
            disconnected = set()
            
            for client in self.websocket_clients:
                try:
                    await client.send(message)
                except:
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
                self.send_header('Content-Type', 'application/json')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                
                self.wfile.write(json.dumps(results).encode())
            
            elif parsed_path.path == '/api/status':
                # 服务器状态
                status = {
                    'status': 'running',
                    'active_connections': len(proxy_addon.websocket_clients),
                    'timestamp': datetime.now().isoformat()
                }
                
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                
                self.wfile.write(json.dumps(status).encode())
            
            else:
                self.send_response(404)
                self.end_headers()
                
        except Exception as e:
            print(f"API请求处理失败: {e}")
            self.send_response(500)
            self.end_headers()
    
    def log_message(self, format, *args):
        # 禁用默认日志
        pass

async def websocket_handler(websocket, path):
    """WebSocket连接处理"""
    proxy_addon.add_websocket_client(websocket)
    try:
        await websocket.wait_closed()
    except:
        pass
    finally:
        proxy_addon.remove_websocket_client(websocket)

def start_api_server(port=5010):
    """启动HTTP API服务器"""
    server = HTTPServer(('0.0.0.0', port), APIHandler)
    print(f"HTTP API服务器启动在端口 {port}")
    server.serve_forever()

def start_websocket_server(port=8765):
    """启动WebSocket服务器"""
    print(f"WebSocket服务器启动在端口 {port}")
    start_server = websockets.serve(websocket_handler, "0.0.0.0", port)
    asyncio.get_event_loop().run_until_complete(start_server)
    asyncio.get_event_loop().run_forever()

def main():
    print("🚀 移动抓包远程代理服务器启动中...")
    print("=" * 50)
    
    # 启动HTTP API服务器 (线程)
    api_thread = threading.Thread(target=start_api_server, args=(5010,))
    api_thread.daemon = True
    api_thread.start()
    
    # 启动WebSocket服务器 (线程)
    ws_thread = threading.Thread(target=start_websocket_server, args=(8765,))
    ws_thread.daemon = True
    ws_thread.start()
    
    print("📡 代理服务器地址: 0.0.0.0:8888")
    print("🌐 WebSocket地址: 0.0.0.0:8765") 
    print("🔗 API地址: 0.0.0.0:5010")
    print("=" * 50)
    print("✅ 服务器启动完成！请在Android应用中配置代理。")
    
    # 启动mitmproxy (主线程)
    mitmdump([
        "-s", __file__, 
        "--listen-port", "8888",
        "--set", "confdir=~/.mitmproxy"
    ])

if __name__ == '__main__':
    main()
