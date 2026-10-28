<!-- AGENTS.md — ChroStar 公共协作约束 -->

## 项目定位

ChroStar 是一个 LSPosed 模块，用于 hook Google Chrome，实现启动直开主页、新标签页替换主页和下载增强。仓库只包含源码、文档、CI 与构建配置，不包含发行版 APK。

## 硬边界

- 不主动推送 GitHub；只有用户明确要求“执行并推送”时才推送任务分支。
- 不自动合并 PR，不启用自动合并，不自动删除分支。
- 不修改 LSPosed 收录仓，除非用户明确要求。
- 不上传 APK、AAB、idsig、ZIP、7z、签名文件、Token、密码、本机路径配置或日志。
- 不删除本地历史交付物；`_交付物/` 仅存在于本地，不进入 Git。
- 不执行 `git reset --hard`、`git clean`、强制推送、镜像推送、`push --all` 或 `push --tags`。
- 不为消除崩溃而添加空 catch 或吞异常；先定位根因，再做最小修复。
- 不用宽松文本替换锚点改代码。任何脚本替换后必须读回目标片段并核对位置。
- 新建 Java/Kotlin 文件后，立即核对文件名大小写与类名一致。
- 为 A Chrome 版本新增的解析或兜底逻辑，默认不得作用于 B 版本。版本行为差异优先集中在版本特征/数据表中。
- 一个入口同时承载平台行为与用户主动行为时，只做单向替换，不额外创建第二个主页。

## 设备边界

默认只允许读取设备日志、推送并安装 APK；不得启动应用、点击、输入、force-stop、修改系统设置或 Chrome prefs。用户明确授予的一次性测试权限仅限当次、指定设备与指定版本，不得延续到后续任务。

## 分支与提交

- 稳定基线：`main`。
- 任务分支：`feat/*`、`fix/*`、`refactor/*`、`chore/*`、`docs/*`。
- 开发从最新 `origin/main` 创建任务分支；修改前检查 `git status --short`，不得覆盖用户未提交内容。
- 日常流程：阅读上下文 → 单一目标修改 → 构建/静态检查 → 审查 diff 与暂存清单 → 清晰提交 → 推送任务分支 → 创建 PR → 等待人工审核。
- 不把“构建成功”写成“真机验证成功”。两者必须分开报告。

## 构建

- Android 工程根：`module/`。
- Gradle：9.6.1，使用仓库内 Wrapper。
- 运行 Gradle 的 JDK：21；Java 源码语言级别：17。
- AGP：9.2.1；compile/min/target SDK：37/33/35。
- 常用命令：

```bash
cd module
./gradlew :app:assembleRelease
./gradlew :app:lintRelease
python3 ../verification/check_repository.py
```

- CI 只编译和检查，不上传 APK artifact，不创建 Release。
- `android.overridePathCheck=true` 是本仓库路径兼容设置，不得随手删除。

## 版本与验证纪律

- 当前源码版本以 `module/app/build.gradle` 为准。
- Chrome 145、152、153 保留已验证独立路径；154+ 使用公开锚点、DexKit 语义解析、运行时校验与按 versionCode 缓存。
- 新 Chrome 小版本优先验证自动解析链，不先新增硬编码短名。
- 能力解析失败时 fail-closed，只跳过受影响能力并记录，不得猜测调用方向。
- 每个功能变更必须声明影响的 Chrome 版本与未验证范围。
- 没有自动化行为测试时，明确写“没有可运行的自动化行为测试”，不得把 Gradle `NO-SOURCE` 当作测试通过。

## 安全与敏感信息

- 公开仓库中不得出现设备 IP、内网地址、用户目录、真实签名口令、Token、密钥、日志或截图。
- 涉及真实环境的状态、日志和逆向产物保存在本地私有目录，不进入 Git。
- CI 第三方 Action 必须固定到已核验的完整提交 SHA。
- 不打印环境变量或 Secret。

## 文档与状态

- `README.md`：公开项目说明与可复现构建。
- `AGENTS.md`：AI 协作与项目硬约束。
- `TASKS.md`：公开任务、验证边界与已知限制。
- `CHANGELOG.md`：版本源码变化；不把未复验的设备行为写成通过。
- 提交前运行仓库静态检查，并审查完整暂存清单。
