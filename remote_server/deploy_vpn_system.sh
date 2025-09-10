#!/bin/bash

# WireGuard VPN流量监控完整部署脚本
# 适用于Ubuntu/Debian系统

set -e

echo "🛡️ 开始部署WireGuard VPN流量监控系统..."

# 检查是否为root用户
if [ "$EUID" -ne 0 ]; then
    echo "❌ 请使用root权限运行此脚本"
    exit 1
fi

# 创建部署目录
DEPLOY_DIR="/opt/vpn-traffic-monitor"
mkdir -p $DEPLOY_DIR
cd $DEPLOY_DIR

echo "📁 部署目录: $DEPLOY_DIR"

# 1. 安装系统依赖
echo "📦 安装系统依赖..."
apt update
apt install -y wireguard wireguard-tools python3 python3-pip ufw curl

# 2. 安装Python依赖
echo "🐍 安装Python依赖..."
pip3 install asyncio websockets sqlite3

# 3. 设置WireGuard
echo "🔧 配置WireGuard..."
./setup_wireguard.sh

# 4. 部署流量监控服务
echo "📋 部署流量监控服务..."
cp vpn_traffic_server.py $DEPLOY_DIR/
chmod +x $DEPLOY_DIR/vpn_traffic_server.py

# 5. 创建systemd服务文件
echo "⚙️ 创建systemd服务..."
cat > /etc/systemd/system/vpn-traffic-monitor.service << EOF
[Unit]
Description=VPN Traffic Monitor Service
After=network.target wg-quick@wg0.service
Requires=wg-quick@wg0.service

[Service]
Type=simple
User=root
WorkingDirectory=$DEPLOY_DIR
ExecStart=/usr/bin/python3 $DEPLOY_DIR/vpn_traffic_server.py
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# 6. 启用并启动服务
echo "🚀 启动服务..."
systemctl daemon-reload
systemctl enable vpn-traffic-monitor.service
systemctl start vpn-traffic-monitor.service

# 7. 配置防火墙
echo "🛡️ 配置防火墙..."
ufw allow 51820/udp comment "WireGuard VPN"
ufw allow 5010/tcp comment "VPN API"
ufw allow 8765/tcp comment "WebSocket"

# 8. 显示服务状态
echo "✅ 部署完成！"
echo "===================="
echo "系统状态:"
echo ""

echo "WireGuard状态:"
systemctl status wg-quick@wg0 --no-pager -l

echo ""
echo "VPN流量监控服务状态:"
systemctl status vpn-traffic-monitor --no-pager -l

echo ""
echo "WireGuard接口状态:"
wg show

echo ""
echo "防火墙状态:"
ufw status

echo ""
echo "🌐 服务地址:"
SERVER_IP=$(curl -s ifconfig.me)
echo "  - VPN服务器: $SERVER_IP:51820"
echo "  - Web界面: https://$SERVER_IP:5010"
echo "  - WebSocket: wss://$SERVER_IP:8765"

echo ""
echo "📱 Android客户端配置文件:"
echo "请查看: /etc/wireguard/client_android.conf"

echo ""
echo "🔧 管理命令:"
echo "  - 查看日志: journalctl -u vpn-traffic-monitor -f"
echo "  - 重启服务: systemctl restart vpn-traffic-monitor"
echo "  - 停止服务: systemctl stop vpn-traffic-monitor"
echo "  - WireGuard状态: wg show"

echo ""
echo "🎉 WireGuard VPN流量监控系统部署完成！"
