#!/bin/bash

# 移动抓包远程代琁E��务器部署脚本

echo "🚀 开始部署移动抓包远程代琁E��务器..."

# 1. 更新系绁Eecho "📦 更新系统软件匁E.."
sudo apt update && sudo apt upgrade -y

# 2. 安裁Eython和pip
echo "🐍 安裁Eython环墁E.."
sudo apt install -y python3 python3-pip python3-venv

# 3. 安裁E��赁Eecho "📚 安裁Eython依赁E.."
pip3 install mitmproxy websockets

# 4. 配置防火墁Eecho "🔥 配置防火墙见E�E..."
sudo ufw allow 8080/tcp  # 代琁E��口
sudo ufw allow 5000/tcp  # API端口
sudo ufw allow 8765/tcp  # WebSocket端口
sudo ufw --force enable

# 5. 创建服务目彁Eecho "📁 创建服务目彁E.."
sudo mkdir -p /opt/mobile-proxy
sudo chown $USER:$USER /opt/mobile-proxy
cd /opt/mobile-proxy

# 6. 下载服务器脚本 (偁E��您已上传到服务器)
echo "📄 复制服务器脚本..."
# cp ~/mobile_proxy_server.py /opt/mobile-proxy/
# 或老E��接在这里创建脚本

# 7. 创建systemd服务
echo "⚙︁E 创建系统服务..."
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
echo "🔄 启动代琁E��务..."
sudo systemctl daemon-reload
sudo systemctl enable mobile-proxy
sudo systemctl start mobile-proxy

# 9. 显示状态Eecho "📊 服务状态E��E
sudo systemctl status mobile-proxy --no-pager

# 10. 显示服务器信息
echo ""
echo "✁E部署完�E�E�E
echo "=" * 50
echo "服务器配置信息�E�E
echo "代琁E��址: $(curl -s ifconfig.me):8080"
echo "WebSocket: ws://$(curl -s ifconfig.me):8765"
echo "API接口: http://$(curl -s ifconfig.me):5000"
echo ""
echo "Android应用配置�E�E
echo "1. 打开应用�E�选择'远程代琁E模弁E
echo "2. 配置WiFi代琁E��吁E $(curl -s ifconfig.me):8080"
echo "3. 开始抓匁E��E
echo ""
echo "查看日忁E sudo journalctl -u mobile-proxy -f"
echo "重启服务: sudo systemctl restart mobile-proxy"
