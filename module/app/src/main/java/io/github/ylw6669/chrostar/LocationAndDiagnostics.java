// ChroStar v2.2.0 — 功能 3+4: 下载位置 DONT_SHOW 语义增强 + 诊断导出
package io.github.ylw6669.chrostar;

import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LocationAndDiagnostics {

    private static final StringBuilder sHookReport = new StringBuilder();

    private LocationAndDiagnostics() {}

    static void report(String line) {
        synchronized (sHookReport) {
            sHookReport.append(line).append('\n');
        }
    }

    // ------------------------------------------------------------------
    // 功能 3: bypass_location 增强 — 源头 preference 层
    // Chrome 的"下载前询问保存位置"由 PrefService kPromptForDownload 控制;
    // hook PrefService.getBoolean(String) 当 key 命中时直接返回 false。
    // PrefService 是公开类(org.chromium.components.prefs.PrefService), 145/152 通用。
    // 注: 不落盘 Chrome 用户设置, 仅 hook 运行时读取, 关闭开关即恢复原行为。
    // ------------------------------------------------------------------
    public static void hookLocationPrefSource(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!HookEntry.readPrefBoolean(HookEntry.KEY_BYPASS_LOCATION, true)) {
            return;
        }
        try {
            Class<?> prefService = XposedHelpers.findClass(
                    "org.chromium.components.prefs.PrefService", lpparam.classLoader);
            // v2.2.1 修正: 实测 pref key 两个:
            //   手机版: "download.prompt_for_download" (boolean, 走 b(String))
            //   电脑式 fork: "download.prompt_for_download_android" (int, 走 c(String))
            // c(String) 方法体 = N.IJO(6, ptr, str) 返回 int; 0 = 不弹窗
            XC_MethodHook booleanHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object key = param.args.length > 0 ? param.args[0] : null;
                        if (key instanceof String
                                && "download.prompt_for_download".equals(key)) {
                            param.setResult(Boolean.FALSE);
                            report("location prompt(b) forced false");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XC_MethodHook intHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object key = param.args.length > 0 ? param.args[0] : null;
                        if (key instanceof String
                                && "download.prompt_for_download_android".equals(key)) {
                            param.setResult(Integer.valueOf(0));
                            report("location prompt_android(c) forced 0");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            int boolCount = 0;
            int intCount = 0;
            for (Method method : prefService.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || params[0] != String.class) continue;
                Class<?> result = method.getReturnType();
                if (result == boolean.class || result == Boolean.class) {
                    XposedBridge.hookMethod(method, booleanHook);
                    boolCount++;
                } else if (result == int.class || result == Integer.class) {
                    XposedBridge.hookMethod(method, intHook);
                    intCount++;
                }
            }
            XposedBridge.log(HookEntry.TAG + ": location prompt source bound structurally"
                    + " (boolean=" + boolCount + ", int=" + intCount + ")");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": location pref hook failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // 功能 4: 诊断导出
    // 由设置页调用(static 入口), 收集引擎/hook/能力信息写 JSON 到 Download 目录。
    // ------------------------------------------------------------------
    public static String exportDiagnostics(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"module\": \"ChroStar\",\n");
        sb.append("  \"moduleVersion\": \"").append(BuildConfig.VERSION_NAME).append("\",\n");
        sb.append("  \"engineVersion\": \"").append(HookEntry.engineVersion).append("\",\n");
        sb.append(CompatibilityResolver.diagnosticsJson(HookEntry.engineVersion)
                .replace("\n", "\n  ")).append(",\n");
        sb.append("  \"targetPackage\": \"").append(ctx.getPackageName()).append("\",\n");
        sb.append("  \"timestamp\": \"").append(
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\",\n");
        sb.append("  \"hooks\": [\n");
        synchronized (sHookReport) {
            String[] lines = sHookReport.toString().split("\n");
            for (int i = 0; i < lines.length; i++) {
                sb.append("    \"").append(lines[i].replace("\"", "'")).append('"');
                if (i < lines.length - 1) sb.append(',');
                sb.append('\n');
            }
        }
        sb.append("  ]\n}\n");
        try {
            File dir = new File("/sdcard/Download");
            if (!dir.exists()) dir = ctx.getExternalFilesDir(null);
            File out = new File(dir, "chrostar-diagnostics.json");
            FileWriter w = new FileWriter(out);
            w.write(sb.toString());
            w.close();
            XposedBridge.log(HookEntry.TAG + ": diagnostics exported -> " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": diagnostics export failed -> " + t);
            return null;
        }
    }

    // hook 挂载报告(供诊断): 各 hook 安装成功时由 HookEntry 调
    public static void noteHook(String name, boolean ok) {
        report((ok ? "[OK] " : "[FAIL] ") + name);
    }
}
