#!/usr/bin/env python3
"""
服务器诊断工具
检查所有服务状态和配置问颁E
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
                # 检查证书斁E��是否可读
                with open(cert_file, 'r') as f:
                    cert_content = f.read()
                with open(key_file, 'r') as f:
                    key_content = f.read()
                    
                print(f"  ✁E{cert_type}: {cert_file}")
                found_certs.append((cert_file, key_file, cert_type))
            except Exception as e:
                print(f"  ❁E{cert_type}: 斁E��存在佁E��法读叁E- {e}")
        else:
            print(f"  ❁E{cert_type}: 斁E��不存在")
    
    return found_certs

def check_port_connectivity():
    """检查端口连通性"""
    print("\n🌐 检查端口连通性...")
    
    ports = [
        (5010, 'API服务器'),
        (8765, 'WebSocket服务器'), 
        (8080, 'mitmproxy代琁E)
    ]
    
    for port, service in ports:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(3)
            result = sock.connect_ex(('127.0.0.1', port))
            sock.close()
            
            if result == 0:
                print(f"  ✁E{service} (端口 {port}): 监听中")
            else:
                print(f"  ❁E{service} (端口 {port}): 无法连接")
        except Exception as e:
            print(f"  ❁E{service} (端口 {port}): 检查失败 - {e}")

def check_api_status():
    """检查API状态E""
    print("\n📊 检查API状态E..")
    
    try:
        # 尝试HTTP
        response = requests.get('http://127.0.0.1:5010/api/status', timeout=5)
        if response.status_code == 200:
            data = response.json()
            print(f"  ✁EHTTP API响应正常")
            print(f"    - WebSocket URL: {data.get('ws_url', 'N/A')}")
            print(f"    - API URL: {data.get('api_url', 'N/A')}")
            print(f"    - 活跁E��接: {data.get('active_connections', 0)}")
            print(f"    - 流E��计数: {data.get('total_traffic', 0)}")
            return data
    except Exception as e:
        print(f"  ❁EHTTP API请求失败: {e}")
    
    try:
        # 尝试HTTPS  
        response = requests.get('https://127.0.0.1:5010/api/status', timeout=5, verify=False)
        if response.status_code == 200:
            data = response.json()
            print(f"  ✁EHTTPS API响应正常")
            print(f"    - WebSocket URL: {data.get('ws_url', 'N/A')}")
            print(f"    - API URL: {data.get('api_url', 'N/A')}")
            print(f"    - 活跁E��接: {data.get('active_connections', 0)}")
            print(f"    - 流E��计数: {data.get('total_traffic', 0)}")
            return data
    except Exception as e:
        print(f"  ❁EHTTPS API请求失败: {e}")
    
    return None

def check_proxy_functionality():
    """检查代琁E��能"""
    print("\n🔄 检查代琁E��能...")
    
    try:
        proxies = {
            'http': 'http://127.0.0.1:8080',
            'https': 'http://127.0.0.1:8080'
        }
        
        response = requests.get('http://httpbin.org/ip', proxies=proxies, timeout=10)
        if response.status_code == 200:
            data = response.json()
            print(f"  ✁E代琁E��能正常")
            print(f"    - 外网IP: {data.get('origin', 'N/A')}")
        else:
            print(f"  ❁E代琁E��回状态码E {response.status_code}")
    except Exception as e:
        print(f"  ❁E代琁E��试失败: {e}")

def check_mitmproxy_config():
    """检查mitmproxy配置"""
    print("\n⚙︁E检查mitmproxy配置...")
    
    config_path = os.path.expanduser('~/.mitmproxy/config.yaml')
    if os.path.exists(config_path):
        try:
            with open(config_path, 'r') as f:
                config_content = f.read()
            print(f"  ✁E配置斁E��存在: {config_path}")
            
            if 'block_global' in config_content:
                if 'block_global: false' in config_content:
                    print(f"  ✁Eblock_global 已设置为 false")
                else:
                    print(f"  ⚠�E�Eblock_global 设置可能有问颁E)
                    print(f"      配置冁E��: {config_content}")
            else:
                print(f"  ⚠�E�E配置中未找到 block_global 设置")
        except Exception as e:
            print(f"  ❁E读取�E置斁E��失败: {e}")
    else:
        print(f"  ❁E配置斁E��不存在: {config_path}")

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
    print("📋 诊断总绁E")
    
    if certs:
        print(f"✁E发现 {len(certs)} 个有效SSL证书")
    else:
        print("❁E未发现有效的SSL证书")
    
    if api_data:
        if api_data.get('active_connections', 0) > 0:
            print("✁EWebSocket有活跁E��接")
        else:
            print("⚠�E�EWebSocket无活跁E��接")
            
        if api_data.get('total_traffic', 0) > 0:
            print("✁E检测到代琁E��E��")
        else:
            print("⚠�E�E未检测到代琁E��E��")
    else:
        print("❁EAPI服务器无响庁E)
    
    print("=" * 60)

if __name__ == '__main__':
    main()
