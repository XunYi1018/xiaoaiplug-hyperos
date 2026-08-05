#!/usr/bin/env bash
# ============================================================================
# XiaoAi Plug — OTA/更新后一键修复脚本
#
# 场景:系统 OTA 或小爱更新后,可能出现:
#   1. 系统版小爱覆盖了商店更新版(hook 类名失配,模块静默降级)
#   2. 模块 APK 丢失(OTA 不会删 /data 应用,但保险起见重新安装)
#   3. 无障碍权限被摘(MIUI 特性)
#
# 用法(在 Git Bash / WSL 里):
#   bash fix-after-ota.sh
#
# 前置:手机已连 USB 且开启 USB 调试。
# ============================================================================
set -u

ADB="${ADB:-$HOME/platform-tools/platform-tools/adb.exe}"
MODULE_APK="${MODULE_APK:-$HOME/io.mo.xiaoaiplug/app/build/outputs/apk/debug/app-debug.apk}"
TARGET_VER_FILE="$HOME/.xiaoaiplug_target_version"

say()  { printf '\033[1;34m[修复]\033[0m %s\n' "$1"; }
warn() { printf '\033[1;33m[注意]\033[0m %s\n' "$1"; }
fail() { printf '\033[1;31m[失败]\033[0m %s\n' "$1"; exit 1; }

# ---------- 1. 设备连接 ----------
say "检查设备连接..."
if ! "$ADB" devices | grep -q "device$"; then
    fail "未检测到设备。请连接手机并开启 USB 调试。"
fi
DEVICE="$("$ADB" devices | grep "device$" | head -1 | cut -f1)"
say "设备: $DEVICE"

# ---------- 2. 小爱版本检测 ----------
VA_VER="$("$ADB" shell dumpsys package com.miui.voiceassist 2>/dev/null | grep -oE 'versionName=[^ ]+' | head -1 | cut -d= -f2)"
VA_VER="${VA_VER%$'\r'}"
say "超级小爱版本: ${VA_VER:-未知}"

if [ -f "$TARGET_VER_FILE" ]; then
    TARGET="$(cat "$TARGET_VER_FILE")"
    if [ -n "$VA_VER" ] && [ "$VA_VER" != "$TARGET" ]; then
        warn "小爱版本已变化: $TARGET → $VA_VER"
        warn "如果这是系统 OTA 带来的还原,请先在小米应用商店把超级小爱更新回新版,"
        warn "或提供新版 APK 后重跑本脚本。"
        read -r -p "继续执行其余修复?(y/N) " ans
        [ "$ans" = "y" ] || [ "$ans" = "Y" ] || exit 0
    fi
else
    warn "首次运行:当前版本 ${VA_VER} 将记为基准版本。"
    [ -n "$VA_VER" ] && echo "$VA_VER" > "$TARGET_VER_FILE"
fi

# ---------- 3. 模块 APK 重装 ----------
if [ -f "$MODULE_APK" ]; then
    say "安装模块 APK..."
    # 先确认设备上是否已装同签名版本:已装且签名一致时 pm install -r 可直接覆盖
    if ! "$ADB" shell pm path io.mo.xiaoaiplug >/dev/null 2>&1; then
        warn "模块未安装,将全新安装。"
    fi
    "$ADB" push "$MODULE_APK" /data/local/tmp/xiaoaiplug.apk >/dev/null 2>&1
    INSTALL_RESULT="$("$ADB" shell "su -c 'pm install -r -d /data/local/tmp/xiaoaiplug.apk'" 2>&1 | tail -1)"
    if echo "$INSTALL_RESULT" | grep -q Success; then
        say "模块安装成功"
    else
        warn "模块安装失败: $INSTALL_RESULT"
        warn "若提示签名不匹配:当前设备装的是别的签名版本,需先卸载再装"
        warn "(卸载会清掉配置,装完需在模块 App 里重新填 API Key)。"
        read -r -p "是否卸载重装?(y/N) " ans2
        if [ "$ans2" = "y" ] || [ "$ans2" = "Y" ]; then
            "$ADB" shell "su -c 'pm uninstall io.mo.xiaoaiplug'" >/dev/null 2>&1
            if "$ADB" shell "su -c 'pm install -r -d /data/local/tmp/xiaoaiplug.apk'" | grep -q Success; then
                say "卸载重装成功(请重新在模块 App 填写 API Key)"
            else
                warn "卸载重装也失败,请手动安装。"
            fi
        fi
    fi
else
    warn "未找到模块 APK($MODULE_APK),跳过安装。"
fi

# ---------- 4. 恢复配置(若数据被清) ----------
CFG="/data/data/io.mo.xiaoaiplug/shared_prefs/xiaoai_plug_config.xml"
if ! "$ADB" shell "run-as io.mo.xiaoaiplug cat shared_prefs/xiaoai_plug_config.xml 2>/dev/null" | grep -q "api_key"; then
    warn "模块配置丢失,需要重新填写 API Key。请在模块 App 设置页重新配置,"
    warn "或把备份的 xiaoai_plug_config.xml 放到项目目录后重跑本脚本。"
else
    say "模块配置完好(API Key 存在)"
fi

# ---------- 5. 无障碍权限 ----------
say "恢复无障碍权限..."
"$ADB" shell "settings put secure enabled_accessibility_services io.mo.xiaoaiplug/io.mo.xiaoaiplug.auto.UiAutoService" >/dev/null 2>&1
"$ADB" shell "settings put secure accessibility_enabled 1" >/dev/null 2>&1

# ---------- 6. 重启小爱 ----------
say "重启超级小爱进程..."
"$ADB" shell "su -c 'am force-stop com.miui.voiceassist'" >/dev/null 2>&1
sleep 2
"$ADB" shell "am start -n com.miui.voiceassist/com.xiaomi.voiceassistant.LaunchHomeRouterActivity" >/dev/null 2>&1
sleep 6

# ---------- 7. 验证 ----------
say "验证 hook 是否生效..."
VERIFY_LOG="$("$ADB" logcat -d 2>/dev/null | grep -E "XiaoAiProbe" | tail -3)"
if [ -n "$VERIFY_LOG" ]; then
    say "hook 日志存在,模块活跃:"
    echo "$VERIFY_LOG"
else
    warn "暂未抓到 hook 日志。请在手机小爱里输入「现在几点了」,"
    warn "若回答来自 MiniMax(非小爱原生)则一切正常;若仍是原生回答,"
    warn "说明小爱版本与模块类名不匹配,运行 ADAPTATION.md 的适配流程。"
fi

say "修复完成。测试:小爱输入「现在几点了」"
