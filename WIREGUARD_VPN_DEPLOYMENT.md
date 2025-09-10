# WireGuard VPN 流量监控系统部署指南

## 🎯 系统概述

本系统采用WireGuard VPN技术实现安全的移动设备流量监控，完全解决了HTTP代理被滥用的安全风险。

### 🏗️ 架构设计

```
┌─────────────────┐    WireGuard VPN    ┌─────────────────┐
│   Android客户端  │◄──────加密隧道─────►│   服务器端       │
│ ├─VpnService    │                    │ ├─WireGuard     │
│ ├─流量解析      │                    │ ├─流量分析      │
│ ├─数据上传      │   HTTPS API        │ ├─WebSocket     │
│ └─实时显示      │◄──────────────────►│ └─Web界面       │
└─────────────────┘                    └─────────────────┘
```

### 🔒 安全特性

- ✅ **加密隧道**: 所有流量通过WireGuard加密传输
- ✅ **密钥认证**: 只有持有正确密钥的设备才能连接
- ✅ **无开放代理**: 不存在被滥用的HTTP代理端口
- ✅ **客户端控制**: 完全由客户端控制流量捕获范围

## 🚀 服务器端部署

### 1. 环境要求

- Ubuntu 20.04+ 或 Debian 11+
- Root权限
- 公网IP地址
- 域名解析到服务器（建议）

### 2. 一键部署

```bash
# 上传部署脚本到服务器
scp setup_wireguard.sh vpn_traffic_server.py deploy_vpn_system.sh root@your-server:/root/

# SSH登录服务器
ssh root@your-server

# 运行部署脚本
chmod +x deploy_vpn_system.sh
./deploy_vpn_system.sh
```

### 3. 手动部署步骤

#### 步骤1: 安装WireGuard
```bash
apt update
apt install -y wireguard wireguard-tools
```

#### 步骤2: 配置WireGuard
```bash
./setup_wireguard.sh
```

#### 步骤3: 部署流量监控服务
```bash
cp vpn_traffic_server.py /opt/vpn-traffic-monitor/
chmod +x /opt/vpn-traffic-monitor/vpn_traffic_server.py
```

#### 步骤4: 创建systemd服务
```bash
# 见deploy_vpn_system.sh中的服务配置
systemctl enable vpn-traffic-monitor
systemctl start vpn-traffic-monitor
```

### 4. 验证部署

```bash
# 检查WireGuard状态
wg show

# 检查服务状态
systemctl status vpn-traffic-monitor

# 检查端口监听
netstat -tlnp | grep -E "(51820|5010|8765)"

# 查看日志
journalctl -u vpn-traffic-monitor -f
```

## 📱 Android客户端配置

### 1. 获取配置文件

从服务器复制配置文件内容：
```bash
cat /etc/wireguard/client_android.conf
```

### 2. 应用内配置

1. 打开Android应用
2. 选择"WireGuard VPN模式"
3. 点击"配置WireGuard"
4. 粘贴配置文件内容或手动输入配置信息
5. 保存配置

### 3. 启动VPN监控

1. 点击"启动流量监控"
2. 授予VPN权限
3. 确认VPN连接
4. 开始监控流量

## 🔧 系统管理

### 服务管理

```bash
# 启动服务
systemctl start vpn-traffic-monitor

# 停止服务
systemctl stop vpn-traffic-monitor

# 重启服务
systemctl restart vpn-traffic-monitor

# 查看状态
systemctl status vpn-traffic-monitor

# 查看日志
journalctl -u vpn-traffic-monitor -f
```

### WireGuard管理

```bash
# 启动WireGuard
systemctl start wg-quick@wg0

# 停止WireGuard
systemctl stop wg-quick@wg0

# 查看连接状态
wg show

# 重新加载配置
wg-quick down wg0 && wg-quick up wg0
```

### 防火墙配置

```bash
# 开放必要端口
ufw allow 51820/udp comment "WireGuard VPN"
ufw allow 5010/tcp comment "VPN API"
ufw allow 8765/tcp comment "WebSocket"

# 查看防火墙状态
ufw status
```

## 🌐 访问地址

- **VPN连接**: `your-server:51820`
- **Web管理界面**: `https://your-server:5010`
- **WebSocket实时**: `wss://your-server:8765`
- **API接口**: `https://your-server:5010/api/`

## 🔍 故障排除

### 1. WireGuard连接失败

```bash
# 检查防火墙
ufw status

# 检查WireGuard配置
wg show

# 检查系统日志
journalctl -u wg-quick@wg0 -f
```

### 2. 流量监控服务异常

```bash
# 查看服务日志
journalctl -u vpn-traffic-monitor -f

# 检查Python依赖
pip3 list | grep -E "(websockets|asyncio)"

# 手动运行服务
cd /opt/vpn-traffic-monitor
python3 vpn_traffic_server.py
```

### 3. Android连接问题

1. 检查配置文件格式
2. 验证服务器地址和端口
3. 确认VPN权限已授予
4. 查看应用日志

### 4. SSL证书问题

```bash
# 检查证书文件
ls -la /etc/letsencrypt/live/your-domain/

# 测试HTTPS访问
curl -k https://your-server:5010/api/status
```

## 📊 监控数据

### 1. 实时流量监控

- WebSocket实时推送流量事件
- 包含源IP、目标IP、端口、协议等信息
- 支持按应用、域名、协议过滤

### 2. 数据库存储

- SQLite数据库存储历史流量
- 支持查询和导出
- 包含详细的连接信息和统计数据

### 3. API接口

- `/api/status` - 系统状态
- `/api/traffic` - 流量数据
- `/api/clients` - 连接的客户端列表

## 🔐 安全考虑

### 1. 密钥管理

- 定期更新WireGuard密钥
- 安全存储私钥文件
- 限制配置文件访问权限

### 2. 网络安全

- 使用防火墙限制端口访问
- 启用HTTPS和WSS加密
- 定期更新系统和软件

### 3. 数据隐私

- 本地数据库存储，不外传
- 可配置数据保留期限
- 支持数据清理和导出

## 📈 性能优化

### 1. 服务器端

- 调整WireGuard MTU大小
- 优化Python服务内存使用
- 配置适当的日志级别

### 2. 客户端

- 优化流量解析算法
- 控制数据上传频率
- 合理设置监控范围

## 🆕 版本升级

### 1. 服务器端升级

```bash
# 备份配置
cp -r /etc/wireguard /etc/wireguard.backup
cp -r /opt/vpn-traffic-monitor /opt/vpn-traffic-monitor.backup

# 更新代码
# 重启服务
systemctl restart vpn-traffic-monitor
```

### 2. 客户端升级

1. 备份现有配置
2. 安装新版本APK
3. 恢复配置信息
4. 测试连接和功能

---

## 💡 技术支持

如遇到问题，请按以下顺序检查：

1. 查看系统日志
2. 验证网络连接
3. 检查配置文件
4. 测试基础功能
5. 提供详细错误信息

系统部署完成后，你将拥有一个安全、可靠、功能完整的VPN流量监控解决方案！
