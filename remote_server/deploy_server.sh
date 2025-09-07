#!/bin/bash

# bigjj.site 移动抓包远程代理服务器一键部署脚本
# 自动从GitHub下载最新版本并部署

echo "🚀 开始一键部署 bigjj.site 移动抓包远程代理服务器..."
echo "🌐 自动从GitHub获取最新版本..."
echo "=" * 60

# GitHub仓库信息
GITHUB_REPO="yunze7373/traffic_tool"
GITHUB_BRANCH="master"
GITHUB_RAW_URL="https://raw.githubusercontent.com/$GITHUB_REPO/$GITHUB_BRANCH"

# 获取服务器IP
SERVER_IP=$(curl -s ifconfig.me 2>/dev/null || curl -s ipinfo.io/ip 2>/dev/null || echo "未知")

# 检测操作系统
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=$NAME
    OS_VERSION=$VERSION_ID
else
    OS=$(uname -s)
fi

echo "📊 系统信息:"
echo "   操作系统: $OS"
echo "   IP地址: $SERVER_IP"
echo ""

# 1. 更新系统并安装基础软件
echo "📦 更新系统软件包..."
if command -v yum >/dev/null 2>&1; then
    # Amazon Linux / CentOS / RHEL
    sudo yum update -y
    sudo yum install -y python3 python3-pip curl wget unzip firewalld nc
elif command -v apt >/dev/null 2>&1; then
    # Ubuntu / Debian
    sudo apt update && sudo apt upgrade -y
    sudo apt install -y python3 python3-pip python3-venv curl wget unzip ufw netcat-openbsd
else
    echo "❌ 不支持的操作系统"
    exit 1
fi

# 2. 安装Python依赖
echo "� 安装Python依赖..."
pip3 install --user mitmproxy websockets flask

# 确保pip安装的程序在PATH中
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
export PATH="$HOME/.local/bin:$PATH"

# 4. 配置防火墙
echo "🔥 配置防火墙规则..."

# 检测操作系统类型并配置防火墙
if command -v ufw >/dev/null 2>&1; then
    # Ubuntu/Debian 系统使用 ufw
    echo "检测到 Ubuntu/Debian 系统，使用 ufw..."
    sudo ufw allow 8888/tcp  # 代理端口
    sudo ufw allow 5010/tcp  # API端口
    sudo ufw allow 8765/tcp  # WebSocket端口
    sudo ufw allow 8010/tcp  # mitmproxy web界面
    sudo ufw allow 22/tcp    # SSH (确保不被锁定)
    sudo ufw --force enable
    echo "✅ ufw 防火墙规则已配置"
elif command -v firewall-cmd >/dev/null 2>&1; then
    # CentOS/RHEL/Amazon Linux 系统使用 firewalld
    echo "检测到 CentOS/RHEL/Amazon Linux 系统，使用 firewalld..."
    sudo systemctl start firewalld 2>/dev/null || true
    sudo systemctl enable firewalld 2>/dev/null || true
    sudo firewall-cmd --permanent --add-port=8888/tcp  # 代理端口
    sudo firewall-cmd --permanent --add-port=5010/tcp  # API端口
    sudo firewall-cmd --permanent --add-port=8765/tcp  # WebSocket端口
    sudo firewall-cmd --permanent --add-port=8010/tcp  # mitmproxy web界面
    sudo firewall-cmd --permanent --add-service=ssh    # SSH (确保不被锁定)
    sudo firewall-cmd --reload 2>/dev/null || true
    echo "✅ firewalld 防火墙规则已配置"
else
    echo "⚠️  未检测到防火墙管理工具，跳过防火墙配置"
    echo "💡 请手动配置防火墙开放端口: 8888, 5010, 8765, 8010"
fi

# 3. 创建服务目录并下载最新文件
echo "📁 创建服务目录..."
sudo mkdir -p /opt/mobile-proxy
sudo chown $USER:$USER /opt/mobile-proxy
cd /opt/mobile-proxy

echo "� 从GitHub下载最新服务器文件..."
# 下载主服务器脚本
wget -q --show-progress -O mobile_proxy_server.py "$GITHUB_RAW_URL/remote_server/mobile_proxy_server.py"
if [ $? -eq 0 ]; then
    echo "✅ mobile_proxy_server.py 下载成功"
else
    echo "❌ 下载 mobile_proxy_server.py 失败"
    exit 1
fi

# 下载README文件
wget -q -O README.md "$GITHUB_RAW_URL/remote_server/README.md" 2>/dev/null
echo "📄 README.md 下载完成"

# 验证Python脚本语法
echo "🔍 验证脚本语法..."
python3 -m py_compile mobile_proxy_server.py
if [ $? -eq 0 ]; then
    echo "✅ 脚本语法验证通过"
else
    echo "❌ 脚本语法验证失败"
    exit 1
fi

# 5. 创建启动脚本
echo "⚙️  创建启动脚本..."
cat > /opt/mobile-proxy/start.sh << 'EOF'
#!/bin/bash
cd /opt/mobile-proxy
export PATH="$HOME/.local/bin:$PATH"
python3 mobile_proxy_server.py
EOF
chmod +x /opt/mobile-proxy/start.sh

# 6. 创建更新脚本
echo "🔄 创建自动更新脚本..."
cat > /opt/mobile-proxy/update.sh << EOF
#!/bin/bash
echo "🔄 更新 bigjj.site 移动代理服务器..."
cd /opt/mobile-proxy

# 备份当前版本
cp mobile_proxy_server.py mobile_proxy_server.py.backup.\$(date +%Y%m%d_%H%M%S)

# 下载最新版本
echo "📥 下载最新版本..."
wget -q -O mobile_proxy_server.py.new "$GITHUB_RAW_URL/remote_server/mobile_proxy_server.py"

if [ \$? -eq 0 ]; then
    # 验证语法
    python3 -m py_compile mobile_proxy_server.py.new
    if [ \$? -eq 0 ]; then
        mv mobile_proxy_server.py.new mobile_proxy_server.py
        echo "✅ 更新成功，重启服务..."
        sudo systemctl restart mobile-proxy
        echo "🎉 服务已重启"
    else
        echo "❌ 新版本语法错误，保持原版本"
        rm mobile_proxy_server.py.new
    fi
else
    echo "❌ 下载失败"
fi
EOF
chmod +x /opt/mobile-proxy/update.sh

# 7. 创建systemd服务
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

# 8. 启动服务
echo "🔄 启动代理服务..."
sudo systemctl daemon-reload
sudo systemctl enable mobile-proxy
sudo systemctl start mobile-proxy

# 等待服务启动
echo "⏳ 等待服务启动..."
sleep 5

# 10. 显示服务状态
echo "📊 检查服务状态..."
sudo systemctl status mobile-proxy --no-pager -l

# 11. 创建SSL证书 (如果需要HTTPS)
echo "🔒 创建SSL证书..."
if [ ! -f /opt/mobile-proxy/cert.pem ]; then
    openssl req -x509 -newkey rsa:2048 -keyout /opt/mobile-proxy/key.pem -out /opt/mobile-proxy/cert.pem -days 365 -nodes -subj "/C=US/ST=State/L=City/O=Organization/CN=bigjj.site" 2>/dev/null
    if [ $? -eq 0 ]; then
        echo "✅ SSL证书已生成"
    else
        echo "⚠️  SSL证书生成失败，将使用默认证书"
    fi
fi

# 9. 创建管理脚本
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
    update)
        /opt/mobile-proxy/update.sh
        ;;
    test)
        echo "🧪 测试服务连接..."
        echo "测试API端口 5010:"
        nc -zv localhost 5010 2>&1 | head -1
        echo "测试代理端口 8888:"
        nc -zv localhost 8888 2>&1 | head -1
        echo "测试WebSocket端口 8765:"
        nc -zv localhost 8765 2>&1 | head -1
        echo "测试mitmproxy端口 8010:"
        nc -zv localhost 8010 2>&1 | head -1
        ;;
    *)
        echo "bigjj.site 移动代理服务器管理工具"
        echo ""
        echo "使用方法: $0 {start|stop|restart|status|logs|enable|disable|update|test}"
        echo ""
        echo "命令说明:"
        echo "  start    - 启动服务"
        echo "  stop     - 停止服务"  
        echo "  restart  - 重启服务"
        echo "  status   - 查看状态"
        echo "  logs     - 查看实时日志"
        echo "  enable   - 设置开机自启"
        echo "  disable  - 取消开机自启"
        echo "  update   - 更新到最新版本"
        echo "  test     - 测试端口连接"
        exit 1
esac
EOF
chmod +x /opt/mobile-proxy/manage.sh

# 12. 显示最终配置信息
echo ""
echo "🎉 一键部署完成！"
echo "=" * 60
echo "📍 服务器信息:"
echo "   域名: bigjj.site"
echo "   IP地址: $SERVER_IP"
echo "   系统: $OS"
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
echo "   /opt/mobile-proxy/manage.sh start     # 启动服务"
echo "   /opt/mobile-proxy/manage.sh stop      # 停止服务"
echo "   /opt/mobile-proxy/manage.sh restart   # 重启服务"
echo "   /opt/mobile-proxy/manage.sh status    # 查看状态"
echo "   /opt/mobile-proxy/manage.sh logs      # 查看日志"
echo "   /opt/mobile-proxy/manage.sh update    # 更新到最新版本"
echo "   /opt/mobile-proxy/manage.sh test      # 测试端口连接"
echo ""
echo "� 自动更新:"
echo "   运行 /opt/mobile-proxy/update.sh 可自动从GitHub获取最新版本"
echo ""
echo "�🔍 测试链接:"
echo "   curl https://bigjj.site:5010/api/status"
echo "   curl https://bigjj.site:5010"
echo ""
echo "=" * 60
echo "✅ bigjj.site 移动抓包代理服务器一键部署完成！"
echo "🚀 所有文件均从GitHub自动获取最新版本"

# 13. 测试服务
echo ""
echo "🧪 测试服务连接..."
sleep 3

# 测试HTTP API
if timeout 10 curl -s http://localhost:5010/api/status >/dev/null 2>&1; then
    echo "✅ HTTP API 服务正常 (端口 5010)"
else
    echo "⚠️  HTTP API 服务可能未启动 (端口 5010)"
fi

# 测试各个端口
for port in 8888 8765 8010; do
    if nc -z localhost $port 2>/dev/null; then
        case $port in
            8888) echo "✅ 代理服务正常 (端口 8888)" ;;
            8765) echo "✅ WebSocket 服务正常 (端口 8765)" ;;
            8010) echo "✅ mitmproxy Web界面正常 (端口 8010)" ;;
        esac
    else
        case $port in
            8888) echo "⚠️  代理服务可能未启动 (端口 8888)" ;;
            8765) echo "⚠️  WebSocket 服务可能未启动 (端口 8765)" ;;
            8010) echo "⚠️  mitmproxy Web界面可能未启动 (端口 8010)" ;;
        esac
    fi
done

echo ""
echo "🔧 如果某些服务未正常启动，请运行："
echo "   sudo journalctl -u mobile-proxy -f  # 查看详细日志"
echo "   /opt/mobile-proxy/manage.sh restart # 重启服务"
echo "   /opt/mobile-proxy/manage.sh test    # 重新测试"
echo ""
echo "🎯 下一步: 在Android应用中配置代理 bigjj.site:8888"
