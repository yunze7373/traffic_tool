## tun2socks Core Library Integration Guide

该项目已经内置 JNI 动态加载逻辑, 会按以下顺序尝试加载核心库 (任意一个存在即可):

1. libtun2socks_core.so (推荐标准名称)
2. libtun2socks_core_v1.so (可选版本名)
3. libtun2socks.so (兼容旧命名)

如果均不存在或缺少必需导出符号 (tt_init / tt_start / tt_stop), 会回退到 "stub-fallback" —— 只产生模拟数据包, 没有真实转发。

### 需要导出的 C 接口 (符号名)
```
int tt_init(int tun_fd, const char* socks_server, const char* dns_server, int mtu);
int tt_start();
void tt_stop();
void tt_set_log_level(int level);            // 可选
const char* tt_version();                    // 可选
void tt_register_callback(void (*cb)(int direction,int proto,const char* srcIp,int srcPort,const char* dstIp,int dstPort,const uint8_t* payload,int length)); // 可选
```
direction: 0 = uplink(客户端->网络), 1 = downlink(网络->客户端)
proto: 6 = TCP, 17 = UDP, 其它可自定义

### 目录放置
将编译好的 so 放到 `app/src/main/jniLibs/<ABI>/` 下, 例如:
```
app/src/main/jniLibs/arm64-v8a/libtun2socks_core.so
app/src/main/jniLibs/armeabi-v7a/libtun2socks_core.so
app/src/main/jniLibs/x86/libtun2socks_core.so
app/src/main/jniLibs/x86_64/libtun2socks_core.so
```

已经在 Gradle 中启用四个 ABI (arm64-v8a, armeabi-v7a, x86, x86_64), 以满足:
1. 真机 ARM 设备
2. VMOS (常为 ARM, 也有转译场景)
3. 雷电 / 其它 PC 模拟器 (多为 x86/x86_64)

### 常见开源 tun2socks 方案
1. gVisor / lwIP 类实现 (C/C++)
2. go-tun2socks (Go) — 需使用 NDK clang 交叉编译生成 .so (buildmode=c-shared)
3. Shadowsocks-Android 项目里集成的 tun2socks 变体 (可参考其构建脚本)

#### 用 Go (go-tun2socks) 编译示例 (概念指引)
假设: ANDROID_NDK_ROOT 指向 NDK, API 级别 23。
```
# arm64-v8a
export GOOS=android
export GOARCH=arm64
export CGO_ENABLED=1
export CC="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-clang"
go build -buildmode=c-shared -o libtun2socks_core.so ./cmd/tun2socks

# x86_64
export GOARCH=amd64
export CC="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android23-clang"
go build -buildmode=c-shared -o libtun2socks_core.so ./cmd/tun2socks
```
然后分别放入对应 ABI 目录。Go 版本可能导出很多符号; 你可以写一个 C 封装层只再导出上述 tt_* 接口。

### 验证是否加载成功
运行 App 后在 logcat 搜索 `Tun2SocksBridge`：
* `Loaded core library candidate:` 表示找到 so。
* `Core library initialized successfully.` 表示可用。
* `Core init failed` 或 `No core library found` 表示仍在使用 stub。
调用 `Tun2SocksBridge.nativeVersion()` 若返回 `stub-fallback` 就是未加载真实核心。

### 回调与数据路径
真实核心在解析 TUN 数据并建立 TCP/UDP 会话后, 对每个方向的报文 (可选截取 payload) 通过 `tt_register_callback` 注册的回调返回到 Java/Kotlin 层, 用于 UI 展示与后续 MITM 逻辑。

### 性能注意事项
* 建议在 native 层做最少量 copy, callback 只传前 N 字节 (例如 4KB) 作为预览, 减少 JNI 压力。
* 大包或长连接数据可考虑分片回调。

### 安全与合规
仅在获得授权的测试 / 调试环境中对 HTTPS 流量进行 MITM 解密。请遵守当地法律法规与应用使用条款。

---
如果你需要我再生成一个最小 C 版本的占位 core (真正转发还未实现, 但导出 tt_*), 可以继续提出需求。
