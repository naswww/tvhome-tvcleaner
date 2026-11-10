# 03 · 自建桌面：设计与不丢桌面的前提

## 设计取舍

| 选择 | 理由 |
| --- | --- |
| 纯 Canvas 自定义 View | 不引入 RecyclerView / AndroidX，规避低端电视上的兼容与内存开销 |
| 无网络权限 | 桌面不需要联网，减少常驻服务与隐私面 |
| 图标**懒加载 + 降采样到实际绘制尺寸** | 一次性解码全部应用图标会让 PSS 明显上升；只在绘制时取 |
| `stateNotNeeded="true"` | 避免系统重建任务栈时把桌面状态搞乱 |
| 固定快捷栏用显式 `ComponentName` | 部分直播 / 系统设置组件没有 `LAUNCHER` 类目，扫描不出来 |
| 同时扫描 `LEANBACK_LAUNCHER` 与 `LAUNCHER` | 只收 LEANBACK 会漏掉手机端应用 |
| `SharedPreferences` 保存常用应用顺序 | 无需数据库，重启后保持 |

## Manifest 要点

```xml
<activity android:name=".MainActivity" android:exported="true"
    android:launchMode="singleTask" android:screenOrientation="landscape"
    android:stateNotNeeded="true"
    android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize|screenLayout|smallestScreenSize|uiMode|density">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

## 遥控器交互的两个实测坑

1. **长按不可靠**：`KeyEvent.getRepeatCount()` 与 `input keyevent --longpress` 在本机都不稳定。
   改用 `Handler.postDelayed(..., 550)` 自己判定长按，并把 `DPAD_CENTER` 的 down / up 分开处理，
   否则长按松手会误触发「启动应用」。
2. **HOME 键被框架拦截**：`input keyevent 3` 常无效果，且若系统里另有声明 HOME category 的
   应用，框架会误判「已经在 home」。测试时用显式 intent：

```bash
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

常用应用编辑：长按进入编辑 → 方向键换位（**不跨行交换**）→ OK 保存 → 返回还原；
菜单键移出 / 加入常用。顺序写进 `SharedPreferences`，`am force-stop` 后重启验证持久化。

## 不丢桌面的四条硬规矩

1. **先确认 ADB over TCP 可用**（优先有线以太网）。黑屏时这是唯一恢复通道。
2. **切默认桌面前后都验证**：

```bash
adb shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN
adb shell cmd package set-home-activity <pkg>/<Activity 全类名>
```

确认 `isDefault=true` 且焦点正确，再进行下一步。

3. **禁用原厂桌面之前，先确认回退脚本能把它重新启用**。本项目第一版回退脚本
   就因为原厂桌面已被停用而失效，修正后才做到 `home-back` 自动 `pm enable`。
4. 分批改、每批 `sync` + 重启验收，不要一次禁用几十个包。

## 回退脚本

`scripts/tvhome/restore.sh` 提供四种模式：

| 模式 | 作用 |
| --- | --- |
| `check` | 只读体检：默认 HOME、禁用包数、监听授权 |
| `home-back` | 切回原厂桌面（**会先重新启用它**） |
| `tvhome` | 切回自建桌面 |
| `full` | 恢复全部被停用的预装包 |

```bash
bash scripts/tvhome/restore.sh check
bash scripts/tvhome/restore.sh home-back
```

> 原厂桌面被停用时，「直接 set-home-activity 切回去」是无效的 —— 这是最容易踩的坑。
