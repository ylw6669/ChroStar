# LSPosed 入口必须保留类名（框架通过接口实现发现模块）
-keep class com.stars.chrostar.HookEntry { *; }

# Activity 由 manifest 引用，R8 自动保留；这里显式声明以防混淆
-keep class com.stars.chrostar.MainActivity { *; }

# Xposed API 是 compileOnly，运行时由框架提供，不需要打进去
-dontwarn de.robv.android.xposed.**
-dontwarn org.lsposed.lspd.**

# 反射字符串引用的类都在目标应用(Chrome)中，不在本 APK 内，无需 keep
# miuix/compose 自带 consumer rules，AGP 会自动应用
