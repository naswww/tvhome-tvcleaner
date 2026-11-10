# 05 · 全量 AOT 为什么会弄崩视频播放

> # ⚠️ 禁止执行 `cmd package compile -m speed -f <pkg>`（全量 AOT）
> **不要对视频客户端执行全量 AOT。**
> 早期文档曾把它当作「优化手段」，这是**已被证伪的错误结论**。
> 终态必须保持 `speed-profile`。
> 若已执行过，回退后必须 `am force-stop <pkg>` 让旧 oat 进程退出，再验收。

## 对照复现数据

在完全相同的设备、相同剧集、相同起播方式下，只改编译状态：

| 编译状态 | 完整播放 / 尝试 | 表现 |
| --- | --- | --- |
| `speed`（全量 AOT） | **0 / 3** | 三次都在广告阶段 10–20 秒内 native 崩溃；其中一次引发完整内核重启 |
| `speed-profile` | **3 / 3** | 每次完整播放 90 秒、顺利过广告进正片、0 个 `Fatal signal`、无重启 |

判定「真的在播」不能只看 Activity 名，必须截图确认已进入正片、暂停按钮可用。

```bash
# 查看当前编译状态
adb shell dumpsys package dexopt | grep -A3 -F "[<pkg>]"

# 回退（这是修复动作，不是优化动作）
adb shell cmd package compile -m speed-profile -f <pkg>
adb shell am force-stop <pkg>

# 查询后台是否会把状态改回去
adb shell dumpsys jobscheduler | grep -i -A6 dexopt
```

实测 `speed-profile [reason=unknown]` 为手动设定且持久；系统里另一个 `status=speed`
属于系统预编译组件，与目标应用无关，不要误判。

## 为什么会这样

- 这类设备的 **boot oat/odex 与运行时 ART 存在一致性问题**，且是**系统级**的：
  大量包处于 `run-from-vdex-fallback`（oat 不可用，只能解释执行），
  `systemui` 反复出现 `Didn't find oat method index ...`，
  `system_server` 的 `art::System_arraycopy` SIGSEGV 甚至会触发重启。
- 全量 AOT 把所有方法钉进 `base.odex`，恰好把执行路径压到这批不健康的 odex 上。
  其中一条崩溃栈直接落在 `base.odex` 的某个 `run()` 方法里并跳到非法地址，
  是最直接的证据。
- 回退到 `speed-profile` 后大部分代码走解释器 / JIT，绕开了坏 odex。

## 与「AOT 优化有效」不矛盾

AOT 对**早期那条 Java 类加载 ANR**（`ClassLinker::DefineClass` →
`WaitForGcToComplete`）确实有帮助；这里否定的是它对**后续 native / ART 崩溃**的适用性。
两类故障机制不同，不要因为前者有效就默认后者也该全量编译。

## 一条重要的教训

「优化」和「改动」不是同义词。一次改造里往往同时改了五件事
（禁用预装包、换默认桌面、加通知监听常驻、加开机守卫、改编译状态），
当效果变差时，**按变量逐个拆开做对照复现**，而不是笼统归因为「桌面太重」或「内存不够」。

本例中前四个变量都被排除：被禁用的遥测应用不含任何 Service、
崩溃时可用内存充裕、开机守卫在播放期间不动作、窗口栈不参与解码。
唯一每次起播都生效、且直接改变代码执行方式的变量，就是编译状态。

## 混杂因素（必须如实说明）

三次成功复现都在**同一次开机周期内**，且紧接前一次完整内核重启之后，
因此「重启清掉了累积坏状态」与「回退编译状态」在时间上重合。
**未做 A/B/A 反转验证**（恢复 `speed` 再测），因为那需要故意让系统崩一次、
把设备留在较差状态。0/3 对 3/3 的差异加上落在 `base.odex` 的栈，
已足够停用 `speed`，但这不是严格的因果证明。详见 `docs/09-limitations.md`。
