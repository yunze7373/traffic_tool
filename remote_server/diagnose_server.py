#!/usr/bin/env python3
"""
譛榊苅蝎ｨ隸頑妙蟾･蜈ｷ
譽譟･謇譛画恪蜉｡迥ｶ諤∝柱驟咲ｽｮ髣ｮ鬚・
"""

import os
import requests
import socket
import ssl
import json
from datetime import datetime

def check_certificates():
    """譽譟･SSL隸∽ｹｦ"""
    print("白 譽譟･SSL隸∽ｹｦ...")
    
    cert_paths = [
        ('/etc/letsencrypt/live/bigjj.site/fullchain.pem', '/etc/letsencrypt/live/bigjj.site/privkey.pem', 'Let\'s Encrypt'),
        ('/etc/ssl/certs/bigjj.site.crt', '/etc/ssl/private/bigjj.site.key', '閾ｪ螳壻ｹ芽ｯ∽ｹｦ'),
        ('/opt/mobile-proxy/cert.pem', '/opt/mobile-proxy/key.pem', '譛ｬ蝨ｰ隸∽ｹｦ')
    ]
    
    found_certs = []
    for cert_file, key_file, cert_type in cert_paths:
        if os.path.exists(cert_file) and os.path.exists(key_file):
            try:
                # 譽譟･隸∽ｹｦ譁・ｻｶ譏ｯ蜷ｦ蜿ｯ隸ｻ
                with open(cert_file, 'r') as f:
                    cert_content = f.read()
                with open(key_file, 'r') as f:
                    key_content = f.read()
                    
                print(f"  笨・{cert_type}: {cert_file}")
                found_certs.append((cert_file, key_file, cert_type))
            except Exception as e:
                print(f"  笶・{cert_type}: 譁・ｻｶ蟄伜惠菴・裏豕戊ｯｻ蜿・- {e}")
        else:
            print(f"  笶・{cert_type}: 譁・ｻｶ荳榊ｭ伜惠")
    
    return found_certs

def check_port_connectivity():
    """譽譟･遶ｯ蜿｣霑樣壽ｧ"""
    print("\n倹 譽譟･遶ｯ蜿｣霑樣壽ｧ...")
    
    ports = [
        (5010, 'API譛榊苅蝎ｨ'),
        (8765, 'WebSocket譛榊苅蝎ｨ'), 
        (8080, 'mitmproxy莉｣逅・)
    ]
    
    for port, service in ports:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(3)
            result = sock.connect_ex(('127.0.0.1', port))
            sock.close()
            
            if result == 0:
                print(f"  笨・{service} (遶ｯ蜿｣ {port}): 逶大成荳ｭ")
            else:
                print(f"  笶・{service} (遶ｯ蜿｣ {port}): 譌豕戊ｿ樊磁")
        except Exception as e:
            print(f"  笶・{service} (遶ｯ蜿｣ {port}): 譽譟･螟ｱ雍･ - {e}")

def check_api_status():
    """譽譟･API迥ｶ諤・""
    print("\n投 譽譟･API迥ｶ諤・..")
    
    try:
        # 蟆晁ｯ菱TTP
        response = requests.get('http://127.0.0.1:5010/api/status', timeout=5)
        if response.status_code == 200:
            data = response.json()
            print(f"  笨・HTTP API蜩榊ｺ疲ｭ｣蟶ｸ")
            print(f"    - WebSocket URL: {data.get('ws_url', 'N/A')}")
            print(f"    - API URL: {data.get('api_url', 'N/A')}")
            print(f"    - 豢ｻ霍・ｿ樊磁: {data.get('active_connections', 0)}")
            print(f"    - 豬・㍼隶｡謨ｰ: {data.get('total_traffic', 0)}")
            return data
    except Exception as e:
        print(f"  笶・HTTP API隸ｷ豎ょ､ｱ雍･: {e}")
    
    try:
        # 蟆晁ｯ菱TTPS  
        response = requests.get('https://127.0.0.1:5010/api/status', timeout=5, verify=False)
        if response.status_code == 200:
            data = response.json()
            print(f"  笨・HTTPS API蜩榊ｺ疲ｭ｣蟶ｸ")
            print(f"    - WebSocket URL: {data.get('ws_url', 'N/A')}")
            print(f"    - API URL: {data.get('api_url', 'N/A')}")
            print(f"    - 豢ｻ霍・ｿ樊磁: {data.get('active_connections', 0)}")
            print(f"    - 豬・㍼隶｡謨ｰ: {data.get('total_traffic', 0)}")
            return data
    except Exception as e:
        print(f"  笶・HTTPS API隸ｷ豎ょ､ｱ雍･: {e}")
    
    return None

def check_proxy_functionality():
    """譽譟･莉｣逅・粥閭ｽ"""
    print("\n売 譽譟･莉｣逅・粥閭ｽ...")
    
    try:
        proxies = {
            'http': 'http://127.0.0.1:8080',
            'https': 'http://127.0.0.1:8080'
        }
        
        response = requests.get('http://httpbin.org/ip', proxies=proxies, timeout=10)
        if response.status_code == 200:
            data = response.json()
            print(f"  笨・莉｣逅・粥閭ｽ豁｣蟶ｸ")
            print(f"    - 螟也ｽ選P: {data.get('origin', 'N/A')}")
        else:
            print(f"  笶・莉｣逅・ｿ泌屓迥ｶ諤∫・ {response.status_code}")
    except Exception as e:
        print(f"  笶・莉｣逅・ｵ玖ｯ募､ｱ雍･: {e}")

def check_mitmproxy_config():
    """譽譟･mitmproxy驟咲ｽｮ"""
    print("\n笞呻ｸ・譽譟･mitmproxy驟咲ｽｮ...")
    
    config_path = os.path.expanduser('~/.mitmproxy/config.yaml')
    if os.path.exists(config_path):
        try:
            with open(config_path, 'r') as f:
                config_content = f.read()
            print(f"  笨・驟咲ｽｮ譁・ｻｶ蟄伜惠: {config_path}")
            
            if 'block_global' in config_content:
                if 'block_global: false' in config_content:
                    print(f"  笨・block_global 蟾ｲ隶ｾ鄂ｮ荳ｺ false")
                else:
                    print(f"  笞・・block_global 隶ｾ鄂ｮ蜿ｯ閭ｽ譛蛾琉鬚・)
                    print(f"      驟咲ｽｮ蜀・ｮｹ: {config_content}")
            else:
                print(f"  笞・・驟咲ｽｮ荳ｭ譛ｪ謇ｾ蛻ｰ block_global 隶ｾ鄂ｮ")
        except Exception as e:
            print(f"  笶・隸ｻ蜿夜・鄂ｮ譁・ｻｶ螟ｱ雍･: {e}")
    else:
        print(f"  笶・驟咲ｽｮ譁・ｻｶ荳榊ｭ伜惠: {config_path}")

def main():
    print("剥 bigjj.site 遘ｻ蜉ｨ謚灘桁譛榊苅蝎ｨ隸頑妙蟾･蜈ｷ")
    print("=" * 60)
    print(f"竢ｰ 隸頑妙譌ｶ髣ｴ: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 60)
    
    # 霑占｡梧園譛画｣譟･
    certs = check_certificates()
    check_port_connectivity()
    api_data = check_api_status()
    check_proxy_functionality()
    check_mitmproxy_config()
    
    print("\n" + "=" * 60)
    print("搭 隸頑妙諤ｻ扈・")
    
    if certs:
        print(f"笨・蜿醍鴫 {len(certs)} 荳ｪ譛画譜SSL隸∽ｹｦ")
    else:
        print("笶・譛ｪ蜿醍鴫譛画譜逧ТSL隸∽ｹｦ")
    
    if api_data:
        if api_data.get('active_connections', 0) > 0:
            print("笨・WebSocket譛画ｴｻ霍・ｿ樊磁")
        else:
            print("笞・・WebSocket譌豢ｻ霍・ｿ樊磁")
            
        if api_data.get('total_traffic', 0) > 0:
            print("笨・譽豬句芦莉｣逅・ｵ・㍼")
        else:
            print("笞・・譛ｪ譽豬句芦莉｣逅・ｵ・㍼")
    else:
        print("笶・API譛榊苅蝎ｨ譌蜩榊ｺ・)
    
    print("=" * 60)

if __name__ == '__main__':
    main()
