# Charles HTTPS证书安装详细步骤

## 第一步：Charles SSL代理配置

### 1. 启动Charles Proxy
### 2. 菜单：Proxy → SSL Proxying Settings
### 3. 勾选 "Enable SSL Proxying"
### 4. 点击"Add"按钮添加位置：
```
Host: *          (星号表示所有域名)
Port: 443        (HTTPS默认端口)
```
### 5. 点击"OK"保存

## 第二步：配置Android模拟器代理

### 1. 模拟器设置 → WiFi
### 2. 长按当前连接的WiFi网络
### 3. 选择"修改网络"
### 4. 展开"高级选项"
### 5. 代理设置选择"手动"
### 6. 配置代理：
```
主机名：192.168.31.200  (您的电脑IP)
端口：8888              (Charles默认端口)
```
### 7. 保存设置

## 第三步：下载并安装Charles证书

### 1. 在模拟器中打开浏览器
### 2. 访问：http://chls.pro/ssl
   - 注意：是HTTP不是HTTPS
   - 这是Charles官方的证书下载地址
### 3. 会自动下载证书文件
### 4. 点击下载通知或文件管理器中的证书文件
### 5. Android会提示安装证书：
   - 证书名称：输入"Charles Proxy"
   - 凭据用途：选择"VPN和应用"
   - 点击"确定"

## 第四步：验证证书安装

### 1. 设置 → 安全和隐私 → 更多安全设置 → 加密与凭据
### 2. 点击"受信任的凭据"
### 3. 切换到"用户"标签页
### 4. 应该能看到"Charles Proxy"证书

## 第五步：测试HTTPS抓包

### 1. 在模拟器中访问HTTPS网站
   - 例如：https://www.baidu.com
### 2. 回到Charles，查看流量列表
### 3. 成功的HTTPS抓包标志：
   - ✅ 显示锁🔒图标
   - ✅ 可以在Contents标签页看到明文内容
   - ✅ Request和Response都可读

## 故障排除

### 问题1：证书下载失败
**解决方案：**
- 确保模拟器代理配置正确
- 确保Charles正在运行
- 尝试重启Charles和模拟器

### 问题2：证书安装后仍显示"Unknown"
**解决方案：**
- 重启模拟器
- 检查Charles中SSL Proxying设置
- 确保添加了*:443的规则

### 问题3：部分应用HTTPS抓不到
**原因：** 应用使用了SSL Pinning
**解决方案：**
- 这是正常的安全机制
- 可以使用Frida等工具绕过
- 或在应用开发时禁用SSL验证

## 成功标志
当您看到以下情况时，说明HTTPS抓包已成功配置：
1. Charles中HTTPS请求显示🔒图标
2. 可以看到JSON/XML等明文响应内容
3. Headers和Body都清晰可读
4. 没有"SSL Handshake failed"错误

现在您可以抓取和分析任何Android应用的HTTPS流量了！
