# 第三方来源、署名与商标声明

> # ⚠️ 禁止执行 `cmd package compile -m speed -f <pkg>`（全量 AOT）
> 该操作会让视频客户端在起播阶段立刻崩溃；**终态必须保持 `speed-profile`**。
> 本项目早期文档曾把它写成「优化手段」，该结论已作废，详见 `docs/05-dexopt-aot.md`。

## 一、项目来源与开发声明

- 本项目的清单化清理思路、预装包清单方法论，源自开源项目 **CleanYourTV**
  （其 `README.zh-CN.md` 与 `PROMPT.md`）。本仓库在其思路基础上做了二次开发与
  面向特定平台的适配；相关方法论已在 `docs/04-cleaner.md` 中注明。
  上游许可与署名要求请以其官方发布页为准。
- 本项目针对 **海信电视 32 位 VIDAA 系统** 做适配。
- 分析、方案设计与代码实现由 **WorkBuddy** 调用 **DeepSeek** 与 **HY4（腾讯混元）** 协助完成。

## 二、代码衍生与许可

- `src/tvcleaner/com/tv/memcleaner/AdbKey.java` 与 `scripts/tvcleaner/genkey.sh`、
  `scripts/tvcleaner/adbkey_pub.py` 衍生自 GPL-3.0 项目 **AndroidTvMouse** 中的同名实现，
  文件头已保留原始归属声明。**GPL-3.0 衍生代码不得降级为宽松许可**，
  因此本仓库整体采用 GPL-3.0（全文见 `LICENSE`）。
- `LICENSE` 为 GPL-3.0 全文，与该上游项目保持一致。
- 文档部分（`docs/`、`README*`）允许在署名前提下自由引用；若与代码一同分发，
  整体仍受 GPL-3.0 约束。

## 三、商标与专有内容

- **海信 / VIDAA / 优酷 / CleanYourTV / WorkBuddy / DeepSeek / 腾讯混元** 等名称
  均为各自权利人的商标或产品名称，本仓库仅作描述性与署名用途，不代表背书或关联。
- 本仓库**不包含**任何第三方应用的 APK、反编译产物、dex、so 库、资源表、
  UI 截图、受版权保护的视频画面或设备备份。
- 文档中引用的崩溃栈函数名、系统库名、配置键名，来自运行时日志，
  仅用于说明诊断方法；不含任何可还原第三方专有代码的内容。
- 文中出现的包名与组件名是公开的 Android 应用标识，作为**示例配置**使用。

## 四、发布内容边界

本仓库由独立发布目录经白名单拷贝生成，只包含 Markdown 文档、Java/XML 源码与 Shell 脚本。
以下内容**从未进入发布目录**：

- 设备状态导出、bugreport、logcat 缓冲、tombstone / dropbox 原始导出
- 屏幕截图、录屏帧、内存 / 显示 raw dump
- 编译产物（`*.apk` / `*.dex` / `*.class` / `*.idsig` / `build/`）
- 签名密钥（`.keystore`）、ADB 私钥（`adbkey`）、本地工具链、备份目录
- 本地工作记忆与内部协作报告

所有命令模板使用 `<电视IP>`、`<pkg>`、`<SDK_ROOT>` 等占位符，不含真实网络地址或路径。

## 五、无担保

本仓库按「现状」提供，不提供任何明示或暗示担保。修改默认桌面、停用预装包、
更改编译状态均可能导致设备不可用或数据丢失，请自行评估并保留回退手段。
