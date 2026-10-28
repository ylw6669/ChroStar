package io.github.ylw6669.chrostar;

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
    /** 无法从 MIME/后缀推断时使用通用文件类型，绝不能回退成 APK MIME。 */
    private static final String GENERIC_OPEN_MIME = "*/*";

    /** 防重复: 同一文件只触发一次安装(v1.9.6: 原子去重) */
    // R2-m10: 单槽 sLastAutoInstalled(+At) → Map<键, 置位时间戳>, 与 sLastAutoOpenEvent 同构。
    //   单槽被不同键覆盖后前一键 5s 窗口失效 → 双开窗口真实存在(尤其 145 上 isDuplicateCompleteEvent
    //   可能因 ContentId 字段缺失 no-op)。锁仍用 sInstallLock, size>32 时 Iterator 淘汰过期项。
    private static final java.util.Map<String, Long> sLastAutoInstalledMap =
            new java.util.HashMap<String, Long>();
    private static final Object sInstallLock = new Object();

    /** 1A-4(a): 完成事件级去重(ContentId -> 上次触发时间戳), 复用 BannerController 5s 窗口范式。
     *  Chrome 对同一次下载完成会以相同 ContentId(OfflineItem 字段 S) 回调两次(ia8.d/zkg.f),
     *  1A-1 双装消除后仍需事件级防御纵深, 避免同一次完成被自动打开两遍。 */
    private static final java.util.Map<Object, Long> sLastAutoOpenEvent =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long AUTO_OPEN_DEDUP_WINDOW_MS = 5000L;

    private AutoInstallApk() {
    }

    /** 安装 hook(主进程) */
    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("chrome153".equals(HookEntry.engineVersion)) {
            hook153(lpparam);
            return;
        }
        if ("chrome152".equals(HookEntry.engineVersion)) {
            hook152(lpparam);
            return; // 152 短名(c9o/zkg)不同, 独立路径
        }
        if ("chrome_symbolic".equals(HookEntry.engineVersion)) {
            // Modern unknown versions never use 145-only c9o/zkg/qe7 names.
            // The structural resolver is primary; 152/153 names are a validated
            // last-resort per-capability fallback.
            hookStructuredDownloadComplete(lpparam);
            hookInlineDownloadPath(lpparam);
            return;
        }
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
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object d9o = XposedHelpers.getObjectField(param.thisObject, "P");
                                if (d9o == null) return;
                                int event = XposedHelpers.getIntField(d9o, "a");
                                if (event != 2) return;
                                Object info = XposedHelpers.getObjectField(d9o, "b");
                                if (info == null) return;
                                String mime = (String) XposedHelpers.getObjectField(info, "c");
                                String path = (String) XposedHelpers.getObjectField(info, "e");
                                String name = (String) XposedHelpers.getObjectField(info, "g");
                                BannerController.onLegacyDownloadCompleted(mime, name, path);
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": legacy c9o banner hook error -> " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_INSTALL_APK, true)
                                        && !HookEntry.readExtAutoOpenGate()) {
                                    return;
                                }
                                Object d9o = XposedHelpers.getObjectField(param.thisObject, "P");
                                if (d9o == null) return;
                                int event = XposedHelpers.getIntField(d9o, "a");
                                // [145 反编译实证 145.0.7632.218 · c9o.run()/e9o.d()/zkg.f()]
                                //   c9o.run() 按 d9o.a 分派通知类型; a==2 才是"下载完成":
                                //     zkg.f 对 OfflineItem.m0==2(COMPLETE) 调 e9o.d(DownloadInfo),
                                //     e9o.d 内 a(new d9o(2, downloadInfo, 0)) → c9o.run() 的 i==2 分支
                                //     建 type-2 完成通知并发 notifyEvent("download_completed")。
                                //   a==1 是 IN_PROGRESS/PENDING(zkg.f 对 m0==1/6 投递), 非完成。
                                //   旧门禁 event!=1 在完成之前的进度事件触发、却漏掉真正完成事件(2)
                                //   → 下载完成时自动打开从不触发(用户实证: 勾选压缩包无反应)。
                                XposedBridge.log(HookEntry.TAG + ": [diag] c9o.run event=" + event);
                                if (event != 2) return;  // 2 = 下载完成(反编译实证)
                                XposedBridge.log(HookEntry.TAG + ": [diag] c9o COMPLETE fired (event=2)");
                                Object info = XposedHelpers.getObjectField(d9o, "b");
                                if (info == null) { XposedBridge.log(HookEntry.TAG + ": [diag] c9o info(b)=null"); return; }
                                ClassLoader cl = info.getClass().getClassLoader();
                                String mime = (String) XposedHelpers.getObjectField(info, "c");
                                String path = (String) XposedHelpers.getObjectField(info, "e");
                                String name = (String) XposedHelpers.getObjectField(info, "g");
                                XposedBridge.log(HookEntry.TAG + ": [diag] c9o fields mime=[" + mime + "] name=[" + name + "] path=[" + path + "]");
                                final String finalName = select145FileName(mime, name, path);
                                if (finalName == null || finalName.isEmpty()) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": apk completed (c9o) but no file name, skip");
                                    return;
                                }
                                if (!OverwriteAndAutoOpen.shouldAutoOpen(mime, finalName)) return;
                                final ClassLoader fcl = cl;
                                final String finalMime = mime;
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        waitAndInstall(finalName, fcl,
                                                (finalMime != null && !finalMime.isEmpty())
                                                        ? finalMime : mimeFromName(finalName));
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
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object item = param.args[0];
                                if (item == null) return;
                                int state = XposedHelpers.getIntField(item, "m0");
                                if (state != 2) return;
                                BannerController.onLegacyOfflineItemCompleted(item,
                                        item.getClass().getClassLoader(),
                                        select145FileName(
                                                XposedHelpers.getObjectField(item, "f0") == null
                                                        ? null
                                                        : String.valueOf(XposedHelpers.getObjectField(item, "f0")),
                                                XposedHelpers.getObjectField(item, "e0") == null
                                                        ? null
                                                        : String.valueOf(XposedHelpers.getObjectField(item, "e0")),
                                                XposedHelpers.getObjectField(item, "P") == null
                                                        ? null
                                                        : String.valueOf(XposedHelpers.getObjectField(item, "P"))));
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": legacy zkg banner hook error -> " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_INSTALL_APK, true)
                                        && !HookEntry.readExtAutoOpenGate()) {
                                    return;
                                }
                                Object item = param.args[0];
                                if (item == null) return;
                                // RC4 修复: 门禁收紧为仅接受完成态(state==2)。
                                // [145 反编译实证 · zkg.f/OfflineItem.m0] m0==1 是 IN_PROGRESS/PENDING,
                                //   m0==2 才是 COMPLETE(与 c9o 的 event==2、ia8.d 的 q0==2 对齐)。
                                //   旧门禁 state!=1&&state!=2 同时放行进行中(1): 进行中事件即启动
                                //   waitAndInstall 轮询, 临时文件 length()>0 时被当作完成 → 抢先打开并置去重键
                                //   → 真正完成(state==2)时被去重挡掉(勾选压缩包仍无法自动打开)。
                                int state = XposedHelpers.getIntField(item, "m0");
                                if (state != 2) return;  // 只接受完成态(2)
                                XposedBridge.log(HookEntry.TAG + ": [diag] zkg.f COMPLETE fired (state=" + state + ")");
                                String mime = (String) XposedHelpers.getObjectField(item, "f0");
                                String name = (String) XposedHelpers.getObjectField(item, "e0");
                                XposedBridge.log(HookEntry.TAG + ": [diag] zkg.f fields mime=[" + mime + "] name=[" + name + "]");
                                // 1A-4(a): ContentId(S)+5s 窗口事件级去重(与 BannerController 同范式)。
                                if (isDuplicateCompleteEvent(item)) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": [diag] duplicate complete event (zkg.f), auto-open skipped");
                                    return;
                                }
                                String path = (String) XposedHelpers.getObjectField(item, "P");
                                if (path == null) path = "";
                                // 关键修复: OfflineItem.P 只有正式文件名, 且此时文件可能还在
                                // 私有临时目录(.com.google.Chrome.XXXX), 尚未复制到公共
                                // /sdcard/Download。因此改为后台轮询等待文件出现在公共目录。
                                final String finalName = select145FileName(mime, name, path);
                                if (finalName == null || finalName.isEmpty()) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": apk download completed but no file name, skip");
                                    return;
                                }
                                if (!OverwriteAndAutoOpen.shouldAutoOpen(mime, finalName)) return;
                                final ClassLoader fcl = item.getClass().getClassLoader();
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        waitAndInstall(finalName, fcl, (mime != null && !mime.isEmpty()) ? mime : mimeFromName(finalName));
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
                                if (!HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_INSTALL_APK, true)
                                        && !HookEntry.readExtAutoOpenGate()) {
                                    return;
                                }
                                Object qe7 = param.args[0];
                                if (qe7 == null) return;
                                String mime = (String) XposedHelpers.getObjectField(qe7, "b");
                                String name = (String) XposedHelpers.getObjectField(qe7, "a");
                                String path = (String) XposedHelpers.getObjectField(qe7, "i");
                                if (path == null) path = "";
                                XposedBridge.log(HookEntry.TAG + ": [diag] DownloadUtils.a(qe7) fired mime=[" + mime + "] name=[" + name + "] path=[" + path + "]");
                                String candidateName = select145FileName(mime, name, path);
                                if (!OverwriteAndAutoOpen.shouldAutoOpen(mime, candidateName)) return;
                                String abs = resolveRealPath(path, name,
                                        qe7.getClass().getClassLoader());
                                if (abs == null || abs.isEmpty()) {
                                    // 路径解析不到就不拦截, 交给 Chrome 原逻辑
                                    return;
                                }
                                // 阻止 Chrome 原打开逻辑(同步, 必须在 beforeHookedMethod 内完成才能拦住原方法)
                                param.setResult(Boolean.TRUE);
                                // 修复E: 实际打开动作切到后台守护线程, 与 c9o/zkg/onDownloadCompleted 一致。
                                // DownloadUtils.a 的 beforeHookedMethod 可能同步运行在 Chrome UI 线程,
                                // 原实现直接调 openApkInstaller → launchInstaller 会持 sInstallLock 跨
                                // startActivity(Binder IPC), AMS 繁忙时阻塞后台守护线程。切后台线程后 UI 线程
                                // 不再持锁跨 IPC, launchInstaller 锁内实现保持不变(仍满足 RC3a: 成功才去重/失败可重试)。
                                final String absPath = abs;
                                final ClassLoader fcl = qe7.getClass().getClassLoader();
                                final String realMime = mime;
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        openApkInstaller(absPath, fcl, realMime);
                                    }
                                });
                                t.setDaemon(true);
                                t.start();
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
                                XposedBridge.log(HookEntry.TAG + ": [diag] onDownloadCompleted fired");
                                if (!HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_INSTALL_APK, true)
                                        && !HookEntry.readExtAutoOpenGate()) {
                                    XposedBridge.log(HookEntry.TAG + ": [diag] onDownloadCompleted gate blocked");
                                    return;
                                }
                                Object info = param.args[1];
                                if (info == null) { XposedBridge.log(HookEntry.TAG + ": [diag] onDownloadCompleted info=null"); return; }
                                ClassLoader cl = info.getClass().getClassLoader();
                                OfflineItemResolver.Result infoFields =
                                        OfflineItemResolver.classify(info, cl);
                                String mime = infoFields.mime;
                                String path = infoFields.bestPath();
                                String name = infoFields.bestName();
                                // 已知引擎保留经真机验证的字段作为兼容兜底；未来路由禁止猜旧短名。
                                if (!"chrome_symbolic".equals(HookEntry.engineVersion)) {
                                    if (mime == null) mime = safeStringField(info, "c");
                                    if (path == null) path = safeStringField(info, "e");
                                    if (name == null) name = safeStringField(info, "g");
                                }
                                XposedBridge.log(HookEntry.TAG + ": [diag] onDownloadCompleted fields mime=[" + mime + "] name=[" + name + "] path=[" + path + "]");
                                // 真机日志证实: onDownloadCompleted 是下载完成时确定触发的回调,
                                // 但此时文件可能还在 Chrome 私有临时目录, 尚未复制到公共目录,
                                // 因此改为后台轮询等待文件落盘后再安装。
                                final String finalName = select145FileName(mime, name, path);
                                if (finalName == null || finalName.isEmpty()) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": apk download completed but no file name, skip");
                                    return;
                                }
                                if (!OverwriteAndAutoOpen.shouldAutoOpen(mime, finalName)) return;
                                final ClassLoader fcl = cl;
                                final String inlineMime = mime;
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        waitAndInstall(finalName, fcl,
                                                (inlineMime != null && !inlineMime.isEmpty())
                                                        ? inlineMime : mimeFromName(finalName));
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
    // 轮询等待已完成文件出现在下载目录, 出现后自动打开。
    // Chrome 内置下载完成时可能先写私有目录, 随后复制到公共目录并改正式名；
    // 因此同时轮询公共 Download 与 Chrome 私有 Download。只接受目标文件的
    // 精确文件名和非空普通文件，绝不把相近名称或任意临时文件当作完成文件。
    // ------------------------------------------------------------------
    /** 支持 MIME 参数(扩展类型自动打开); 未知类型使用通用 MIME，不按 APK 处理。 */
    private static void waitAndInstall(final String fileName, final ClassLoader cl,
                                       final String mimeOverride) {
        // v2.3.4: 有效 MIME = 调用方传入的真实 MIME 优先, 否则按文件名推断。
        // 这是"扩展类型自动打开"的关键: 之前打开环节写死 APK_MIME, 非 APK 文件无法被对应 App 打开。
        // v2.3.20: 传入 MIME 为通用 octet-stream(自定义后缀常见)时, 优先用文件名推断的类型,
        //   否则 ACTION_VIEW(octet-stream) 往往无 handler → 用户勾选也打不开。
        String effectiveMime = resolveOpenMime(fileName, mimeOverride);
        XposedBridge.log(HookEntry.TAG + ": [diag] waitAndInstall ENTER fileName=[" + fileName
                + "] effectiveMime=[" + effectiveMime + "]");
        // 路径可能是 content://(MediaStore/FileProvider), 直接打开
        if (fileName != null && fileName.startsWith("content://")) {
            try {
                String dedupName = fileName;
                String contentMime = effectiveMime;
                OfflineItemResolver.Resolved resolved =
                        OfflineItemResolver.resolveContentUri(
                                HookEntry.getAppContext(cl), fileName, 3000L);
                if (resolved != null) {
                    if (resolved.displayName != null && !resolved.displayName.isEmpty()) {
                        dedupName = resolved.displayName;
                    }
                    if (resolved.mime != null && !resolved.mime.isEmpty()) {
                        contentMime = resolveOpenMime(dedupName, resolved.mime);
                    }
                }
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse(fileName), contentMime);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchInstaller(dedupName, intent, cl);
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG
                        + ": open file failed (content uri) -> " + t);
            }
            return;
        }
        // 普通文件只接受裸文件名。路径字段可能是完整路径，统一取 basename，
        // 避免 new File(downloadDir, fileName) 越出下载目录或匹配到错误路径。
        final String expectedName = new File(fileName).getName();
        if (expectedName.isEmpty()) {
            XposedBridge.log(HookEntry.TAG + ": empty file name after basename, skip");
            return;
        }
        effectiveMime = resolveOpenMime(expectedName, mimeOverride);
        File publicDl = null;
        File privateDl = null;
        try {
            publicDl = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
        } catch (Throwable ignored) {
        }
        try {
            privateDl = new File(new File(Environment.getExternalStorageDirectory(),
                    "Android/data/com.android.chrome/files"), "Download");
        } catch (Throwable ignored) {
        }
        long deadline = System.currentTimeMillis() + 60000L;
        File found = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (publicDl != null) {
                    File f = completedFile(publicDl, expectedName);
                    if (f != null) {
                        found = f;
                        break;
                    }
                }
                if (privateDl != null) {
                    File f2 = completedFile(privateDl, expectedName);
                    if (f2 != null) {
                        found = f2;
                        break;
                    }
                }
                // MediaStore 兑底: Android 11+ 作用域存储下 File.exists 可能不可见,
                // 但 MediaStore 一定能查到刚下载的文件
                Uri msUri = queryMediaStore(expectedName);
                if (msUri != null) {
                    openApkInstallerUri(msUri, cl, effectiveMime, expectedName);
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
            XposedBridge.log(HookEntry.TAG + ": file not found in Download dir within 60s ["
                    + expectedName + "], give up");
            return;
        }
        String abs = found.getAbsolutePath();
        // 传真实 MIME(非 APK 用推断类型打开), 原子去重在 openApkInstaller 内部
        openApkInstaller(abs, cl, effectiveMime);
    }

    /** 只接受目标文件的精确名称和已完成的非空普通文件。 */
    private static File completedFile(File dir, String expectedName) {
        if (dir == null || expectedName == null || expectedName.isEmpty()) return null;
        File candidate = new File(dir, expectedName);
        return candidate.isFile() && candidate.length() > 0L ? candidate : null;
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

    /** 直接用 content:// Uri 打开(v1.9.6 原子去重; v2.3.4 支持真实 MIME, 非 APK 类型自动打开)
     *  1A-4(b): 新增 dedupName —— MediaStore uri.toString() 结尾是数字 ID(content://media/.../1234),
     *  归一化 basename 后是 "1234", 与文件路径 basename "foo.zip" 仍不一致 → 去重键分裂可能双开。
     *  故由调用方显式传真实文件名作去重键, 缺失时才退回 uri.toString()。 */
    private static void openApkInstallerUri(Uri uri, ClassLoader cl, String mime, String dedupName) {
        try {
            String realMime = resolveOpenMime(dedupName, mime);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, realMime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchInstaller((dedupName != null && !dedupName.isEmpty())
                    ? dedupName : uri.toString(), intent, cl);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": open file failed (uri) -> " + t);
        }
    }

    /** 1A-4(b): 去重键归一化为 basename, 使文件绝对路径与 content uri 收敛到同一文件名键,
     *  消除 openApkInstaller(absPath) 与 openApkInstallerUri(uri) 去重键不一致导致的双开。
     *  先剥 query(?...) 再取最后一段路径; 结果为空则回退原键(不误伤)。 */
    private static String normalizeDedupKey(String key) {
        if (key == null) return null;
        String s = key;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        // m8: 末尾斜杠彻底剥离(while) —— "dir//" 先归一再取 basename, 避免尾斜杠导致键不一致。
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        return s.isEmpty() ? key : s;
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
        // 1A-4(b): 去重键归一化为 basename, 统一 absPath / content uri 两条路径的键。
        final String key = normalizeDedupKey(dedupKey);
        // RC3(a) 修复: 去重键必须在 startActivity 成功返回之后才置位。
        // 旧逻辑先置 sLastAutoInstalled 再 startActivity: 若设备无处理该 MIME 的 VIEW
        // handler(如 application/zip), startActivity 抛 ActivityNotFoundException,
        // 去重键却已写死 → 同一文件名后续触发(包括真正完成事件)全部命中
        // "already installed" 被跳过 → 表现为勾选后仍无法打开。
        // 整个 check-launch-set 置于同一 synchronized 块内, 既保留多线程原子去重
        // (避免 c9o/zkg/onDownloadCompleted 多点并发重复弹安装), 又仅在成功后去重。
        synchronized (sInstallLock) {
            // R2-m10: Map+5s 时间窗去重(替换单槽) —— 每个键独立保留窗口, 不再被后续键覆盖而失效
            //   (旧单槽: A、B 先后完成, A 的重复事件到达时 slot 已是 B → A.equals(B)=false → A 二次打开)。
            long now = android.os.SystemClock.elapsedRealtime();
            if (key == null) {
                XposedBridge.log(HookEntry.TAG + ": apk install skipped (null dedup key)");
                return;
            }
            Long lastAt = sLastAutoInstalledMap.get(key);
            if (lastAt != null && now - lastAt.longValue() < AUTO_OPEN_DEDUP_WINDOW_MS) {
                XposedBridge.log(HookEntry.TAG
                        + ": apk install skipped (already installed) -> " + key);
                return;
            }
            try {
                Context ctx = HookEntry.getAppContext(cl);
                if (ctx == null) {
                    XposedBridge.log(HookEntry.TAG + ": no context for apk install, skip");
                    return;
                }
                ctx.startActivity(intent);
                // 仅在成功启动后置去重键: 失败时保留后续重试机会。
                sLastAutoInstalledMap.put(key, Long.valueOf(now));
                // R2-m10: size>32 时按时间戳淘汰过期项(不清空, 保留仍在窗口内的键)。
                if (sLastAutoInstalledMap.size() > 32) {
                    java.util.Iterator<java.util.Map.Entry<String, Long>> it =
                            sLastAutoInstalledMap.entrySet().iterator();
                    while (it.hasNext()) {
                        java.util.Map.Entry<String, Long> e = it.next();
                        Long v = e.getValue();
                        if (v == null || now - v.longValue() >= AUTO_OPEN_DEDUP_WINDOW_MS) {
                            it.remove();
                        }
                    }
                }
                XposedBridge.log(HookEntry.TAG + ": apk install intent launched -> " + key);
                LogTail.add("Auto", "install launched -> " + key);
                LogTail.push(HookEntry.getAppContext(null));
            } catch (android.content.ActivityNotFoundException e) {
                // v2.3.20: 无精确 MIME handler(自定义后缀常落 application/octet-stream 或 text/plain 但
                //   系统未分配默认应用时) → 回退通用选择器(让用户自选应用), 否则表现为"勾选了也不打开"。
                boolean launched = false;
                try {
                    Context ctx2 = HookEntry.getAppContext(cl);
                    if (ctx2 != null) {
                        Intent chooser = Intent.createChooser(
                                new Intent(intent).setType("*/*"), "打开文件");
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx2.startActivity(chooser);
                        sLastAutoInstalledMap.put(key, Long.valueOf(now));
                        launched = true;
                        XposedBridge.log(HookEntry.TAG + ": no exact handler, launched chooser -> "
                                + key + " mime=[" + intent.getType() + "]");
                    }
                } catch (Throwable t2) {
                    XposedBridge.log(HookEntry.TAG + ": chooser fallback failed [" + key
                            + "] -> " + t2);
                }
                if (!launched) {
                    XposedBridge.log(HookEntry.TAG + ": no activity to open [" + key
                            + "] mime=[" + intent.getType() + "] -> " + e
                            + " (dedup key NOT set, will retry on next event)");
                }
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG + ": open apk installer failed [" + key
                        + "] mime=[" + intent.getType() + "] -> " + t
                        + " (dedup key NOT set, will retry on next event)");
            }
        }
    }


    // ------------------------------------------------------------------
    // 1A-4(a): 完成事件级去重 helper。
    // 返回 true=重复事件(抑制); false=首个事件(放行并已记录时间戳)。
    // R2-M3: 用类型优先探测的 ContentId(不再写死字段 S) + 组合标量键(namespace#id)
    //   + 5s 窗口去重; ContentId 无标量字段时显式 fail-open(不伪装)。145(zkg.f)/152(hook152) 共用。
    // 异常时返回 false(放行)并记录日志, 不吞异常掩盖问题。
    // ------------------------------------------------------------------
    private static boolean isDuplicateCompleteEvent(Object item) {
        try {
            // R2-M3: 类型优先探测 ContentId(不再写死字段名 "S"), 构造组合标量键(namespace+id),
            //   禁用 toString() 回落。145(字段 m0/e0/f0/P/S) 与 152 两路都走同一 helper,
            //   解决 "S" 在 145 不存在→抛异常→静默 no-op, 以及只取 long id 丢 namespace→撞键。
            Object cid = OfflineItemResolver.extractContentId(item);
            if (cid == null) return false;
            String key = OfflineItemResolver.contentIdKey(cid);
            if (key == null) {
                // 显式 fail-open: ContentId 无任何标量字段 → 无法构造稳定键, 放行。
                //   不伪装成安全去重(旧实现 toString 回落 = 每实例唯一键 → 恒放行但假装安全)。
                XposedBridge.log(HookEntry.TAG
                        + ": [diag] complete-event dedup: ContentId has no scalar fields, fail-open (allow) -> "
                        + cid.getClass().getName());
                return false;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            // m7: 原子 check-and-set —— synchronized 消除 get-then-put 竞态(两线程同时 get 到
            //   旧值都判非重复 → 双开); 容量控制改按时间戳淘汰过期条目, 而非整体 clear()
            //   (clear 会丢失仍在窗口内的键 → 去重失效)。
            synchronized (sLastAutoOpenEvent) {
                Long last = sLastAutoOpenEvent.get(key);
                if (last != null && now - last.longValue() < AUTO_OPEN_DEDUP_WINDOW_MS) {
                    return true;
                }
                sLastAutoOpenEvent.put(key, Long.valueOf(now));
                if (sLastAutoOpenEvent.size() > 32) {
                    java.util.Iterator<java.util.Map.Entry<Object, Long>> it =
                            sLastAutoOpenEvent.entrySet().iterator();
                    while (it.hasNext()) {
                        java.util.Map.Entry<Object, Long> e = it.next();
                        Long v = e.getValue();
                        if (v == null || now - v.longValue() >= AUTO_OPEN_DEDUP_WINDOW_MS) {
                            it.remove();
                        }
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [diag] complete-event dedup error -> " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 路径解析: 把可能只是裸文件名的 path 解析成真实绝对路径
    // 依次尝试: ①已是绝对路径 ②公共 Download 目录 ③Chrome 私有下载目录
    //          ④PathUtils.getDownloadsDirectory() ⑤当前应用文件目录
    // ------------------------------------------------------------------
    // 1B-1/1B-4: 改为包级可见(去掉 private), 供 OfflineItemResolver.resolveToAbsolutePath 复用。
    static String resolveRealPath(String path, String name, ClassLoader cl) {
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


    /** 打开文件(绝对路径 → content URI; v2.3.4 支持真实 MIME, 非 APK 用推断类型打开) */
    private static void openApkInstaller(String absPath, ClassLoader cl, String mime) {
        XposedBridge.log(HookEntry.TAG + ": [diag] openApkInstaller ENTER abs=[" + absPath + "] mime=[" + mime + "]");
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
            String realMime = resolveOpenMime(new File(absPath).getName(), mime);
            XposedBridge.log(HookEntry.TAG + ": [diag] openApkInstaller uri=[" + uri + "] realMime=[" + realMime + "]");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, realMime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchInstaller(absPath, intent, cl);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": open apk installer failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // v2.1.0: Chrome 152 专用安装路径。
    // 语义锚点: ia8.d(OfflineItem,boolean,boolean,boolean) 同 145 je7.d —
    // 完成态(offlineItem.q0==2)时从 OfflineItem 拿路径/文件名执行安装逻辑。
    // OfflineItem 152 字段: q0=state, 其余路径/名字段名经 DownloadInfo 转换确认后补;
    // 本实现仅对 APK( mime 判定 via OfflineItem 兜底字段) 触发, 字段缺失时安全跳过。
    // ------------------------------------------------------------------
    private static void hook152(XC_LoadPackage.LoadPackageParam lpparam) {
        hookStructuredDownloadComplete(lpparam);
    }

    /** Chrome 153 独立入口：仅复用已验证的 OfflineItem 布局，不回落 152 短名。 */
    private static void hook153(XC_LoadPackage.LoadPackageParam lpparam) {
        hookStructuredDownloadComplete(lpparam);
        // 153 的 DownloadMessageUiController(sa8.d)不是所有下载类型的必经路径。
        // 公开完成回调在普通 APK/附件下载中稳定触发，作为 153 专用兜底；
        // launchInstaller 的文件名时间窗继续负责两入口同时触发时的最终去重。
        hookInlineDownloadPath(lpparam);
    }

    private static void hookStructuredDownloadComplete(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            String ia8Name = ChromeSymbols.downloadMessageOrFallback(lpparam.classLoader);
            if (ia8Name == null) {
                XposedBridge.log(HookEntry.TAG + ": [diag] structured download completion unavailable; "
                        + "public DownloadController callback remains active");
                return;
            }
            Class<?> ia8 = XposedHelpers.findClass(ia8Name, lpparam.classLoader);
            Method completedMethod = ChromeSymbols.downloadMessageCompletedMethod(ia8);
            if (completedMethod == null) {
                XposedBridge.log(HookEntry.TAG + ": [diag] download completion method ambiguous/unresolved on "
                        + ia8.getName() + "; public callback remains active");
                return;
            }
            XposedBridge.hookMethod(completedMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object item = param.args[0];
                                if (item == null) return;
                                Integer state = OfflineItemResolver.completionState(item);
                                if (state == null || state.intValue() != 2) return;
                                if (!HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_INSTALL_APK, true)
                                        && !HookEntry.readExtAutoOpenGate()) {
                                    return;
                                }
                                // 1B-1: 用语义解析器取代写死字母读取(path=T/name=i0/mime=j0)。
                                //   .82 上这些字段语义漂移(i0=content uri / T="Download" / j0=octet-stream),
                                //   写死读取 → shouldAutoOpen NO-MATCH → 自动打开失效。classify 按内容特征分类,
                                //   纯反射同步执行(无 IPC), 可在 ia8.d 回调线程安全调用。
                                final OfflineItemResolver.Result r =
                                        OfflineItemResolver.classify(item, lpparam.classLoader);
                                XposedBridge.log(HookEntry.TAG + ": [diag] " + HookEntry.engineVersion
                                        + " download-message.d COMPLETE state=" + state
                                        + " resolver " + r);
                                // 1A-4(a): ContentId(S)+5s 窗口事件级去重(同步, 与 BannerController 同范式)。
                                if (isDuplicateCompleteEvent(item)) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": [" + HookEntry.engineVersion
                                            + "] duplicate complete event, auto-open skipped");
                                    return;
                                }
                                // fail-closed: 无任何文件身份线索(名/径/uri 全空) → 安全跳过, 绝不误开。
                                if (r.bestName() == null && r.bestPath() == null) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": [" + HookEntry.engineVersion
                                            + "] resolver found no file identity, skip (fail-closed)");
                                    return;
                                }
                                // content:// 且身份不可判定 → 需 ContentResolver 查询, 必须后台线程(防 ANR);
                                // 其余情况也统一切后台守护线程执行 shouldAutoOpen + waitAndInstall 轮询,
                                // 与 145 的 c9o/zkg.f 后台模式一致(避免在 ia8.d 回调线程同步负 60s 轮询)。
                                final ClassLoader fcl = lpparam.classLoader;
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        String name = r.bestName();
                                        String mime = r.mime;
                                        String path = r.bestPath();
                                        // content:// 且名/mime 不可判定 → 后台读 DISPLAY_NAME/MIME(带超时)
                                        if (r.uri != null && !r.hasDecidableIdentity()) {
                                            OfflineItemResolver.Resolved res =
                                                    OfflineItemResolver.resolveContentUri(
                                                            HookEntry.getAppContext(fcl), r.uri, 3000L);
                                            if (res != null) {
                                                if (res.displayName != null && !res.displayName.isEmpty()) {
                                                    name = res.displayName;
                                                }
                                                if ((mime == null || OfflineItemResolver.isGenericMime(mime))
                                                        && res.mime != null && !res.mime.isEmpty()) {
                                                    mime = res.mime;
                                                }
                                            }
                                        }
                                        XposedBridge.log(HookEntry.TAG + ": [diag] " + HookEntry.engineVersion
                                                + " resolved name=["
                                                + name + "] mime=[" + mime + "] path=[" + path + "]");
                                        // fail-closed: 仍判不出可用身份(无名且 mime 通用/空) → 跳过
                                        if ((name == null || name.isEmpty())
                                                && (mime == null || OfflineItemResolver.isGenericMime(mime))) {
                                            XposedBridge.log(HookEntry.TAG
                                                    + ": [" + HookEntry.engineVersion
                                                    + "] still no decidable identity, skip (fail-closed)");
                                            return;
                                        }
                                        if (!OverwriteAndAutoOpen.shouldAutoOpen(mime, name)) return;
                                        XposedBridge.log(HookEntry.TAG
                                                + ": [" + HookEntry.engineVersion
                                                + "] download completed, auto-open: " + name);
                                        installApkStructured(path, name, mime, fcl);
                                    }
                                });
                                t.setDaemon(true);
                                t.start();
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG + ": [" + HookEntry.engineVersion
                                        + "] install hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG + ": [" + HookEntry.engineVersion + "] hooked "
                    + ia8.getName() + "." + completedMethod.getName()
                    + " (auto-install path, structural)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [" + HookEntry.engineVersion
                    + "] hook download-message failed -> " + t);
        }
    }

    private static String safeStringField(Object target, String name) {
        try {
            Object value = XposedHelpers.getObjectField(target, name);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                    + ": [diag] DownloadInfo field " + name + " unavailable -> " + t);
            return null;
        }
    }

    private static void installApkStructured(String path, String name, String mime, ClassLoader cl) {
        // 复用 145 已有解析/去重/安装链; mime 透传(扩展类型用)。
        // m11: name/path 来自 OfflineItemResolver.classify 的语义分类结果(bestName/bestPath),
        //   不再假设 T=完整路径 / i0=裸文件名 的固定字段语义(.82 上已漂移: i0=content uri、
        //   T=目录名)。waitAndInstall 需"裸文件名"在下载目录检索, 故优先用 name;
        //   name 缺失时从 path 取 basename。修正前直接传 path(完整路径)会检索失败。
        // R2-M1: name 缺失且 path 是 content:// URI 时必须**原样透传**, 不能 new File(uri).getName()
        //   —— 后者只得到 MediaStore 行 ID(如 "1000002660") 而非文件名, 会击穿 waitAndInstall
        //   的 content:// 快速通道(fileName.startsWith("content://") → ACTION_VIEW +
        //   FLAG_GRANT_READ_URI_PERMISSION 直接打开), 落入 60s 轮询找名为 "1000002660" 的文件
        //   而永不可得 → 自动打开失败。透传后命中快速通道。
        String fileName;
        if (name != null && !name.isEmpty()) {
            fileName = name;
        } else if (path != null && path.startsWith("content://")) {
            fileName = path;
        } else if (path != null && path.contains("/")) {
            fileName = new File(path).getName();
        } else {
            fileName = path;
        }
        if (fileName == null || fileName.isEmpty()) {
            XposedBridge.log(HookEntry.TAG + ": [" + HookEntry.engineVersion
                    + "] no file name for auto-open, skip");
            return;
        }
        waitAndInstall(fileName, cl, mime);
    }


    /** v2.2.1: 按文件名推断 MIME(用于扩展类型自动打开) */
    private static String mimeFromName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".7z")) return "application/x-7z-compressed";
        if (n.endsWith(".rar")) return "application/x-rar-compressed";
        // RC3(b) 修复: 补 .tar/.gz/.tgz/.tar.gz 映射(旧版返回 null → 上层回退成 APK_MIME, 用安装器 MIME 打开压缩包必然失败)。
        // 顺序重要: .tar.gz 必须先于 .gz/.tar 判定(endsWith 后缀匹配)。
        if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) return "application/gzip";
        if (n.endsWith(".tar")) return "application/x-tar";
        if (n.endsWith(".gz")) return "application/gzip";
        // 修复B2: 补 .bz2/.xz 映射, 与 isExt / isArchiveMime 三处压缩格式集合对齐。
        if (n.endsWith(".bz2")) return "application/x-bzip2";
        if (n.endsWith(".xz")) return "application/x-xz";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".epub")) return "application/epub+zip";
        if (n.endsWith(".mobi")) return "application/x-mobipocket-ebook";
        // v2.3.20: 自定义后缀常见类型的 MIME 提示。这些类型本身不属于内置分类, 但 Chrome/系统
        //   对 application/octet-stream 往往无 VIEW handler → ACTION_VIEW 抛 ActivityNotFoundException
        //   → 用户勾选自定后缀也"不自动打开"。给出可命中处理器的 MIME 以提升打开成功率。
        if (n.endsWith(".bat") || n.endsWith(".cmd")) return "text/plain";
        if (n.endsWith(".ps1") || n.endsWith(".sh")) return "text/x-shellscript";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".xml")) return "text/xml";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".ini") || n.endsWith(".cfg") || n.endsWith(".conf")) return "text/plain";
        if (n.endsWith(".psd")) return "image/vnd.adobe.photoshop";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".heic")) return "image/heic";
        if (n.endsWith(".apks") || n.endsWith(".xapk")) return "application/octet-stream";
        if (n.endsWith(".torrent")) return "application/x-bittorrent";
        return null;
    }

    /**
     * 统一计算 ACTION_VIEW MIME。
     * 具体 MIME 优先，其次按文件名推断；只有确认是 APK 时才使用安装器 MIME，
     * 其他无法识别的自定义后缀使用通用 MIME，交给系统选择可处理该文件的应用。
     */
    private static String resolveOpenMime(String fileName, String mimeOverride) {
        String byName = mimeFromName(fileName);
        if (mimeOverride != null && !mimeOverride.trim().isEmpty()
                && !"application/octet-stream".equalsIgnoreCase(mimeOverride)
                && !"application/unknown".equalsIgnoreCase(mimeOverride)) {
            return mimeOverride;
        }
        if (byName != null && !byName.isEmpty()) return byName;
        if (HookEntry.isApk(mimeOverride, fileName)) return APK_MIME;
        return GENERIC_OPEN_MIME;
    }

    /**
     * 145 的部分完成回调会把展示名称与真实落盘名称分开放置。
     * 先保留 2.0.0 的 name 优先语义；若主名称未命中而 path 的 basename 命中，
     * 则使用 path basename，避免自定义后缀在完成回调的字段漂移下被提前过滤。
     */
    private static String select145FileName(String mime, String name, String path) {
        String primary = name == null ? "" : name.trim();
        String fallback = path == null ? "" : path.trim();
        if (fallback.contains("/")) fallback = new File(fallback).getName();
        if (!primary.isEmpty() && OverwriteAndAutoOpen.shouldAutoOpen(mime, primary)) {
            return primary;
        }
        if (!fallback.isEmpty() && OverwriteAndAutoOpen.shouldAutoOpen(mime, fallback)) {
            return fallback;
        }
        return !primary.isEmpty() ? primary : fallback;
    }
}
