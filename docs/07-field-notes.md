# 07 · 可复用经验卡片

每张卡片按「适用 / 症状 / 判据 / 命令 / 坑」组织，命令均可直接换成自己的机型使用。
所有 `<...>` 为占位符。

---

### 卡片 1 · 先分清软重启与内核重启
- **适用**：电视播放中突然黑屏、重播开机动画
- **判据**：`BOOT_COUNT` +1 且 `/proc/uptime` **未归零** → 框架层软重启（zygote/system_server 重建）；`uptime` **归零** → 完整内核重启
- **命令**：`adb shell settings get global boot_count; adb shell cat /proc/uptime`
- **坑**：只看「有没有开机动画」会误判；两者排查方向完全不同

### 卡片 2 · 无 root 取 native 崩溃栈
- **适用**：`/data/tombstones` / `dropbox` 目录 Permission denied
- **命令**：`adb shell dumpsys dropbox --print data_app_native_crash`（另有 `SYSTEM_TOMBSTONE`、`system_server_native_crash`）
- **坑**：不要因为拿不到 tombstone 就转去猜「内存不足」；这是 native crash，不是 ANR，不会写 ANR trace

### 卡片 3 · 第三方应用日志看不到时，结果落盘
- **适用**：自制工具跑了没反应、logcat 里搜不到自己的 TAG
- **做法**：把关键结果写到 `/sdcard/Android/data/<pkg>/files/`，再 `adb pull` 取回
- **坑**：部分 ROM 屏蔽第三方应用 logcat，光靠 `Log.i()` 等于黑盒

### 卡片 4 · 崩溃栈不止一条时不要修单点
- **症状**：清内存后偶尔能播，偶尔仍崩
- **判据**：多次复现导出栈，若 OMX 回调 / GPU Surface / 音频消费 / ART odex 各出现一次，就是多点失稳
- **坑**：把概率性故障当成单一 bug，会陷入「每次只修一条、每次都还崩」

### 卡片 5 · 对照复现的最小记录集
- **做法**：每次起播记 ①冷/热启动 ②截图确认是否进正片 ③`Fatal signal` 计数 ④`BOOT_COUNT` + `uptime`
- **坑**：单次成功不算修复；概率性故障至少 2–3 次独立冷启动，且一次只改一个变量

### 卡片 6 · 禁止对视频客户端做全量 AOT
- **症状**：「桌面优化之后，原本偶尔能播的视频一次都播不了」
- **判据**：`dumpsys package dexopt` 显示 `status=speed`；崩溃栈出现 `base.odex`
- **命令**：`adb shell cmd package compile -m speed-profile -f <pkg>` + `adb shell am force-stop <pkg>`
- **坑**：回退后不 force-stop，旧 oat 进程仍在跑，会误判「回退无效」

### 卡片 7 · 第三方开机自启
- **症状**：设了默认桌面，一重启就回原厂
- **判据**：`BOOT_COMPLETED` 只投系统/白名单；`JobScheduler` 持久化 job 重启后消失
- **命令**：`adb shell cmd notification allow_listener <pkg>/<监听服务>` + `NotificationListenerService`
- **坑**：**不要**用 `elapsedRealtime()` 开机窗口；装 APK / 监听器重连会在使用中误触发。用 `Settings.Global.BOOT_COUNT` 门控

### 卡片 8 · 授权会被重置
- **判据**：`settings get secure enabled_notification_listeners` 读回为空或少了自己
- **做法**：把授权命令固化成脚本，每次验收读回确认
- **坑**：系统设置里一次「重置应用偏好」就可能清掉，自启静默失效

### 卡片 9 · 切换默认桌面必须先留后路
- **做法**：先确认 `adb connect <电视IP>:5555` 可用（优先有线）；切之前 `resolve-activity` 记下原桌面；切之后确认 `isDefault=true`
- **坑**：**原厂桌面被禁用后，`set-home-activity` 切回去是无效的**，回退脚本必须先 `pm enable` 它

### 卡片 10 · 清理器白名单必须包含当前桌面
- **症状**：点「深度清理」后首屏掉到直播 / FallbackHome
- **做法**：`KEEP` 里放桌面、投屏、直播、设置、输入法、系统 UI；清理后重读 `ps` 输出 `X/Y really stopped`
- **坑**：`am start-foreground-service .../.MouseService` 在目标包无 Service Resolver 时是空操作；改用 LAUNCHER intent + 发 HOME 带回桌面

### 卡片 11 · 无 root 的能力边界要如实报告
- **事实**：`pm disable-user --user 0` 对普通包有效且重启保持；对 **system uid(1000)** 进程无效（状态 disabled 但框架仍拉起）；`/system` 白名单不可改；`drop_caches` 需 root
- **坑**：工具谎报「已全部关闭」，会让后续诊断建立在错误前提上

---

## 一条元经验

改造往往**同时改了多个变量**。效果变差时，先列出全部变量，
再逐个做对照复现排除，而不是凭直觉归因给「最显眼的那个改动」。
本项目里最显眼的是「换桌面」，真正的元凶却是「顺手做的编译优化」。
