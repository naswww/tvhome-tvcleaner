# 06 · 媒体栈：为什么「切软解」走不通

这一节记录所有**已排除**的路，目的是避免别人重复踩坑。结论是：
在这台无 root 的设备上，本地没有可用的软解开关。

## 系统媒体配置在哪

- 实际生效的是 `/vendor/etc/media_codecs*.xml`
  （`/system/etc/media_codec*` 在该设备上**不存在**）
- 其中同时注册了厂商硬解组件（如 `OMX.NVT.Video.Decoder.hevc` / `.avc`）
  与 Google 软解组件（`OMX.google.hevc.decoder` 等）
- **厂商组件排在软解组件前面**，默认走硬解
- `/vendor` 只读、无 root → 无法调整顺序或禁用硬解组件

运行时日志可确认实际路径：解码配置输出显示 `decode=H265 decode_mode=HW`。

## 应用侧没有开关

| 排查点 | 结果 |
| --- | --- |
| 用户可见设置页 | 「播放」分组只有跳过片头片尾 / 非 WiFi 自动播放 / 自动小窗；**没有解码方式** |
| APK 内字符串资源 | 确有 `播放器解码` / `优先硬解` / `优先软解` 等**遗留字符串**，但只出现在 `R$string`，数千个布局 XML 均未引用，现版 UI 未暴露 |
| 隐藏调试页 | `PlayerDebugActivity` 虽在 Manifest 中 exported，但 action / 显式组件 / deeplink 三种启动方式都打不开，焦点仍在设置页 |
| 本地配置文件 | 外置目录里没有可覆盖的解码配置；配置日志来自运行时在线下发 |

解码策略由**服务端在线配置**下发（键名涉及 `disableHardDecode`、`disableH265`、
`HEVC_whitelist`、`enable_*_soft_decode_retry`、`decode_mode` 等），
本地没有对应开关，改客户端配置文件的思路不成立。

## adb backup / restore 注入配置也不可行

- Manifest 里 `android:allowBackup=false`
- `/data/data/<pkg>/shared_prefs/` 对 shell 不可读（Permission denied）
- 因此「备份 → 改 SharedPreferences → 恢复」这条路被堵死

## 固件组件残缺（间接证据）

- 日志反复出现 `load <厂商 wrapper 库> Failed....`
- 检查该库的 `DT_NEEDED`，发现其中一个依赖 `.so` **整机缺失**，`/vendor/lib` 下也没有
- `/dev/ntkconverter` 设备节点存在，但 mediacodec 域有 SELinux `avc: denied` 审计
  （`permissive=1` 下仍记录）

这解释「加载失败」，但**不等于全部崩溃的根因**；无 root 也无法补齐缺失库。

## 两个临时开关的实测

| 开关 | 结果 |
| --- | --- |
| `setprop media.nvt.pq.enable 0`（原值 1） | 关闭厂商画质后处理，复现也稳、GPU/Surface 相关日志明显减少；但**编译状态回退后已 100% 稳，增量效果测不出来**；且非 persist 属性、重启即失效，还可能降画质 —— 不建议长期固化，留作 GPU/Surface 类栈复发时的临时开关 |
| 降低清晰度以拿 H264 流 | **未跑通**：崩溃发生在广告阶段，正片尚未开始，来不及切清晰度 |

## 改包强制软解为什么不推荐

1. 需要反编译改包 + 重签 → **必须卸载重装**，会丢登录态与会员状态
2. 官方包 `allowBackup=false`，无法保留私有数据
3. 32 位 CPU 上软解 HEVC 性能与稳定性**未必更好**
4. 崩溃栈有四条且互不相同，只改解码路径覆盖不了 GPU/Surface、音频、ART 三类

## 更实际的替代

用系统自带的**投屏接收端**：手机端选片并播放，电视只做投屏渲染，
绕开手机客户端在电视上的整套解码链路。零改动、零风险、会员体系不变。
