# mitmproxy 移动抓包方案

## 概述
mitmproxy是一个免费的交互式HTTPS代理，专门为流量分析设计。

## 安装
```bash
# Windows (使用pip)
pip install mitmproxy

# 或下载二进制包
# https://mitmproxy.org/downloads/
```

## 快速启动

### 1. 启动mitmproxy
```bash
# 启动Web界面版本 (推荐)
mitmweb --listen-port 8080

# 或命令行版本
mitmdump --listen-port 8080
```

### 2. 配置Android模拟器代理
1. WiFi设置 → 长按网络 → 修改
2. 代理：手动
3. 主机名：你的PC IP
4. 端口：8080

### 3. 安装HTTPS证书
1. 模拟器浏览器访问：mitm.it
2. 下载Android证书
3. 安装为CA证书

### 4. 开始抓包
- 访问 http://127.0.0.1:8081 查看Web界面
- 实时查看所有HTTP/HTTPS流量
- 支持流量重放、修改、过滤

## 高级功能

### 脚本化处理
```python
# addon.py - 自动处理流量
from mitmproxy import http

def response(flow: http.HTTPFlow) -> None:
    # 记录所有响应
    print(f"{flow.request.url} -> {flow.response.status_code}")
    
    # 修改响应 (用于测试)
    if "api.example.com" in flow.request.pretty_host:
        flow.response.text = '{"modified": true}'
```

运行：`mitmproxy -s addon.py`

### 导出数据
```bash
# 导出为HAR文件 (可用于其他工具分析)
mitmdump -w capture.mitm
mitmproxy --set confdir=~/.mitmproxy --set export-preserve-original-ip --export-har-format
```

## 优势
✅ 完全免费开源
✅ 专门为HTTPS代理设计
✅ 强大的脚本化能力
✅ 现代化Web界面
✅ 活跃的社区支持

## 特别适合
- API调试
- 移动应用安全测试
- 自动化流量分析
- 开发环境调试
