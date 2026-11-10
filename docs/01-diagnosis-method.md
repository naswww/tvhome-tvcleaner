# 01 · 无 root 的取证方法

改任何配置之前，先把「到底崩在哪」钉死。这台设备无 root、`/system` 只读，
很多常规路径走不通，下面是实际可用的一套。

## 1. 先分清三类「看起来像重启」

| 现象 | 判据 | 含义 |
| --- | --- | --- |
| 应用闪退回首页 | 焦点从播放页变回首页 Activity，进程未死或被重建 | 应用级崩溃 |
| 框架层软重启 | `BOOT_COUNT` +1，但 `/proc/uptime` **未归零**，重播开机动画 | zygote / system_server 重建 |
| 完整内核重启 | `BOOT_COUNT` +1 且 `/proc/uptime` **归零**，`ro.boot.bootreason` 可查 | 内核重启 |

```bash
adb shell settings get global boot_count
adb shell cat /proc/uptime
adb shell getprop ro.boot.bootreason
```

> 只用「看到开机动画」判断会误判为整机重启。`uptime` 归零才是硬判据。

## 2. tombstones 拿不到时，走 dropbox 服务接口

`/data/tombstones/`、`/data/system/dropbox/`、`/data/data/*` 对 shell 均不可读。
但 `dumpsys dropbox --print <tag>` 是系统服务接口，可以导出同样内容：

```bash
adb shell dumpsys dropbox --print data_app_native_crash
adb shell dumpsys dropbox --print SYSTEM_TOMBSTONE
adb shell dumpsys dropbox --print system_server_native_crash
adb shell dumpsys dropbox --print system_server_wtf
```

配合 `logcat -d -b all -v threadtime` 保存全缓冲，再按 `Fatal signal` / `am_proc_died` /
`am_finish_activity` / `boot_progress_start` 串时间线。

## 3. 固件屏蔽第三方应用 logcat 时，把结果落盘

部分电视 ROM 不把第三方应用的日志暴露给 logcat。自建工具应把关键结果
**写到自己的外置目录**，再用 `adb pull` 取回：

```
/sdcard/Android/data/<pkg>/files/
```

本项目清理器的开机守卫就靠这个目录输出校验报告，否则「到底跑了没有」完全黑盒。

## 4. native crash 不是 ANR

- 不产生 `/data/anr/traces.txt` 新条目
- 直接证据是 `Fatal signal 11 (SIGSEGV)` / `signal 7 (SIGBUS)` + tombstone backtrace
- AMS 随后 `am_finish_activity ... proc died without state saved`，再把首页 Activity 拉起
  —— 这就是用户看到的「自动退出回到首页」

如果只盯着 ANR 和「内存不足」，会完全走错方向。

## 5. 观测到的四类独立崩溃栈

| 类型 | 典型栈 | 触发阶段 |
| --- | --- | --- |
| OMX 回调 | `CodecLooper → onOMXEmptyBufferDone → AMessage::dup → RefBase::createWeak`，SIGBUS / `BUS_ADRALN` | 起播 |
| GPU / Surface | `libGLES_mali → Surface::dequeueBuffer → libbinder Parcel → jemalloc invalid free`，SIGSEGV | 广告 |
| 音频 | `CAudioConsumer` SIGSEGV | 广告 |
| ART / odex | 跳到未知地址，栈里出现 `base.odex` | 广告 |

另有系统侧级联：`system_server` 的 `art::System_arraycopy` SIGSEGV 会触发重启；
`systemui` 的 `Didn't find oat method index` SIGABRT 通常可自愈。

**结论：这不是「一个 bug」。** 任何只修其中一条的方案都不会根治。

## 6. 判定流程

```
播放异常
 ├─ 有 Fatal signal？ ── 是 → 导出 tombstone，按栈分类（不要先清内存）
 │                      └─ 否 → 查 ANR / 低内存 / 被杀记录
 ├─ BOOT_COUNT 变了？ ── 是 → uptime 归零？ → 内核重启 / 否则框架层软重启
 └─ 做对照复现：一次只改一个变量，记录「是否完整播到正片 + Fatal signal 数 + BOOT_COUNT」
```

## 7. 对照复现的最小记录集

每次起播都记这四项，否则无法区分「运气好」和「真的修好了」：

1. 起播方式（冷启动 / 热启动）
2. 是否过广告进入正片（截图为准，不要只看 Activity 名）
3. `Fatal signal` 计数
4. `BOOT_COUNT` 与 `/proc/uptime`

> 单次成功不能证明修复。这类故障是概率性的，至少做 2–3 次独立冷启动。
