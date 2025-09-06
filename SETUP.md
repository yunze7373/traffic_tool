# Android 抓包工具 - 环境设置指南

## 快速开始

### 1. 安装 Java Development Kit (JDK)

1. 访问 [Adoptium OpenJDK](https://adoptium.net/)
2. 下载并安装 Java 17 或更高版本
3. 设置环境变量：
   ```
   JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.8.101-hotspot
   PATH=%JAVA_HOME%\bin;%PATH%
   ```

### 2. 安装 Android SDK

**选项A: 通过 Android Studio (推荐)**
1. 下载 [Android Studio](https://developer.android.com/studio)
2. 安装时选择标准安装，会自动安装SDK
3. 设置环境变量：
   ```
   ANDROID_HOME=C:\Users\%USERNAME%\AppData\Local\Android\Sdk
   PATH=%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\tools;%PATH%
   ```

**选项B: 仅安装命令行工具**
1. 下载 [Android SDK 命令行工具](https://developer.android.com/studio#cmdline-tools)
2. 解压到目标目录
3. 设置环境变量同上

### 3. 构建项目

1. 打开命令提示符或PowerShell
2. 进入项目目录：
   ```
   cd c:\Users\han\source\repos\eizawa\traffic_tool
   ```
3. 运行构建脚本：
   ```
   build.bat
   ```
   或直接运行Gradle：
   ```
   .\gradlew.bat assembleDebug
   ```

### 4. 安装和运行

1. 启动Android模拟器或连接真机
2. 安装APK：
   ```
   adb install app\build\outputs\apk\debug\app-debug.apk
   ```
3. 在设备上启动"Traffic Capture Tool"应用

## 故障排除

### Java相关问题
- 确保安装了Java 17+
- 检查JAVA_HOME环境变量是否正确设置
- 重新启动命令提示符以刷新环境变量

### Android SDK相关问题
- 确保ANDROID_HOME环境变量指向正确的SDK目录
- 运行 `sdkmanager --list` 检查SDK安装状态
- 确保安装了所需的API级别（API 23-34）

### 构建问题
- 检查网络连接（下载依赖需要网络）
- 清理项目：`.\gradlew.bat clean`
- 重新构建：`.\gradlew.bat assembleDebug`

## 项目结构

```
traffic_tool/
├── app/                     # 主应用模块
│   ├── src/main/java/      # Kotlin源代码
│   └── build.gradle        # 应用构建配置
├── gradle/                 # Gradle wrapper文件
├── build.gradle           # 项目级构建配置
├── gradlew.bat           # Windows Gradle wrapper
├── build.bat             # 自定义构建脚本
└── README.md             # 项目说明文档
```

需要帮助？请查看项目的README.md文件或联系开发者。
