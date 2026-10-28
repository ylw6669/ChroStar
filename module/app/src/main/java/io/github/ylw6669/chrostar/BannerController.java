package io.github.ylw6669.chrostar;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

/**
 * 横幅控制(v1.9.7 引入; v1.15.0 移除自定义时长) —— 下载完成横幅 -> 系统 Toast 替代。
 *
 * 机制:
 * 1. je7.d(OfflineItem,boolean,boolean,boolean) = DownloadMessageUiController 状态更新,
 *    m0==2(完成)时按开关弹 Toast 并标记待拦横幅。
 * 2. nze.c(PropertyModel,boolean) / nze.b(...) = 所有消息横幅统一上屏点,
 *    标记存在时拦截横幅(setResult); 翻译横幅另有 showMessage/create 双重拦截。
 *
 * 只影响下载完成横幅(je7 路径), 翻译/密码等横幅不受 Toast 拦截影响。
 */
public final class BannerController {

    private static final String CLS_JE7 = "je7";
    private static final String CLS_NZE = "nze";
    private static final String CLS_JE7_152 = "ia8";
    private static final String CLS_NZE_152 = "gig";
    private static final String CLS_OFFLINE_ITEM =
            "org.chromium.components.offline_items_collection.OfflineItem";

    /**
     * 下载替代 Toast 的横幅抑制窗口。154 会从公开完成回调和 zc8.d
     * 两条路径派发同一横幅，且 bng.b/c 可能各调用一次；一次性 boolean
     * 会在第一个入口被消费，后续入口就重新显示原生横幅。用短窗口覆盖同一
     * 下载的所有派发入口，窗口外自动失效，不影响后续普通消息。
     */
    private static volatile long sPendingBannerUntil;
    /** Chrome 145 兼容 2.0 的持久一次性标志，不能被现代版本的 5 秒窗口替代。 */
    private static volatile boolean sLegacyPendingBanner;

    /** v2.3.2: 152 完成事件去重(ContentId -> 上次 Toast 时间戳) */
    private static final java.util.Map<Object, Long> sLastToast = new java.util.concurrent.ConcurrentHashMap<>();
    /** 153 公开完成回调没有 ContentId，按规范化文件名独立去重。 */
    private static final java.util.Map<String, Long> sLastInlineToast =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 5000L;

    /**
     * 145 completion callbacks use different name sources: c9o.run supplies the
     * display file name, while zkg.f often supplies a MediaStore content URI.
     * Normalize the identity first so one download cannot toast twice.
     */
    private static volatile String sLastLegacyToastKey;
    private static volatile long sLastLegacyToastAt;
    /**
     * 145 的多个完成回调经常分别给出 content URI 与真实文件名。除文件名键外，
     * 同时记录 URI→文件名的映射，任一方向到达都能识别同一次下载。
     */
    private static final java.util.Map<String, String> sLegacyUriNames =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> sLegacyUriTimes =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void armBannerSuppression() {
        if (ProfileTraits.current().legacyBannerOneShot) {
            // 2.0 实证逻辑：145 的横幅回调可能晚于下载完成事件，不能用短时间窗截断。
            sLegacyPendingBanner = true;
        } else {
            sPendingBannerUntil = android.os.SystemClock.elapsedRealtime() + DEDUP_WINDOW_MS;
        }
    }

    /**
     * Chrome 145 的下载完成通知有时不经过 je7.d，而是由 zkg.f/c9o.run
     * 直接驱动。所有完成入口都调用这里，保证 Toast 与原生横幅仍然二选一。
     */
    public static void onLegacyDownloadCompleted(String mime, String name, String path) {
        try {
            boolean apkToast = HookEntry.readPrefBoolean(
                    HookEntry.KEY_BANNER_APK_TOAST, true);
            boolean allToast = HookEntry.readPrefBoolean(
                    HookEntry.KEY_BANNER_ALL_TOAST, false);
            boolean isApk = HookEntry.isApk(mime, name != null ? name : path);
            if (!allToast && !(apkToast && isApk)) return;

            String contentUri = contentUri(path, name);
            String fileName = legacyFileName(path, name);
            String uriFileName = contentUri == null ? null : sLegacyUriNames.get(contentUri);
            if (fileName == null && uriFileName != null) fileName = uriFileName;

            if (fileName == null) {
                // Chrome 145 的公开/内嵌完成回调可能只给 content URI，且比 c9o/zkg.f
                // 更早触发。此时不能立刻弹“下载文件”：先布防原生横幅拦截，让随后
                // 携带真实文件名的 145 专用入口负责唯一 Toast。若 URI 可解析，也在
                // 后台补齐 URI→文件名映射，供其它回调到达时复用。
                armBannerSuppression();
                XposedBridge.log(HookEntry.TAG
                        + ": [diag] legacy banner identity pending, toast deferred uri=" + contentUri);
                resolveLegacyUriName(contentUri);
                return;
            }
            if (contentUri != null) rememberLegacyUriName(contentUri, fileName);

            String key = legacyToastKey(mime, fileName);
            if (duplicateLegacyToast(key) || duplicateToast(fileName, mime, null)) {
                armBannerSuppression();
                XposedBridge.log(HookEntry.TAG
                        + ": [diag] duplicate legacy banner event, toast skipped key=" + key);
                return;
            }

            showToast("下载完成: " + fileName);
            armBannerSuppression();
            XposedBridge.log(HookEntry.TAG
                    + ": [diag] legacy download banner armed: " + fileName + " key=" + key);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG
                    + ": legacy download banner error -> " + t);
        }
    }

    /** Prefer the real path/name and never use an opaque MediaStore URI as identity. */
    private static String legacyFileName(String path, String name) {
        String[] candidates = {path, name};
        for (String value : candidates) {
            if (value == null || value.isEmpty() || value.startsWith("content://")) continue;
            String candidate = value;
            if (candidate.contains("/")) candidate = candidate.substring(candidate.lastIndexOf('/') + 1);
            if (candidate.contains("\\")) candidate = candidate.substring(candidate.lastIndexOf('\\') + 1);
            int query = candidate.indexOf('?');
            if (query >= 0) candidate = candidate.substring(0, query);
            if (!candidate.isEmpty() && candidate.indexOf('.') > 0) return candidate;
        }
        String candidate = path;
        if (candidate == null || candidate.isEmpty() || candidate.startsWith("content://")) {
            candidate = name;
        }
        if (candidate == null || candidate.isEmpty() || candidate.startsWith("content://")) return null;
        if (candidate.contains("/")) candidate = candidate.substring(candidate.lastIndexOf('/') + 1);
        if (candidate.contains("\\")) candidate = candidate.substring(candidate.lastIndexOf('\\') + 1);
        int query = candidate.indexOf('?');
        if (query >= 0) candidate = candidate.substring(0, query);
        return candidate.isEmpty() ? null : candidate;
    }

    /** Use the normalized file name as the cross-callback identity. */
    private static String legacyToastKey(String mime, String fileName) {
        if (fileName != null && !"下载文件".equals(fileName)) {
            return "file:" + fileName.toLowerCase(java.util.Locale.ROOT);
        }
        return "mime:" + (mime == null ? "" : mime.toLowerCase(java.util.Locale.ROOT));
    }

    private static String contentUri(String path, String name) {
        if (path != null && path.startsWith("content://")) return path;
        if (name != null && name.startsWith("content://")) return name;
        return null;
    }

    private static void rememberLegacyUriName(String uri, String fileName) {
        if (uri == null || fileName == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        sLegacyUriNames.put(uri, fileName);
        sLegacyUriTimes.put(uri, Long.valueOf(now));
        if (sLegacyUriTimes.size() <= 16) return;
        for (java.util.Map.Entry<String, Long> entry : sLegacyUriTimes.entrySet()) {
            if (now - entry.getValue().longValue() >= DEDUP_WINDOW_MS) {
                sLegacyUriTimes.remove(entry.getKey());
                sLegacyUriNames.remove(entry.getKey());
            }
        }
        if (sLegacyUriTimes.size() > 32) {
            sLegacyUriNames.clear();
            sLegacyUriTimes.clear();
        }
    }

    private static void resolveLegacyUriName(final String uri) {
        if (uri == null) return;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OfflineItemResolver.Resolved resolved = OfflineItemResolver.resolveContentUri(
                            HookEntry.getAppContext(null), uri, 3000L);
                    if (resolved == null
                            || resolved.displayName == null
                            || resolved.displayName.isEmpty()) return;
                    rememberLegacyUriName(uri, resolved.displayName);
                    XposedBridge.log(HookEntry.TAG
                            + ": [diag] legacy banner identity resolved uri=" + uri
                            + " name=" + resolved.displayName);
                } catch (Throwable t) {
                    XposedBridge.log(HookEntry.TAG
                            + ": legacy banner identity resolve error -> " + t);
                }
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean duplicateLegacyToast(String key) {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (BannerController.class) {
            if (key != null && key.equals(sLastLegacyToastKey)
                    && now - sLastLegacyToastAt < DEDUP_WINDOW_MS) return true;
            sLastLegacyToastKey = key;
            sLastLegacyToastAt = now;
        }
        return false;
    }

    /** 145 OfflineItem 完成入口的语义适配。 */
    public static void onLegacyOfflineItemCompleted(Object item, ClassLoader cl) {
        onLegacyOfflineItemCompleted(item, cl, null);
    }

    /**
     * 145 OfflineItem 完成入口的语义适配。
     *
     * @param resolvedName 上游已解析出的真实文件名（OfflineItem 的 e0/P 可能挂
     *                     content:// URI，直接当身份会让同一次下载产生两个去重键）
     */
    public static void onLegacyOfflineItemCompleted(Object item, ClassLoader cl,
                                                    String resolvedName) {
        if (item == null) return;
        try {
            OfflineItemResolver.Result r = OfflineItemResolver.classify(item, cl);
            String name = resolvedName;
            if (name == null || name.isEmpty()) name = r.bestName();
            String path = r.bestPath();
            if ((path == null || path.startsWith("content://"))
                    && resolvedName != null && !resolvedName.isEmpty()) {
                path = resolvedName;
            }
            onLegacyDownloadCompleted(r.mime, name, path);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG
                    + ": legacy OfflineItem banner error -> " + t);
        }
    }

    /** 文件名优先、ContentId 兜底的跨回调去重。 */
    private static boolean duplicateToast(String name, String mime, Object contentId) {
        long now = android.os.SystemClock.elapsedRealtime();
        java.util.ArrayList<String> keys = new java.util.ArrayList<>(3);
        if (name != null && !name.isEmpty()) {
            keys.add("name:" + name.toLowerCase(java.util.Locale.ROOT));
        }
        if (contentId != null) {
            keys.add("id:" + String.valueOf(contentId));
        }
        if (keys.isEmpty() && mime != null && !mime.isEmpty()) {
            keys.add("mime:" + mime.toLowerCase(java.util.Locale.ROOT));
        }
        for (String key : keys) {
            Long last = sLastToast.get(key);
            if (last != null && now - last.longValue() < DEDUP_WINDOW_MS) {
                return true;
            }
        }
        for (String key : keys) {
            sLastToast.put(key, Long.valueOf(now));
        }
        if (sLastToast.size() > 64) sLastToast.clear();
        return false;
    }

    private BannerController() {
    }

    private static boolean is152() {
        return "chrome152".equals(HookEntry.engineVersion)
                || "chrome153".equals(HookEntry.engineVersion);
    }

    /** v2.4.0: 版本无关 —— 下载消息类名(145 je7 / 152 ia8 / 153+ 结构解析) */
    private static String msgCls(ClassLoader cl) {
        // 145 必须保持 2.0 的固定路径；结构解析不能把未来同名类误接到旧语义。
        if ("chrome145".equals(HookEntry.engineVersion)) return CLS_JE7;
        if ("chrome152".equals(HookEntry.engineVersion)) return CLS_JE7_152;
        String s = ChromeSymbols.downloadMessageOrFallback(cl);
        if (s != null) return s;
        if ("chrome153".equals(HookEntry.engineVersion)) return "sa8";
        if (is152()) return CLS_JE7_152;
        if ("chrome_symbolic".equals(HookEntry.engineVersion)) return null;
        return CLS_JE7;
    }

    /** v2.4.0: 版本无关 —— 消息分发器类名(145 nze / 152 gig / 153+ 结构解析) */
    private static String dispatchCls(ClassLoader cl) {
        // 145/152 保持各自已验证过的 2.0/152 符号表，不与 symbolic 解析结果混用。
        if ("chrome145".equals(HookEntry.engineVersion)) return CLS_NZE;
        if ("chrome152".equals(HookEntry.engineVersion)) return CLS_NZE_152;
        String s = ChromeSymbols.messageDispatcherOrFallback(cl);
        if (s != null) return s;
        if ("chrome153".equals(HookEntry.engineVersion)) return "bjg";
        if (is152()) return CLS_NZE_152;
        if ("chrome_symbolic".equals(HookEntry.engineVersion)) return null;
        return CLS_NZE;
    }

    /** 安装 hook(主进程) */
    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookDownloadBanner(lpparam);
        // This fallback cannot run on Chrome 145. Its public callback often sees
        // a content URI before c9o/zkg.f resolves the real file name, producing a
        // second "下载文件" Toast ahead of the verified 145-specific Toast.
        if (ProfileTraits.current().inlineBannerFallback
                && !"chrome145".equals(HookEntry.engineVersion)) {
            hookInlineDownloadBanner153(lpparam);
            // Chrome 154 的下载完成提示有时复用已有 MessageContainer 子项，
            // 不再经过 bng.c/bng.b 的“首次添加”入口；在最终 View 加入容器时
            // 做一次内容级拦截，保持 Toast 与原生横幅二选一。
            hookMessageContainer(lpparam);
        }
        hookTranslateBanner(lpparam);
        hookTranslateCreate(lpparam);
        hookBannerDisplay(lpparam);
    }

    /**
     * Chrome 153+ 的消息容器最终显示入口。154 下载完成文案来自 plurals
     * download-complete（中文为“文件已下载完毕”），在某些路径中只更新/复用
     * 既有消息项，bng.c/bng.b 不会再次调用。只检查待抑制窗口内且 View 文本
     * 明确包含下载完成语义的消息，避免误伤翻译、密码和普通操作提示。
     */
    private static void hookMessageContainer(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> viewCls = android.view.View.class;
            XposedHelpers.findAndHookMethod(
                    "org.chromium.components.messages.MessageContainer",
                    lpparam.classLoader,
                    "a",
                    viewCls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!isModernBannerPending()) return;
                                Object view = param.args[0];
                                if (!(view instanceof android.view.View)
                                        || !containsDownloadCompletionText((android.view.View) view)) {
                                    return;
                                }
                                // 不能直接跳过 MessageContainer.a：Chrome 154 的 qlg 动画
                                // 管理器仍会按“消息已加入”读取 childAt(0).getHeight()，
                                // 跳过添加会留下空子项并在 onResume/动画回调中 NPE。
                                // 保留对象进入队列，仅隐藏实际 View，维持 Chrome 内部状态机。
                                ((android.view.View) view).setVisibility(android.view.View.GONE);
                                ((android.view.View) view).setAlpha(0.0f);
                                // 兜底入口已经处理了这一次目标横幅，立即消费窗口。
                                // 否则 APK 模式下随后几秒内其它下载的完成/进度消息也会
                                // 被误认为同一个目标而隐藏。
                                sPendingBannerUntil = 0L;
                                XposedBridge.log(HookEntry.TAG
                                        + ": [diag] download banner hidden via MessageContainer.a");
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": MessageContainer hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked MessageContainer.a (download-complete banner fallback)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG
                    + ": hook MessageContainer.a failed -> " + t);
        }
    }

    private static boolean isModernBannerPending() {
        return !ProfileTraits.current().legacyBannerOneShot
                && sPendingBannerUntil > android.os.SystemClock.elapsedRealtime();
    }

    private static boolean containsDownloadCompletionText(android.view.View view) {
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (isDownloadCompletionText(text)) return true;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsDownloadCompletionText(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static boolean isDownloadCompletionText(CharSequence text) {
        if (text == null) return false;
        String s = text.toString().toLowerCase(java.util.Locale.ROOT);
        return s.contains("下载完成") || s.contains("下载完毕")
                || s.contains("download complete");
    }

    /**
     * 从 PropertyModel 的值包装器中提取明确的完成文案。
     * 返回 null 表示当前模型无法读取/没有可判定文本，false 表示明确不是完成消息。
     */
    private static Boolean downloadCompletionModel(Object model) {
        if (model == null) return null;
        boolean sawText = false;
        try {
            Object values = XposedHelpers.getObjectField(model, "b");
            if (!(values instanceof java.util.Map)) return null;
            for (Object value : ((java.util.Map<?, ?>) values).values()) {
                Object unwrapped = value;
                if (value != null) {
                    try {
                        Object a = XposedHelpers.getObjectField(value, "a");
                        if (a != null) unwrapped = a;
                    } catch (Throwable ignored) {
                    }
                }
                if (unwrapped instanceof CharSequence) {
                    sawText = true;
                    if (isDownloadCompletionText((CharSequence) unwrapped)) return Boolean.TRUE;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return sawText ? Boolean.FALSE : null;
    }

    /**
     * Chrome 153 普通附件下载不一定经过 sa8.d，但必经公开的
     * DownloadController.onDownloadCompleted。before-hook 先设置拦截标志，确保原方法
     * 同步派发下载横幅时 bjg.b/c 能将其替换为 Toast。
     */
    private static void hookInlineDownloadBanner153(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> tabCls = XposedHelpers.findClass(
                    "org.chromium.chrome.browser.tab.Tab", lpparam.classLoader);
            Class<?> infoCls = XposedHelpers.findClass(
                    "org.chromium.chrome.browser.download.DownloadInfo", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "org.chromium.chrome.browser.download.DownloadController",
                    lpparam.classLoader,
                    "onDownloadCompleted",
                    tabCls,
                    infoCls,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object info = param.args[1];
                                if (info == null) return;
                                OfflineItemResolver.Result fields =
                                        OfflineItemResolver.classify(info, info.getClass().getClassLoader());
                                String mime = fields.mime;
                                String path = fields.bestPath();
                                String name = fields.bestName();
                                if (!"chrome_symbolic".equals(HookEntry.engineVersion)) {
                                    if (mime == null) mime = safeStringField(info, "c");
                                    if (path == null) path = safeStringField(info, "e");
                                    if (name == null) name = safeStringField(info, "g");
                                }
                                String displayName = inlineDisplayName(name, path);
                                boolean apkToast = HookEntry.readPrefBoolean(
                                        HookEntry.KEY_BANNER_APK_TOAST, true);
                                boolean allToast = HookEntry.readPrefBoolean(
                                        HookEntry.KEY_BANNER_ALL_TOAST, false);
                                if (!allToast && !(apkToast && HookEntry.isApk(mime, displayName))) {
                                    return;
                                }
                                String key = displayName == null
                                        ? String.valueOf(mime)
                                        : displayName.toLowerCase(java.util.Locale.ROOT);
                                long now = android.os.SystemClock.elapsedRealtime();
                                Long last = sLastInlineToast.get(key);
                                if (last != null && now - last.longValue() < DEDUP_WINDOW_MS) {
                                    armBannerSuppression();
                                    return;
                                }
                                if (duplicateToast(displayName, mime, null)) {
                                    armBannerSuppression();
                                    XposedBridge.log(HookEntry.TAG + ": [diag] duplicate inline toast skipped: "
                                            + displayName);
                                    return;
                                }
                                sLastInlineToast.put(key, Long.valueOf(now));
                                if (sLastInlineToast.size() > 32) sLastInlineToast.clear();
                                showToast("下载完成: "
                                        + (displayName == null ? "下载文件" : displayName));
                                armBannerSuppression();
                                XposedBridge.log(HookEntry.TAG
                                        + ": [" + HookEntry.engineVersion
                                        + "] inline download Toast: " + displayName);
                                LogTail.add("Banner", "toast: " + displayName);
                                LogTail.push(HookEntry.getAppContext(null));
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": [" + HookEntry.engineVersion
                                        + "] inline banner hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": [" + HookEntry.engineVersion
                    + "] hooked DownloadController.onDownloadCompleted"
                    + " (download-banner-toast fallback)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG
                    + ": [" + HookEntry.engineVersion
                    + "] hook inline download banner failed -> " + t);
        }
    }

    private static String inlineDisplayName(String name, String path) {
        String candidate = path;
        if (candidate == null || candidate.isEmpty() || candidate.startsWith("content://")) {
            candidate = name;
        }
        if (candidate == null || candidate.isEmpty() || candidate.startsWith("content://")) {
            return null;
        }
        int query = candidate.indexOf('?');
        if (query >= 0) candidate = candidate.substring(0, query);
        int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        if (slash >= 0 && slash < candidate.length() - 1) {
            candidate = candidate.substring(slash + 1);
        }
        return candidate.isEmpty() ? null : candidate;
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

    // ------------------------------------------------------------------
    // v1.10.3: 最彻底拦截 —— native 创建翻译消息对象时直接断掉
    // TranslateMessage.create(WebContents, long, int) 是 native 调用的静态工厂,
    // 返回 null 则 native 拿不到对象, 横幅无从显示(create 内部本身有 null 兜底路径)。
    // ------------------------------------------------------------------
    private static void hookTranslateCreate(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> tmCls = XposedHelpers.findClass(
                    "org.chromium.components.translate.TranslateMessage",
                    lpparam.classLoader);
            Class<?> wcCls = XposedHelpers.findClass(
                    "org.chromium.content_public.browser.WebContents",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(tmCls, "create", wcCls, long.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                boolean hide = HookEntry.readPrefBoolean(
                                        HookEntry.KEY_HIDE_TRANSLATE_BANNER, true);
                                if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                                        + ": TranslateMessage.create called, hide=" + hide);
                                if (hide) {
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": translate create hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked TranslateMessage.create (block translate banner)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook TranslateMessage.create failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // v1.10.0: 隐藏翻译幅幅开关
    // TranslateMessage.showMessage(String, String, String, boolean)
    // = 翻译提示幅幅的显示入口(native 触发),
    // 拦截后翻译提示完全不显示。
    // ------------------------------------------------------------------
    private static void hookTranslateBanner(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> tmCls = XposedHelpers.findClass(
                    "org.chromium.components.translate.TranslateMessage",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(tmCls, "showMessage",
                    String.class, String.class, String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                boolean hide = HookEntry.readPrefBoolean(
                                        HookEntry.KEY_HIDE_TRANSLATE_BANNER, true);
                                if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                                        + ": TranslateMessage.showMessage called, hide=" + hide);
                                if (hide) {
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": translate banner hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked TranslateMessage.showMessage (hide translate banner)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook TranslateMessage failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // je7.d(OfflineItem, boolean, boolean, boolean) → 标记下载完成横幅
    // ------------------------------------------------------------------
    private static void hookDownloadBanner(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final String jc = msgCls(lpparam.classLoader);
            if (jc == null) {
                XposedBridge.log(HookEntry.TAG
                        + ": [diag] download banner message class unresolved; public callback only");
                return;
            }
            Class<?> je7 = XposedHelpers.findClass(jc, lpparam.classLoader);
            Method completedMethod = ChromeSymbols.downloadMessageCompletedMethod(je7);
            if (completedMethod == null) {
                XposedBridge.log(HookEntry.TAG
                        + ": [diag] download banner completion method ambiguous/unresolved on " + jc);
                return;
            }
            XposedBridge.hookMethod(completedMethod, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object item = param.args[0];
                                if (item == null) return;
                                Integer state = OfflineItemResolver.completionState(item);
                                if (state == null || state.intValue() != 2) return;
                                // 1B-1: 用 OfflineItemResolver 语义分类取代写死字母读取(免符号、版本无关)。
                                //   classify 纯反射同步执行(内部 instanceof String 类型安全, 不会 CCE),
                                //   不做任何 IPC; content:// 的真实名解析涉及 ContentResolver 查询,
                                //   绝不在横幅回调线程执行(防 ANR) → 这里只用同步可得的 bestName/mime,
                                //   判不出时下游回退既有 "下载文件" 文案(fail-safe, 不误报具体文件名)。
                                OfflineItemResolver.Result r =
                                        OfflineItemResolver.classify(item, lpparam.classLoader);
                                String mime = r.mime;
                                String name = r.bestName();
                                boolean isApk = HookEntry.isApk(mime, name);

                                onLegacyDownloadCompleted(mime, name, r.bestPath());
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": banner (je7.d) error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked " + je7.getName() + "." + completedMethod.getName()
                    + " (download-banner-toast control, structural)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook je7.d failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // nze.b(PropertyModel, WebContents, int, boolean) / nze.c(PropertyModel, boolean)
    // → 拦截横幅(Toast) + 自定义显示时长
    // 注意: 下载横幅走 c(), 翻译横幅走 b()(TranslateMessage.showMessage), 两个都 hook。
    // v1.9.8: 修复 after 中 getResult()==null 对 void 方法恒真导致时长从不生效的 bug,
    //         改用 mIntercepted 标志位判断是否被拦截。
    // ------------------------------------------------------------------
    private static void hookBannerDisplay(XC_LoadPackage.LoadPackageParam lpparam) {
        // v1.10.2: 分开写死参数类型, 不再用 Class[] 数组传递
        // (LSPosed varargs 不展开数组, 导致 hook nze.b/c 注册失败)
        hookNzeC(lpparam);
        hookNzeB(lpparam);
    }

    private static void hookNzeC(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final String nc = dispatchCls(lpparam.classLoader);
            if (nc == null) return;
            Class<?> nze = XposedHelpers.findClass(nc, lpparam.classLoader);
            Method method = ChromeSymbols.messageDispatcherCompactMethod(nze);
            if (method == null && !"chrome_symbolic".equals(HookEntry.engineVersion)) {
                XposedBridge.hookAllMethods(nze, "c", new BannerHook("c"));
            } else if (method != null) {
                XposedBridge.hookMethod(method, new BannerHook(method.getName()));
            } else {
                XposedBridge.log(HookEntry.TAG + ": [diag] compact banner dispatcher unresolved");
                return;
            }
            XposedBridge.log(HookEntry.TAG
                    + ": hooked " + nze.getName() + " compact banner dispatcher");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook nze.c failed -> " + t);
        }
    }

    private static void hookNzeB(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final String nc2 = dispatchCls(lpparam.classLoader);
            if (nc2 == null) return;
            Class<?> nze = XposedHelpers.findClass(nc2, lpparam.classLoader);
            Method method = ChromeSymbols.messageDispatcherWebMethod(nze);
            if (method == null && !"chrome_symbolic".equals(HookEntry.engineVersion)) {
                XposedBridge.hookAllMethods(nze, "b", new BannerHook("b"));
            } else if (method != null) {
                XposedBridge.hookMethod(method, new BannerHook(method.getName()));
            } else {
                XposedBridge.log(HookEntry.TAG + ": [diag] WebContents banner dispatcher unresolved");
                return;
            }
            XposedBridge.log(HookEntry.TAG
                    + ": hooked " + nze.getName() + " WebContents banner dispatcher");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook nze.b failed -> " + t);
        }
    }

    /** nze.b / nze.c 共用 hook 逻辑 */
    private static final class BannerHook extends XC_MethodHook {
        private final String mName;
        private boolean mIntercepted;

        BannerHook(String name) {
            this.mName = name;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            mIntercepted = false;
            try {
                // v1.10.1: 隐藏翻译横幅双保险 —— 消息 ID == 29 即翻译横幅
                if (HookEntry.readPrefBoolean(
                        HookEntry.KEY_HIDE_TRANSLATE_BANNER, true)) {
                    Object model = param.args[0];
                    boolean isTrans = isTranslateMessage(model,
                            param.thisObject.getClass().getClassLoader());
                    if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                            + ": nze." + mName + " called, isTranslate=" + isTrans);
                    if (isTrans) {
                        mIntercepted = true;
                        param.setResult(null);
                        return;
                    }
                }
                // 拦截下载完成横幅。一个下载可能同步触发 bng.c、异步触发 bng.b，
                // 因此不能在第一次入口后清除标志，而要让短窗口覆盖所有入口。
                boolean legacyPending = ProfileTraits.current().legacyBannerOneShot
                        && sLegacyPendingBanner;
                long pendingUntil = sPendingBannerUntil;
                boolean modernPending = !ProfileTraits.current().legacyBannerOneShot
                        && pendingUntil > android.os.SystemClock.elapsedRealtime();
                // 所有版本优先要求明确的下载完成文案，避免 APK 模式误拦普通消息。
                // 145 若 R8 模型在此入口暂时无法读取文本，则保留 2.0 的一次性兜底；
                // 只消费一次，不改变 2.0 的成功语义。
                Boolean completion = downloadCompletionModel(
                        param.args.length == 0 ? null : param.args[0]);
                // Chrome 145 must retain the exact 2.0 semantics. Its nze model
                // does not expose a reliable completion text: FALSE can mean the
                // download banner model is already populated with a non-text value,
                // not that this is a different banner. Once je7/c9o/zkg armed the
                // flag, the next nze entry is the platform's download banner.
                // Modern engines keep the stricter text gate below.
                boolean legacyIntercept = legacyPending;
                boolean modernIntercept = modernPending && completion == Boolean.TRUE;
                if (legacyIntercept || modernIntercept) {
                    if (legacyPending) sLegacyPendingBanner = false;
                    mIntercepted = true;
                    param.setResult(null);
                    XposedBridge.log(HookEntry.TAG + ": [diag] download banner suppressed via "
                            + param.thisObject.getClass().getSimpleName() + "." + mName);
                    LogTail.add("Banner", "suppressed via "
                            + param.thisObject.getClass().getSimpleName() + "." + mName);
                    LogTail.push(HookEntry.getAppContext(null));
                    if (modernPending) {
                        // 本次目标已由 bng 入口处理；后续独立下载不得继承窗口。
                        sPendingBannerUntil = 0L;
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG
                        + ": banner (nze." + mName + " before) error -> " + t);
            }
        }
    }

    /** v2.3.2/R2-m13: 判断 PropertyModel 是否为翻译横幅(消息 ID == 29)。
     *  152(.82)实证: 消息 ID 属性键是 ghg.a(x8l), TranslateMessage.showMessage 内
     *    `s8lVar.c(ghg.a, 29)` 即翻译横幅; 145 的消息 ID 键才是 qye.a。
     *  旧实现写死 145 短名 "qye" → 152 上类虽存在(无关 POJO)但静态字段 a 语义不符 →
     *   取值恒非 29 → 翻译横幅“消息 ID”双保险在 152 静默失效(主通道 showMessage/create 仍有效,
     *   故此路径为冗余保险, 但违反了硬编码混淆名纪律)。按引擎选键类。 */
    private static boolean isTranslateMessage(Object model, ClassLoader cl) {
        // This is only a redundant message-id guard. Future builds must not reuse a
        // historical R8 key class; the stable TranslateMessage hooks remain active.
        if ("chrome_symbolic".equals(HookEntry.engineVersion)) return false;
        try {
            String keyCls = "chrome153".equals(HookEntry.engineVersion)
                    ? "big" : (is152() ? "ghg" : "qye");
            Class<?> kc = Class.forName(keyCls, false, cl);
            Object idKey = XposedHelpers.getStaticObjectField(kc, "a");
            if (idKey == null) return false;
            Object id = XposedHelpers.callMethod(model, "g", idKey);
            return id instanceof Integer && ((Integer) id).intValue() == 29;
        } catch (Throwable t) {
            return false;
        }
    }


    private static void showToast(final String msg) {
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.content.Context app = HookEntry.getAppContext(null);
                        if (app != null) {
                            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
