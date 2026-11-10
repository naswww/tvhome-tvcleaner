#!/usr/bin/env bash
# 为 TVHome 开启开机自启（通知监听服务通道）。
#
# 本机固件（海信 VIDAA / Android 9）只把 BOOT_COMPLETED 投递给系统应用，
# 第三方应用的静态广播接收器收不到，JobScheduler 的持久化任务重启后也不会恢复。
# 唯一稳定可用的自启通道是「已授权的通知监听服务」——系统开机后会主动绑定它。
#
# 用法：bash scripts/enable-autostart.sh
set -u

ADB="${ADB:-adb}"
HOST="${HOST:?请设置 HOST，例如 <电视IP>:5555}"
COMP="com.tv.tvhome/com.tv.tvhome.BootListener"

echo "[1/4] 连接 $HOST"
$ADB connect "$HOST" >/dev/null 2>&1
$ADB devices | grep -q "$HOST" || { echo "连接失败"; exit 1; }

echo "[2/4] 当前通知监听授权"
BEFORE=$($ADB shell "settings get secure enabled_notification_listeners" | tr -d '\r')
echo "  $BEFORE"

echo "[3/4] 授权 $COMP"
$ADB shell "cmd notification allow_listener ${COMP/./\/}" >/dev/null 2>&1 \
  || $ADB shell "cmd notification allow_listener $COMP" >/dev/null 2>&1

AFTER=$($ADB shell "settings get secure enabled_notification_listeners" | tr -d '\r')
echo "  $AFTER"
case "$AFTER" in
  *BootListener*) echo "  OK 已授权" ;;
  *) echo "  !! 授权失败，请手动执行："; echo "     adb shell cmd notification allow_listener $COMP"; exit 1 ;;
esac

echo "[4/4] 确认服务被系统绑定"
sleep 2
$ADB shell "dumpsys notification 2>/dev/null | grep -c 'com.tv.tvhome.BootListener'" | tr -d '\r' | \
  awk '{ if ($1+0 > 0) print "  OK 已绑定 ("$1" 处引用)"; else print "  !! 未绑定，重启后再看" }'

echo ""
echo "回滚（取消自启授权）："
echo "  adb shell cmd notification disallow_listener $COMP"
