# 更新记录

## 2.0.0（Chrome 152 适配）

- 新增 Chrome 152 isolated split APK 支持。
- 延迟到 `chrome` split ClassLoader 可用后再安装 Chrome 核心 hook。
- 更新 Chrome 152 的标签模型、主页、新标签页、下载弹窗和关闭标签页历史清理路径。
- 保留签名仍兼容的 Chrome 145 fallback 路径。
- 已在 Chrome `152.0.7977.76` 验证：冷启动打开主页、清理关闭标签页历史，注入后 Chrome 保持稳定。

Chrome 152 中旧混淆入口（`c9o`、`zkg`、`je7`、`nze`）出现兼容性告警是预期现象，不会禁用新的适配路径。
