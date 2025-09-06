@echo off
chcp 65001 >nul
setlocal

echo =================================================
echo Traffic Capture Tool - APK Build Script (JDK 21)
echo =================================================
echo.

REM Set JDK 21 Environment Variables
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo Current Java Version:
java -version
echo.

echo Checking Android SDK...
if not defined ANDROID_HOME (
    echo [WARNING] ANDROID_HOME environment variable not set
    echo Please install Android Studio or Android SDK
    echo Download: https://developer.android.com/studio
    echo.
    echo After installation, set environment variable:
    echo set ANDROID_HOME=C:\Users\%USERNAME%\AppData\Local\Android\Sdk
    echo.
    echo Press any key to continue build attempt...
    pause
)

echo Starting APK build...
echo.

REM Try to build with Gradle Wrapper
echo Method 1: Building with Gradle Wrapper...
gradlew.bat clean assembleDebug
if %errorlevel% equ 0 (
    echo.
    echo =================================================
    echo ✅ APK Build Successful!
    echo =================================================
    echo APK Location: app\build\outputs\apk\debug\app-debug.apk
    goto :success
)

echo.
echo Method 1 failed, trying Method 2: Download new Gradle...

REM Download and use system Gradle
where gradle >nul 2>&1
if %errorlevel% equ 0 (
    echo Building with system Gradle...
    gradle clean assembleDebug
    if %errorlevel% equ 0 (
        echo.
        echo =================================================
        echo ✅ APK Build Successful!
        echo =================================================
        echo APK Location: app\build\outputs\apk\debug\app-debug.apk
        goto :success
    )
)

echo.
echo =================================================
echo ❌ Build Failed!
echo =================================================
echo.
echo Possible Solutions:
echo 1. Install Android Studio (includes Android SDK)
echo 2. Set ANDROID_HOME environment variable
echo 3. Ensure network connection (downloading dependencies)
echo.
echo Manual Build Steps:
echo 1. Install Android Studio
echo 2. Open this project in Android Studio
echo 3. Click Build -> Build Bundle(s)/APK(s) -> Build APK(s)
echo.
goto :end

:success
echo.
echo 🎉 APK packaging complete!
echo.
echo Install command:
echo adb install app\build\outputs\apk\debug\app-debug.apk
echo.
echo Or transfer APK file to Android device for installation
echo.

:end
echo Press any key to exit...
pause
