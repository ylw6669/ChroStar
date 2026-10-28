# LSPosed 入口必须保留类名（框架通过接口实现发现模块）
-keep class io.github.ylw6669.chrostar.HookEntry { *; }

# Activity 由 manifest 引用，R8 自动保留；这里显式声明以防混淆
-keep class io.github.ylw6669.chrostar.MainActivity { *; }

# Xposed API 是 compileOnly，运行时由框架提供，不需要打进去
-dontwarn de.robv.android.xposed.**
-dontwarn org.lsposed.lspd.**

# 反射字符串引用的类都在目标应用(Chrome)中，不在本 APK 内，无需 keep
# miuix/compose 自带 consumer rules，AGP 会自动应用

# v2.1.0: Chrome152 符号表常量与 matches 反射被 R8 误裁
-keep class io.github.ylw6669.chrostar.Chrome152 { *; }
-keep class io.github.ylw6669.chrostar.Chrome153 { *; }

# v2.3.19: 配置链路类。
#   ConfigProvider 由 manifest 引用(provider), R8 通常自动保留; 显式 keep 以防 authority 相关行为被扰动。
#   PrefsStore / ConfigManager 被跨类调用, 显式 keep 防误裁。
-keep class io.github.ylw6669.chrostar.ConfigProvider { *; }
-keep class io.github.ylw6669.chrostar.PrefsStore { *; }
-keep class io.github.ylw6669.chrostar.ConfigManager { *; }

# v2.2.0: 新功能类(反射字段名/跨类调用)
# v2.3.13: 同名覆盖保留原文件功能已移除; 本类保留仅用于「下载自动打开类型扩展」策略判定
-keep class io.github.ylw6669.chrostar.OverwriteAndAutoOpen { *; }
-keep class io.github.ylw6669.chrostar.LocationAndDiagnostics { *; }
-keep class io.github.ylw6669.chrostar.CompatibilityResolver { *; }
# m13: R8 的 `-keep class X { *; }` 不覆盖嵌套类 —— CompatibilityResolver 的内部枚举/类($Cap 等)
# 被跨类引用且可能用 name() 反射, 须成对 keep(与 OfflineItemResolver 的 $* 策略对齐)。
-keep class io.github.ylw6669.chrostar.CompatibilityResolver$* { *; }

# v2.3.7(1B-1): OfflineItem 语义解析器 —— 全反射遍历字段 + 内部类 Result/Resolved 被跨类引用,
# 且 resolveRealPath 由本类反射调用, R8 可能误裁 → 显式 keep(含嵌套类)。
-keep class io.github.ylw6669.chrostar.OfflineItemResolver { *; }
-keep class io.github.ylw6669.chrostar.OfflineItemResolver$* { *; }
