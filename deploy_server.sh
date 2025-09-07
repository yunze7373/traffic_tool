#!/bin/bash

# 移动抓包远程代理服务器部署脚本

echo "🚀 开始部署移动抓包远程代理服务器..."

# 1. 更新系统
echo "📦 更新系统软件包..."
sudo apt update && sudo apt upgrade -y

# 2. 安装Python和pip
echo "🐍 安装Python环境..."
sudo apt install -y python3 python3-pip python3-venv

# 3. 安装依赖
echo "📚 安装Python依赖..."
pip3 install mitmproxy websockets

# 4. 配置防火墙
echo "🔥 配置防火墙规则..."
sudo ufw allow 8888/tcp  # 代理端口
sudo ufw allow 5000/tcp  # API端口
sudo ufw allow 8765/tcp  # WebSocket端口
sudo ufw --force enable

# 5. 创建服务目录
echo "📁 创建服务目录..."
sudo mkdir -p /opt/mobile-proxy
sudo chown $USER:$USER /opt/mobile-proxy
cd /opt/mobile-proxy

# 6. 下载服务器脚本 (假设您已上传到服务器)
echo "📄 复制服务器脚本..."
# cp ~/mobile_proxy_server.py /opt/mobile-proxy/
# 或者直接在这里创建脚本

# 7. 创建systemd服务
echo "⚙️  创建系统服务..."
sudo tee /etc/systemd/system/mobile-proxy.service > /dev/null <<EOF
[Unit]
Description=Mobile Traffic Capture Proxy Server
After=network.target

[Service]
Type=simple
User=$USER
WorkingDirectory=/opt/mobile-proxy
ExecStart=/usr/bin/python3 /opt/mobile-proxy/mobile_proxy_server.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

# 8. 启动服务
echo "🔄 启动代理服务..."
sudo systemctl daemon-reload
sudo systemctl enable mobile-proxy
sudo systemctl start mobile-proxy

# 9. 显示状态
echo "📊 服务状态："
sudo systemctl status mobile-proxy --no-pager

# 10. 显示服务器信息
echo ""
echo "✅ 部署完成！"
echo "=" * 50
echo "服务器配置信息："
echo "代理地址: $(curl -s ifconfig.me):8888"
echo "WebSocket: ws://$(curl -s ifconfig.me):8765"
echo "API接口: http://$(curl -s ifconfig.me):5000"
echo ""
echo "Android应用配置："
echo "1. 打开应用，选择'远程代理'模式"
echo "2. 配置WiFi代理指向: $(curl -s ifconfig.me):8888"
echo "3. 开始抓包！"
echo ""
echo "查看日志: sudo journalctl -u mobile-proxy -f"
echo "重启服务: sudo systemctl restart mobile-proxy"
