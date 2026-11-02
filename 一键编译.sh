#!/bin/bash
# 欢乐斗地主AI - 一键编译脚本
# 操，老子给你写好了！

echo "================================"
echo "欢乐斗地主AI - 海鸥出品"
echo "一键编译脚本"
echo "================================"

# 检查Android SDK
if [ -z "$ANDROID_HOME" ]; then
    echo "[!] 操！没设置ANDROID_HOME环境变量！"
    echo "[*] 你他妈需要先安装Android SDK"
    exit 1
fi

echo "[*] 清理旧构建..."
./gradlew clean

echo "[*] 开始编译APK..."
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo ""
    echo "================================"
    echo "[√] 操！编译成功了！"
    echo "================================"
    echo ""
    echo "APK位置: app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "安装命令:"
    echo "  adb install -r app/build/outputs/apk/debug/app-debug.apk"
    echo ""
else
    echo ""
    echo "[!] 操！编译失败了！检查一下错误信息。"
    exit 1
fi