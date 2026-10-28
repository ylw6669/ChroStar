package io.github.ylw6669.chrostar;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * v2.3.16 自定义下载目录重定向(145 / 152 通用)。
 *
 * 需求: 让用户可以自定义 Chrome 下载文件落盘目录(默认公共 Download)。
 *
 * 为什么用"系统 API 层"而非 Chrome 内部符号:
 *   LSPosed 模块注入 Chrome 进程后, 可直接 hook android.* 系统类。这些类在 145/152
 *   完全相同且不参与 R8 混淆, 因此本实现天然同时适配 145 与 152, 不需要任何 Chrome
 *   混淆短名 —— 与项目"按语义/签名而非类名判定"的原则一致。
 *   (参考: 夸克网盘下载重定向模块的进程内多轨 hook 思路。)
 *
 * 逆向实证(152 .82 jadx):
 *   - Chrome 在 Android 10+ 把用户可见下载交给 MediaStore.Downloads 管理:
 *     DownloadCollectionBridge / FileUtils.copyFileToDownloadsCollection 均走
 *     ContentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
 *     values 里的 "relative_path" 决定文件最终落盘目录。
 *   - DownloadDialogBridge.a(Profile) 读 PrefService 键 "download.default_directory"
 *     作为默认目录(仅此一处引用该键)。
 *
 * 三条轨道:
 *   [A] ContentResolver.insert(Uri,ContentValues[,Bundle])
 *       MediaStore Downloads 集合插入 → 覆盖/写入 relative_path 指向自定义目录。
 *       这是 Android 10+ 用户可见下载的关键路径。
 *   [B] Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)
 *       旧式路径入口(Android 9- 及部分调用方) → 返回自定义目录。
 *   [C] PrefService.getString("download.default_directory")
 *       Chrome 自身默认下载目录查询 → 返回自定义目录, 使对话框/重命名逻辑一致。
 *
 * 存储约束(scoped storage, 必须如实告知用户):
 *   模块代码运行在 Chrome 进程、继承 Chrome 的 uid 与存储权限。Android 11+ 下普通
 *   应用无法写任意共享目录, 只能写 MediaStore 集合(如 Download 及其子目录)或自身私有
 *   目录。因此:
 *     · 自定义目录位于公共 Download 之下(如 Download/chrome) —— 各版本均可靠可用。
 *     · 自定义目录位于别处(如 /storage/emulated/0/chrome) —— 真机实测(MPTEST, Android 15)
 *       证实 MediaProvider 会抛 "Primary directory <X> not allowed ... allowed directories
 *       are [Download]"，而 Chrome 自身**未声明** MANAGE_EXTERNAL_STORAGE，root 也无法为其
 *       授予(权限非 changeable) → 该路径**无法实现**。为免用户误配导致下载失败, 本模块对
 *       Download 之外的自定义目录一律不干预(fail-closed, 三条轨道全部跳过), 并在日志记录原因。
 *
 * 安全: 所有 hook 内部独立 try-catch; 自定义目录为空 = 完全不做任何干预(默认行为)。
 *   绝不吞异常且不设置结果(避免影响 Chrome 正常下载)。
 */
public final class DownloadDirRedirect {

    private static final String TAG = HookEntry.TAG;
    /** 内部存储根前缀, 用于把绝对路径规范化为 MediaStore relative_path */
    private static final String EMULATED_ROOT = "/storage/emulated/0/";
    /** Chrome 读取/使用的默认下载目录 pref 键(152 逆向实证) */
    private static final String PREF_DEFAULT_DIR = "download.default_directory";

    private DownloadDirRedirect() {
    }

    /** 读用户自定义下载目录(空 = 不启用重定向)。统一去首尾空白与尾斜杠。 */
    static String customDir() {
        String d = HookEntry.readPrefString(HookEntry.KEY_DOWNLOAD_DIR, "");
        if (d == null) {
            return "";
        }
        d = d.trim();
        while (d.length() > 1 && d.endsWith("/")) {
            d = d.substring(0, d.length() - 1);
        }
        if ("/".equals(d)) {
            return "";
        }
        return d;
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": [dl-dir] " + msg);
    }

    /** 安装全部轨道(在 145/152 各安装路径与降级路径统一调用)。 */
    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookContentResolverInsert(lpparam);
        hookEnvironmentApi(lpparam);
        hookPrefServiceGetString(lpparam);
        String d = customDir();
        log("download-dir redirect armed (customDir="
                + (d.isEmpty() ? "<default>" : d) + ")");
    }

    // ------------------------------------------------------------------
    // [A] MediaStore downloads 插入: 覆盖 relative_path
    // ------------------------------------------------------------------
    private static void hookContentResolverInsert(final XC_LoadPackage.LoadPackageParam lpparam) {
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String dir = customDir();
                    if (dir.isEmpty() || param.args == null || param.args.length < 2) {
                        return;
                    }
                    Object u = param.args[0];
                    Object v = param.args[1];
                    if (!(u instanceof Uri) || !(v instanceof ContentValues)) {
                        return;
                    }
                    if (!isDownloadsCollection((Uri) u)) {
                        return;
                    }
                    String rel = toRelative(dir);
                    if (rel.isEmpty()) {
                        return;
                    }
                    // fail-closed: MediaStore 要求 MediaColumns.RELATIVE_PATH 的顶层目录与媒体类型
                    //   相关(Downloads 集合必须落在 Download 下), 否则 MediaProvider 会拒绝插入 →
                    //   下载失败。自定义目录不在 Download 之下时一律不改写(保持 Chrome 原行为), 只记录日志。
                    //   这样绝不会因我们的重定向导致下载丢失。
                    if (!isUnderDownloads(rel)) {
                        log("skip MediaStore rewrite: custom dir '" + dir
                                + "' is not under Download (scoped storage: MediaProvider only"
                                + " allows Download; Chrome lacks MANAGE_EXTERNAL_STORAGE)");
                        return;
                    }
                    ContentValues values = (ContentValues) v;
                    String old = values.getAsString("relative_path");
                    if (rel.equals(old)) {
                        return;
                    }
                    values.put("relative_path", rel);
                    log("MediaStore.insert relative_path: " + old + " -> " + rel);
                } catch (Throwable t) {
                    log("insert hook err: " + t);
                }
            }
        };
        // API 30+: insert(Uri, ContentValues, Bundle)
        try {
            XposedHelpers.findAndHookMethod(
                    "android.content.ContentResolver", lpparam.classLoader,
                    "insert", Uri.class, ContentValues.class, android.os.Bundle.class, cb);
            log("hooked ContentResolver.insert(Uri,ContentValues,Bundle)");
        } catch (Throwable t) {
            log("insert(3-arg) hook failed: " + t);
        }
        // API < 30: insert(Uri, ContentValues)
        try {
            XposedHelpers.findAndHookMethod(
                    "android.content.ContentResolver", lpparam.classLoader,
                    "insert", Uri.class, ContentValues.class, cb);
            log("hooked ContentResolver.insert(Uri,ContentValues)");
        } catch (Throwable t) {
            log("insert(2-arg) hook failed: " + t);
        }
    }

    /** 仅处理 MediaStore Downloads 集合(external_primary/downloads 等)。 */
    private static boolean isDownloadsCollection(Uri uri) {
        if (uri == null) {
            return false;
        }
        String s = uri.toString();
        return s.startsWith("content://media/external/downloads")
                || s.startsWith("content://media/external_primary/downloads")
                || s.contains("/downloads");
    }

    /** 绝对路径 → MediaStore relative_path(去掉 /storage/emulated/0/ 或 /sdcard/ 前缀及首斜杠)。 */
    private static String toRelative(String abs) {
        String p = abs;
        if (p.startsWith(EMULATED_ROOT)) {
            p = p.substring(EMULATED_ROOT.length());
        } else if (p.startsWith("/sdcard/")) {
            p = p.substring("/sdcard/".length());
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    /** relative_path 是否位于 Download 顶层目录之下(MediaStore Downloads 集合的硬性要求)。 */
    private static boolean isUnderDownloads(String rel) {
        if (rel == null || rel.isEmpty()) {
            return false;
        }
        String r = rel;
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        return r.equalsIgnoreCase(Environment.DIRECTORY_DOWNLOADS)
                || r.toLowerCase().startsWith(Environment.DIRECTORY_DOWNLOADS.toLowerCase() + "/");
    }

    // ------------------------------------------------------------------
    // [B] Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)
    // ------------------------------------------------------------------
    private static void hookEnvironmentApi(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.os.Environment", lpparam.classLoader,
                    "getExternalStoragePublicDirectory", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String dir = customDir();
                                if (dir.isEmpty()) {
                                    return;
                                }
                                Object a0 = param.args[0];
                                if (!Environment.DIRECTORY_DOWNLOADS.equals(a0)) {
                                    return;
                                }
                                // v2.3.22: 仅当自定义目录位于 Download 之下才重定向; 否则不干预
                                //   (Chrome 无法写入 Download 以外目录, 避免误配致下载失败)。
                                if (!isUnderDownloads(toRelative(dir))) {
                                    log("skip Environment redirect: custom dir '" + dir
                                            + "' is not under Download");
                                    return;
                                }
                                Object r = param.getResult();
                                if (r instanceof File) {
                                    String cur = ((File) r).getAbsolutePath();
                                    if (!dir.equals(cur)) {
                                        param.setResult(new File(dir));
                                        log("Environment.DIRECTORY_DOWNLOADS: " + cur + " -> " + dir);
                                    }
                                }
                            } catch (Throwable t) {
                                log("Environment hook err: " + t);
                            }
                        }
                    });
            log("hooked Environment.getExternalStoragePublicDirectory");
        } catch (Throwable t) {
            log("Environment hook failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // [C] PrefService.getString("download.default_directory")
    //     仅此一处引用该键(152 实证), 用签名定位(String)->String, 不写死混淆方法名。
    // ------------------------------------------------------------------
    private static void hookPrefServiceGetString(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                    "org.chromium.components.prefs.PrefService", lpparam.classLoader);
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == String.class
                        && m.getReturnType() == String.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                log("PrefService.getString(String) not found — skip track C");
                return;
            }
            final String mName = target.getName();
            XposedHelpers.findAndHookMethod(cls, mName, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String dir = customDir();
                        if (dir.isEmpty()) {
                            return;
                        }
                        Object a0 = param.args[0];
                        if (!PREF_DEFAULT_DIR.equals(a0)) {
                            return;
                        }
                        // v2.3.22: 仅当自定义目录位于 Download 之下才重定向; 否则不干预。
                        if (!isUnderDownloads(toRelative(dir))) {
                            log("skip PrefService redirect: custom dir '" + dir
                                    + "' is not under Download");
                            return;
                        }
                        Object r = param.getResult();
                        if (!dir.equals(r)) {
                            param.setResult(dir);
                            log("PrefService." + mName + "(" + PREF_DEFAULT_DIR + ") -> " + dir);
                        }
                    } catch (Throwable t) {
                        log("PrefService hook err: " + t);
                    }
                }
            });
            log("hooked PrefService." + mName + "(String)");
        } catch (Throwable t) {
            log("PrefService hook failed: " + t);
        }
    }
}
