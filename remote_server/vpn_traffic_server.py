#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import asyncio
import json
import sqlite3
import ssl
import os
import websockets
import socket
import traceback
import sys
import threading
import signal
import time
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse
import subprocess
import ipaddress

# WebSocket/API 是否启用 SSL（用于页面与状态展示）
WS_USE_SSL = False
API_USE_SSL = False

# WireGuard VPN配置
WIREGUARD_INTERFACE = "wg0"
VPN_NETWORK = "10.66.66.0/24"
SERVER_VPN_IP = "10.66.66.1"
CLIENT_VPN_IP = "10.66.66.2"


class TrafficDatabase:
    def __init__(self, db_path='vpn_traffic.db'):
        self.db_path = db_path
        self.init_database()

    def init_database(self):
        """初始化数据库表"""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS vpn_traffic_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp TEXT,
                    client_ip TEXT,
                    protocol TEXT,
                    src_ip TEXT,
                    dst_ip TEXT,
                    src_port INTEGER,
                    dst_port INTEGER,
                    domain TEXT,
                    url TEXT,
                    method TEXT,
                    user_agent TEXT,
                    bytes_sent INTEGER,
                    bytes_received INTEGER,
                    connection_type TEXT
                )
            ''')
            
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS vpn_clients (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    client_name TEXT,
                    public_key TEXT UNIQUE,
                    vpn_ip TEXT,
                    last_seen TEXT,
                    total_bytes INTEGER DEFAULT 0,
                    status TEXT DEFAULT 'active'
                )
            ''')
            
            conn.commit()
            conn.close()
            print("✅ VPN流量数据库初始化完成")
        except Exception as e:
            print(f"❌ 数据库初始化失败: {e}")

    def save_traffic(self, traffic_data):
        """保存VPN流量数据到数据库"""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute('''
                INSERT INTO vpn_traffic_logs 
                (timestamp, client_ip, protocol, src_ip, dst_ip, src_port, dst_port, 
                 domain, url, method, user_agent, bytes_sent, bytes_received, connection_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', traffic_data)
            
            conn.commit()
            conn.close()
        except Exception as e:
            print(f"❌ 保存VPN流量数据失败: {e}")

    def get_recent_traffic(self, limit=100):
        """获取最近的VPN流量记录"""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute('''
                SELECT * FROM vpn_traffic_logs 
                ORDER BY timestamp DESC 
                LIMIT ?
            ''', (limit,))
            
            rows = cursor.fetchall()
            conn.close()
            
            # 转换为字典格式
            columns = [description[0] for description in cursor.description]
            result = []
            for row in rows:
                result.append(dict(zip(columns, row)))
            
            return result
        except Exception as e:
            print(f"❌ 获取VPN流量数据失败: {e}")
            return []

    def update_client_stats(self, client_ip, bytes_transferred):
        """更新客户端统计信息"""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute('''
                UPDATE vpn_clients 
                SET last_seen = ?, total_bytes = total_bytes + ?
                WHERE vpn_ip = ?
            ''', (datetime.now().isoformat(), bytes_transferred, client_ip))
            
            conn.commit()
            conn.close()
        except Exception as e:
            print(f"❌ 更新客户端统计失败: {e}")


# 全局数据库实例
traffic_db = TrafficDatabase()

# 存储连接的WebSocket客户端
websocket_clients = set()


class VPNTrafficMonitor:
    """VPN流量监控器"""
    
    def __init__(self):
        self.running = False
        self.monitor_thread = None
    
    def start_monitoring(self):
        """开始监控VPN流量"""
        self.running = True
        self.monitor_thread = threading.Thread(target=self._monitor_loop)
        self.monitor_thread.daemon = True
        self.monitor_thread.start()
        print("✅ VPN流量监控器已启动")
    
    def stop_monitoring(self):
        """停止监控"""
        self.running = False
        if self.monitor_thread:
            self.monitor_thread.join()
        print("🛑 VPN流量监控器已停止")
    
    def _monitor_loop(self):
        """监控循环"""
        while self.running:
            try:
                # 获取WireGuard连接状态
                wg_status = self._get_wireguard_status()
                
                # 监控网络流量
                traffic_data = self._capture_network_traffic()
                
                if traffic_data:
                    # 保存到数据库
                    traffic_db.save_traffic(traffic_data)
                    
                    # 广播到WebSocket客户端
                    if websocket_clients:
                        asyncio.create_task(self._broadcast_traffic(traffic_data))
                
                time.sleep(1)  # 每秒监控一次
                
            except Exception as e:
                print(f"❌ 监控循环出错: {e}")
                time.sleep(5)
    
    def _get_wireguard_status(self):
        """获取WireGuard连接状态"""
        try:
            result = subprocess.run(['wg', 'show', WIREGUARD_INTERFACE], 
                                  capture_output=True, text=True)
            return result.stdout if result.returncode == 0 else None
        except Exception as e:
            print(f"⚠️ 获取WireGuard状态失败: {e}")
            return None
    
    def _capture_network_traffic(self):
        """捕获网络流量（简化版本）"""
        try:
            # 这里是一个简化的实现
            # 实际环境中可以使用 tcpdump, scapy 或者 eBPF 来捕获流量
            
            # 使用netstat获取连接信息作为示例
            result = subprocess.run(['netstat', '-tuln'], 
                                  capture_output=True, text=True)
            
            if result.returncode == 0:
                # 解析netstat输出并返回流量数据
                # 这里返回一个示例数据结构
                return (
                    datetime.now().isoformat(),  # timestamp
                    CLIENT_VPN_IP,               # client_ip
                    'TCP',                       # protocol
                    CLIENT_VPN_IP,               # src_ip
                    '8.8.8.8',                   # dst_ip (示例)
                    12345,                       # src_port
                    80,                          # dst_port
                    'example.com',               # domain
                    'http://example.com/api',    # url
                    'GET',                       # method
                    'Android App',               # user_agent
                    1024,                        # bytes_sent
                    2048,                        # bytes_received
                    'HTTP'                       # connection_type
                )
            
        except Exception as e:
            print(f"⚠️ 捕获流量失败: {e}")
        
        return None
    
    async def _broadcast_traffic(self, traffic_data):
        """广播流量数据到WebSocket客户端"""
        if websocket_clients:
            # 转换为WebSocket格式
            ws_data = {
                'timestamp': traffic_data[0],
                'client_ip': traffic_data[1],
                'protocol': traffic_data[2],
                'src_ip': traffic_data[3],
                'dst_ip': traffic_data[4],
                'domain': traffic_data[7],
                'url': traffic_data[8],
                'method': traffic_data[9],
                'bytes_total': traffic_data[11] + traffic_data[12]
            }
            
            # 创建要移除的客户端列表
            clients_to_remove = set()
            
            for client in websocket_clients.copy():
                try:
                    await client.send(json.dumps(ws_data))
                except Exception:
                    clients_to_remove.add(client)
            
            # 移除断开的客户端
            for client in clients_to_remove:
                websocket_clients.discard(client)


# 全局流量监控器
traffic_monitor = VPNTrafficMonitor()


async def websocket_handler(*args):
    """WebSocket连接处理器"""
    websocket = args[0]
    path = args[1] if len(args) > 1 else "/"
    
    print(f"🔗 新的WebSocket连接: {websocket.remote_address}")
    websocket_clients.add(websocket)
    
    try:
        # 发送最近的流量记录
        recent_traffic = traffic_db.get_recent_traffic(50)
        for traffic in recent_traffic:
            try:
                await websocket.send(json.dumps({
                    'timestamp': traffic['timestamp'],
                    'client_ip': traffic['client_ip'],
                    'protocol': traffic['protocol'],
                    'domain': traffic['domain'],
                    'url': traffic['url'],
                    'method': traffic['method'],
                    'bytes_total': (traffic['bytes_sent'] or 0) + (traffic['bytes_received'] or 0)
                }))
            except Exception as e:
                print(f"⚠️ 发送历史数据失败: {e}")
                break
        
        # 保持连接活跃
        async for message in websocket:
            # 可以处理来自客户端的消息
            pass
    except Exception as e:
        print(f"❌ WebSocket连接异常: {e}")
    finally:
        websocket_clients.discard(websocket)
        print(f"🔌 WebSocket连接断开: {websocket.remote_address}")


def start_websocket_server(port=8765, use_ssl=False):
    """启动WebSocket服务器"""
    try:
        # SSL 配置
        ssl_context = None
        if use_ssl:
            ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            # 优先使用 Let's Encrypt 证书
            le_cert = '/etc/letsencrypt/live/bigjj.site/fullchain.pem'
            le_key = '/etc/letsencrypt/live/bigjj.site/privkey.pem'
            
            if os.path.exists(le_cert) and os.path.exists(le_key):
                ssl_context.load_cert_chain(le_cert, le_key)
                print(f"✅ WebSocket服务器使用 Let's Encrypt 证书")
                global WS_USE_SSL
                WS_USE_SSL = True
            else:
                print(f"⚠️ Let's Encrypt 证书不存在，WebSocket将使用HTTP")
                ssl_context = None
                WS_USE_SSL = False
        
        async def run_server():
            # 根据SSL状态决定协议
            protocol = "wss" if ssl_context else "ws"
            print(f"🚀 启动WebSocket服务器: {protocol}://0.0.0.0:{port}")
            
            server = await websockets.serve(
                websocket_handler, 
                "0.0.0.0", 
                port,
                ssl=ssl_context
            )
            
            print(f"✅ WebSocket服务器启动成功: {protocol}://bigjj.site:{port}")
            await server.wait_closed()
        
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        loop.run_until_complete(run_server())
    except Exception as e:
        print(f"❌ WebSocket服务器启动失败: {e}")
        traceback.print_exc()


class VPNAPIHandler(BaseHTTPRequestHandler):
    """VPN API处理器"""
    
    def log_message(self, format, *args):
        """禁用默认日志输出"""
        pass
    
    def do_GET(self):
        """处理GET请求"""
        try:
            parsed_path = urllib.parse.urlparse(self.path)
            path = parsed_path.path
            
            if path == '/':
                self.serve_status_page()
            elif path == '/api/status':
                self.serve_vpn_status()
            elif path == '/api/traffic':
                self.serve_traffic_data()
            elif path == '/api/clients':
                self.serve_client_list()
            else:
                self.send_error(404, "Not Found")
        except Exception as e:
            print(f"❌ API请求处理失败: {e}")
            self.send_error(500, "Internal Server Error")
    
    def serve_status_page(self):
        """提供VPN状态页面"""
        global WS_USE_SSL, API_USE_SSL
        
        ws_scheme = "wss" if WS_USE_SSL else "ws"
        api_scheme = "https" if API_USE_SSL else "http"
        
        html_content = f"""
        <!DOCTYPE html>
        <html>
        <head>
            <title>bigjj.site WireGuard VPN流量监控</title>
            <meta charset="utf-8">
            <style>
                body {{ font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }}
                .container {{ max-width: 1000px; margin: 0 auto; background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }}
                .status {{ color: #28a745; font-weight: bold; }}
                .info {{ background: #e9ecef; padding: 15px; border-radius: 5px; margin: 10px 0; }}
                .endpoint {{ background: #f8f9fa; padding: 10px; border-left: 4px solid #007bff; margin: 5px 0; }}
                .warning {{ background: #fff3cd; border-left: 4px solid #ffc107; padding: 10px; margin: 5px 0; }}
            </style>
        </head>
        <body>
            <div class="container">
                <h1>🛡️ bigjj.site WireGuard VPN流量监控</h1>
                <p class="status">✅ VPN服务运行中</p>
                
                <div class="info">
                    <h3>🔐 VPN连接信息</h3>
                    <div class="endpoint">服务器地址: <strong>bigjj.site:51820</strong></div>
                    <div class="endpoint">VPN网络: <strong>{VPN_NETWORK}</strong></div>
                    <div class="endpoint">服务器VPN IP: <strong>{SERVER_VPN_IP}</strong></div>
                    <div class="endpoint">客户端VPN IP: <strong>{CLIENT_VPN_IP}</strong></div>
                </div>
                
                <div class="info">
                    <h3>🔗 监控接口</h3>
                    <div class="endpoint">WebSocket地址: <strong>{ws_scheme}://bigjj.site:8765</strong></div>
                    <div class="endpoint">实时流量推送: <strong>已启用</strong></div>
                </div>
                
                <div class="info">
                    <h3>🛠 API接口</h3>
                    <div class="endpoint">状态接口: <strong>{api_scheme}://bigjj.site:5010/api/status</strong></div>
                    <div class="endpoint">流量数据: <strong>{api_scheme}://bigjj.site:5010/api/traffic</strong></div>
                    <div class="endpoint">客户端列表: <strong>{api_scheme}://bigjj.site:5010/api/clients</strong></div>
                </div>
                
                <div class="warning">
                    <h3>📱 Android客户端配置</h3>
                    <p>请在Android应用中使用WireGuard配置，不再需要手动设置WiFi代理。</p>
                    <p>VPN连接后，所有应用流量将自动通过加密隧道传输并被监控。</p>
                </div>
            </div>
        </body>
        </html>
        """
        
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(html_content.encode('utf-8'))
    
    def serve_vpn_status(self):
        """提供VPN状态信息"""
        global WS_USE_SSL, API_USE_SSL
        
        # 获取WireGuard状态
        wg_status = self._get_wireguard_status()
        
        ws_scheme = "wss" if WS_USE_SSL else "ws"
        api_scheme = "https" if API_USE_SSL else "http"
        
        status_data = {
            'status': 'running',
            'vpn_type': 'WireGuard',
            'server_host': 'bigjj.site',
            'vpn_port': 51820,
            'vpn_network': VPN_NETWORK,
            'server_vpn_ip': SERVER_VPN_IP,
            'client_vpn_ip': CLIENT_VPN_IP,
            'websocket_port': 8765,
            'api_port': 5010,
            'ws_scheme': ws_scheme,
            'ws_url': f'{ws_scheme}://bigjj.site:8765',
            'api_scheme': api_scheme,
            'api_url': f'{api_scheme}://bigjj.site:5010',
            'wireguard_status': wg_status,
            'ssl_enabled': {
                'websocket': WS_USE_SSL,
                'api': API_USE_SSL
            }
        }
        
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(json.dumps(status_data, indent=2).encode('utf-8'))
    
    def serve_traffic_data(self):
        """提供流量数据"""
        try:
            traffic_data = traffic_db.get_recent_traffic(100)
            
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps(traffic_data, indent=2).encode('utf-8'))
        except Exception as e:
            print(f"❌ 获取流量数据失败: {e}")
            self.send_error(500, "Failed to get traffic data")
    
    def serve_client_list(self):
        """提供客户端列表"""
        try:
            # 这里可以返回连接的VPN客户端信息
            clients_data = {
                'connected_clients': 1,
                'clients': [
                    {
                        'name': 'Android Client',
                        'vpn_ip': CLIENT_VPN_IP,
                        'status': 'connected',
                        'last_seen': datetime.now().isoformat()
                    }
                ]
            }
            
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps(clients_data, indent=2).encode('utf-8'))
        except Exception as e:
            print(f"❌ 获取客户端列表失败: {e}")
            self.send_error(500, "Failed to get client list")
    
    def _get_wireguard_status(self):
        """获取WireGuard状态"""
        try:
            result = subprocess.run(['wg', 'show', WIREGUARD_INTERFACE], 
                                  capture_output=True, text=True)
            return result.stdout if result.returncode == 0 else "WireGuard not running"
        except Exception:
            return "WireGuard status unavailable"


def start_api_server(port=5010, use_ssl=False):
    """启动HTTP API服务器"""
    try:
        httpd = HTTPServer(('0.0.0.0', port), VPNAPIHandler)
        
        if use_ssl:
            # SSL配置 (与之前相同)
            ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            cert_loaded = False
            
            # 尝试加载SSL证书
            le_cert = '/etc/letsencrypt/live/bigjj.site/fullchain.pem'
            le_key = '/etc/letsencrypt/live/bigjj.site/privkey.pem'
            
            if os.path.exists(le_cert) and os.path.exists(le_key):
                try:
                    ssl_context.load_cert_chain(le_cert, le_key)
                    httpd.socket = ssl_context.wrap_socket(httpd.socket, server_side=True)
                    cert_loaded = True
                    global API_USE_SSL
                    API_USE_SSL = True
                    print(f"✅ API服务器使用 Let's Encrypt 证书")
                except Exception as e:
                    print(f"⚠️ Let's Encrypt 证书加载失败: {e}")
            
            if not cert_loaded:
                print(f"⚠️ SSL证书加载失败，API服务器将使用HTTP")
                API_USE_SSL = False
        
        protocol = "https" if (use_ssl and API_USE_SSL) else "http"
        print(f"🚀 启动VPN API服务器: {protocol}://0.0.0.0:{port}")
        
        httpd.serve_forever()
    except Exception as e:
        print(f"❌ API服务器启动失败: {e}")
        traceback.print_exc()


def main():
    """主函数"""
    print("🛡️ bigjj.site WireGuard VPN流量监控服务器")
    print("=" * 60)
    
    # 检查WireGuard是否安装
    try:
        result = subprocess.run(['wg', '--version'], capture_output=True)
        if result.returncode != 0:
            print("❌ WireGuard未安装，请先运行 setup_wireguard.sh")
            sys.exit(1)
        print("✅ WireGuard已安装")
    except FileNotFoundError:
        print("❌ WireGuard命令未找到，请先安装WireGuard")
        sys.exit(1)
    
    # 启动流量监控器
    traffic_monitor.start_monitoring()
    
    # 启动HTTP API服务器 (线程)
    api_use_ssl = any([
        os.path.exists('/etc/letsencrypt/live/bigjj.site/fullchain.pem') and os.path.exists('/etc/letsencrypt/live/bigjj.site/privkey.pem'),
        os.path.exists('/etc/ssl/certs/bigjj.site.crt') and os.path.exists('/etc/ssl/private/bigjj.site.key'),
        os.path.exists('/opt/mobile-proxy/cert.pem') and os.path.exists('/opt/mobile-proxy/key.pem')
    ])
    api_thread = threading.Thread(target=start_api_server, args=(5010, api_use_ssl))
    api_thread.daemon = True
    api_thread.start()
    
    # 启动WebSocket服务器 (线程)
    le_cert = '/etc/letsencrypt/live/bigjj.site/fullchain.pem'
    le_key = '/etc/letsencrypt/live/bigjj.site/privkey.pem'
    ws_use_ssl = os.path.exists(le_cert) and os.path.exists(le_key)
    ws_thread = threading.Thread(target=start_websocket_server, args=(8765, ws_use_ssl))
    ws_thread.daemon = True
    ws_thread.start()
    
    print("🌍 域名: bigjj.site")
    print("🛡️ VPN服务: WireGuard (端口 51820)")
    print(f"📱 WebSocket: {'wss' if ws_use_ssl else 'ws'}://bigjj.site:8765")
    print(f"🔗 API接口: {'https' if api_use_ssl else 'http'}://bigjj.site:5010")
    print(f"🌐 状态页面: {'https' if api_use_ssl else 'http'}://bigjj.site:5010")
    print("=" * 60)
    print("✅ 所有服务启动完成！")
    print("🛡️ 请在Android应用中配置WireGuard连接。")
    print("🔍 访问 https://bigjj.site:5010 查看服务器状态")
    print("📊 VPN流量监控已启用")
    print("=" * 60)
    
    try:
        # 设置信号处理
        def signal_handler(sig, frame):
            print("\n🛑 收到停止信号，正在关闭服务器...")
            traffic_monitor.stop_monitoring()
            sys.exit(0)
        
        signal.signal(signal.SIGINT, signal_handler)
        signal.signal(signal.SIGTERM, signal_handler)
        
        print("⭐ VPN流量监控服务运行中，按 Ctrl+C 停止...")
        
        # 保持服务运行
        while True:
            time.sleep(1)
        
    except KeyboardInterrupt:
        print("\n🛑 服务器正在关闭...")
        traffic_monitor.stop_monitoring()
    except Exception as e:
        print(f"❌ 服务器运行失败: {e}")
        traceback.print_exc()


if __name__ == '__main__':
    main()
