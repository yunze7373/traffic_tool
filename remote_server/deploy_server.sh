#!/bin/bash

# bigjj.site 移动抓包远程代琁E��务器一键部署脚本
# 自动从GitHub下载最新版本并部署

echo "🚀 开始一键部署 bigjj.site 移动抓包远程代琁E��务器..."
echo "🌐 自动从GitHub获取最新版本..."
echo "=" * 60

# GitHub仓库信息
GITHUB_REPO="yunze7373/traffic_tool"
GITHUB_BRANCH="master"
GITHUB_RAW_URL="https://raw.githubusercontent.com/$GITHUB_REPO/$GITHUB_BRANCH"

# 获取服务器IP
SERVER_IP=$(curl -s ifconfig.me 2>/dev/null || curl -s ipinfo.io/ip 2>/dev/null || echo "未知")

# 检测操作系绁Eif [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=$NAME
    OS_VERSION=$VERSION_ID
else
    OS=$(uname -s)
fi

echo "📊 系统信息:"
echo "   操作系绁E $OS"
echo "   IP地址: $SERVER_IP"
echo ""

# 1. 更新系统并安裁E��础软件
echo "📦 更新系统软件匁E.."
if command -v yum >/dev/null 2>&1; then
    # Amazon Linux / CentOS / RHEL
    sudo yum update -y
    sudo yum install -y python3 python3-pip curl wget unzip firewalld nc
elif command -v apt >/dev/null 2>&1; then
    # Ubuntu / Debian
    sudo apt update && sudo apt upgrade -y
    sudo apt install -y python3 python3-pip python3-venv curl wget unzip ufw netcat-openbsd
else
    echo "❁E不支持的操作系绁E
    exit 1
fi

# 2. 安裁Eython依赁Eecho "�E� 安裁Eython依赁E.."
pip3 install --user mitmproxy websockets flask

# 确保pip安裁E��程序在PATH中
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
export PATH="$HOME/.local/bin:$PATH"

# 4. 配置防火墁Eecho "🔥 配置防火墙见E�E..."

# 检测操作系统类型并配置防火墁Eif command -v ufw >/dev/null 2>&1; then
    # Ubuntu/Debian 系统使用 ufw
    echo "检测到 Ubuntu/Debian 系统，使用 ufw..."
    sudo ufw allow 8080/tcp  # 代琁E��口
    sudo ufw allow 5010/tcp  # API端口
    sudo ufw allow 8765/tcp  # WebSocket端口
    sudo ufw allow 8010/tcp  # mitmproxy web界面
    sudo ufw allow 22/tcp    # SSH (确保不被锁宁E
    sudo ufw --force enable
    echo "✁Eufw 防火墙见E�E已配置"
elif command -v firewall-cmd >/dev/null 2>&1; then
    # CentOS/RHEL/Amazon Linux 系统使用 firewalld
    echo "检测到 CentOS/RHEL/Amazon Linux 系统，使用 firewalld..."
    sudo systemctl start firewalld 2>/dev/null || true
    sudo systemctl enable firewalld 2>/dev/null || true
    sudo firewall-cmd --permanent --add-port=8080/tcp  # 代琁E��口
    sudo firewall-cmd --permanent --add-port=5010/tcp  # API端口
    sudo firewall-cmd --permanent --add-port=8765/tcp  # WebSocket端口
    sudo firewall-cmd --permanent --add-port=8010/tcp  # mitmproxy web界面
    sudo firewall-cmd --permanent --add-service=ssh    # SSH (确保不被锁宁E
    sudo firewall-cmd --reload 2>/dev/null || true
    echo "✁Efirewalld 防火墙见E�E已配置"
else
    echo "⚠�E�E 未检测到防火墙管琁E��具�E�跳迁E��火墙�E置"
    echo "💡 请手动配置防火墙开放端口: 8080, 5010, 8765, 8010"
fi

# 3. 创建服务目录并下载最新斁E��
echo "📁 创建服务目彁E.."
sudo mkdir -p /opt/mobile-proxy
sudo chown $USER:$USER /opt/mobile-proxy
cd /opt/mobile-proxy

echo "�E� 从GitHub下载最新服务器斁E��..."
# 下载主服务器脚本
wget -q --show-progress -O mobile_proxy_server.py "$GITHUB_RAW_URL/remote_server/mobile_proxy_server.py"
if [ $? -eq 0 ]; then
    echo "✁Emobile_proxy_server.py 下载成功"
else
    echo "❁E下载 mobile_proxy_server.py 失败"
    exit 1
fi

# 下载README斁E��
wget -q -O README.md "$GITHUB_RAW_URL/remote_server/README.md" 2>/dev/null
echo "📄 README.md 下载完�E"

# 验证Python脚本语況Eecho "🔍 验证�E本语況E.."
python3 -m py_compile mobile_proxy_server.py
if [ $? -eq 0 ]; then
    echo "✁E脚本语法验证E��迁E
else
    echo "❁E脚本语法验证失败"
    exit 1
fi

# 5. 创建启动脚本
echo "⚙︁E 创建启动脚本..."
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
echo "🔄 更新 bigjj.site 移动代琁E��务器..."
cd /opt/mobile-proxy

# 夁E��当前版本
cp mobile_proxy_server.py mobile_proxy_server.py.backup.\$(date +%Y%m%d_%H%M%S)

# 下载最新版本
echo "📥 下载最新版本..."
wget -q -O mobile_proxy_server.py.new "$GITHUB_RAW_URL/remote_server/mobile_proxy_server.py"

if [ \$? -eq 0 ]; then
    # 验证语況E    python3 -m py_compile mobile_proxy_server.py.new
    if [ \$? -eq 0 ]; then
        mv mobile_proxy_server.py.new mobile_proxy_server.py
        echo "✁E更新成功�E�重启服务..."
        sudo systemctl restart mobile-proxy
        sudo systemctl restart mitmweb
        echo "🎉 服务已重启"
    else
        echo "❁E新版本语法错误�E�保持原版本"
        rm mobile_proxy_server.py.new
    fi
else
    echo "❁E下载失败"
fi
EOF
chmod +x /opt/mobile-proxy/update.sh

# 7. 创建mitmproxy Web界面启动脚本
echo "🌐 创建mitmproxy Web界面脚本..."
cat > /opt/mobile-proxy/start-mitmweb.sh << 'EOF'
#!/bin/bash
cd /opt/mobile-proxy
export PATH="$HOME/.local/bin:$PATH"
mitmweb --web-host 0.0.0.0 --web-port 8010 --set confdir=~/.mitmproxy
EOF
chmod +x /opt/mobile-proxy/start-mitmweb.sh

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

# 8. 创建mitmproxy Web界面服务
echo "🌐 创建mitmproxy Web界面服务..."
sudo tee /etc/systemd/system/mitmweb.service > /dev/null <<EOF
[Unit]
Description=mitmproxy Web Interface for bigjj.site
After=network.target mobile-proxy.service
Wants=network.target

[Service]
Type=simple
User=$USER
Group=$USER
WorkingDirectory=/opt/mobile-proxy
Environment=PATH=/home/$USER/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
ExecStart=/opt/mobile-proxy/start-mitmweb.sh
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# 8. 启动服务
echo "🔄 启动代琁E��务..."
sudo systemctl daemon-reload
sudo systemctl enable mobile-proxy
sudo systemctl enable mitmweb
sudo systemctl start mobile-proxy
sudo systemctl start mitmweb

# 等征E��务启动
echo "⏳ 等征E��务启动..."
sleep 5

# 10. 显示服务状态Eecho "📊 检查服务状态E.."
sudo systemctl status mobile-proxy --no-pager -l

# 11. 创建SSL证书 (如果需要HTTPS)
echo "🔒 创建SSL证书..."
if [ ! -f /opt/mobile-proxy/cert.pem ]; then
    openssl req -x509 -newkey rsa:2048 -keyout /opt/mobile-proxy/key.pem -out /opt/mobile-proxy/cert.pem -days 365 -nodes -subj "/C=US/ST=State/L=City/O=Organization/CN=bigjj.site" 2>/dev/null
    if [ $? -eq 0 ]; then
        echo "✁ESSL证书已生�E"
    else
        echo "⚠�E�E SSL证书生�E失败�E�封E��用默认证书"
    fi
fi

# 9. 创建管琁E�E本
echo "🛠�E�E 创建管琁E�E本..."
cat > /opt/mobile-proxy/manage.sh << 'EOF'
#!/bin/bash

case "$1" in
    start)
        sudo systemctl start mobile-proxy
        echo "✁E服务已启动"
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
        echo "✁E服务已设置为开机自启"
        ;;
    disable)
        sudo systemctl disable mobile-proxy
        echo "❁E服务已取消开机自启"
        ;;
    update)
        /opt/mobile-proxy/update.sh
        ;;
    test)
        echo "🧪 测试服务连接..."
        echo "测试API端口 5010:"
        nc -zv localhost 5010 2>&1 | head -1
        echo "测试代琁E��口 8080:"
        nc -zv localhost 8080 2>&1 | head -1
        echo "测试WebSocket端口 8765:"
        nc -zv localhost 8765 2>&1 | head -1
        echo "测试mitmproxy端口 8010:"
        nc -zv localhost 8010 2>&1 | head -1
        ;;
    *)
        echo "bigjj.site 移动代琁E��务器管琁E��具"
        echo ""
        echo "使用方況E $0 {start|stop|restart|status|logs|enable|disable|update|test}"
        echo ""
        echo "命令说昁E"
        echo "  start    - 启动服务"
        echo "  stop     - 停止服务"  
        echo "  restart  - 重启服务"
        echo "  status   - 查看状态E
        echo "  logs     - 查看实时日忁E
        echo "  enable   - 设置开机自启"
        echo "  disable  - 取消开机自启"
        echo "  update   - 更新到最新版本"
        echo "  test     - 测试端口连接"
        exit 1
esac
EOF
chmod +x /opt/mobile-proxy/manage.sh

# 12. 显示最终�E置信息
echo ""
echo "🎉 一键部署完�E�E�E
echo "=" * 60
echo "📍 服务器信息:"
echo "   域名: bigjj.site"
echo "   IP地址: $SERVER_IP"
echo "   系绁E $OS"
echo ""
echo "🌐 服务端口:"
echo "   代琁E��务器: bigjj.site:8080"
echo "   WebSocket: wss://bigjj.site:8765"
echo "   API接口: https://bigjj.site:5010"
echo "   Web管琁E http://bigjj.site:8010"
echo "   状态E��面: https://bigjj.site:5010"
echo ""
echo "📱 Android应用配置:"
echo "   1. 选择'远程代琁E模弁E
echo "   2. WiFi设置 ↁE修改网绁EↁE高级选项"
echo "   3. 代琁E 手动"
echo "   4. 主机吁E bigjj.site"
echo "   5. 端口: 8080"
echo ""
echo "🔒 HTTPS证书下载:"
echo "   http://bigjj.site:8080/cert.pem"
echo ""
echo "🛠�E�E 管琁E��令:"
echo "   /opt/mobile-proxy/manage.sh start     # 启动服务"
echo "   /opt/mobile-proxy/manage.sh stop      # 停止服务"
echo "   /opt/mobile-proxy/manage.sh restart   # 重启服务"
echo "   /opt/mobile-proxy/manage.sh status    # 查看状态E
echo "   /opt/mobile-proxy/manage.sh logs      # 查看日忁E
echo "   /opt/mobile-proxy/manage.sh update    # 更新到最新版本"
echo "   /opt/mobile-proxy/manage.sh test      # 测试端口连接"
echo ""
echo "�E� 自动更新:"
echo "   运衁E/opt/mobile-proxy/update.sh 可自动从GitHub获取最新版本"
echo ""
echo "�E�🔍 测试链接:"
echo "   curl https://bigjj.site:5010/api/status"
echo "   curl https://bigjj.site:5010"
echo ""
echo "=" * 60
echo "✁Ebigjj.site 移动抓包代琁E��务器一键部署完�E�E�E
echo "🚀 所有文件坁E��GitHub自动获取最新版本"

# 13. 测试服务
echo ""
echo "🧪 测试服务连接..."
sleep 3

# 测试HTTP API
if timeout 10 curl -s http://localhost:5010/api/status >/dev/null 2>&1; then
    echo "✁EHTTP API 服务正常 (端口 5010)"
else
    echo "⚠�E�E HTTP API 服务可能未启动 (端口 5010)"
fi

# 测试各个端口
for port in 8080 8765 8010; do
    if nc -z localhost $port 2>/dev/null; then
        case $port in
            8080) echo "✁E代琁E��务正常 (端口 8080)" ;;
            8765) echo "✁EWebSocket 服务正常 (端口 8765)" ;;
            8010) echo "✁Emitmproxy Web界面正常 (端口 8010)" ;;
        esac
    else
        case $port in
            8080) echo "⚠�E�E 代琁E��务可能未启动 (端口 8080)" ;;
            8765) echo "⚠�E�E WebSocket 服务可能未启动 (端口 8765)" ;;
            8010) echo "⚠�E�E mitmproxy Web界面可能未启动 (端口 8010)" ;;
        esac
    fi
done

echo ""
echo "🔧 如果某些服务未正常启动�E�请运行！E
echo "   sudo journalctl -u mobile-proxy -f  # 查看详绁E��忁E
echo "   /opt/mobile-proxy/manage.sh restart # 重启服务"
echo "   /opt/mobile-proxy/manage.sh test    # 重新测证E
echo ""
echo "🎯 下一步: 在Android应用中配置代琁Ebigjj.site:8080"
