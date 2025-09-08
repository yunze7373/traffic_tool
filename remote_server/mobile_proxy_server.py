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
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse
import signal
import time

# WebSocket/API 是否启用 SSL（用于页面与状态展示）
WS_USE_SSL = False
API_USE_SSL = False

# 尝试导入mitmproxy模块
try:
    from mitmproxy import http, options
    from mitmproxy.tools.dump import DumpMaster
    MITMPROXY_AVAILABLE = True
except ImportError:
    MITMPROXY_AVAILABLE = False
    print("⚠️ mitmproxy模块未安装，部分功能可能受限")


class TrafficDatabase:
    def __init__(self, db_path='mobile_traffic.db'):
        self.db_path = db_path
        self.init_database()

    def init_database(self):
        """初始化数据库表"""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS traffic_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp TEXT,
                    method TEXT,
                    url TEXT,
                    host TEXT,
                    path TEXT,
                    status_code INTEGER,
                    request_headers TEXT,
                    response_headers TEXT,
                    request_body TEXT,
                    response_body TEXT,
                    content_type TEXT,
                    size INTEGER
                )
            ''')
            
            conn.commit()
            conn.close()
            print("✅ 数据库初始化完成")
        except Exception as e:
            print(f"❌ 数据库初始化失败: {e}")

    def save_traffic(self, flow_data):
        """保存流量数据到数据库"""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute('''
                INSERT INTO traffic_logs 
                (timestamp, method, url, host, path, status_code, request_headers, 
                 response_headers, request_body, response_body, content_type, size)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', flow_data)
            
            conn.commit()
            conn.close()
        except Exception as e:
            print(f"❌ 保存流量数据失败: {e}")

    def get_recent_traffic(self, limit=100):
        """获取最近的流量记录"""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute('''
                SELECT * FROM traffic_logs 
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
            print(f"❌ 获取流量数据失败: {e}")
            return []


# 全局数据库实例
traffic_db = TrafficDatabase()

# 存储连接的WebSocket客户端
websocket_clients = set()


class TrafficCaptureAddon:
    """mitmproxy插件：捕获HTTP流量"""
    
    def response(self, flow: http.HTTPFlow) -> None:
        """处理HTTP响应"""
        try:
            # 构造流量数据
            request = flow.request
            response = flow.response
            
            # 安全地获取请求体和响应体
            request_body = ""
            response_body = ""
            
            try:
                if request.content:
                    request_body = request.content.decode('utf-8', errors='ignore')[:10000]  # 限制长度
            except:
                request_body = f"[二进制数据: {len(request.content)} bytes]"
            
            try:
                if response and response.content:
                    response_body = response.content.decode('utf-8', errors='ignore')[:10000]  # 限制长度
            except:
                response_body = f"[二进制数据: {len(response.content)} bytes]" if response else ""
            
            flow_data = (
                datetime.now().isoformat(),
                request.method,
                request.pretty_url,
                request.host,
                request.path,
                response.status_code if response else 0,
                json.dumps(dict(request.headers)),
                json.dumps(dict(response.headers)) if response else "{}",
                request_body,
                response_body,
                response.headers.get('content-type', '') if response else '',
                len(response.content) if response and response.content else 0
            )
            
            # 保存到数据库
            traffic_db.save_traffic(flow_data)
            
            # 发送到WebSocket客户端
            websocket_data = {
                'timestamp': datetime.now().isoformat(),
                'method': request.method,
                'url': request.pretty_url,
                'host': request.host,
                'path': request.path,
                'status_code': response.status_code if response else 0,
                'content_type': response.headers.get('content-type', '') if response else '',
                'size': len(response.content) if response and response.content else 0
            }
            
            # 异步发送到所有WebSocket客户端
            if websocket_clients:
                asyncio.create_task(broadcast_to_websockets(websocket_data))
            
        except Exception as e:
            print(f"❌ 处理流量数据时出错: {e}")


async def broadcast_to_websockets(data):
    """向所有WebSocket客户端广播数据"""
    if websocket_clients:
        # 创建要移除的客户端列表
        clients_to_remove = set()
        
        for client in websocket_clients.copy():
            try:
                await client.send(json.dumps(data))
            except Exception:
                clients_to_remove.add(client)
        
        # 移除断开的客户端
        for client in clients_to_remove:
            websocket_clients.discard(client)


async def websocket_handler(*args):
    """WebSocket连接处理器（兼容不同版本的websockets库）"""
    # 兼容性处理：支持 (websocket,) 和 (websocket, path) 两种参数形式
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
                    'method': traffic['method'],
                    'url': traffic['url'],
                    'host': traffic['host'],
                    'path': traffic['path'],
                    'status_code': traffic['status_code'],
                    'content_type': traffic['content_type'],
                    'size': traffic['size']
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


class APIHandler(BaseHTTPRequestHandler):
    """HTTP API处理器"""
    
    def log_message(self, format, *args):
        """禁用默认日志输出"""
        pass
    
    def do_GET(self):
        """处理GET请求"""
        try:
            # 解析URL路径
            parsed_path = urllib.parse.urlparse(self.path)
            path = parsed_path.path
            
            if path == '/':
                self.serve_status_page()
            elif path == '/api/status':
                self.serve_api_status()
            elif path == '/api/traffic':
                self.serve_traffic_data()
            else:
                self.send_error(404, "Not Found")
        except Exception as e:
            print(f"❌ API请求处理失败: {e}")
            self.send_error(500, "Internal Server Error")
    
    def serve_status_page(self):
        """提供状态页面"""
        global WS_USE_SSL, API_USE_SSL
        
        # 确定WebSocket协议
        ws_scheme = "wss" if WS_USE_SSL else "ws"
        api_scheme = "https" if API_USE_SSL else "http"
        
        html_content = f"""
        <!DOCTYPE html>
        <html>
        <head>
            <title>bigjj.site 移动抓包代理服务器</title>
            <meta charset="utf-8">
            <style>
                body {{ font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }}
                .container {{ max-width: 800px; margin: 0 auto; background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }}
                .status {{ color: #28a745; font-weight: bold; }}
                .info {{ background: #e9ecef; padding: 15px; border-radius: 5px; margin: 10px 0; }}
                .endpoint {{ background: #f8f9fa; padding: 10px; border-left: 4px solid #007bff; margin: 5px 0; }}
            </style>
        </head>
        <body>
            <div class="container">
                <h1>🚀 bigjj.site 移动抓包代理服务器</h1>
                <p class="status">✅ 服务运行中</p>
                
                <div class="info">
                    <h3>📡 代理配置</h3>
                    <div class="endpoint">代理服务器: <strong>bigjj.site:8888</strong></div>
                    <div class="endpoint">代理类型: <strong>HTTP代理</strong></div>
                </div>
                
                <div class="info">
                    <h3>🔗 WebSocket连接</h3>
                    <div class="endpoint">WebSocket地址: <strong>{ws_scheme}://bigjj.site:8765</strong></div>
                    <div class="endpoint">实时流量推送: <strong>已启用</strong></div>
                </div>
                
                <div class="info">
                    <h3>🛠 API接口</h3>
                    <div class="endpoint">状态接口: <strong>{api_scheme}://bigjj.site:5010/api/status</strong></div>
                    <div class="endpoint">流量数据: <strong>{api_scheme}://bigjj.site:5010/api/traffic</strong></div>
                </div>
                
                <div class="info">
                    <h3>📱 使用说明</h3>
                    <ol>
                        <li>在手机WiFi设置中配置HTTP代理</li>
                        <li>代理服务器地址: <strong>bigjj.site</strong></li>
                        <li>代理端口: <strong>8888</strong></li>
                        <li>启动Android应用查看实时流量</li>
                    </ol>
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
    
    def serve_api_status(self):
        """提供API状态信息"""
        global WS_USE_SSL, API_USE_SSL
        
        # 确定协议
        ws_scheme = "wss" if WS_USE_SSL else "ws"
        api_scheme = "https" if API_USE_SSL else "http"
        
        status_data = {
            'status': 'running',
            'proxy_host': 'bigjj.site',
            'proxy_port': 8888,
            'proxy_url': 'bigjj.site:8888',
            'websocket_port': 8765,
            'api_port': 5010,
            'ws_scheme': ws_scheme,
            'ws_url': f'{ws_scheme}://bigjj.site:8765',
            'api_scheme': api_scheme,
            'api_url': f'{api_scheme}://bigjj.site:5010',
            'mitmproxy_available': MITMPROXY_AVAILABLE,
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


def start_api_server(port=5010, use_ssl=False):
    """启动HTTP API服务器"""
    try:
        # SSL配置
        httpd = HTTPServer(('0.0.0.0', port), APIHandler)
        
        if use_ssl:
            # 尝试加载SSL证书
            ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            cert_loaded = False
            
            # 优先尝试 Let's Encrypt 证书
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
            
            # 如果Let's Encrypt证书失败，尝试其他证书
            if not cert_loaded:
                other_certs = [
                    ('/etc/ssl/certs/bigjj.site.crt', '/etc/ssl/private/bigjj.site.key'),
                    ('/opt/mobile-proxy/cert.pem', '/opt/mobile-proxy/key.pem')
                ]
                
                for cert_path, key_path in other_certs:
                    if os.path.exists(cert_path) and os.path.exists(key_path):
                        try:
                            ssl_context.load_cert_chain(cert_path, key_path)
                            httpd.socket = ssl_context.wrap_socket(httpd.socket, server_side=True)
                            cert_loaded = True
                            API_USE_SSL = True
                            print(f"✅ API服务器使用证书: {cert_path}")
                            break
                        except Exception as e:
                            print(f"⚠️ 证书 {cert_path} 加载失败: {e}")
            
            if not cert_loaded:
                print(f"⚠️ 所有SSL证书加载失败，API服务器将使用HTTP")
                API_USE_SSL = False
        
        # 根据SSL状态决定协议
        protocol = "https" if (use_ssl and API_USE_SSL) else "http"
        print(f"🚀 启动API服务器: {protocol}://0.0.0.0:{port}")
        
        httpd.serve_forever()
    except Exception as e:
        print(f"❌ API服务器启动失败: {e}")
        traceback.print_exc()


def get_addon_instance():
    """获取addon实例"""
    return TrafficCaptureAddon()


def ensure_mitmproxy_config(confdir: str):
    """确保 mitmproxy 配置禁用 block_global"""
    try:
        path = os.path.expanduser(confdir)
        os.makedirs(path, exist_ok=True)
        cfg = os.path.join(path, 'config.yaml')
        content = ''
        if os.path.exists(cfg):
            try:
                with open(cfg, 'r', encoding='utf-8') as f:
                    content = f.read()
            except Exception:
                content = ''
        if 'block_global' not in content:
            # 追加或写入设置，确保关闭外网阻止
            with open(cfg, 'a', encoding='utf-8') as f:
                f.write("\nblock_global: false\n")
            print(f"✅ 已在 {cfg} 中写入 block_global: false")
        else:
            # 简单替换为 false
            if 'block_global: true' in content:
                newc = content.replace('block_global: true', 'block_global: false')
                with open(cfg, 'w', encoding='utf-8') as f:
                    f.write(newc)
                print(f"✅ 已将 {cfg} 中的 block_global 设置为 false")
    except Exception as e:
        print(f"⚠️ 写入 mitmproxy 配置失败(可忽略): {e}")


async def run_mitmproxy_async(addon, opts):
    """异步运行mitmproxy"""
    if not MITMPROXY_AVAILABLE:
        print("❌ mitmproxy 不可用，无法启动代理服务")
        return
    
    try:
        # 创建DumpMaster
        master = DumpMaster(opts)
        
        # 运行期再次确保关闭 block_global 限制（多种方法）
        try:
            if hasattr(master, 'options'):
                # 方法1: 使用 options.set
                try:
                    master.options.set('block_global', False)
                    print("✅ 已在运行期关闭 block_global (master.options.set)")
                except Exception as e1:
                    print(f"⚠️ master.options.set 失败: {e1}")
                    
                    # 方法2: 直接设置属性
                    try:
                        if hasattr(master.options, 'block_global'):
                            master.options.block_global = False
                            print("✅ 已通过直接赋值关闭 master.options.block_global")
                    except Exception as e2:
                        print(f"⚠️ 直接设置 master.options.block_global 失败: {e2}")
        except Exception as e:
            print(f"⚠️ 访问 master.options 失败: {e}")
        
        master.addons.add(addon)
        print("✅ Addon已注册到mitmproxy")
        
        # 运行mitmproxy
        await master.run()
    except Exception as e:
        print(f"❌ mitmproxy运行失败: {e}")
        traceback.print_exc()


def main():
    # 启动横幅
    print("🚀 bigjj.site 移动抓包远程代理服务器")
    print("=" * 60)
    
    # 检查mitmproxy可用性
    if not MITMPROXY_AVAILABLE:
        print("❌ mitmproxy未安装或不可用")
        print("请运行: pip install mitmproxy")
        sys.exit(1)
    
    # 创建addon实例
    addon = get_addon_instance()
    print("✅ TrafficCaptureAddon 实例已创建")
    
    # 启动HTTP API服务器 (线程) - 优先尝试启用HTTPS（若证书存在）
    api_use_ssl = any([
        os.path.exists('/etc/letsencrypt/live/bigjj.site/fullchain.pem') and os.path.exists('/etc/letsencrypt/live/bigjj.site/privkey.pem'),
        os.path.exists('/etc/ssl/certs/bigjj.site.crt') and os.path.exists('/etc/ssl/private/bigjj.site.key'),
        os.path.exists('/opt/mobile-proxy/cert.pem') and os.path.exists('/opt/mobile-proxy/key.pem')
    ])
    api_thread = threading.Thread(target=start_api_server, args=(5010, api_use_ssl))
    api_thread.daemon = True
    api_thread.start()
    
    # 启动WebSocket服务器 (线程)
    # 仅当存在有效的 Let's Encrypt 证书时启用 WSS；自签名默认禁用，避免移动端 TLS 失败
    le_cert = '/etc/letsencrypt/live/bigjj.site/fullchain.pem'
    le_key = '/etc/letsencrypt/live/bigjj.site/privkey.pem'
    ws_use_ssl = os.path.exists(le_cert) and os.path.exists(le_key)
    ws_thread = threading.Thread(target=start_websocket_server, args=(8765, ws_use_ssl))
    ws_thread.daemon = True
    ws_thread.start()
    
    print("🌍 域名: bigjj.site")
    print("📡 代理服务器: bigjj.site:8888")  # 统一使用8888端口
    print(f"📱 WebSocket: {'wss' if ws_use_ssl else 'ws'}://bigjj.site:8765")
    print(f"🔗 API接口: {'https' if api_use_ssl else 'http'}://bigjj.site:5010")
    print(f"🌐 状态页面: {'https' if api_use_ssl else 'http'}://bigjj.site:5010")
    print("=" * 60)
    print("✅ 所有服务启动完成！")
    print("📱 请在Android应用中选择'远程代理'模式并配置WiFi代理。")
    print("🔍 访问 https://bigjj.site:5010 查看服务器状态")
    print("🌐 mitmproxy Web界面: http://bigjj.site:8081")
    print("=" * 60)
    
    try:
        # 配置mitmproxy选项
        ensure_mitmproxy_config('~/.mitmproxy')
        
        # 创建 mitmproxy 选项
        opts = options.Options(
            listen_host='0.0.0.0',
            listen_port=8888,  # 统一使用8888端口
            web_host='0.0.0.0', 
            web_port=8081,
            web_open_browser=False,
            block_global=False,  # 确保不阻止外部访问
            confdir='~/.mitmproxy'
        )
        
        print("🔧 mitmproxy 配置:")
        print(f"   代理端口: {opts.listen_port}")
        print(f"   Web界面端口: {opts.web_port}")
        print(f"   外网访问: {'允许' if not opts.block_global else '阻止'}")
        print("=" * 60)
        
        # 启动mitmproxy
        print("🚀 启动 mitmproxy...")
        
        # 设置信号处理
        def signal_handler(sig, frame):
            print("\n🛑 收到停止信号，正在关闭服务器...")
            sys.exit(0)
        
        signal.signal(signal.SIGINT, signal_handler)
        signal.signal(signal.SIGTERM, signal_handler)
        
        # 运行 mitmproxy
        asyncio.run(run_mitmproxy_async(addon, opts))
        
    except KeyboardInterrupt:
        print("\n🛑 服务器正在关闭...")
    except Exception as e:
        print(f"❌ 服务器运行失败: {e}")
        traceback.print_exc()


if __name__ == '__main__':
    main()
