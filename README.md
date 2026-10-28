# ChroStar

ChroStar 是一个面向 Google Chrome 的 LSPosed 模块，提供启动直开主页、新标签页替换主页和下载体验增强。当前源码为 `2.6.7 / versionCode 163` 开发快照，仓库不包含发行版 APK。

## 功能

- 启动直开自定义主页，并避免恢复历史标签造成重复主页。
- 点击“+”新建标签时，将新标签页替换为自定义主页。
- 打开主页时清理“已关闭标签页”历史。
- Chrome 退出时关闭全部标签页，包含无痕标签。
- 绕过六类下载确认弹窗：危险文件、不安全下载、重复下载、策略警告、保存位置重命名、打开确认。
- APK 下载完成后自动打开安装器；支持扩展类型与自定义后缀自动打开。
- 下载完成横幅可替换为系统 Toast，并可隐藏翻译横幅。
- 设置页使用 miuix / Compose，并提供调试与能力状态页面。

## 组件关系

ChroStar 以 LSPosed 模块形式注入 Google Chrome 进程。模块设置页负责按 Chrome 大版本维护独立配置；Hook 侧通过 New XSharedPreferences、ContentProvider 兜底和旧 XSharedPreferences 末位通道读取配置，不直接修改 Chrome 的主页设置。

## 支持与验证边界

| Chrome | 适配策略 | 当前验证边界 |
|---|---|---|
| 145 | 独立旧路径 | v2.6.7 下载 Toast 单显待真机复验 |
| 152 | 独立指纹与单向建页改写 | 核心功能已完成真机回归 |
| 153 | 独立指纹与快速主页恢复 | 核心功能已验证；部分下载弹窗真实触发源未完全构造 |
| 154+ | 公开锚点 + DexKit 语义解析 + 运行时校验 + versionCode 缓存 | 154 核心功能已验证；155 仅离线结构验证 |

“构建成功”或“静态解析成功”不代表真机全部功能通过。每个版本的最终状态以用户真机回归为准。

## 目录结构

```text
.github/       CI 与 PR 模板
docs/          架构与历史问题摘要
module/        Android 工程（LSPosed 模块与 Compose 设置页）
verification/  静态仓库完整性检查
AGENTS.md      AI 协作与项目约束
TASKS.md       公开任务与验证边界
```

## 构建

要求：

- JDK 21（用于运行 Gradle）
- Android SDK Platform 37 与 Build Tools 37.0.0
- Gradle 9.6.1（仓库内 Wrapper）
- 源码 Java 语言级别 17

命令：

```bash
cd module
./gradlew :app:assembleRelease
./gradlew :app:lintRelease
python3 ../verification/check_repository.py
```

Windows 可使用 `gradlew.bat`。构建产物只留在 Gradle 输出目录，本仓库 CI 不上传 APK artifact。

## 静态检查与测试边界

`verification/check_repository.py` 只检查仓库完整性：禁止提交发布包、签名文件、本机路径配置、日志，核对 Wrapper、版本号与 CI 文件。它不是运行时行为测试。

当前没有可运行的自动化行为测试。Gradle 测试任务出现 `NO-SOURCE` 不代表测试通过。LSPosed 功能必须通过对应 Chrome 版本真机验证。

## 安装与发布说明

可从 [Releases](https://github.com/ylw6669/ChroStar/releases) 获取历史发行版 APK，但 Release 中的版本可能落后或领先于当前源码快照。本仓库源码不保证已发布，也不随 CI 自动产生发行版。

安装启用流程：

1. 安装与设备 Chrome 版本匹配的模块 APK。
2. 在 LSPosed 中启用 ChroStar，作用域勾选 Google Chrome。
3. 重启 Chrome 后查看 LSPosed 日志中的 `HomeLauncherLSP` 与 `engine=` 判定。

## 安全与隐私

- 仓库不提交签名材料、Token、日志、设备截图、私有状态与发布包。
- CI 只读取源码和公开依赖，不传入 Secret。
- 模块只作用于配置的 Chrome 作用域；调试日志可能包含用户设置的 URL 与下载文件名，导出时需自行注意。

## 已知限制

- Chrome 混淆短名会随版本漂移；新版本优先走结构解析，无法验证的能力会 fail-closed。
- 首次遇到新 Chrome versionCode 时，部分能力可能在后台扫描完成后的下一次启动完整生效。
- 自动适配不能保证所有未来 Chrome 结构变化免适配；公开锚点或语义结构变化时仍需人工分析。
- 未知版本的危险/策略弹窗 ID 无法确认时保留原生弹窗，不做猜测调用。

## 免责声明

本项目仅供学习研究，请勿用于非法用途。使用本模块产生的一切后果由使用者自行承担。
