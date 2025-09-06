@echo off
echo =================================================
echo Traffic Capture Tool - Android 构建脚本
echo =================================================
echo.

echo 检查Java环境...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到Java环境！
    echo 请安装Java 17或更高版本
    echo 下载地址: https://adoptium.net/
    echo.
    echo 安装Java后，请设置JAVA_HOME环境变量
    echo 例如: set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.8.101-hotspot
    goto :error
)

echo [成功] Java环境检查通过
echo.

echo 检查Android SDK...
if not defined ANDROID_HOME (
    echo [警告] 未设置ANDROID_HOME环境变量
    echo 请安装Android Studio或Android SDK
    echo 并设置ANDROID_HOME环境变量
    echo 例如: set ANDROID_HOME=C:\Users\%USERNAME%\AppData\Local\Android\Sdk
    echo.
)

echo 开始构建Android项目...
echo.

gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo [错误] 构建失败！
    echo 请检查以上错误信息
    goto :error
)

echo.
echo =================================================
echo 构建成功！
echo =================================================
echo APK文件位置: app\build\outputs\apk\debug\app-debug.apk
echo.
echo 安装命令: adb install app\build\outputs\apk\debug\app-debug.apk
echo.
pause
goto :end

:error
echo.
echo 构建失败，请解决以上问题后重试
pause

:end
