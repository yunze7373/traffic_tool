# Remaining Work & Implementation Steps

## A. tun2socks 引擎 (Real Forwarding)
Pending:
1. 提供真实 TCP/IP 用户态栈 (建议基于现有 tun2socks/lwIP/go-tun2socks)。
2. 在 `tt_init` 中：
   - 保存 TUN fd、启动事件循环线程。
   - 创建 epoll/kqueue(只需 Linux epoll) 监控 TUN fd + 所有 socket。
3. 解析 TUN 层：
   - 仅处理 IPv4 (首期)，识别 TCP/UDP。
   - TCP：维护连接表 (五元组->状态)，执行三次握手模拟 (seq/ack) 或委托现有库。
   - UDP：无状态/超时回收。
4. 写回 TUN：收到远端数据/完成握手后封装 IP+TCP/UDP 头返回。
5. 回调 `tt_register_callback`：
   - 方向 0 (uplink): 客户端->网络 (从 TUN 读出时)。
   - 方向 1 (downlink): 网络->客户端 (准备写回 TUN 前)。
   - Payload 限制截取前 N (配置，如 4KB)。
6. 日志级别：`tt_set_log_level` 控制 DEBUG/INFO/WARN (0/1/2)。
7. `tt_version` 返回形如 `real-core-<gitsha>`。

线程模型建议：
* 主事件循环线程：epoll wait。
* TCP 重传/超时管理定时器线程或轮询。
* 可选工作线程池处理大 payload 拷贝/解析。

## B. 抓包钩子真实化
1. 真核心在回调时将协议号 (6/17) + 五元组 + payload 切片。
2. Kotlin 侧已有解析器，确认 `direction` 与现有 UI 语义一致。
3. 添加丢包统计与回调节流：大量小包合并 (e.g., Nagle-like 聚合) 可选。

## C. HTTPS 明文拓展
1. 内联 MITM 路径：
   - 方式 1 (当前 SOCKS 思路)：核心把所有 TCP -> local 127.0.0.1:8889；由 Kotlin MITM 建立到远端。
   - 方式 2 (native hook)：核心直接解析 TLS ClientHello，生成 SNI，调用 JNI 请求证书，再在 native 做双向转发。
   - 先实现方式 1，快。
2. 多事务保持：HTTP/1.1 keep-alive 解析循环；在 `MitmProxyManager` 中对同一 socket 循环 read -> parse -> dispatch event。
3. HTTP/2：
   - 检测 ALPN = h2；短期标记“(HTTP/2 未解码)”只记录头帧伪装 (可用简单 Magic: PRI * HTTP/2)。
   - 长期：集成轻量 HTTP/2 帧解析器（只抽取 :method/:path/:authority 和 DATA 前 N bytes）。
4. QUIC/HTTP3：
   - UDP 首字节+Version 判定；标记“QUIC 不解密”；可提示用户关闭 QUIC (chrome flags) / 等待降级。
5. SNI/ALPN：
   - 现 MITM 可在握手时解析 ClientHello (若未做，增加 Handshake parser)。
6. Pinning 侦测：
   - 分类：证书校验失败 VS 主动关闭连接。
   - 捕获 IOException / SSLPeerUnverifiedException；生成事件 `type=PINNING_SUSPECT`。
7. 大 Body：
   - 设置全局截取上限 (配置项 e.g., 128KB)；超过只保留前缀 + 标记 truncated。
8. 压缩：HTTP/2 + gzip 组合时仅在 HTTP/1.1 透传场景解压；其它保留原样。

## D. 性能 & 稳定性
1. JNI 回调批量：可选队列 + 定时 flush，减少频繁跨界。
2. 连接回收策略：TCP FIN/ RST -> 删除；UDP inactivity > 60s 回收。
3. 内存池：payload 缓冲循环复用 (避免频繁 new)。
4. Metrics：总连接数、活跃连接、回调速率、丢弃包计数。

## E. 配置与可视化
1. Settings 界面：截取长度、是否启用 HTTP/2 解码、是否启用 QUIC 标记、最大事件保留。
2. 导出：明文事件 -> JSON / HAR；PCAP 已有。

## F. 最小里程碑拆解 (建议顺序)
M1: 引擎真实转发 (仅 TCP + SOCKS redirect) + uplink/downlink 回调。
M2: MITM 连续多请求/响应 + SNI/ALPN 捕获。
M3: Pinning 事件、截断策略、HTTP/2 标记。
M4: QUIC 识别 + 基础统计面板。
M5: 可选 HTTP/2 帧解析 / HAR 导出。

## G. 验证清单
[] 打开网页正常加载（核心转发）
[] 回调 direction 覆盖双向
[] 首个 HTTPS 域名显示 SNI
[] 同一连接多次请求均出现事件
[] Pinning App 触发异常事件
[] QUIC 流量被识别
[] 大 Body 被截断并标记

---
替换核心后若需要辅助调试日志筛选，可临时在 `tt_init/tt_start` 增加详细连接创建打印，再编译 release 关闭。
