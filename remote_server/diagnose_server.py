#!/usr/bin/env python3
"""
服务器诊断工具
检查所有服务状态和配置问题
"""

import os
import requests
import socket
import ssl
import json
from datetime import datetime

def check_certificates():
    """检查SSL证书"""
    print("🔒 检查SSL证书...")
    
    cert_paths = [
        ('/etc/letsencrypt/live/bigjj.site/fullchain.pem', '/etc/letsencrypt/live/bigjj.site/privkey.pem', 'Let\'s Encrypt'),
        ('/etc/ssl/certs/bigjj.site.crt', '/etc/ssl/private/bigjj.site.key', '自定义证书'),
        ('/opt/mobile-proxy/cert.pem', '/opt/mobile-proxy/key.pem', '本地证书')
    ]
    
    found_certs = []
    for cert_file, key_file, cert_type in cert_paths:
        if os.path.exists(cert_file) and os.path.exists(key_file):
            try:
                # 检查证书文件是否可读
                with open(cert_file, 'r') as f:
                    cert_content = f.read()
                with open(key_file, 'r') as f:
                    key_content = f.read()
                    
                print(f"  ✅ {cert_type}: {cert_file}")
                found_certs.append((cert_file, key_file, cert_type))
            except Exception as e:
                print(f"  ❌ {cert_type}: 文件存在但无法读取 - {e}")
        else:
            print(f"  ❌ {cert_type}: 文件不存在")
    
    return found_certs

def check_port_connectivity():
    """检查端口连通性"""
    print("\n🌐 检查端口连通性...")
    
    ports = [
        (5010, 'API服务器'),
        (8765, 'WebSocket服务器'), 
        (8888, 'mitmproxy代理')
    ]
    
    for port, service in ports:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(3)
            result = sock.connect_ex(('127.0.0.1', port))
            sock.close()
            
            if result == 0:
                print(f"  ✅ {service} (端口 {port}): 监听中")
            else:
                print(f"  ❌ {service} (端口 {port}): 无法连接")
        except Exception as e:
            print(f"  ❌ {service} (端口 {port}): 检查失败 - {e}")

def check_api_status():
    """检查API状态"""
    print("\n📊 检查API状态...")
    
    try:
        # 尝试HTTP
        response = requests.get('http://127.0.0.1:5010/api/status', timeout=5)
        if response.status_code == 200:
            data = response.json()
            print(f"  ✅ HTTP API响应正常")
            print(f"    - WebSocket URL: {data.get('ws_url', 'N/A')}")
            print(f"    - API URL: {data.get('api_url', 'N/A')}")
            print(f"    - 活跃连接: {data.get('active_connections', 0)}")
            print(f"    - 流量计数: {data.get('total_traffic', 0)}")
            return data
    except Exception as e:
        print(f"  ❌ HTTP API请求失败: {e}")
    
    try:
        # 尝试HTTPS  
        response = requests.get('https://127.0.0.1:5010/api/status', timeout=5, verify=False)
        if response.status_code == 200:
            data = response.json()
            print(f"  ✅ HTTPS API响应正常")
            print(f"    - WebSocket URL: {data.get('ws_url', 'N/A')}")
            print(f"    - API URL: {data.get('api_url', 'N/A')}")
            print(f"    - 活跃连接: {data.get('active_connections', 0)}")
            print(f"    - 流量计数: {data.get('total_traffic', 0)}")
            return data
    except Exception as e:
        print(f"  ❌ HTTPS API请求失败: {e}")
    
    return None

def check_proxy_functionality():
    """检查代理功能"""
    print("\n🔄 检查代理功能...")
    
    try:
        proxies = {
            'http': 'http://127.0.0.1:8888',
            'https': 'http://127.0.0.1:8888'
        }
        
        response = requests.get('http://httpbin.org/ip', proxies=proxies, timeout=10)
        if response.status_code == 200:
            data = response.json()
            print(f"  ✅ 代理功能正常")
            print(f"    - 外网IP: {data.get('origin', 'N/A')}")
        else:
            print(f"  ❌ 代理返回状态码: {response.status_code}")
    except Exception as e:
        print(f"  ❌ 代理测试失败: {e}")

def check_mitmproxy_config():
    """检查mitmproxy配置"""
    print("\n⚙️ 检查mitmproxy配置...")
    
    config_path = os.path.expanduser('~/.mitmproxy/config.yaml')
    if os.path.exists(config_path):
        try:
            with open(config_path, 'r') as f:
                config_content = f.read()
            print(f"  ✅ 配置文件存在: {config_path}")
            
            if 'block_global' in config_content:
                if 'block_global: false' in config_content:
                    print(f"  ✅ block_global 已设置为 false")
                else:
                    print(f"  ⚠️ block_global 设置可能有问题")
                    print(f"      配置内容: {config_content}")
            else:
                print(f"  ⚠️ 配置中未找到 block_global 设置")
        except Exception as e:
            print(f"  ❌ 读取配置文件失败: {e}")
    else:
        print(f"  ❌ 配置文件不存在: {config_path}")

def main():
    print("🔍 bigjj.site 移动抓包服务器诊断工具")
    print("=" * 60)
    print(f"⏰ 诊断时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 60)
    
    # 运行所有检查
    certs = check_certificates()
    check_port_connectivity()
    api_data = check_api_status()
    check_proxy_functionality()
    check_mitmproxy_config()
    
    print("\n" + "=" * 60)
    print("📋 诊断总结:")
    
    if certs:
        print(f"✅ 发现 {len(certs)} 个有效SSL证书")
    else:
        print("❌ 未发现有效的SSL证书")
    
    if api_data:
        if api_data.get('active_connections', 0) > 0:
            print("✅ WebSocket有活跃连接")
        else:
            print("⚠️ WebSocket无活跃连接")
            
        if api_data.get('total_traffic', 0) > 0:
            print("✅ 检测到代理流量")
        else:
            print("⚠️ 未检测到代理流量")
    else:
        print("❌ API服务器无响应")
    
    print("=" * 60)

if __name__ == '__main__':
    main()
