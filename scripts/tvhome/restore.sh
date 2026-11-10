#!/usr/bin/env bash
# 电视桌面回滚脚本 —— 任何一步出问题都能用有线 ADB 救回来。
#
# 用法（在 Git Bash 里）：
#   bash restore.sh check        # 只看当前状态，不改任何东西
#   bash restore.sh home-back    # 切回当贝桌面（会先自动启用当贝）
#   bash restore.sh tvhome       # 切回 TVHome 桌面
#   bash restore.sh enable-all   # 只恢复全部被停用的包，不动桌面
#   bash restore.sh full         # 恢复全部包 + 切回当贝 + 重启
#
set -uo pipefail
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
TV="${TV_HOST:?请设置 TV_HOST，例如 <电视IP>:5555}"

DANGBEI_PKG="com.dangbei.TVlauncher"
DANGBEI_ACT="com.dangbei.TVlauncher/com.dangbei.tvlauncher.MainActivity"
TVHOME_ACT="com.tv.tvhome/.MainActivity"

# 示例禁用清单（桌面 / 广告 / 遥测 / 预装 / 语音 / AI / 天气 / 通话等），请按机型替换
DISABLED_PKGS="
com.jamdeo.tv.vod
com.dangbei.TVlauncher
com.hisense.business.ad
com.hisense.service.ad
com.hisense.hitv.das
com.hisense.logupload
com.hisense.demonstrate
com.hisense.electronictutorial
com.hisense.autotest
com.hisense.tv.foodmanage
com.hisense.raycodereceiver
com.hisense.tv.weather
com.hisense.tv.videochat.social
com.hisense.tv.aivirtualassistant
com.keylab.speech.view.vidaa
com.keylab.speech.core.vidaa
com.hisense.ai.service
com.aispeech.dui.dds.tvassistant
"

"$ADB" connect "$TV" >/dev/null 2>&1
sleep 1

if [ "$("$ADB" get-state 2>/dev/null)" != "device" ]; then
  echo "[ERROR] 连不上电视 $TV"
  exit 1
fi

enable_all() {
  for p in $DISABLED_PKGS; do
    out=$("$ADB" shell "pm enable --user 0 $p" 2>&1 | tr -d '\r')
    echo "    $p -> $out"
  done
}

case "${1:-check}" in
  check)
    echo "=== 当前默认桌面 ==="
    "$ADB" shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    echo "=== 桌面进程 ==="
    "$ADB" shell "ps -A | grep -E 'tvhome|dangbei|jamdeo.tv.vod'" || echo "  (无)"
    echo "=== 已停用的包 ($("$ADB" shell 'pm list packages -d' | wc -l) 个) ==="
    "$ADB" shell "pm list packages -d"
    echo "=== 可用内存 ==="
    "$ADB" shell "cat /proc/meminfo | grep -E 'MemAvailable|MemTotal'"
    echo "=== 自启授权 ==="
    "$ADB" shell "settings get secure enabled_notification_listeners"
    ;;

  home-back)
    echo "[1] 启用当贝（它现在可能被停用，不先启用就切不过去）"
    "$ADB" shell "pm enable --user 0 $DANGBEI_PKG" 2>&1 | sed 's/^/    /'
    sleep 2
    echo "[2] 切回当贝桌面"
    "$ADB" shell "cmd package set-home-activity $DANGBEI_ACT" 2>&1 | sed 's/^/    /'
    echo "[3] 确认"
    "$ADB" shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    echo "[4] 拉起桌面"
    "$ADB" shell "am start -n $DANGBEI_ACT" >/dev/null 2>&1
    ;;

  tvhome)
    echo "[1] 切回 TVHome 桌面"
    "$ADB" shell "cmd package set-home-activity $TVHOME_ACT" 2>&1 | sed 's/^/    /'
    echo "[2] 确认"
    "$ADB" shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    echo "[3] 拉起桌面"
    "$ADB" shell "am start -n $TVHOME_ACT" >/dev/null 2>&1
    ;;

  enable-all)
    echo "[1] 恢复全部被停用的包"
    enable_all
    echo "[2] 落盘"
    "$ADB" shell "sync"
    ;;

  full)
    echo "[1] 恢复全部被停用的包"
    enable_all
    echo "[2] 切回当贝桌面"
    "$ADB" shell "cmd package set-home-activity $DANGBEI_ACT" 2>&1 | sed 's/^/    /'
    echo "[3] 落盘"
    "$ADB" shell "sync"
    sleep 3
    echo "[4] 重启电视"
    "$ADB" reboot
    ;;

  *)
    echo "用法: bash restore.sh [check|home-back|tvhome|enable-all|full]"
    ;;
esac
