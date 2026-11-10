# 02 · 第三方应用开机自启（本机最反直觉的一节）

## 现象

自建桌面装好了、也设成了默认 HOME，但**一重启就回到原厂直播或 FallbackHome**，
自建应用进程根本不存在。

## 逐条排除的路

| 方案 | 实测结果 | 原因 |
| --- | --- | --- |
| `RECEIVE_BOOT_COMPLETED` + BroadcastReceiver | 无日志、不触发 | 本机 `BOOT_COMPLETED` 只投递系统应用与 `/system/etc/app_start_when_boot_whitelist.txt` 白名单应用 |
| 手动 `am broadcast` 验证 | 连广播队列都进不去 | 同上；`am force-stop` 后包进入 STOPPED 状态，广播测试本身也不可靠 |
| `JobScheduler.setPersisted(true)` | 注册成功（可见 PERSISTED job），**重启后任务消失** | 本机不恢复持久化任务 |
| 修改系统开机白名单 | 不可行 | 文件在 `/system`，无 root 且只读 |

## 可用的通道

`NotificationListenerService`：系统开机时会主动绑定**已授权**的通知监听器，
这个绑定动作本身就是一次可靠的开机回调。授权命令：

```bash
adb shell cmd notification allow_listener <pkg>/<监听服务全类名>
adb shell settings get secure enabled_notification_listeners
```

Manifest 声明要点：

```xml
<service android:name=".BootListener"
    android:exported="true"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

## 关键坑：不要用「开机时间窗口」

早期用 `elapsedRealtime() < 240000` 判断「是否刚开机」，结果：

- 安装 / 更新 APK 会让监听器**重新连接**，在正常使用中误触发
- 开机守卫会在用户使用到一半时把桌面顶到前台、或重复执行清理

正确做法是用 `Settings.Global.BOOT_COUNT` 做门控，每次开机只跑一次：

```java
int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
int last = prefs.getInt("last_boot_count", -1);
if (boot == last) return;          // 本次开机已执行过
prefs.edit().putInt("last_boot_count", boot).apply();
// ... 执行且仅执行一次开机逻辑
```

`BOOT_COUNT` 在框架层软重启与完整内核重启时都会 +1，正好覆盖两种场景。

## 连带要注意的两点

1. **授权会被清掉**：`enabled_notification_listeners` 一旦被系统设置重置，自启即失效。
   应把授权命令固化成脚本（本仓库 `scripts/tvhome/enable-autostart.sh`），
   并在验收时读回确认。
2. **多个监听器共存**：桌面和清理器各有一个监听服务时，两者都会被绑定；
   各自用 `BOOT_COUNT` 独立门控，不要互相依赖启动顺序。

## 验收方法

```bash
adb reboot
# 等待开机完成后
adb shell settings get global boot_count
adb shell dumpsys activity activities | grep mResumedActivity   # 应为自建桌面
adb shell settings get secure enabled_notification_listeners     # 授权仍在
```

连续验证至少两次重启，确认首屏稳定落在自建桌面。
