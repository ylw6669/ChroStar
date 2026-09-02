package com.stars.chrostar;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

/**
 * v1.8.0 下载弹窗分类绕过 —— 6 种弹窗各自独立开关, 默认全部开启(直接下载)。
 *
 * 逆向结论(全部为保留名类, jadx 反编译逐一确认, chromium 145.0.7632.218):
 *
 * 1) DangerousDownloadDialogBridge.showDialog(WindowAndroid, String, String, long, String, int)
 *    "文件可能有害"警告。允许 = N.VJO(124, nativePtr, guid)。开关 bypass_dangerous。
 * 2) InsecureDownloadDialogBridge.showDialog(WindowAndroid, String, long, long)
 *    "无法安全地下载"警告(不安全连接)。允许 = N.VJJZ(3, nativePtr, downloadId, true)。
 *    开关 bypass_insecure。
 * 3) DuplicateDownloadDialogBridge.showDialog(WindowAndroid, String, String, long, boolean, OtrProfileId, long)
 *    重复下载确认。允许 = N.VJJZ(2, nativePtr, downloadId, true)。开关 bypass_duplicate。
 * 4) PolicyWarningDownloadDialogBridge.showDialog(WindowAndroid, String, String)
 *    企业策略警告。允许 = N.VJO(129, nativePtr, guid)。取消 = VJO(130,...) 丢弃下载。
 *    开关 bypass_policy。
 * 5) DownloadDialogBridge.showDialog(WindowAndroid, long, int, int, String, Profile)
 *    保存位置/重命名对话框(download.prompt_for_download 开启时)。
 *    确认 = 实例方法 b(默认目录路径, false) → N.VJOZ(14, ptr, path, false)。
 *    开关 bypass_location。
 * 6) OpenDownloadDialogBridge.showDialog(Profile, String)
 *    "要打开此文件吗"。保留下载不打开 = N.VJOZ(15, ptr, path, false)。开关 bypass_open。
 *
 * J.N 的方法全部为 static:
 *   VJO(int, long, Object), VJJZ(int, long, long, boolean), VJOZ(int, long, String, boolean)。
 *
 * 统一策略: 拦截 showDialog → 各自开关关闭则放行原弹窗; 开启则反射调 native 回调
 * 模拟"允许/保留" → setResult(null) 跳过弹窗。回调失败也放行原逻辑, 不影响下载。
 */
public final class DownloadSafetyBypass {

    private static final String TAG = HookEntry.TAG;

    private DownloadSafetyBypass() {
    }

    /** 安装全部 hook(主进程) */
    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookDangerous(lpparam);
        hookInsecure(lpparam);
        hookDuplicate(lpparam);
        hookPolicyWarning(lpparam);
        hookLocationDialog(lpparam);
        hookOpenDialog(lpparam);
    }

    private static boolean bypassOff(String key) {
        return !HookEntry.readPrefBoolean(key, true);
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    private static void err(String where, Throwable t) {
        XposedBridge.log(TAG + ": [" + where + "] " + t);
    }

    /** 反射调用 J.N 的 static 方法 */
    private static Object callNative(Object bridge, String method,
            Class<?>[] types, Object[] args) throws Exception {
        Class<?> nClass = Class.forName(HookEntry.CLS_J_N, false,
                bridge.getClass().getClassLoader());
        Method m = nClass.getMethod(method, types);
        return m.invoke(null, args);
    }

    /** VJO(int, long, Object) */
    private static void allowVJO(Object bridge, int id, long ptr, Object arg) {
        try {
            callNative(bridge, "VJO",
                    new Class<?>[]{int.class, long.class, Object.class},
                    new Object[]{Integer.valueOf(id), Long.valueOf(ptr), arg});
        } catch (Throwable t) {
            err("VJO/" + id, t);
        }
    }

    /** VJJZ(int, long, long, boolean) */
    private static void allowVJJZ(Object bridge, int id, long ptr,
            long downloadId, boolean allow) {
        try {
            callNative(bridge, "VJJZ",
                    new Class<?>[]{int.class, long.class, long.class, boolean.class},
                    new Object[]{Integer.valueOf(id), Long.valueOf(ptr),
                            Long.valueOf(downloadId), Boolean.valueOf(allow)});
        } catch (Throwable t) {
            err("VJJZ/" + id, t);
        }
    }

    /** VJOZ(int, long, String, boolean) */
    private static void allowVJOZ(Object bridge, int id, long ptr,
            String path, boolean open) {
        try {
            callNative(bridge, "VJOZ",
                    new Class<?>[]{int.class, long.class, String.class, boolean.class},
                    new Object[]{Integer.valueOf(id), Long.valueOf(ptr), path,
                            Boolean.valueOf(open)});
        } catch (Throwable t) {
            err("VJOZ/" + id, t);
        }
    }

    private static long nativePtr(Object bridge) throws Exception {
        return XposedHelpers.getLongField(bridge, "a");
    }

    // ------------------------------------------------------------------
    // 1) 危险下载警告 ("文件可能有害") — 开关 bypass_dangerous
    // ------------------------------------------------------------------
    private static void hookDangerous(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> windowClass = XposedHelpers.findClass(
                    "org.chromium.ui.base.WindowAndroid", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "org.chromium.chrome.browser.download.DangerousDownloadDialogBridge",
                    lpparam.classLoader, "showDialog",
                    windowClass, String.class, String.class,
                    long.class, String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (bypassOff(HookEntry.KEY_BYPASS_DANGEROUS)) return;
                            try {
                                long ptr = nativePtr(param.thisObject);
                                String guid = (String) param.args[1];
                                allowVJO(param.thisObject, 124, ptr, guid);
                                param.setResult(null);
                                log("dangerous download bypassed (VJO 124)");
                            } catch (Throwable t) {
                                err("dangerous", t);
                            }
                        }
                    });
            log("hooked DangerousDownloadDialogBridge.showDialog");
        } catch (Throwable t) {
            err("hook dangerous", t);
        }
    }

    // ------------------------------------------------------------------
    // 2) 不安全下载警告 ("无法安全地下载") — 开关 bypass_insecure
    // ------------------------------------------------------------------
    private static void hookInsecure(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> windowClass = XposedHelpers.findClass(
                    "org.chromium.ui.base.WindowAndroid", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "org.chromium.chrome.browser.download.InsecureDownloadDialogBridge",
                    lpparam.classLoader, "showDialog",
                    windowClass, String.class, long.class, long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (bypassOff(HookEntry.KEY_BYPASS_INSECURE)) return;
                            try {
                                long ptr = nativePtr(param.thisObject);
                                long downloadId = (Long) param.args[3];
                                allowVJJZ(param.thisObject, 3, ptr, downloadId, true);
                                param.setResult(null);
                                log("insecure download bypassed (VJJZ 3, true)");
                            } catch (Throwable t) {
                                err("insecure", t);
                            }
                        }
                    });
            log("hooked InsecureDownloadDialogBridge.showDialog");
        } catch (Throwable t) {
            err("hook insecure", t);
        }
    }

    // ------------------------------------------------------------------
    // 3) 重复下载确认 — 开关 bypass_duplicate
    // ------------------------------------------------------------------
    private static void hookDuplicate(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> windowClass = XposedHelpers.findClass(
                    "org.chromium.ui.base.WindowAndroid", lpparam.classLoader);
            Class<?> otrClass = XposedHelpers.findClass(
                    "org.chromium.chrome.browser.profiles.OtrProfileId",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge",
                    lpparam.classLoader, "showDialog",
                    windowClass, String.class, String.class, long.class,
                    boolean.class, otrClass, long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (bypassOff(HookEntry.KEY_BYPASS_DUPLICATE)) return;
                            try {
                                long ptr = nativePtr(param.thisObject);
                                long downloadId = (Long) param.args[6];
                                allowVJJZ(param.thisObject, 2, ptr, downloadId, true);
                                param.setResult(null);
                                log("duplicate download bypassed (VJJZ 2, true)");
                            } catch (Throwable t) {
                                err("duplicate", t);
                            }
                        }
                    });
            log("hooked DuplicateDownloadDialogBridge.showDialog");
        } catch (Throwable t) {
            err("hook duplicate", t);
        }
    }

    // ------------------------------------------------------------------
    // 4) 策略警告下载 — 开关 bypass_policy
    // ------------------------------------------------------------------
    private static void hookPolicyWarning(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> windowClass = XposedHelpers.findClass(
                    "org.chromium.ui.base.WindowAndroid", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridge",
                    lpparam.classLoader, "showDialog",
                    windowClass, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (bypassOff(HookEntry.KEY_BYPASS_POLICY)) return;
                            try {
                                long ptr = nativePtr(param.thisObject);
                                String guid = (String) param.args[0];
                                allowVJO(param.thisObject, 129, ptr, guid);
                                param.setResult(null);
                                log("policy warning download bypassed (VJO 129)");
                            } catch (Throwable t) {
                                err("policy", t);
                            }
                        }
                    });
            log("hooked PolicyWarningDownloadDialogBridge.showDialog");
        } catch (Throwable t) {
            err("hook policy", t);
        }
    }

    // ------------------------------------------------------------------
    // 5) 保存位置/重命名对话框 — 开关 bypass_location
    // ------------------------------------------------------------------
    private static void hookLocationDialog(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> windowClass = XposedHelpers.findClass(
                    "org.chromium.ui.base.WindowAndroid", lpparam.classLoader);
            Class<?> profileClass = XposedHelpers.findClass(
                    "org.chromium.chrome.browser.profiles.Profile",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "org.chromium.chrome.browser.download.DownloadDialogBridge",
                    lpparam.classLoader, "showDialog",
                    windowClass, long.class, int.class, int.class,
                    String.class, profileClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (bypassOff(HookEntry.KEY_BYPASS_LOCATION)) return;
                            try {
                                // 确认路径: 实例方法 b(默认目录, false) → VJOZ(14,...)
                                Object profile = param.args[5];
                                Object bridge = param.thisObject;
                                Class<?> cls = bridge.getClass();
                                String defDir = "";
                                try {
                                    // 静态方法 a(Profile) 返回默认下载目录;
                                    // Profile 是接口, 从类加载器按名解析
                                    Class<?> pType = Class.forName(
                                            "org.chromium.chrome.browser.profiles.Profile",
                                            false, cls.getClassLoader());
                                    Method a = cls.getMethod("a", pType);
                                    Object dir = a.invoke(null, profile);
                                    if (dir instanceof String) defDir = (String) dir;
                                } catch (Throwable ignore) {
                                    // 默认目录取不到时用空串, native 侧通常接受
                                }
                                Method b = cls.getMethod("b", String.class, boolean.class);
                                b.invoke(bridge, defDir, Boolean.FALSE);
                                param.setResult(null);
                                log("location dialog bypassed (b/default dir)");
                            } catch (Throwable t) {
                                err("location", t);
                            }
                        }
                    });
            log("hooked DownloadDialogBridge.showDialog");
        } catch (Throwable t) {
            err("hook location", t);
        }
    }

    // ------------------------------------------------------------------
    // 6) 打开方式询问 ("要打开此文件吗") — 开关 bypass_open
    // ------------------------------------------------------------------
    private static void hookOpenDialog(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> profileClass = XposedHelpers.findClass(
                    "org.chromium.chrome.browser.profiles.Profile",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "org.chromium.chrome.browser.download.OpenDownloadDialogBridge",
                    lpparam.classLoader, "showDialog",
                    profileClass, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (bypassOff(HookEntry.KEY_BYPASS_OPEN)) return;
                            try {
                                long ptr = nativePtr(param.thisObject);
                                String path = (String) param.args[1];
                                allowVJOZ(param.thisObject, 15, ptr, path, false);
                                param.setResult(null);
                                log("open dialog bypassed (VJOZ 15, false)");
                            } catch (Throwable t) {
                                err("open", t);
                            }
                        }
                    });
            log("hooked OpenDownloadDialogBridge.showDialog");
        } catch (Throwable t) {
            err("hook open", t);
        }
    }
}
