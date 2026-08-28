package com.android.chrostar;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.File;
import java.lang.reflect.Method;

/**
 * APK 自动安装(v1.8.2 引入, v1.15.0 工具收拢) —— 下载完成后若为 APK 文件, 自动打开系统安装器。
 *
 * 只匹配 APK 文件(MIME application/vnd.android.package-archive 或 .apk 后缀),
 * 其他文件一律不自动打开。
 *
 * 用户日志确认的根因(v1.8.1):
 *  - zkg.f hook 已生效, APK 也被识别, 但 OfflineItem.P 只给了裸文件名
 *    (如 FA4TB_26081901_arm64-v8a.apk), 没有目录前缀。
 *  - DownloadUtils.e(裸文件名) 里 new File("xxx.apk").getAbsolutePath() = "/xxx.apk"
 *    → t49.a() 生成 content uri 失败 → 退回 Uri.fromFile = file:///xxx.apk
 *    → 启动安装器时抛 android.os.FileUriExposedException:
 *      "file:///FA4TB_26081901_arm64-v8a.apk exposed beyond app through Intent.getData()"
 *
 * v1.8.2 修复:
 *  1. zkg.f 完成回调: 裸文件名 → 先解析出真实绝对路径(公共 Download /
 *     Chrome 私有下载目录 / PathUtils.getDownloadsDirectory()), 再走
 *     DownloadUtils.e(绝对路径) → 得到 content://com.android.chrome.DownloadFileProvider/...
 *     → ACTION_VIEW + package-archive 拉起系统安装器。
 *  2. 新增 hook DownloadUtils.a(qe7): Chrome 所有"打开下载项"的入口(通知上的
 *     打开按钮、下载页点文件、下载完自动打开)都会经过这里; 只要目标是 APK
 *     就自动转成安装 Intent 并阻止原逻辑, 其他文件完全放行。
 *  3. 保留 qgg(系统 DownloadManager) + onDownloadCompleted(内嵌) 兜底。
 */
public final class AutoInstallApk {

    private static final String CLS_DOWNLOAD_CONTROLLER =
            "org.chromium.chrome.browser.download.DownloadController";
    private static final String CLS_DOWNLOAD_UTILS =
            "org.chromium.chrome.browser.download.DownloadUtils";
    private static final String CLS_OFFLINE_ITEM =
            "org.chromium.components.offline_items_collection.OfflineItem";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    /** 防重复: 同一文件只触发一次安装(v1.9.6: 原子去重) */
    private static String sLastAutoInstalled;
    private static final Object sInstallLock = new Object();

    private AutoInstallApk() {
    }

    /** 安装 hook(主进程) */
    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookCompletedNotification(lpparam);
        hookOfflineItemCompletePath(lpparam);
        hookOpenDownloadEntry(lpparam);
        hookInlineDownloadPath(lpparam);
    }

    // ------------------------------------------------------------------
    // 主路径 0(最新): c9o.run() —— "文件已下载完毕"通知的更新执行点。
    // c9o 是 Runnable, 字段 O=e9o(DownloadManagerService观察者宿主), P=d9o(事件)。
    // d9o 字段: a=事件类型(1=完成), b=DownloadInfo, d=时间, e/f=标志, g=int。
    // DownloadInfo: c=MIME, e=路径, g=文件名。
    // 下载完成时 Chrome 必执行 c9o.run() 且 i==1 → 更新完成通知,
    // 这就是用户看到的"文件已下载完毕"通知 —— 最准确的完成时机。
    // ------------------------------------------------------------------
    private static void hookCompletedNotification(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> c9o = XposedHelpers.findClass("c9o", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(c9o, "run",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!HookEntry.readPrefBoolean(
                                        HookEntry.KEY_AUTO_INSTALL_APK, true)) {
                                    return;
                                }
                                Object d9o = XposedHelpers.getObjectField(param.thisObject, "P");
                                if (d9o == null) return;
                                int event = XposedHelpers.getIntField(d9o, "a");
                                if (event != 1) return;  // 1 = 下载完成
                                Object info = XposedHelpers.getObjectField(d9o, "b");
                                if (info == null) return;
                                ClassLoader cl = info.getClass().getClassLoader();
                                String mime = (String) XposedHelpers.getObjectField(info, "c");
                                String path = (String) XposedHelpers.getObjectField(info, "e");
                                String name = (String) XposedHelpers.getObjectField(info, "g");
                                if (!HookEntry.isApk(mime, name)) return;
                                final String fileName = (path != null && path.contains("/"))
                                        ? new File(path).getName() : path;
                                final String finalName = (name != null && !name.isEmpty())
                                        ? name : fileName;
                                if (finalName == null || finalName.isEmpty()) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": apk completed (c9o) but no file name, skip");
                                    return;
                                }
                                final ClassLoader fcl = cl;
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        waitAndInstall(finalName, fcl);
                                    }
                                });
                                t.setDaemon(true);
                                t.start();
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": auto-install apk (c9o path) error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked c9o.run (download-complete notification, auto-install apk)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook c9o.run failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // 主路径 1: 内置下载完成 → zkg.f(OfflineItem, OfflineItemVisuals)
    // 下载完成后"文件已下载完毕"通知由此产生, 自动触发安装。
    // ------------------------------------------------------------------
    private static void hookOfflineItemCompletePath(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> zkg = XposedHelpers.findClass("zkg", lpparam.classLoader);
            Class<?> offlineItemCls = XposedHelpers.findClass(CLS_OFFLINE_ITEM,
                    lpparam.classLoader);
            Class<?> visualsCls = XposedHelpers.findClass(
                    "org.chromium.components.offline_items_collection.OfflineItemVisuals",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(zkg, "f", offlineItemCls, visualsCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!HookEntry.readPrefBoolean(
                                        HookEntry.KEY_AUTO_INSTALL_APK, true)) {
                                    return;
                                }
                                Object item = param.args[0];
                                if (item == null) return;
                                // 完成态: Chromium OfflineItem 枚举 COMPLETE 在不同版本/混淆中
                                // 可能映射为 1 或 2, 两个都接受, 由 isApk 过滤避免误伤。
                                int state = XposedHelpers.getIntField(item, "m0");
                                if (state != 1 && state != 2) return;
                                String mime = (String) XposedHelpers.getObjectField(item, "f0");
                                String name = (String) XposedHelpers.getObjectField(item, "e0");
                                if (!HookEntry.isApk(mime, name)) return;
                                String path = (String) XposedHelpers.getObjectField(item, "P");
                                if (path == null) path = "";
                                // 关键修复: OfflineItem.P 只有正式文件名, 且此时文件可能还在
                                // 私有临时目录(.com.google.Chrome.XXXX), 尚未复制到公共
                                // /sdcard/Download。因此改为后台轮询等待文件出现在公共目录。
                                final String fileName = (path.contains("/")
                                        ? new File(path).getName() : path);
                                final String finalName = (name != null && !name.isEmpty())
                                        ? name : fileName;
                                if (finalName == null || finalName.isEmpty()) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": apk download completed but no file name, skip");
                                    return;
                                }
                                final ClassLoader fcl = item.getClass().getClassLoader();
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        waitAndInstall(finalName, fcl);
                                    }
                                });
                                t.setDaemon(true);
                                t.start();
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": auto-install apk (zkg.f path) error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked zkg.f (built-in download complete, auto-install apk)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook zkg.f failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // 主路径 2: Chrome 所有"打开下载项"入口 → DownloadUtils.a(qe7)
    // 覆盖: 通知"打开"按钮 / 下载页点文件 / 下载完自动打开。
    // qe7 字段: a=文件名, b=MIME, c=id, d=OtrProfileId, e/f=URL, g=action,
    //           h=Context, i=完整路径。
    // 只拦截 APK → 自动安装; 其他文件放行原逻辑。
    // ------------------------------------------------------------------
    private static void hookOpenDownloadEntry(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> utilsCls = XposedHelpers.findClass(CLS_DOWNLOAD_UTILS,
                    lpparam.classLoader);
            Class<?> qe7Cls = XposedHelpers.findClass("qe7", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(utilsCls, "a", qe7Cls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!HookEntry.readPrefBoolean(
                                        HookEntry.KEY_AUTO_INSTALL_APK, true)) {
                                    return;
                                }
                                Object qe7 = param.args[0];
                                if (qe7 == null) return;
                                String mime = (String) XposedHelpers.getObjectField(qe7, "b");
                                String name = (String) XposedHelpers.getObjectField(qe7, "a");
                                if (!HookEntry.isApk(mime, name)) return;
                                String path = (String) XposedHelpers.getObjectField(qe7, "i");
                                if (path == null) path = "";
                                String abs = resolveRealPath(path, name,
                                        qe7.getClass().getClassLoader());
                                if (abs == null || abs.isEmpty()) {
                                    // 路径解析不到就不拦截, 交给 Chrome 原逻辑
                                    return;
                                }
                                openApkInstaller(abs, qe7.getClass().getClassLoader());
                                // 阻止 Chrome 原打开逻辑
                                param.setResult(Boolean.TRUE);
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": auto-install apk (DownloadUtils.a) error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked DownloadUtils.a(qe7) (open download entry, auto-install apk)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook DownloadUtils.a failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // 兜底 2: Chrome 内嵌下载完成 → DownloadController.onDownloadCompleted
    // ------------------------------------------------------------------
    private static void hookInlineDownloadPath(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_DOWNLOAD_CONTROLLER,
                    lpparam.classLoader,
                    "onDownloadCompleted",
                    XposedHelpers.findClass("org.chromium.chrome.browser.tab.Tab",
                            lpparam.classLoader),
                    XposedHelpers.findClass(
                            "org.chromium.chrome.browser.download.DownloadInfo",
                            lpparam.classLoader),
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!HookEntry.readPrefBoolean(
                                        HookEntry.KEY_AUTO_INSTALL_APK, true)) {
                                    return;
                                }
                                Object info = param.args[1];
                                if (info == null) return;
                                ClassLoader cl = info.getClass().getClassLoader();
                                String mime = (String) XposedHelpers.getObjectField(info, "c");
                                String path = (String) XposedHelpers.getObjectField(info, "e");
                                String name = (String) XposedHelpers.getObjectField(info, "g");
                                if (!HookEntry.isApk(mime, name)) return;
                                // 真机日志证实: onDownloadCompleted 是下载完成时确定触发的回调,
                                // 但此时文件可能还在 Chrome 私有临时目录, 尚未复制到公共目录,
                                // 因此改为后台轮询等待文件落盘后再安装。
                                final String fileName = (path != null && path.contains("/"))
                                        ? new File(path).getName() : path;
                                final String finalName = (name != null && !name.isEmpty())
                                        ? name : fileName;
                                if (finalName == null || finalName.isEmpty()) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": apk download completed but no file name, skip");
                                    return;
                                }
                                final ClassLoader fcl = cl;
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        waitAndInstall(finalName, fcl);
                                    }
                                });
                                t.setDaemon(true);
                                t.start();
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": auto-install apk (inline path) error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked DownloadController.onDownloadCompleted (auto-install apk)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook auto-install apk failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // 轮询等待 APK 文件出现在下载目录, 出现后自动安装。
    // Chrome 内置下载完成时只有正式文件名, 真实文件先落在私有目录
    // (.com.google.Chrome.XXXX 临时名), 随后复制到公共目录并改正式名。
    // 因此同时轮询: ①公共 Download ②Chrome 私有 Download ③私有目录下
    // 的 .com.google.Chrome. 临时文件(大小>0 即认为完成)。最多 60 秒,
    // 每 500ms 查一次。
    // ------------------------------------------------------------------
    private static void waitAndInstall(final String fileName, final ClassLoader cl) {
        // 路径可能是 content://(MediaStore/FileProvider), 直接打开
        if (fileName != null && fileName.startsWith("content://")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse(fileName), APK_MIME);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchInstaller(fileName, intent, cl);
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG
                        + ": open apk installer failed (content uri) -> " + t);
            }
            return;
        }
        File publicDl = null;
        File privateDl = null;
        File privateRoot = null;
        try {
            publicDl = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
        } catch (Throwable ignored) {
        }
        try {
            privateRoot = new File(Environment.getExternalStorageDirectory(),
                    "Android/data/com.android.chrome/files");
            privateDl = new File(privateRoot, "Download");
        } catch (Throwable ignored) {
        }
        long deadline = System.currentTimeMillis() + 60000L;
        File found = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (publicDl != null) {
                    File f = new File(publicDl, fileName);
                    if (f.exists() && f.length() > 0) {
                        found = f;
                        break;
                    }
                    // 部分下载器会先写 .crdownload/.tmp 再改名
                    File[] cands = publicDl.listFiles();
                    if (cands != null) {
                        for (File c : cands) {
                            if (c == null) continue;
                            String cn = c.getName();
                            if (cn.equals(fileName)) {
                                found = c;
                                break;
                            }
                            if (cn.startsWith(fileName) || fileName.startsWith(cn)) {
                                found = c;
                                break;
                            }
                        }
                    }
                    if (found != null) break;
                }
                if (privateDl != null) {
                    File f2 = new File(privateDl, fileName);
                    if (f2.exists() && f2.length() > 0) {
                        found = f2;
                        break;
                    }
                    File[] cands2 = privateDl.listFiles();
                    if (cands2 != null) {
                        for (File c : cands2) {
                            if (c == null) continue;
                            String cn = c.getName();
                            if (cn.equals(fileName)) {
                                found = c;
                                break;
                            }
                            if (cn.startsWith(fileName) || fileName.startsWith(cn)) {
                                found = c;
                                break;
                            }
                        }
                    }
                    if (found != null) break;
                    // Chrome 临时文件 .com.google.Chrome.XXXX
                    if (privateRoot != null) {
                        File[] tmps = privateDl.listFiles();
                        if (tmps != null) {
                            for (File c : tmps) {
                                if (c == null) continue;
                                String cn = c.getName();
                                if (cn.startsWith(".com.google.Chrome.")
                                        && c.length() > 0
                                        && c.lastModified() > System.currentTimeMillis() - 120000L) {
                                    found = c;
                                    break;
                                }
                            }
                        }
                    }
                    if (found != null) break;
                }
                // MediaStore 兑底: Android 11+ 作用域存储下 File.exists 可能不可见,
                // 但 MediaStore 一定能查到刚下载的文件
                Uri msUri = queryMediaStore(fileName);
                if (msUri != null) {
                    openApkInstallerUri(msUri, cl);
                    return;
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                return;
            }
        }
        if (found == null) {
            XposedBridge.log(HookEntry.TAG + ": apk file not found in Download dir within 60s ["
                    + fileName + "], give up");
            return;
        }
        String abs = found.getAbsolutePath();
        openApkInstaller(abs, cl); // v1.9.6: 原子去重在 openApkInstaller 内部
    }

    /** MediaStore 查询刚下载的 APK(按文件名, 取最新) */
    private static Uri queryMediaStore(String fileName) {
        try {
            Context ctx = HookEntry.getAppContext(null);
            if (ctx == null) return null;
            android.content.ContentResolver cr = ctx.getContentResolver();
            Uri collection = android.provider.MediaStore.Files
                    .getContentUri("external");
            String sel = android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?";
            android.database.Cursor c = cr.query(collection,
                    new String[]{android.provider.MediaStore.MediaColumns._ID},
                    sel, new String[]{fileName},
                    android.provider.MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        long id = c.getLong(0);
                        return android.content.ContentUris.withAppendedId(collection, id);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 直接用 content:// Uri 打开安装器(v1.9.6: 原子去重) */
    private static void openApkInstallerUri(Uri uri, ClassLoader cl) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchInstaller(uri.toString(), intent, cl);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": open apk installer failed (uri) -> " + t);
        }
    }

    /**
     * v1.9.6: 全局原子去重安装。
     * 所有安装路径(content:// / MediaStore / File)统一走此方法:
     * synchronized 块内 check-and-set, 同一文件只发一次 Intent。
     * 之前的问题: 多个 hook 点(c9o.run/zkg.f/onDownloadCompleted)同时触发,
     * 多线程轮询找到文件后都通过了非原子的去重检查
     * → 多次安装提示。
     */
    private static void launchInstaller(String dedupKey, Intent intent, ClassLoader cl) {
        synchronized (sInstallLock) {
            if (dedupKey == null || dedupKey.equals(sLastAutoInstalled)) {
                XposedBridge.log(HookEntry.TAG
                        + ": apk install skipped (already installed) -> " + dedupKey);
                return;
            }
            sLastAutoInstalled = dedupKey;
        }
        try {
            Context ctx = HookEntry.getAppContext(cl);
            if (ctx == null) {
                XposedBridge.log(HookEntry.TAG + ": no context for apk install, skip");
                return;
            }
            ctx.startActivity(intent);
            XposedBridge.log(HookEntry.TAG + ": apk install intent launched -> " + dedupKey);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": open apk installer failed -> " + t);
        }
    }


    // ------------------------------------------------------------------
    // 路径解析: 把可能只是裸文件名的 path 解析成真实绝对路径
    // 依次尝试: ①已是绝对路径 ②公共 Download 目录 ③Chrome 私有下载目录
    //          ④PathUtils.getDownloadsDirectory() ⑤当前应用文件目录
    // ------------------------------------------------------------------
    private static String resolveRealPath(String path, String name, ClassLoader cl) {
        try {
            if (path != null && !path.isEmpty()) {
                String p = path.trim();
                if (p.startsWith("/")) {
                    File f = new File(p);
                    if (f.exists()) return f.getAbsolutePath();
                }
            }
            String fileName = (name != null && !name.isEmpty()) ? name
                    : (path != null ? new File(path).getName() : null);
            if (fileName == null || fileName.isEmpty()) return null;

            // ① 公共 Download 目录
            try {
                File publicDl = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (publicDl != null) {
                    File f = new File(publicDl, fileName);
                    if (f.exists()) return f.getAbsolutePath();
                }
            } catch (Throwable ignored) {
            }
            // ② Chrome 私有下载目录 /data/user/0/com.android.chrome/.../Download
            try {
                Class<?> pathUtils = Class.forName(
                        "org.chromium.base.PathUtils", false, cl);
                Method getDl = pathUtils.getMethod("getDownloadsDirectory");
                Object dlDir = getDl.invoke(null);
                if (dlDir instanceof String) {
                    String s = (String) dlDir;
                    if (!TextUtils.isEmpty(s)) {
                        File f = new File(s, fileName);
                        if (f.exists()) return f.getAbsolutePath();
                    }
                }
            } catch (Throwable ignored) {
            }
            // ③ 应用外部文件目录 Download
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread",
                        false, cl);
                Object app = activityThread.getMethod("currentApplication").invoke(null);
                if (app instanceof Context) {
                    Context ctx = (Context) app;
                    File[] dirs = ctx.getExternalFilesDirs(null);
                    if (dirs != null) {
                        for (File dir : dirs) {
                            if (dir == null) continue;
                            File f = new File(new File(dir, "Download"), fileName);
                            if (f.exists()) return f.getAbsolutePath();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            // ④ 最后尝试: 文件名带完整路径的情况(如 content:// 已解析)
            if (path != null && path.contains("/")) {
                File f = new File(path);
                if (f.exists()) return f.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }


    /** 打开系统安装器(绝对路径 → content URI) */
    private static void openApkInstaller(String absPath, ClassLoader cl) {
        try {
            Uri uri = null;
            // 优先用 Chrome 的 DownloadUtils.e(绝对路径) 转 content:// URI
            try {
                Class<?> utilsClass = Class.forName(CLS_DOWNLOAD_UTILS, false, cl);
                Method toUri = utilsClass.getMethod("e", String.class);
                Object r = toUri.invoke(null, absPath);
                if (r instanceof Uri) {
                    Uri u = (Uri) r;
                    if (u != null && !Uri.EMPTY.equals(u)
                            && !"file".equals(u.getScheme())) {
                        uri = u;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (uri == null) {
                // 兜底: MediaStore 查询(Android 11+ 作用域存储)
                try {
                    Uri ms = queryMediaStore(new File(absPath).getName());
                    if (ms != null) {
                        uri = ms;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (uri == null) {
                XposedBridge.log(HookEntry.TAG
                        + ": cannot build content uri for [" + absPath + "], skip");
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchInstaller(absPath, intent, cl);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": open apk installer failed -> " + t);
        }
    }
}
