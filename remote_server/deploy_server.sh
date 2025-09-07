#!/bin/bash

# bigjj.site 移动抓包远程代理服务器部署脚本
# 自动化部署到 Ubuntu/Debian 服务器

echo "🚀 开始部署 bigjj.site 移动抓包远程代理服务器..."
echo "=" * 60

# 获取服务器IP
SERVER_IP=$(curl -s ifconfig.me 2>/dev/null || curl -s ipinfo.io/ip 2>/dev/null || echo "未知")

# 1. 更新系统
echo "📦 更新系统软件包..."
sudo apt update && sudo apt upgrade -y

# 2. 安装Python和pip
echo "🐍 安装Python环境..."
sudo apt install -y python3 python3-pip python3-venv curl wget unzip

# 3. 安装依赖
echo "📚 安装Python依赖..."
pip3 install --user mitmproxy websockets

# 确保pip安装的程序在PATH中
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc

# 4. 配置防火墙
echo "🔥 配置防火墙规则..."

# 检测操作系统类型
if command -v ufw >/dev/null 2>&1; then
    # Ubuntu/Debian 系统使用 ufw
    echo "检测到 Ubuntu/Debian 系统，使用 ufw..."
    sudo ufw allow 8888/tcp  # 代理端口
    sudo ufw allow 5010/tcp  # API端口
    sudo ufw allow 8765/tcp  # WebSocket端口
    sudo ufw allow 8010/tcp  # mitmproxy web界面
    sudo ufw allow 22/tcp    # SSH (确保不被锁定)
    sudo ufw --force enable
elif command -v firewall-cmd >/dev/null 2>&1; then
    # CentOS/RHEL/Amazon Linux 系统使用 firewalld
    echo "检测到 CentOS/RHEL/Amazon Linux 系统，使用 firewalld..."
    sudo systemctl start firewalld
    sudo systemctl enable firewalld
    sudo firewall-cmd --permanent --add-port=8888/tcp  # 代理端口
    sudo firewall-cmd --permanent --add-port=5010/tcp  # API端口
    sudo firewall-cmd --permanent --add-port=8765/tcp  # WebSocket端口
    sudo firewall-cmd --permanent --add-port=8010/tcp  # mitmproxy web界面
    sudo firewall-cmd --permanent --add-service=ssh    # SSH (确保不被锁定)
    sudo firewall-cmd --reload
    echo "✅ firewalld 规则已配置"
else
    echo "⚠️  未检测到防火墙管理工具，请手动配置防火墙规则"
    echo "需要开放端口: 8888, 5010, 8765, 8010"
fi

# 5. 创建服务目录
echo "📁 创建服务目录..."
sudo mkdir -p /opt/mobile-proxy
sudo chown $USER:$USER /opt/mobile-proxy
cd /opt/mobile-proxy

# 6. 复制服务器脚本
echo "📄 部署服务器脚本..."

# 获取脚本所在目录的绝对路径
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "$SCRIPT_DIR/mobile_proxy_server.py" ]; then
    cp "$SCRIPT_DIR/mobile_proxy_server.py" /opt/mobile-proxy/
    echo "✅ 脚本复制成功"
elif [ -f "mobile_proxy_server.py" ]; then
    # 如果在当前目录
    cp "mobile_proxy_server.py" /opt/mobile-proxy/
    echo "✅ 脚本复制成功"
else
    echo "❌ 未找到 mobile_proxy_server.py 文件"
    echo "请确保在包含脚本的目录中运行此部署脚本"
    exit 1
fi

# 7. 创建启动脚本
echo "⚙️  创建启动脚本..."
cat > /opt/mobile-proxy/start.sh << 'EOF'
#!/bin/bash
cd /opt/mobile-proxy
export PATH="$HOME/.local/bin:$PATH"
python3 mobile_proxy_server.py
EOF
chmod +x /opt/mobile-proxy/start.sh

# 8. 创建systemd服务
echo "🔧 创建系统服务..."
sudo tee /etc/systemd/system/mobile-proxy.service > /dev/null <<EOF
[Unit]
Description=bigjj.site Mobile Traffic Capture Proxy Server
After=network.target
Wants=network.target

[Service]
Type=simple
User=$USER
Group=$USER
WorkingDirectory=/opt/mobile-proxy
Environment=PATH=/home/$USER/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
ExecStart=/opt/mobile-proxy/start.sh
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# 9. 启动服务
echo "🔄 启动代理服务..."
sudo systemctl daemon-reload
sudo systemctl enable mobile-proxy
sudo systemctl start mobile-proxy

# 等待服务启动
sleep 3

# 10. 显示状态
echo "📊 服务状态："
sudo systemctl status mobile-proxy --no-pager -l

# 11. 创建SSL证书 (如果需要HTTPS)
echo "🔒 创建SSL证书..."
if [ ! -f /opt/mobile-proxy/cert.pem ]; then
    openssl req -x509 -newkey rsa:2048 -keyout /opt/mobile-proxy/key.pem -out /opt/mobile-proxy/cert.pem -days 365 -nodes -subj "/C=US/ST=State/L=City/O=Organization/CN=bigjj.site"
    echo "✅ SSL证书已生成"
fi

# 12. 创建管理脚本
echo "🛠️  创建管理脚本..."
cat > /opt/mobile-proxy/manage.sh << 'EOF'
#!/bin/bash

case "$1" in
    start)
        sudo systemctl start mobile-proxy
        echo "✅ 服务已启动"
        ;;
    stop)
        sudo systemctl stop mobile-proxy
        echo "🛑 服务已停止"
        ;;
    restart)
        sudo systemctl restart mobile-proxy
        echo "🔄 服务已重启"
        ;;
    status)
        sudo systemctl status mobile-proxy --no-pager
        ;;
    logs)
        sudo journalctl -u mobile-proxy -f
        ;;
    enable)
        sudo systemctl enable mobile-proxy
        echo "✅ 服务已设置为开机自启"
        ;;
    disable)
        sudo systemctl disable mobile-proxy
        echo "❌ 服务已取消开机自启"
        ;;
    *)
        echo "使用方法: $0 {start|stop|restart|status|logs|enable|disable}"
        exit 1
        ;;
esac
EOF
chmod +x /opt/mobile-proxy/manage.sh

# 13. 显示最终配置信息
echo ""
echo "🎉 部署完成！"
echo "=" * 60
echo "📍 服务器信息:"
echo "   域名: bigjj.site"
echo "   IP地址: $SERVER_IP"
echo ""
echo "🌐 服务端口:"
echo "   代理服务器: bigjj.site:8888"
echo "   WebSocket: wss://bigjj.site:8765"
echo "   API接口: https://bigjj.site:5010"
echo "   Web管理: http://bigjj.site:8010"
echo "   状态页面: https://bigjj.site:5010"
echo ""
echo "📱 Android应用配置:"
echo "   1. 选择'远程代理'模式"
echo "   2. WiFi设置 → 修改网络 → 高级选项"
echo "   3. 代理: 手动"
echo "   4. 主机名: bigjj.site"
echo "   5. 端口: 8888"
echo ""
echo "🔒 HTTPS证书下载:"
echo "   http://bigjj.site:8888/cert.pem"
echo ""
echo "🛠️  管理命令:"
echo "   启动服务: /opt/mobile-proxy/manage.sh start"
echo "   停止服务: /opt/mobile-proxy/manage.sh stop"
echo "   重启服务: /opt/mobile-proxy/manage.sh restart"
echo "   查看状态: /opt/mobile-proxy/manage.sh status"
echo "   查看日志: /opt/mobile-proxy/manage.sh logs"
echo ""
echo "🔍 测试链接:"
echo "   curl https://bigjj.site:5010/api/status"
echo "   curl https://bigjj.site:5010"
echo ""
echo "=" * 60
echo "✅ bigjj.site 移动抓包代理服务器部署完成！"

# 14. 测试服务
echo "🧪 测试服务..."
sleep 2
if curl -s --connect-timeout 5 http://localhost:5010/api/status > /dev/null; then
    echo "✅ HTTP API 服务正常"
else
    echo "⚠️  HTTP API 服务可能未启动，请检查日志"
fi

if nc -z localhost 8765 2>/dev/null; then
    echo "✅ WebSocket 服务正常"
else
    echo "⚠️  WebSocket 服务可能未启动，请检查日志"
fi

if nc -z localhost 8888 2>/dev/null; then
    echo "✅ 代理服务正常"
else
    echo "⚠️  代理服务可能未启动，请检查日志"
fi

echo ""
echo "🔧 如果服务未正常启动，请运行："
echo "   sudo journalctl -u mobile-proxy -f"
echo "   查看详细日志"
