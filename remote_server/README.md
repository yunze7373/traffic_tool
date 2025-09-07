# bigjj.site 移动抓包远程代理服务器

这个文件夹包含了在您的远程服务器 `bigjj.site` 上部署移动抓包代理服务器的所有文件。

## 📁 文件列表

- `mobile_proxy_server.py` - 主服务器脚本
- `deploy_server.sh` - 自动部署脚本
- `README.md` - 本说明文件

## 🚀 快速部署

### 1. 上传文件到服务器
```bash
# 将整个 remote_server 文件夹上传到您的服务器
scp -r remote_server/ user@bigjj.site:~/
```

### 2. 运行部署脚本
```bash
# 连接到服务器
ssh user@bigjj.site

# 进入文件夹
cd ~/remote_server

# 给脚本执行权限
chmod +x deploy_server.sh

# 运行部署脚本
./deploy_server.sh
```

### 3. 验证部署
部署完成后，访问以下链接验证服务：
- 状态页面: https://bigjj.site:5010
- API接口: https://bigjj.site:5010/api/status
- Web管理: http://bigjj.site:8010

## 📱 Android应用配置

1. 在Android应用中选择**"远程代理"**模式
2. 配置WiFi代理：
   - 主机名: `bigjj.site`
   - 端口: `8888`
3. 开始抓包！

## 🔧 服务管理

### 启动/停止服务
```bash
# 启动服务
sudo systemctl start mobile-proxy

# 停止服务
sudo systemctl stop mobile-proxy

# 重启服务
sudo systemctl restart mobile-proxy

# 查看状态
sudo systemctl status mobile-proxy
```

### 查看日志
```bash
# 实时日志
sudo journalctl -u mobile-proxy -f

# 最近日志
sudo journalctl -u mobile-proxy --since "1 hour ago"
```

### 使用管理脚本
```bash
cd /opt/mobile-proxy

# 启动服务
./manage.sh start

# 停止服务  
./manage.sh stop

# 重启服务
./manage.sh restart

# 查看状态
./manage.sh status

# 查看实时日志
./manage.sh logs
```

## 🌐 服务端口

| 服务 | 端口 | 用途 |
|------|------|------|
| 代理服务器 | 8888 | HTTP/HTTPS代理，Android设备连接此端口 |
| WebSocket | 8765 | 实时数据推送到Android应用 |
| API接口 | 5010 | RESTful API，获取历史数据和状态 |
| Web管理 | 8010 | mitmproxy web界面，浏览器管理 |

## 🔒 HTTPS支持

### 下载并安装证书
在Android设备浏览器中访问：
```
http://bigjj.site:8888/cert.pem
```
下载证书并安装为"VPN和应用"证书。

### 手动生成证书
如果需要自定义证书：
```bash
cd /opt/mobile-proxy
openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 365 -nodes
```

## 📊 数据存储

- 流量数据存储在: `/opt/mobile-proxy/mobile_traffic.db`
- 数据库类型: SQLite
- 自动创建表结构
- 支持多设备数据分离

### 数据库操作
```bash
cd /opt/mobile-proxy

# 查看数据库
sqlite3 mobile_traffic.db "SELECT COUNT(*) FROM traffic_logs;"

# 导出数据
sqlite3 mobile_traffic.db ".dump" > backup.sql

# 清理旧数据 (30天前)
sqlite3 mobile_traffic.db "DELETE FROM traffic_logs WHERE created_at < datetime('now', '-30 days');"
```

## 🔧 故障排除

### 常见问题

1. **服务无法启动**
   ```bash
   # 检查Python依赖
   pip3 list | grep mitmproxy
   
   # 检查端口占用
   netstat -tlnp | grep -E '(8888|5010|8765|8010)'
   
   # 手动启动测试
   cd /opt/mobile-proxy
   python3 mobile_proxy_server.py
   ```

2. **Android无法连接**
   ```bash
   # 检查防火墙
   sudo ufw status
   
   # 测试端口连通性
   telnet bigjj.site 8888
   ```

3. **证书问题**
   ```bash
   # 重新生成证书
   cd /opt/mobile-proxy
   rm -f cert.pem key.pem
   openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 365 -nodes -subj "/CN=bigjj.site"
   ```

### 性能优化

1. **增加文件描述符限制**
   ```bash
   # 临时设置
   ulimit -n 65536
   
   # 永久设置
   echo "* soft nofile 65536" | sudo tee -a /etc/security/limits.conf
   echo "* hard nofile 65536" | sudo tee -a /etc/security/limits.conf
   ```

2. **优化数据库**
   ```bash
   # 定期清理旧数据
   crontab -e
   # 添加: 0 2 * * * cd /opt/mobile-proxy && sqlite3 mobile_traffic.db "DELETE FROM traffic_logs WHERE created_at < datetime('now', '-7 days');"
   ```

## 📞 技术支持

如果遇到问题：
1. 查看服务日志: `sudo journalctl -u mobile-proxy -f`
2. 检查服务状态: `sudo systemctl status mobile-proxy`
3. 测试网络连接: `curl https://bigjj.site:5010/api/status`
4. 检查防火墙设置: `sudo ufw status`

## 🔄 更新服务

如果需要更新服务器脚本：
```bash
# 停止服务
sudo systemctl stop mobile-proxy

# 备份旧文件
cp /opt/mobile-proxy/mobile_proxy_server.py /opt/mobile-proxy/mobile_proxy_server.py.backup

# 复制新文件
cp ~/remote_server/mobile_proxy_server.py /opt/mobile-proxy/

# 重启服务
sudo systemctl start mobile-proxy
```

---

**🎉 享受您的专属移动抓包服务器！**
