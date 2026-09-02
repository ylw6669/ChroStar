package com.stars.chrostar;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * LSPosed 模块入口 ChroStar v1.15.0。
 *
 * 机制(逆向依据见 DELIVERY 分析报告, 混淆类名为根包短名):
 *  1) 冷启动防恢复: hook 根包 oo4(ChromeCommandLineFlags).c -> "no-restore-state" 返回 true
 *  2) 冷启动兜底: onStart -> 2s 起 4 轮复查, 确保单主页标签(HomeCleaner.coldStartCleanup)
 *  3) 模型记忆: TabModelJniBridge 无参 int 方法(签名匹配) -> 记模型实例
 *  4) 新标签页(+)/主页: l04.l + TabModelJniBridge.openNewTab 的 NTP 替换为主页 URL
 *  5) 下载六类弹窗绕过(DownloadSafetyBypass) + APK 自动安装(AutoInstallApk 四路径)
 *  6) 横幅控制(BannerController): 下载完成横幅 -> Toast; 翻译横幅双重拦截
 *  7) 退出清理: 关全部标签(含无痕) + 原生删除「关闭的标签页」历史
 *
 * 配置读取(v1.15.0): ConfigProvider(5s 缓存)优先, XSharedPreferences 兜底。
 * 版本号单源: 日志/关于页均引用 BuildConfig.VERSION_NAME。
 */
public class HookEntry implements IXposedHookLoadPackage {

    public static final String TAG = "HomeLauncherLSP";
    /** 热路径诊断日志开关(编译期常量, 发布版关闭) */
    static final boolean DEBUG = false;
    public static final String PREFS_NAME = "com.stars.chrostar_preferences";
    /** Chrome native 桥类(J.N), DownloadSafetyBypass/HomeCleaner 共用 */
    public static final String CLS_J_N = "J.N";

    public static final String KEY_CLEAR_TABS = "clear_tabs";
    public static final String KEY_BYPASS_DANGEROUS = "bypass_dangerous";
    public static final String KEY_BYPASS_INSECURE = "bypass_insecure";
    public static final String KEY_BYPASS_DUPLICATE = "bypass_duplicate";
    public static final String KEY_BYPASS_POLICY = "bypass_policy";
    public static final String KEY_BYPASS_LOCATION = "bypass_location";
    public static final String KEY_BYPASS_OPEN = "bypass_open";
    public static final String KEY_CLEAN_START = "clean_start";
    public static final String KEY_AUTO_INSTALL_APK = "auto_install_apk";
    public static final String KEY_BANNER_APK_TOAST = "banner_apk_toast";
    public static final String KEY_BANNER_ALL_TOAST = "banner_all_toast";
    public static final String KEY_HIDE_TRANSLATE_BANNER = "hide_translate_banner";
    public static final String KEY_NEWTAB_HOME = "newtab_home";

    private static final String CLS_CHROME_TABBED_ACTIVITY =
            "org.chromium.chrome.browser.ChromeTabbedActivity";
    private static final String CLS_TAB_MODEL_JNI_BRIDGE =
            "org.chromium.chrome.browser.tabmodel.TabModelJniBridge";

    private static final Set<Activity> sColdHandled =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!isChromePackage(lpparam)) {
            return;
        }
        if (lpparam.processName != null && !lpparam.processName.equals(lpparam.packageName)) {
            return;
        }
        hookCommandLineFlags(lpparam);
        hookTabModelMemory(lpparam);
        hookOnStart(lpparam);
        HomeCleaner.hookOpenNewTab(lpparam);
        DownloadSafetyBypass.hook(lpparam);
        AutoInstallApk.hook(lpparam);
        BannerController.hook(lpparam);
        XposedBridge.log(TAG + ": v" + BuildConfig.VERSION_NAME + " hooks installed for " + lpparam.packageName
                + " (process " + lpparam.processName + ")");
    }

    private static boolean isChromePackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null) {
            return false;
        }
        if ("com.android.chrome".equals(pkg)) {
            return true;
        }
        if (pkg.toLowerCase().contains("chrome")) {
            try {
                lpparam.classLoader.loadClass(CLS_CHROME_TABBED_ACTIVITY);
                XposedBridge.log(TAG + ": matched chrome-family package: " + pkg);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /** Hook 1: 根包 oo4(ChromeCommandLineFlags).c(String)=hasSwitch → 强制 no-restore-state */
    private static void hookCommandLineFlags(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "oo4",
                    lpparam.classLoader,
                    "c",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readPrefBoolean(KEY_CLEAN_START, true)) {
                                    return; // 用户关闭了冷启动防恢复
                                }
                                Object arg = param.args[0];
                                if ("no-restore-state".equals(arg)) {
                                    param.setResult(Boolean.TRUE);
                                    XposedBridge.log(TAG + ": no-restore-state switch forced ON");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": oo4.c hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked oo4.c(String) [root-pkg commandline flags]");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook oo4.c(String) failed -> " + t);
        }
    }

    /** Hook 2: TabModelJniBridge 无参返回 int 方法(签名匹配) → 模型记忆 */
    private static void hookTabModelMemory(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> bridge = Class.forName(CLS_TAB_MODEL_JNI_BRIDGE, false, lpparam.classLoader);
            Method target = null;
            for (Method m : XposedHelpers.findMethodsByExactParameters(bridge, int.class)) {
                if (m.getParameterTypes().length == 0 && !m.getName().equals("getCount")) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                try {
                    target = bridge.getDeclaredMethod("getCount");
                } catch (Throwable ignored) {
                }
            }
            if (target == null) {
                XposedBridge.log(TAG + ": getCount-like method not found on TabModelJniBridge");
                return;
            }
            final Method finalTarget = target;
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        HomeCleaner.rememberModel(param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": rememberModel error -> " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked TabModelJniBridge#" + target.getName()
                    + "() [model memory]");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook TabModel memory failed -> " + t);
        }
    }

    /** Hook 3: ChromeTabbedActivity.onStart() → 冷启动兜底管线 */
    private static void hookOnStart(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_CHROME_TABBED_ACTIVITY,
                    lpparam.classLoader,
                    "onStart",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                                        final Activity activity = (Activity) param.thisObject;
                                if (activity == null || activity.isFinishing()) {
                                    return;
                                }
                                // 冷启动防恢复关闭时, 保留 Chrome 原生行为(恢复提示+标签恢复)
                                if (!readPrefBoolean(KEY_CLEAN_START, true)) {
                                    return;
                                }
                                if (!sColdHandled.add(activity)) {
                                    return;
                                }
                                Intent intent = activity.getIntent();
                                if (intent == null
                                        || !Intent.ACTION_MAIN.equals(intent.getAction())
                                        || intent.getData() != null) {
                                    return;
                                }
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (activity.isFinishing() || activity.isDestroyed()) {
                                            return;
                                        }
                                        HomeCleaner.coldStartCleanup(activity, 0);
                                    }
                                }, 2000L);
                                XposedBridge.log(TAG + ": cold start(MAIN) detected, "
                                        + "cleanup scheduled in 2s");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": onStart after-hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked ChromeTabbedActivity.onStart()");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ChromeTabbedActivity.onStart() failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // v1.10.4: 配置读取 —— XSharedPreferences 优先, ContentProvider 兜底。
    // XSharedPreferences 在 Android 11+ 跨进程读取模块 prefs 失败(无文件权限),
    // 因此通过 ConfigProvider(exported ContentProvider) 经 ContentResolver 读取,
    // 带 5 秒缓存避免频繁拉起模块进程。
    // ------------------------------------------------------------------
    private static long sCfgCacheTime;
    private static java.util.Map<String, String> sCfgCache;

    static boolean readPrefBoolean(String key, boolean defValue) {
        String v = readPref(key);
        return v == null ? defValue : "true".equalsIgnoreCase(v);
    }

    static int readPrefInt(String key, int defValue) {
        String v = readPref(key);
        if (v == null) return defValue;
        try {
            return Integer.parseInt(v);
        } catch (Throwable t) {
            return defValue;
        }
    }

    static String readPrefString(String key, String defValue) {
        String v = readPref(key);
        return v == null ? defValue : v;
    }

    private static String readPref(String key) {
        // v1.15.0: ConfigProvider(带 5 秒缓存)优先 —— Android 11+ XSharedPreferences 跨进程
        // 必失败(无文件权限), 不再每次白跑文件 I/O; XSP 降为 CP 不可用时的兜底。
        try {
            long now = System.currentTimeMillis();
            if (sCfgCache != null && now - sCfgCacheTime < 5000L) {
                String cv = sCfgCache.get(key);
                if (DEBUG) XposedBridge.log(TAG + ": pref CP-cache key=" + key + " -> " + cv);
                return cv;
            }
            android.content.Context ctx = getAppContext(null);
            if (ctx != null) {
                android.database.Cursor cur = ctx.getContentResolver().query(
                        android.net.Uri.parse("content://"
                                + ConfigProvider.AUTHORITY + "/config"),
                        null, null, null, null);
                if (cur != null) {
                    try {
                        java.util.Map<String, String> map =
                                new java.util.HashMap<String, String>();
                        while (cur.moveToNext()) {
                            String k = cur.getString(0);
                            String v = cur.getString(1);
                            if (k != null && v != null) {
                                map.put(k, v);
                            }
                        }
                        sCfgCache = map;
                        sCfgCacheTime = now;
                        if (DEBUG) XposedBridge.log(TAG + ": pref CP key=" + key + " -> "
                                + map.get(key) + " (total=" + map.size() + ")");
                        return map.get(key);
                    } finally {
                        cur.close();
                    }
                }
            }
        } catch (Throwable t) {
            if (DEBUG) XposedBridge.log(TAG + ": pref CP key=" + key + " err -> " + t);
        }
        // 兜底: XSharedPreferences(旧环境/CP 不可用时)
        try {
            XSharedPreferences prefs = new XSharedPreferences("com.stars.chrostar");
            prefs.reload();
            if (prefs.contains(key)) {
                Object v = prefs.getAll().get(key);
                if (v != null) {
                    return String.valueOf(v);
                }
            }
        } catch (Throwable t) {
            if (DEBUG) XposedBridge.log(TAG + ": pref XSP key=" + key + " err -> " + t);
        }
        return null;
    }

    /** 获取应用上下文: Chrome 侧 ApplicationStatus(反射)优先, ActivityThread.currentApplication 兜底 */
    static android.content.Context getAppContext(ClassLoader cl) {
        try {
            if (cl != null) {
                Class<?> appStatus = Class.forName(
                        "org.chromium.base.ApplicationStatus", false, cl);
                Object activity = XposedHelpers.getStaticObjectField(appStatus, "d");
                if (activity instanceof android.content.Context) {
                    return (android.content.Context) activity;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                return (android.content.Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** APK 判断: MIME 或文件名后缀(收拢自 AutoInstallApk/BannerController) */
    static boolean isApk(String mime, String name) {
        if (mime != null && mime.contains("package-archive")) {
            return true;
        }
        return name != null && name.toLowerCase().endsWith(".apk");
    }
}
