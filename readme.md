# ChroStar

**LSPosed 模块**：让 Chrome 启动直开主页，并接管下载体验 —— Chrome 145.0.7632.218 实测通过。

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

混淆类名为 Chrome 145 根包短名（R8 产物），全部经 jadx + 真机日志验证：

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

配置跨进程读取：`XSharedPreferences` 失效（Android 11+），改用 exported `ConfigProvider` + 5 秒缓存，hook 侧优先走 ContentProvider。

## 安装

1. 安装 APK（Releases 下载）
2. LSPosed 中启用模块，勾选作用域 **Google Chrome**
3. 强制停止 Chrome 后重新打开

> 排错：LSPosed 日志过滤 `HomeLauncherLSP` 可看到全部 hook 安装与执行日志。

## 构建

```bash
# Android Studio 或命令行（AGP + JDK 17）
./gradlew assembleRelease
```

- minSdk 33 / targetSdk 35 / compose + miuix 0.9.x
- 签名：debug keystore 即可（模块签名与 Chrome 无关）

## 免责声明

本项目仅供学习研究，请勿用于非法用途。使用本模块产生的一切后果由使用者自行承担。

---

模块名 **ChroStar** · 作者 [星辰](https://www.coolapk.com/u/3110354)
