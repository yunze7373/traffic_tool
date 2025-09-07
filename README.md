# Traffic Capture Tool for Android

一个用于Android模拟器的网络流量抓包工具，基于VPN服务实现网络数据包的拦截和分析。

## 功能特性

- 🔍 **网络流量监控**: 实时捕获和显示网络请求
- 📱 **Android模拟器支持**: 专为Android模拟器环境优化
- 🛡️ **VPN技术**: 使用VPN服务进行数据包拦截
- 🔐 **HTTPS解密**: 支持HTTPS流量解密和内容查看
- 🎮 **游戏流量分析**: 专门针对游戏APP的深度数据包分析
- 📊 **流量分析**: 显示协议类型、源地址、目标地址等信息
- 🎯 **简洁界面**: 直观的用户界面，便于操作和监控
- 📋 **证书管理**: 自动生成和管理CA证书

## 环境要求

### 开发环境
- **Java**: 21.0.8+ (推荐Eclipse Adoptium)
- **Android Studio**: 2023.1+ 或任何支持Android开发的IDE
- **Android SDK**: API 34 (Android 14)
- **Gradle**: 8.9 (自动通过wrapper下载)

### 运行环境
- **Android**: 6.0+ (API 23+)
- **架构**: ARM64, x86_64 (支持模拟器)

## 快速开始

### 1. 克隆项目
```bash
git clone https://github.com/your-username/traffic_tool.git
cd traffic_tool
```

### 2. 环境配置
确保已安装Java 21和Android SDK，然后创建`local.properties`文件：
```properties
sdk.dir=C:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

### 3. 构建项目

#### 方法1: 使用构建脚本 (推荐)
```bash
# Windows
build_with_java21.bat
```

#### 方法2: 手动构建
```bash
# 设置Java 21环境变量 (Windows)
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot

# 构建APK
gradlew.bat assembleDebug
```

#### 方法3: PowerShell
```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
.\gradlew.bat assembleDebug
```

### 4. 安装APK
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

### 基本操作
1. **启动应用**: 安装后打开Traffic Capture Tool
2. **启用VPN**: 点击"Enable Capture"开关，授予VPN权限
3. **开始抓包**: 点击"START CAPTURE"按钮
4. **查看流量**: 在下方列表中查看实时网络流量

### HTTPS解密设置
1. **启用HTTPS解密**: 打开"HTTPS Decrypt"开关
2. **导出CA证书**: 点击"EXPORT CA CERT"按钮
3. **安装证书**: 
   - 进入 设置 > 安全 > 加密和凭据
   - 选择"从存储设备安装"
   - 选择导出的证书文件
4. **配置代理**: 在需要监控的应用中设置代理
   - 代理地址: `127.0.0.1`
   - 端口: `8080`

## 技术实现

本项目基于以下技术实现：

- **VPN Service**: 使用Android VPN API创建虚拟专用网络
- **Packet Capture**: 在VPN层面拦截和分析网络数据包
- **Protocol Analysis**: 解析IP、TCP、UDP等网络协议
- **Real-time Display**: 实时显示捕获的网络流量信息

## 项目结构

```
app/
├── src/main/
│   ├── java/com/trafficcapture/
│   │   ├── MainActivity.kt          # 主界面Activity
│   │   ├── VpnService.kt           # VPN服务，负责数据包捕获
│   │   ├── NetworkRequest.kt       # 网络请求数据模型
│   │   └── TrafficAdapter.kt       # RecyclerView适配器
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml   # 主界面布局
│   │   │   └── item_traffic.xml    # 流量条目布局
│   │   ├── values/
│   │   │   ├── strings.xml         # 字符串资源
│   │   │   ├── colors.xml          # 颜色资源
│   │   │   └── themes.xml          # 主题样式
│   │   └── xml/
│   │       ├── network_security_config.xml  # 网络安全配置
│   │       ├── data_extraction_rules.xml    # 数据提取规则
│   │       └── backup_rules.xml             # 备份规则
│   └── AndroidManifest.xml        # 应用清单文件
├── build.gradle                   # 应用级构建配置
└── proguard-rules.pro             # 代码混淆规则
```

## 构建要求

- **Android Studio**: Arctic Fox 2020.3.1 或更新版本
- **Gradle**: 7.0+
- **Kotlin**: 1.8.10+
- **最低Android版本**: API 23 (Android 6.0)
- **目标Android版本**: API 34 (Android 14)

## 安装和使用

### 1. 环境准备

确保你已经安装了以下软件：
- Android Studio
- Android SDK
- Android模拟器或真机

### 2. 项目构建

```bash
# 克隆项目
git clone <repository-url>
cd traffic_tool

# 在Android Studio中打开项目
# 或使用命令行构建
./gradlew assembleDebug
```

### 3. 权限说明

应用需要以下权限：
- `INTERNET`: 网络访问权限
- `ACCESS_NETWORK_STATE`: 网络状态访问权限
- `ACCESS_WIFI_STATE`: WiFi状态访问权限
- `BIND_VPN_SERVICE`: VPN服务绑定权限
- `SYSTEM_ALERT_WINDOW`: 系统窗口权限
- `WRITE_EXTERNAL_STORAGE`: 外部存储写入权限

### 4. 使用步骤

#### 基础VPN抓包：
1. **安装应用**: 将APK安装到Android设备或模拟器
2. **启动应用**: 打开Traffic Capture Tool应用
3. **授权VPN**: 点击"Start Capture"按钮，系统会请求VPN权限
4. **开始捕获**: 授权后，应用开始捕获网络流量
5. **查看数据**: 在界面下方的列表中查看捕获的网络请求
6. **停止捕获**: 点击"Stop Capture"按钮停止捕获

#### HTTPS解密抓包：
1. **启动VPN捕获**: 首先按照上述步骤启动基础抓包
2. **启用HTTPS解密**: 打开"HTTPS Decrypt"开关
3. **导出CA证书**: 点击"Export CA Cert"按钮导出证书文件
4. **安装证书**: 
   - 进入 设置 > 安全 > 加密和凭据
   - 选择"从存储设备安装"
   - 选择导出的CA证书文件
   - 设置证书名称并确认安装
5. **配置代理**: 在需要抓包的应用中设置代理：
   - 代理地址：127.0.0.1
   - 端口：8080
6. **开始HTTPS抓包**: 现在可以查看解密后的HTTPS内容

⚠️ **HTTPS解密注意事项**:
- 仅在学习和测试环境中使用
- 某些应用使用证书绑定技术，可能无法解密
- Android 7.0+系统对用户证书有额外限制

## 工作原理

### VPN服务架构

1. **VPN建立**: 应用创建VPN连接，将设备流量路由通过自定义的VPN服务
2. **数据包拦截**: VPN服务拦截所有网络数据包
3. **协议解析**: 解析IP头部信息，提取源地址、目标地址、协议类型等
4. **数据转发**: 将数据包转发到真实的网络目标

### 核心技术栈
- **VPN API**: 基于Android VpnService实现数据包拦截
- **HTTPS代理**: 本地HTTP代理服务器 (端口8080)  
- **SSL/TLS解密**: BouncyCastle加密库进行证书生成
- **协议解析**: TCP/UDP/HTTP/DNS协议深度分析
- **UI框架**: Android原生Kotlin + Material Design

### 系统架构
```
┌─ MainActivity.kt ─────────────────────┐
│  UI控制器与权限管理                      │
├─────────────────────────────────────┤
│  VpnCaptureService.kt                │
│  ├─ 数据包拦截 (TUN接口)                │
│  ├─ 流量路由控制                       │
│  └─ 实时监控展示                       │
├─────────────────────────────────────┤
│  HttpsDecryptor.kt                   │
│  ├─ CA证书动态生成                     │
│  ├─ 本地代理服务器                     │
│  └─ TLS握手处理                       │
├─────────────────────────────────────┤
│  GameTrafficAnalyzer.kt              │
│  ├─ Unity游戏协议识别                  │
│  ├─ 知名游戏特征检测                    │
│  └─ 加密流量分析                       │
└─────────────────────────────────────┘
```

### 技术特性
- **非ROOT设计**: 基于VPN权限，无需设备ROOT
- **零配置启动**: 自动初始化网络环境
- **内存优化**: 实时流量处理，避免大文件缓存
- **协议兼容**: 支持IPv4/IPv6双栈网络

### 支持的协议与功能
- **标准协议**: HTTP/HTTPS, TCP/UDP, DNS, WebSocket
- **游戏引擎**: Unity, Unreal Engine网络层
- **移动游戏**: 王者荣耀, 和平精英, 原神等
- **数据格式**: JSON, Protocol Buffers, 自定义二进制

## 安全注意事项

⚠️ **使用须知**:
- 本工具仅供学习研究和合法网络调试使用
- 严禁用于破解、逆向或任何违法活动
- 使用时请遵守当地法律法规和用户协议
- 建议仅在测试环境和自有应用中使用

### 权限说明
| 权限 | 用途 | 必要性 |
|------|------|--------|
| `android.permission.INTERNET` | 网络访问 | 必需 |
| `VpnService` | 数据包拦截 | 核心功能 |
| `WRITE_EXTERNAL_STORAGE` | 证书导出 | HTTPS功能 |

## 兼容性支持

### 设备兼容性
- ✅ **Android模拟器**: x86/x86_64/ARM架构
- ✅ **物理设备**: Android 6.0+ (API 23+)
- ✅ **架构支持**: ARM64, ARMv7, x86, x86_64

### 系统兼容性
| Android版本 | API级别 | 支持状态 | 备注 |
|-------------|---------|----------|------|
| Android 6.0+ | 23+ | ✅ 完全支持 | VPN权限可用 |
| Android 5.x | 21-22 | ⚠️ 部分支持 | 需要额外配置 |
| Android 4.x | 19-20 | ❌ 不支持 | VPN API限制 |

## 故障排除

### 构建问题
```bash
# 检查Java版本
java -version
# 应显示: openjdk version "21.0.8"

# 清理构建缓存
.\gradlew.bat clean

# 检查Android SDK路径
echo $env:ANDROID_HOME  # PowerShell
echo %ANDROID_HOME%     # CMD
```

### 运行时问题
**Q: VPN连接后无法上网**  
A: 检查VPN路由配置，确保DNS设置正确

**Q: HTTPS解密失败**  
A: 确认CA证书已安装到系统证书存储

**Q: 游戏流量无法解析**  
A: 部分游戏使用私有加密协议，仅能显示基础连接信息

## 开发文档

### 核心类说明
- **MainActivity**: UI控制器，管理界面状态和用户交互
- **VpnCaptureService**: VPN服务实现，负责数据包拦截
- **HttpsDecryptor**: HTTPS代理和证书管理
- **GameTrafficAnalyzer**: 游戏流量识别和协议解析

### 构建变体
```gradle
buildTypes {
    debug {
        debuggable true
        minifyEnabled false
    }
    release {
        minifyEnabled true
        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
    }
}
```

### 扩展开发
项目支持插件化扩展：
- 自定义协议解析器
- 新游戏流量分析模块  
- 数据导出格式支持
- UI主题定制

## 更新日志

### v1.0.0 (当前版本)
- ✨ 基础VPN流量拦截功能
- ✨ HTTPS代理和证书管理
- ✨ 游戏流量分析支持
- ✨ Material Design界面
- 🐛 修复Java 21兼容性问题
- 🐛 修复VPN网络连接问题

## 贡献指南

### 开发环境搭建
1. 安装Java 21 (Eclipse Adoptium推荐)
2. 配置Android SDK (API 34)
3. 克隆项目并导入IDE
4. 运行`gradlew.bat assembleDebug`测试构建

### 代码规范
- 使用Kotlin进行开发
- 遵循Android代码规范
- 添加适当的注释和文档
- 确保代码通过lint检查

### 提交流程
1. Fork项目仓库
2. 创建特性分支 (`git checkout -b feature/new-feature`)
3. 提交代码 (`git commit -am 'Add new feature'`)
4. 推送分支 (`git push origin feature/new-feature`)
5. 创建Pull Request

## 许可协议

本项目基于 **MIT License** 开源协议发布。

## 联系信息

- 📧 **问题反馈**: [GitHub Issues](https://github.com/eizawa/traffic_tool/issues)
- 📚 **项目主页**: [GitHub Repository](https://github.com/eizawa/traffic_tool)
- 💬 **讨论区**: [GitHub Discussions](https://github.com/eizawa/traffic_tool/discussions)

---

> **免责声明**: 本软件按"现状"提供，不提供任何明示或暗示的担保。使用本软件产生的任何直接或间接后果，开发者概不负责。请在法律允许范围内合理使用。
5. **界面显示**: 将捕获的信息展示在用户界面

### 支持的协议和应用

#### 网络协议：
- **TCP**: 传输控制协议
- **UDP**: 用户数据报协议  
- **ICMP**: 网际控制报文协议
- **HTTP**: 超文本传输协议（明文）
- **HTTPS**: 安全超文本传输协议（支持解密）
- **其他**: 其他IP协议

#### 游戏引擎和平台：
- **Unity游戏**: 自动识别Unity网络协议
- **Unreal游戏**: 支持Unreal引擎网络分析
- **腾讯游戏**: 王者荣耀、和平精英等（基础分析）
- **网易游戏**: 识别网易游戏服务器通信
- **独立游戏**: 大部分使用标准协议的游戏
- **其他平台**: Steam、Epic等平台游戏的网络流量

### HTTPS解密原理

1. **CA证书生成**: 应用自动生成根CA证书和私钥
2. **代理服务器**: 在本地8080端口启动HTTPS代理服务器
3. **动态证书**: 为每个访问的域名动态生成对应的SSL证书
4. **中间人代理**: 拦截HTTPS连接，使用自签名证书与客户端建立连接
5. **流量解密**: 解密HTTPS流量并显示明文内容
6. **数据转发**: 将请求转发到真实的目标服务器

## 注意事项

⚠️ **重要提醒**:

1. **仅用于学习和测试**: 本工具仅用于学习网络协议和测试目的
2. **遵守法律法规**: 请遵守当地法律法规，不要用于非法目的
3. **隐私保护**: 注意保护用户隐私，不要捕获敏感信息
4. **权限谨慎**: VPN权限较为敏感，请谨慎使用
5. **模拟器环境**: 建议在Android模拟器中测试使用

## 开发计划

- [x] 基础VPN流量捕获
- [x] HTTP/HTTPS协议识别  
- [x] HTTPS代理服务器
- [x] 动态证书生成
- [x] CA证书管理和导出
- [ ] 完善HTTPS解密实现
- [ ] 添加流量过滤和搜索功能
- [ ] 添加数据导出功能
- [ ] 支持WebSocket协议
- [ ] 优化用户界面和体验
- [ ] 添加流量统计功能
- [ ] 支持证书绑定绕过

## 贡献

欢迎提交问题和改进建议！

## 许可证

本项目仅用于学习和研究目的。

---

**免责声明**: 本工具仅用于教育和学习目的。使用者应当遵守相关法律法规，作者不承担任何因使用本工具而产生的法律责任。
