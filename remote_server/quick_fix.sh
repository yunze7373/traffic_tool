#!/bin/bash
"""
快速修复移动抓包服务器问颁E解决WebSocket连接和代琁E��问问颁E"""

echo "🔧 开始修夁Ebigjj.site 移动抓包服务器问颁E.."
echo "================================================================"

# 1. 部署最新代码Eecho "📦 1. 更新代码到最新版本..."
cd /opt/mobile-proxy
git pull origin master
if [ $? -eq 0 ]; then
    echo "✁E代码更新成功"
else
    echo "❁E代码更新失败"
    exit 1
fi

# 2. 运行诊断工具
echo -e "\n🔍 2. 运行诊断工具..."
python3 diagnose_server.py

# 3. 检查并修复mitmproxy配置
echo -e "\n⚙︁E3. 检查mitmproxy配置..."
mkdir -p ~/.mitmproxy

# 强制写�E正确皁Eitmproxy配置
cat > ~/.mitmproxy/config.yaml << 'EOF'
# Mobile Proxy Configuration
block_global: false
listen_host: 0.0.0.0
listen_port: 8080
mode:
  - regular
ssl_insecure: true
EOF

echo "✁Emitmproxy配置已更新"

# 4. 检查证书杁E��
echo -e "\n🔒 4. 检查SSL证书杁E��..."
if [ -f "/etc/letsencrypt/live/bigjj.site/fullchain.pem" ]; then
    sudo chmod 644 /etc/letsencrypt/live/bigjj.site/fullchain.pem
    sudo chmod 644 /etc/letsencrypt/live/bigjj.site/privkey.pem
    echo "✁ELet's Encrypt证书杁E��已修夁E
fi

if [ -f "/opt/mobile-proxy/cert.pem" ]; then
    sudo chmod 644 /opt/mobile-proxy/cert.pem
    sudo chmod 644 /opt/mobile-proxy/key.pem
    echo "✁E本地证书杁E��已修夁E
fi

# 5. 重启服务
echo -e "\n🔄 5. 重启移动代琁E��务..."
sudo systemctl stop mobile-proxy.service
sleep 2
sudo systemctl start mobile-proxy.service
sleep 3

# 6. 检查服务状态Eecho -e "\n📊 6. 检查服务状态E.."
sudo systemctl status mobile-proxy.service --no-pager -l

# 7. 检查端口监听
echo -e "\n🌐 7. 检查端口监听状态E.."
netstat -tlnp | grep -E ":(5010|8765|8080)"

# 8. 测试API连接
echo -e "\n🧪 8. 测试API连接..."
curl -s -k http://localhost:5010/api/status | jq '.' 2>/dev/null || echo "HTTP API测试失败"
curl -s -k https://localhost:5010/api/status | jq '.' 2>/dev/null || echo "HTTPS API测试失败"

# 9. 测试代琁E��能
echo -e "\n🔄 9. 测试代琁E��能..."
curl --proxy http://127.0.0.1:8080 -s -I http://httpbin.org/get | head -1 || echo "代琁E��试失败"

# 10. 显示最新日忁Eecho -e "\n📋 10. 最新服务日忁E(最吁E0衁E:"
sudo journalctl -u mobile-proxy.service -n 20 --no-pager

echo -e "\n================================================================"
echo "🎯 修复完�E�E�E
echo "📱 现在可以尝试重新连接Android应用"
echo "🌐 状态E��面: https://bigjj.site:5010"
echo "================================================================"
