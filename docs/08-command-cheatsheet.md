# 08 · 命令速查

所有 `<电视IP>`、`<pkg>`、`<监听服务>`、`<SDK_ROOT>` 均为占位符。
Windows + Git Bash 下路径转换常踩坑，建议先 `export MSYS_NO_PATHCONV=1`。

## 连接与体检

```bash
export ADB=<SDK_ROOT>/platform-tools/adb
export TV_HOST=<电视IP>:5555
$ADB connect "$TV_HOST"

$ADB shell "echo model=$(getprop ro.product.model); echo sdk=$(getprop ro.build.version.sdk)"
$ADB shell "grep -E 'MemTotal|MemAvailable' /proc/meminfo"
$ADB shell "ps -A | grep <pkg>"
```

> 优先用**有线以太网**连接。黑屏 / 桌面异常时，ADB over TCP 是唯一恢复通道。

## 桌面与回退

```bash
# 查当前默认 HOME
$ADB shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN

# 切换 / 确认
$ADB shell cmd package set-home-activity <pkg>/<Activity 全类名>
$ADB shell am start -a android.intent.action.MAIN -c android.intent.category.HOME

# 回退（脚本会自动重新启用被停用的原厂桌面）
bash scripts/tvhome/restore.sh check
bash scripts/tvhome/restore.sh home-back
bash scripts/tvhome/restore.sh tvhome
bash scripts/tvhome/restore.sh full
```

> `input keyevent 3`（HOME 键）在部分 ROM 被框架拦截，测试请用上面的显式 intent。

## 预装包与自启

```bash
$ADB shell pm disable-user --user 0 <pkg>
$ADB shell pm list packages -d
$ADB shell am force-stop <pkg>                 # 仅会话级

$ADB shell cmd notification allow_listener <pkg>/<监听服务>
$ADB shell settings get secure enabled_notification_listeners
$ADB shell settings get global boot_count
$ADB reboot
```

## 取证

```bash
$ADB shell logcat -c -b all
$ADB shell logcat -d -b all -v threadtime > logcat_all.txt

$ADB shell dumpsys dropbox --print data_app_native_crash
$ADB shell dumpsys dropbox --print SYSTEM_TOMBSTONE
$ADB shell dumpsys dropbox --print system_server_native_crash

$ADB shell "dumpsys activity activities | grep mResumedActivity"
$ADB shell "cat /proc/uptime"
$ADB exec-out screencap -p > screen.png        # 判断是否真进正片
```

> 取证文件含设备指纹，**不要提交到公开仓库**。

## 编译状态（关键禁忌）

```bash
# 查看
$ADB shell dumpsys package dexopt | grep -A3 -F "[<pkg>]"

# ⚠️ 不要执行：cmd package compile -m speed -f <pkg>
# 回退到安全状态
$ADB shell cmd package compile -m speed-profile -f <pkg>
$ADB shell am force-stop <pkg>

# 检查后台是否会自动改回去
$ADB shell dumpsys jobscheduler | grep -i -A6 dexopt
```

## 媒体栈只读排查

```bash
$ADB shell "ls /vendor/etc/media_codecs*.xml"
$ADB shell "grep -n -iE 'hevc|h265|OMX.google|Decoders' /vendor/etc/media_codecs.xml | head -40"
$ADB shell "dumpsys media.player"
$ADB shell "getprop | grep -iE 'media|stagefright|omx|codec'"
```

> 这些是**只读**排查。无 root 不要试图修改 `/vendor/etc/media_codecs*.xml`。

## 构建（Gradle-free）

```bash
bash scripts/tvhome/build.sh <版本名> <版本号> <输出名>
bash scripts/tvcleaner/build.sh <版本名> <版本号> <输出名>
# 需要 JDK 17 + Android SDK 34 / build-tools 34
# 签名口令优先用 KS_PASS 环境变量注入；未注入时本地随机生成，不入库
```

工具链顺序：`aapt2 compile → aapt2 link → javac → d8 → zipalign → apksigner`。
不需要 Gradle、AndroidX 或网络依赖。

## 发布前自检（本仓库自带）

```bash
bash scripts/verify-release.sh
```

该脚本检查：密钥/签名产物/二进制零残留、隐私正则零命中、超大文件兜底、
脚本语法、以及 git 历史中是否夹带敏感对象名。
