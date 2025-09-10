#!/bin/bash

# WireGuard服务器安装和配置脚本
# 适用于Ubuntu/Debian系统

set -e

echo "🚀 开始安装和配置WireGuard服务器..."

# 检查是否为root用户
if [ "$EUID" -ne 0 ]; then
    echo "❌ 请使用root权限运行此脚本"
    exit 1
fi

# 安装WireGuard
echo "📦 安装WireGuard..."
apt update
apt install -y wireguard wireguard-tools

# 创建配置目录
WG_DIR="/etc/wireguard"
KEYS_DIR="$WG_DIR/keys"
mkdir -p $KEYS_DIR
cd $WG_DIR

# 生成服务器密钥对
echo "🔑 生成服务器密钥对..."
wg genkey | tee $KEYS_DIR/server_private.key | wg pubkey > $KEYS_DIR/server_public.key

# 生成客户端密钥对
echo "🔑 生成客户端密钥对..."
wg genkey | tee $KEYS_DIR/client_private.key | wg pubkey > $KEYS_DIR/client_public.key

# 获取服务器私钥和客户端公钥
SERVER_PRIVATE_KEY=$(cat $KEYS_DIR/server_private.key)
SERVER_PUBLIC_KEY=$(cat $KEYS_DIR/server_public.key)
CLIENT_PRIVATE_KEY=$(cat $KEYS_DIR/client_private.key)
CLIENT_PUBLIC_KEY=$(cat $KEYS_DIR/client_public.key)

# 获取服务器公网IP
SERVER_IP=$(curl -s ifconfig.me)
echo "📡 服务器公网IP: $SERVER_IP"

# 创建服务器配置文件
echo "📝 创建WireGuard服务器配置..."
cat > $WG_DIR/wg0.conf << EOF
[Interface]
PrivateKey = $SERVER_PRIVATE_KEY
Address = 10.66.66.1/24
ListenPort = 51820
SaveConfig = true

# 启用IP转发和NAT
PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -A FORWARD -o %i -j ACCEPT; iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT; iptables -t nat -D POSTROUTING -o eth0 -j MASQUERADE

[Peer]
PublicKey = $CLIENT_PUBLIC_KEY
AllowedIPs = 10.66.66.2/32
EOF

# 创建Android客户端配置文件
echo "📱 创建Android客户端配置文件..."
cat > $WG_DIR/client_android.conf << EOF
[Interface]
PrivateKey = $CLIENT_PRIVATE_KEY
Address = 10.66.66.2/24
DNS = 8.8.8.8, 8.8.4.4

[Peer]
PublicKey = $SERVER_PUBLIC_KEY
Endpoint = $SERVER_IP:51820
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
EOF

# 启用IP转发
echo "🌐 启用IP转发..."
echo 'net.ipv4.ip_forward=1' >> /etc/sysctl.conf
sysctl -p

# 设置权限
chmod 600 $WG_DIR/*.conf
chmod 600 $KEYS_DIR/*

# 启动WireGuard服务
echo "🚀 启动WireGuard服务..."
systemctl enable wg-quick@wg0
systemctl start wg-quick@wg0

# 配置防火墙
echo "🛡️ 配置防火墙规则..."
ufw allow 51820/udp
ufw allow 5010/tcp  # API端口
ufw allow 8765/tcp  # WebSocket端口

# 显示状态
echo "✅ WireGuard服务器配置完成！"
echo "===================="
echo "服务器状态:"
systemctl status wg-quick@wg0 --no-pager
echo ""
echo "WireGuard接口状态:"
wg show
echo ""
echo "🔑 密钥信息保存在: $KEYS_DIR"
echo "📝 服务器配置: $WG_DIR/wg0.conf"
echo "📱 Android配置: $WG_DIR/client_android.conf"
echo ""
echo "请将 client_android.conf 文件内容用于Android应用配置"
