# TVHome / TVCleaner · 安卓电视轻量桌面与运维实战笔记

> **来源与开发声明**
> 本项目从开源项目 **CleanYourTV** 的清单化思路出发二次开发，并针对
> **海信电视 32 位 VIDAA 系统** 做了专门适配。
> 分析、方案设计与代码实现由 **WorkBuddy** 调用 **DeepSeek** 与 **HY4（腾讯混元）** 协助完成。

---

## 这是什么

一台国产安卓电视上，「手机端视频客户端播放十几秒就闪退、卡死，甚至整机重启」，
以及「原厂桌面太重、预装服务常驻」这两类问题，通常被归因于「内存不够」或「应用不兼容」，
于是反复清理、反复重装，问题依旧。

这个仓库记录的是一次完整的排查与改造过程，以及两个可自行构建的小工具：

| 组件 | 作用 |
| --- | --- |
| **TVHome** | 纯 Canvas 自建桌面，无 AndroidX / 无网络，低内存占用，常用应用可长按换位 |
| **TVCleaner** | 清理器 + 预装包停用基线 + 开机守卫，清理后逐包复验是否真的停掉 |

**但更重要的是 `docs/` 里的经验笔记**：怎么在这类设备上做取证、怎么让第三方应用开机自启、
为什么全量 AOT 会让视频应用崩掉、以及哪些路走不通。工具只是这些结论的载体。

## 适用平台与限制

- 目标平台：**海信电视 32 位 VIDAA 系统**（Android 9 / SDK 28 一代，32 位 `zygote32`，约 1.5 GB RAM）
- **无需 root**，`/system` 与 `/vendor` 保持只读
- 方法本身通用，但**包名、组件名、白名单都是示例配置**，换机型必须自己核对
- 不提供任何成品 APK、密钥、设备备份或第三方应用安装包

> 这不是通用刷机教程。改默认桌面、停用预装包都可能让设备暂时无法操作，
> 请务必先确认 **ADB over TCP 可连接**（有线网络比 Wi-Fi 可靠），并准备好回退脚本。

## 快速开始

```bash
# 1. 连接设备（把 <电视IP> 换成电视实际地址）
export TV_HOST=<电视IP>:5555
adb connect "$TV_HOST"

# 2. 从源码构建（需要 JDK 17 + Android SDK 34 / build-tools 34）
bash scripts/tvhome/build.sh 1.5.1 151 tvhome-release
bash scripts/tvcleaner/build.sh 1.1.4 15 tvcleaner-release

# 3. 切换默认桌面 + 授权开机自启（细节见 docs/02、docs/03）
bash scripts/tvhome/enable-autostart.sh

# 4. 任何时候都能回退
bash scripts/tvhome/restore.sh check
bash scripts/tvhome/restore.sh home-back
bash scripts/tvhome/restore.sh tvhome
bash scripts/tvhome/restore.sh full
```

构建脚本会在本地自动生成调试用签名密钥与 ADB 密钥，**这些文件不入库、也不应提交**。

## 目录

```
README.md / README.en.md     项目说明（中文 / 英文）
LICENSE                      GPL-3.0（详见 NOTICE.md）
NOTICE.md       来源、署名与商标声明
SHA256SUMS.txt               发布文件校验和
docs/00-overview.md          总览与结论速查表
docs/01-diagnosis-method.md  取证方法（无 root 也能拿到崩溃栈）
docs/02-boot-autostart.md    第三方应用开机自启
docs/03-launcher.md          自建桌面设计与回退
docs/04-cleaner.md           清理器、停用基线与开机守卫
docs/05-dexopt-aot.md        ⚠️ 全量 AOT 为什么会弄崩视频播放
docs/06-media-stack.md       媒体栈与「为什么切不了软解」
docs/07-field-notes.md       可复用经验卡片
docs/08-command-cheatsheet.md 命令速查（全部使用占位符）
docs/09-limitations.md       证据等级、混杂因素与已知局限
src/tvhome/                 TVHome 源码（Gradle-free）
src/tvcleaner/              TVCleaner 源码（Gradle-free）
scripts/                    构建、密钥生成、自启授权、回退脚本
```

## 最值得先读的三条结论

1. **别对视频客户端做全量 AOT**（`cmd package compile -m speed -f <pkg>`）。
   实测全量编译状态下连续 3 次起播全部在广告阶段崩溃；回退到 `speed-profile` 后
   连续 3 次都完整播放并进入正片。详见 `docs/05`。
2. **第三方应用收不到 `BOOT_COMPLETED`**，持久化 Job 也不会在重启后恢复。
   可用通道是 `NotificationListenerService` + `Settings.Global.BOOT_COUNT` 门控。详见 `docs/02`。
3. **native 崩溃栈不止一条**。OMX 回调、GPU/Surface、音频消费、ART odex 各崩过一次，
   所以「清内存」不能根治。详见 `docs/01` 与 `docs/09`。

## 隐私声明

本仓库**不含**任何真实设备信息：局域网 IP、设备序列号 / MAC、固件版本号、
本机文件系统路径、用户名 / 主机名、ADB 密钥、签名密钥与指纹、
会员 / 登录态、观看记录、内部工单编号与逐秒操作流水，均已删除或替换为占位符。

源码中出现的包名与组件名是**公开的 Android 应用标识，仅作示例配置**，
请按你的机型自行替换。所有命令中的 `<电视IP>`、`<pkg>`、`<SDK_ROOT>` 均为占位符。

## 证据等级与免责

`docs/09-limitations.md` 明确标注了每条结论的证据强度。其中若干结论来自**同一开机周期内的
对照复现**，未做反转（A/B/A）验证，存在「重启清掉累积状态」与「配置变更」时间重合的混杂因素，
不能当作严格因果证明。

这些都是在一台设备上的经验，不是厂商文档。改系统配置前请自行评估风险，
作者不对设备变砖、数据丢失、账号或会员状态变化负责。

## 许可与致谢

代码部分遵循 **GPL-3.0**（含衍生自 GPL 项目的密钥处理代码，详见 `NOTICE.md`）；
文档允许在署名前提下自由引用。

- **CleanYourTV** —— 清单化清理思路与方法论的来源
- **WorkBuddy + DeepSeek + HY4（腾讯混元）** —— 分析、设计与编码协助
- **AndroidTvMouse** —— 上游 GPL 组件与发布范式参考
