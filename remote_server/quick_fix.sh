#!/bin/bash
"""
快速修复移动抓包服务器问题
解决WebSocket连接和代理访问问题
"""

echo "🔧 开始修复 bigjj.site 移动抓包服务器问题..."
echo "================================================================"

# 1. 部署最新代码
echo "📦 1. 更新代码到最新版本..."
cd /opt/mobile-proxy
git pull origin master
if [ $? -eq 0 ]; then
    echo "✅ 代码更新成功"
else
    echo "❌ 代码更新失败"
    exit 1
fi

# 2. 运行诊断工具
echo -e "\n🔍 2. 运行诊断工具..."
python3 diagnose_server.py

# 3. 检查并修复mitmproxy配置
echo -e "\n⚙️ 3. 检查mitmproxy配置..."
mkdir -p ~/.mitmproxy

# 强制写入正确的mitmproxy配置
cat > ~/.mitmproxy/config.yaml << 'EOF'
# Mobile Proxy Configuration
block_global: false
listen_host: 0.0.0.0
listen_port: 8888
mode:
  - regular
ssl_insecure: true
EOF

echo "✅ mitmproxy配置已更新"

# 4. 检查证书权限
echo -e "\n🔒 4. 检查SSL证书权限..."
if [ -f "/etc/letsencrypt/live/bigjj.site/fullchain.pem" ]; then
    sudo chmod 644 /etc/letsencrypt/live/bigjj.site/fullchain.pem
    sudo chmod 644 /etc/letsencrypt/live/bigjj.site/privkey.pem
    echo "✅ Let's Encrypt证书权限已修复"
fi

if [ -f "/opt/mobile-proxy/cert.pem" ]; then
    sudo chmod 644 /opt/mobile-proxy/cert.pem
    sudo chmod 644 /opt/mobile-proxy/key.pem
    echo "✅ 本地证书权限已修复"
fi

# 5. 重启服务
echo -e "\n🔄 5. 重启移动代理服务..."
sudo systemctl stop mobile-proxy.service
sleep 2
sudo systemctl start mobile-proxy.service
sleep 3

# 6. 检查服务状态
echo -e "\n📊 6. 检查服务状态..."
sudo systemctl status mobile-proxy.service --no-pager -l

# 7. 检查端口监听
echo -e "\n🌐 7. 检查端口监听状态..."
netstat -tlnp | grep -E ":(5010|8765|8888)"

# 8. 测试API连接
echo -e "\n🧪 8. 测试API连接..."
curl -s -k http://localhost:5010/api/status | jq '.' 2>/dev/null || echo "HTTP API测试失败"
curl -s -k https://localhost:5010/api/status | jq '.' 2>/dev/null || echo "HTTPS API测试失败"

# 9. 测试代理功能
echo -e "\n🔄 9. 测试代理功能..."
curl --proxy http://127.0.0.1:8888 -s -I http://httpbin.org/get | head -1 || echo "代理测试失败"

# 10. 显示最新日志
echo -e "\n📋 10. 最新服务日志 (最后20行):"
sudo journalctl -u mobile-proxy.service -n 20 --no-pager

echo -e "\n================================================================"
echo "🎯 修复完成！"
echo "📱 现在可以尝试重新连接Android应用"
echo "🌐 状态页面: https://bigjj.site:5010"
echo "================================================================"
