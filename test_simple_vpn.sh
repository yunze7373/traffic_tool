#!/bin/bash

# Simple VPN Test Script
# 基于成功开源项目的测试方法

echo "=== Simple VPN Functionality Test ==="
echo "请在手机上："
echo "1. 打开应用"
echo "2. 点击"简化代理"按钮"
echo "3. 授予VPN权限"
echo "4. 开启抓包开关"
echo ""

echo "开始测试网络连通性..."
sleep 2

echo "=== Test 1: Basic Connectivity ==="
adb -s a917adc8 shell "ping -c 3 8.8.8.8"

echo ""
echo "=== Test 2: DNS Resolution ==="
adb -s a917adc8 shell "nslookup google.com 8.8.8.8"

echo ""
echo "=== Test 3: HTTP Request ==="
adb -s a917adc8 shell "curl -I --connect-timeout 10 http://httpbin.org/get"

echo ""
echo "=== Test 4: Check VPN Interface ==="
adb -s a917adc8 shell "ip addr show tun0"

echo ""
echo "=== Test 5: Check Routes ==="
adb -s a917adc8 shell "ip route | grep tun"

echo ""
echo "=== Log Output from Simple VPN ==="
adb -s a917adc8 logcat -t 50 | grep -E "(SimpleProxy|SimpleVpnService|SimpleTun2socks)"

echo ""
echo "=== 测试完成 ==="
echo "如果看到日志输出显示Simple proxy started successfully"
echo "但网络连接失败，说明需要进一步优化数据转发机制"
