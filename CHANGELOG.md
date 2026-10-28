# Changelog

## [2.6.7] - 未发布开发快照 (versionCode 163)

### Chrome 145 下载完成 Toast

- 修复多个完成回调分别携带 content URI 与真实文件名时，同一下载先后弹出“下载文件”和真实文件名两次 Toast 的问题。
- 身份未解析时先布防 145 一次性横幅拦截并延迟 Toast，由后续携带真实文件名的专用回调唯一显示。
- 建立 content URI 与真实文件名的映射；不使用 MIME 粗去重，避免连续两个不同 APK 下载被误合并。
- 153/154 的现代横幅路径未改动。
- 待真机复验：Chrome 145 下载完成后仅显示一条真实文件名 Toast，且原生横幅被拦截。

## [2.6.6] - 未发布开发快照

- Chrome 145 不再安装 153+ 公开完成回调 Toast 兜底。
- 下载弹窗 JNI allow ID 按版本档案硬优先；未知版本危险/策略弹窗 fail-closed。
- 主设置页标题与 About 页支持滚动消失/恢复。
- 「适配 Chrome 版本」改为 Miuix OverlayDialog。

## [2.6.0] - 未发布开发快照

- 配置按 Chrome 大版本独立建档。
- 引入 Capability、CapsCache、DexKitResolver 与 ProfileTraits。
- 154+ 使用公开锚点结构解析、DexKit 语义查询、运行时验证和按 versionCode 持久缓存。
- Chrome 155 完成离线结构验证，尚待真机回归。
- 增加调试页、能力状态回传与日志尾部。

## [2.5.0] - 未发布开发快照

- 配置档案键格式调整为 `v{major}.{key}`。
- 保留旧全局键作为只读迁移来源，不删除用户历史配置。
- New XSharedPreferences、ConfigProvider 与旧 XSharedPreferences 降级链保持不变。

## [2.4.x] - 历史开发过程摘要

- Chrome 154 增加下载消息内容级拦截，并保留消息队列状态机，避免空子项导致 Chrome 动画 NPE。
- 收紧现代版本下载完成文案判定，避免 APK Toast 模式影响其他下载。
- Chrome 153 自定义主页保留并复用原生恢复快照，减少冷启动预览和延迟。
- Chrome 152 移除主动建页，改为原生建页单向 URL 改写，并增加只读主页门禁。
- Chrome 145 保留 2.0 已验证的旧路径与一次性横幅语义。

## [2.2.1] - 已发布基线

- 上一版公开源码基线，包含双通道提示配置、MIME-aware 自动打开与同名覆盖轮询。
