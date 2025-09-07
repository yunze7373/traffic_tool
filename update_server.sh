#!/bin/bash

echo "🔄 正在更新bigjj.site代理服务器..."

# 上传新的代码文件
scp -i ~/.ssh/id_rsa remote_server/mobile_proxy_server.py han@bigjj.site:/opt/mobile-proxy/

echo "✅ 代码文件已上传"

# 重启服务
ssh -i ~/.ssh/id_rsa han@bigjj.site "sudo systemctl restart mobile-proxy && sudo systemctl restart mitmweb"

echo "🚀 服务已重启"

# 检查服务状态
ssh -i ~/.ssh/id_rsa han@bigjj.site "sudo systemctl status mobile-proxy --no-pager -l"

echo "🎉 更新完成！"
