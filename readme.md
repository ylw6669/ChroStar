# ChroStar

**LSPosed 模块**：让 Chrome 启动直开主页，并接管下载体验。

当前已实测通过 Chrome `152.0.7977.76`，并保留 Chrome `145.0.7632.218` 的兼容路径。

> **关于该版本 Chrome**：此版本自 Aluminium OS（Android 17 电脑版）提取，已支持官方添加扩展。下载链接：[123 云盘](https://1639741.share.123pan.cn/123pan/GNzA-6VjO)

> Chrome 启动直开主页/新标签页、禁止恢复并清空历史标签页、去除下载文件有害警告、APK 文件下载完成自动安装。

## 功能

- **启动直开主页**：冷启动禁止 Chrome 恢复历史标签，自动打开主页（默认新标签页或 Chrome 设置的主页），多轮兜底确保单主页标签
- **新标签页(+) 开主页**：点击 +号新建标签时把 NTP 替换为主页
- **历史标签清理**：打开主页时自动删除「关闭的标签页」历史（全部时间段）
- **退出清理**：Chrome 退出时关闭全部标签页（含无痕），下次启动干净进入主页
- **下载弹窗全绕过**（各配独立开关）：文件可能有害 / 无法安全地下载 / 重复下载确认 / 策略警告 / 保存位置重命名 / 要打开此文件吗
- **APK 自动安装**：APK 下载完成后自动拉起系统安装器（多路径覆盖 + 原子去重）
- **横幅控制**：下载完成横幅转为系统 Toast；翻译横幅可隐藏（双重拦截）
- **深浅色跟随系统**：KernelSU 式系统默认开屏 + miuix 风格设置页 + AGSL 流光关于页

## 实现原理（Hook 点位）

混淆类名来自 Chrome R8 产物，全部经 jadx + 真机日志验证。Chrome 152 使用
isolated split，核心类位于 `split_chrome.apk`，模块会在
`SplitChromeApplication.createContextForSplit("chrome")` 返回真实 ClassLoader 后再安装核心 hook；
Chrome 145 的旧入口作为 fallback 保留：

| 功能 | Hook 点 |
|------|---------|
| 冷启动防恢复 | `oo4.c(String)` → `no-restore-state` 强制 true |
| 冷启动清理 | `ChromeTabbedActivity.onStart()` → 多轮兜底管线 |
| 标签模型记忆 | `TabModelJniBridge` 无参 int 方法（签名匹配） |
| +号开主页 | `l04.l(LoadUrlParams,…)`（ChromeTabCreator.createNewTab）NTP 替换 |
| 下载弹窗 ×6 | `DangerousDownloadDialogBridge` 等 6 个 `showDialog` → 直调 `J.N` 回调 |
| APK 自动安装 | `c9o.run` / `zkg.f` / `DownloadUtils.a(qe7)` / `onDownloadCompleted` 四路径 |
| 横幅 | `je7.d` 标记 + `nze.b/c` 拦截；`TranslateMessage.create/showMessage` 断翻译横幅 |
| 历史清理 | `J.N.VIOOOOOOO(0, …, {8}, …)` 删除「关闭的标签页」 |

### Chrome 152 验证结果

- `ChromeTabbedActivity.onStart()`、`qiq#getCount()`、`k3r.B*` 和
  `TabModelJniBridge.openNewTab` hook 成功。
- 六类下载安全弹窗 hook 成功，翻译横幅拦截成功。
- 冷启动成功打开主页，并执行关闭标签页历史清理。
- Chrome 进程在 hook 完成后保持运行，无 `FATAL EXCEPTION`。

旧版本专用的 `c9o`、`zkg`、`je7`、`nze` 入口在 Chrome 152 中可能打印找不到类或方法，
这些是兼容性 fallback 的诊断日志，不影响 Chrome 152 的新入口。

配置跨进程读取：`XSharedPreferences` 失效（Android 11+），改用 exported `ConfigProvider` + 5 秒缓存，hook 侧优先走 ContentProvider。

## 安装

1. 安装 APK（Releases 下载）
2. LSPosed 中启用模块，勾选作用域 **Google Chrome**
3. 强制停止 Chrome 后重新打开

> 排错：LSPosed 日志过滤 `HomeLauncherLSP` 可看到全部 hook 安装与执行日志。

## 构建

使用 Android Studio 打开 `module` 目录，或使用 AGP 9.0.0 + JDK 17 执行
`assembleDebug` / `assembleRelease`。仓库当前未提交 Gradle wrapper，命令行构建需要本机安装 Gradle。

- minSdk 33 / targetSdk 35 / compose + miuix 0.9.x
- 签名：debug keystore 即可（模块签名与 Chrome 无关）

当前验证 APK：`module/app/build/outputs/apk/debug/app-debug.apk`，版本 `2.0.0`（versionCode `76`）。

## 免责声明

本项目仅供学习研究，请勿用于非法用途。使用本模块产生的一切后果由使用者自行承担。

---

模块名 **ChroStar** · 作者 [星辰](https://www.coolapk.com/u/3110354)
