@echo off
echo Setting JAVA_HOME for Java 21...
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot

echo Building Traffic Capture Tool with Java 21...
echo.

gradlew.bat clean assembleDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo BUILD SUCCESSFUL!
    echo APK generated at: app\build\outputs\apk\debug\app-debug.apk
    echo.
    pause
) else (
    echo.
    echo BUILD FAILED!
    echo.
    pause
)
