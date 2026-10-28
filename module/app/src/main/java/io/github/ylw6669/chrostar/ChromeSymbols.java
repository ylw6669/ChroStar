// ChroStar v2.4.0 — 版本无关符号解析器(未来小版本适配核心)
//
// 背景: 145/152 适配此前把 R8 混淆短名写死(iq4/l04/jza/w5c/ia8/je7/k3r/tuo/gig/nze/...)。
//   R8 每次构建重分配短名 → Chrome 升到 153 时全部写死名失效 → 判 unknown → 大量功能 UNAVAILABLE。
//
// 方案: 用 **稳定公开类名** 作锚点, 通过 **字段/方法结构签名**(类型 + 参数布尔模式 + 返回类型)
//   在运行时“发现”混淆类, 而非写死字母。145/152 结构解析失败时回退旧短名表(行为零变化);
//   153+ 只要结构不变即可自动适配, 无需重打符号表。
//
// 实证锚点(152.0.7977.82 jadx + 145.0.7632.218 apktool):
//   - PartnerBrowserCustomizations.<field>  : 145 -> Ljza / 152 -> Lw5c (HomepageManager; 具备 (boolean..)->GURL)
//   - ChromeTabbedActivity 返回 TabCreator 的方法(r/i3): 145 -> l04 / 152 -> iq4
//   - ChromeTabbedActivity 字段(O2)          : 145 -> Ltuo / 152 -> Lk3r (TabModelSelector; 具备 (boolean)->TabModel)
//   - DownloadManagerService.<field>          : 145 -> Lje7 / 152 -> Lia8 (下载消息类; 具备 d(OfflineItem,Z,Z,Z))
//   - 下载消息类 i() 返回类型                  : 145 -> Lnze / 152 -> Lgig (MessageUiController; 具备 b(PM,WC,int,Z))
//
// 设计原则: 绝不误判 —— 只按精确结构签名匹配; 解析不到一律返回 null, 交由调用方回退。
package io.github.ylw6669.chrostar;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ChromeSymbols {

    // ---- 稳定公开类名(不随 R8 混淆; 跨 145/152/153 同名) ----
    private static final String CLS_CHROME_TABBED_ACTIVITY =
            "org.chromium.chrome.browser.ChromeTabbedActivity";
    private static final String CLS_PARTNER_CUSTOMIZATIONS =
            "org.chromium.chrome.browser.partnercustomizations.PartnerBrowserCustomizations";
    private static final String CLS_DOWNLOAD_MGR_SERVICE =
            "org.chromium.chrome.browser.download.DownloadManagerService";
    private static final String CLS_OFFLINE_ITEM =
            "org.chromium.components.offline_items_collection.OfflineItem";
    private static final String CLS_PROPERTY_MODEL = "org.chromium.ui.modelutil.PropertyModel";
    private static final String CLS_WEB_CONTENTS =
            "org.chromium.content_public.browser.WebContents";
    private static final String CLS_LOAD_URL_PARAMS =
            "org.chromium.content_public.browser.LoadUrlParams";
    private static final String CLS_TAB = "org.chromium.chrome.browser.tab.Tab";
    private static final String CLS_TAB_MODEL =
            "org.chromium.chrome.browser.tabmodel.TabModel";
    private static final String CLS_GURL = "org.chromium.url.GURL";

    // ---- 解析结果缓存(per ClassLoader; 同一进程 loader 稳定) ----
    private static volatile ClassLoader sCachedLoader;
    private static volatile String sHomepageManager;
    private static volatile String sTabCreator;
    private static volatile String sTabSelector;
    private static volatile String sDownloadMessage;
    private static volatile String sMessageDispatcher;
    private static volatile boolean sResolved;
    /** v2.5.0: 中心建页入口方法名(6/7 参, 第三个参数为 TabLaunchType), 来自缓存或结构解析 */
    private static volatile String sTabCreatorCentralCreate;
    /** v2.5.0: 本次解析结果(槽位 → 取值 + 来源标记 + 缺失集合) */
    private static final ResolvedCaps sCaps = new ResolvedCaps();
    /** 供 LogTail 读取的实时状态快照，避免业务事件回传覆盖成旧缓存状态。 */
    private static volatile int sStatusCount;
    private static volatile String sStatusSources = "";
    private static volatile String sStatusMissing = "";
    /** v2.5.0: 配置档案跟随的 Chrome 包名 */
    private static final String CHROME_PKG = "com.android.chrome";

    private ChromeSymbols() {
    }

    private static Class<?> safeClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isTab(Class<?> c) {
        if (c == null) return false;
        try {
            Class<?> tab = Class.forName(CLS_TAB, false, c.getClassLoader());
            return tab.isAssignableFrom(c) || c.isAssignableFrom(tab);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 触发一次解析(幂等); 对同一 loader 只解析一次。
     *
     *  <p>v2.5.0 解析管线(命中即返回): 磁盘缓存(逐项反射校验) → 锚点结构解析 →
     *  DexKit 特征查询(后台补缺, 结果落盘) → 缺失槽位明确报告(fail-closed)。
     *  缓存按 Chrome versionCode 分文件, 版本变化天然失效; 设置页可写
     *  rescan_request_at 时间戳强制重扫。 */
    public static synchronized void ensureResolved(ClassLoader cl) {
        if (cl == null) return;
        if (sResolved && sCachedLoader == cl) return;
        sCachedLoader = cl;

        android.content.Context ctx = HookEntry.getAppContext(cl);
        final long versionCode = ctx == null ? -1L
                : CapsCache.installedVersionCode(ctx, CHROME_PKG);
        boolean forced = false;
        java.util.Map<String, String> cached = java.util.Collections.emptyMap();
        if (ctx != null && versionCode > 0) {
            forced = isRescanRequested(ctx, versionCode);
            if (forced) {
                CapsCache.invalidate(ctx, versionCode);
            } else {
                cached = CapsCache.load(ctx, versionCode);
            }
        }

        Set<Capability> missing = new LinkedHashSet<Capability>();
        sHomepageManager = pick(cl, Capability.HOMEPAGE_MANAGER, cached,
                resolveHomepageManager(cl), missing);
        sTabCreator = pick(cl, Capability.TAB_CREATOR, cached,
                resolveTabCreator(cl), missing);
        sTabSelector = pick(cl, Capability.TAB_SELECTOR, cached,
                resolveTabSelector(cl), missing);
        sDownloadMessage = pick(cl, Capability.DOWNLOAD_MESSAGE, cached,
                resolveDownloadMessage(cl), missing);
        sMessageDispatcher = pick(cl, Capability.MESSAGE_DISPATCHER, cached,
                resolveMessageDispatcher(cl, classOf(sDownloadMessage, cl)), missing);
        sTabCreatorCentralCreate = cachedValue(cl, Capability.TAB_CREATOR_CENTRAL_CREATE, cached);
        if (sTabCreatorCentralCreate == null) {
            Method central = tabCreatorCentralCreateMethod(classOf(sTabCreator, cl));
            sTabCreatorCentralCreate = central == null ? null : central.getName();
            if (sTabCreatorCentralCreate != null
                    && sCaps.sourceOf(Capability.TAB_CREATOR_CENTRAL_CREATE) == null) {
                sCaps.put(Capability.TAB_CREATOR_CENTRAL_CREATE, sTabCreatorCentralCreate,
                        ResolvedCaps.SRC_ANCHOR);
            }
        }

        // 新版本/手动重扫时先同步补齐缺失槽位。DEX 查询会按 Chrome versionCode 缓存，
        // 因此只有升级后的第一次启动需要承担这部分成本；后续启动直接走 CapsCache。
        // 这一步必须在 Hook 安装前完成，否则第一次启动会整会话降级，用户必须再启动一次。
        java.util.Map<String, String> dexResolved = java.util.Collections.emptyMap();
        if (!missing.isEmpty() && ctx != null && versionCode > 0L && DexKitResolver.available()) {
            long scanStarted = android.os.SystemClock.elapsedRealtime();
            dexResolved = DexKitResolver.resolveMissing(cl, missing);
            for (java.util.Map.Entry<String, String> en : dexResolved.entrySet()) {
                Capability cap = Capability.byId(en.getKey());
                if (cap != null && applyResolved(cap, en.getValue())) {
                    missing.remove(cap);
                }
            }
            de.robv.android.xposed.XposedBridge.log(HookEntry.TAG
                    + ": [capabilities] first-launch dex resolve took "
                    + (android.os.SystemClock.elapsedRealtime() - scanStarted) + "ms, found="
                    + dexResolved.keySet() + ", remaining=" + missing);
        }
        sResolved = true;
        sStatusCount = sCaps.size();
        sStatusSources = sCaps.sourceSummary();
        sStatusMissing = missing.isEmpty() ? "" : missing.toString();

        Class<?> creator = classOf(sTabCreator, cl);
        Class<?> message = classOf(sDownloadMessage, cl);
        Class<?> dispatcher = classOf(sMessageDispatcher, cl);
        de.robv.android.xposed.XposedBridge.log(HookEntry.TAG
                + ": [capabilities] home=" + value(sHomepageManager)
                + ", tabCreator=" + value(sTabCreator)
                + ", centralCreate=" + value(sTabCreatorCentralCreate)
                + ", tabSelector=" + value(sTabSelector)
                + ", downloadMessage=" + value(sDownloadMessage)
                + ", completed=" + methodName(downloadMessageCompletedMethod(message))
                + ", dispatcher=" + value(sMessageDispatcher)
                + ", compact=" + methodName(messageDispatcherCompactMethod(dispatcher))
                + ", web=" + methodName(messageDispatcherWebMethod(dispatcher))
                + " | cached=" + cached.size()
                + ", dexkit=" + dexResolved.size()
                + ", missing=" + missing
                + " | sources=" + sCaps.sourceSummary());
        LogTail.addAndPush(ctx, "Caps", "missing=" + missing + " sources=" + sCaps.sourceSummary());
        if (!missing.isEmpty()) {
            de.robv.android.xposed.XposedBridge.log(HookEntry.TAG
                    + ": [capabilities] DEGRADED session — " + sCaps.impactSummary()
                    + " (缺槽位的功能本会话不装; DexKit 扫描结果自下次启动生效)");
        }
        // v2.5.8: 锚点解析成功也必须落缓存。此前只缓存 DexKit 补缺结果，所有槽位由
        // ANCHOR 命中时不会生成 caps-*.json，导致 rescan_request_at 永远大于 resolvedAt，
        // 每次启动都被误判为“手动重扫”。
        if (ctx != null && versionCode > 0 && !sCaps.values().isEmpty()
                && (forced || cached.isEmpty() || !dexResolved.isEmpty())) {
            try {
                String source = dexResolved.isEmpty()
                        ? (forced ? "ANCHOR_MANUAL" : "ANCHOR")
                        : (missing.isEmpty() ? "DEXKIT" : "DEXKIT_PARTIAL");
                CapsCache.save(ctx, versionCode,
                        CapsCache.installedVersionName(ctx, CHROME_PKG),
                        new java.util.LinkedHashMap<String, String>(sCaps.values()),
                        source);
            } catch (Throwable t) {
                de.robv.android.xposed.XposedBridge.log(HookEntry.TAG
                        + ": [capabilities] anchor cache save failed -> " + t);
            }
        }
        if (!missing.isEmpty()) {
            de.robv.android.xposed.XposedBridge.log(HookEntry.TAG
                    + ": [capabilities] dexkit exhausted; remaining slots " + missing
                    + " stay fail-closed for this session");
        }
        pushStatus(ctx, versionCode, sCaps.size(), missing,
                forced ? "MANUAL_RESCAN" : (dexResolved.isEmpty()
                        ? (cached.isEmpty() ? "ANCHOR" : "CACHE") : "DEXKIT"));
    }

    /** 把 DexKit 结果写回静态槽位，后续 Hook 安装即可在首次启动直接使用。 */
    private static boolean applyResolved(Capability cap, String value) {
        if (cap == null || value == null || value.isEmpty()) return false;
        switch (cap) {
            case HOMEPAGE_MANAGER:
                sHomepageManager = value;
                break;
            case TAB_CREATOR:
                sTabCreator = value;
                break;
            case TAB_CREATOR_CENTRAL_CREATE:
                sTabCreatorCentralCreate = value;
                break;
            case TAB_SELECTOR:
                sTabSelector = value;
                break;
            case DOWNLOAD_MESSAGE:
                sDownloadMessage = value;
                break;
            case MESSAGE_DISPATCHER:
                sMessageDispatcher = value;
                break;
            default:
                return false;
        }
        sCaps.put(cap, value, ResolvedCaps.SRC_DEXKIT);
        return true;
    }

    static boolean hasCurrentCapabilityStatus() {
        return sStatusCount > 0;
    }

    static int currentCapabilityCount() {
        return sStatusCount;
    }

    static String currentCapabilitySources() {
        return sStatusSources;
    }

    static String currentCapabilityMissing() {
        return sStatusMissing;
    }

    /** 槽位取值: 缓存(校验通过) > 锚点解析值(校验通过) > 记入缺失集合。 */
    private static String pick(ClassLoader cl, Capability cap, java.util.Map<String, String> cached,
                               String anchorValue, Set<Capability> missing) {
        String fromCache = cached.get(cap.id);
        if (fromCache != null && validateSlot(cl, cap, fromCache)) {
            sCaps.put(cap, fromCache, ResolvedCaps.SRC_CACHE);
            return fromCache;
        }
        if (anchorValue != null && validateSlot(cl, cap, anchorValue)) {
            sCaps.put(cap, anchorValue, ResolvedCaps.SRC_ANCHOR);
            return anchorValue;
        }
        missing.add(cap);
        sCaps.markMissing(cap);
        return null;
    }

    private static String cachedValue(ClassLoader cl, Capability cap,
                                      java.util.Map<String, String> cached) {
        String fromCache = cached.get(cap.id);
        if (fromCache != null && validateSlot(cl, cap, fromCache)) {
            sCaps.put(cap, fromCache, ResolvedCaps.SRC_CACHE);
            return fromCache;
        }
        return null;
    }

    /** 槽位值反射校验: 类可加载 + 目标签名存在; 任一项不成立即视为无效(丢弃并重扫)。 */
    private static boolean validateSlot(ClassLoader cl, Capability cap, String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            switch (cap) {
                case HOMEPAGE_MANAGER:
                    return hasHomepageGetter(classOf(value, cl));
                case TAB_CREATOR:
                    return hasCreateNewTab(cl, classOf(value, cl));
                case TAB_SELECTOR:
                    return hasSelectorGetter(cl, classOf(value, cl));
                case DOWNLOAD_MESSAGE:
                    return hasDownloadMessageMethod(cl, classOf(value, cl));
                case MESSAGE_DISPATCHER:
                    return hasDispatcherMethod(cl, classOf(value, cl));
                case TAB_CREATOR_CENTRAL_CREATE:
                    Class<?> creatorClass = classOf(sTabCreator, cl);
                    if (creatorClass == null) return false;
                    for (Method m : creatorClass.getDeclaredMethods()) {
                        if (m.getName().equals(value) && centralCreateScore(m) > 0) return true;
                    }
                    return false;
                default:
                    return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasCreateNewTab(ClassLoader cl, Class<?> creator) {
        Class<?> lup = safeClass(CLS_LOAD_URL_PARAMS, cl);
        Class<?> tab = safeClass(CLS_TAB, cl);
        if (creator == null || lup == null || tab == null) return false;
        for (Method m : creator.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length >= 1 && ps[0] == lup && m.getReturnType() == tab) return true;
        }
        return false;
    }

    private static boolean hasSelectorGetter(ClassLoader cl, Class<?> cls) {
        Class<?> tm = safeClass(CLS_TAB_MODEL, cl);
        if (cls == null || tm == null) return false;
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length < 1 || ps.length > 3 || !allBoolean(ps)) continue;
            if (tm.isAssignableFrom(m.getReturnType())) return true;
        }
        return false;
    }

    private static boolean hasDownloadMessageMethod(ClassLoader cl, Class<?> cls) {
        Class<?> oi = safeClass(CLS_OFFLINE_ITEM, cl);
        if (cls == null || oi == null) return false;
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (isOfflineMessageShape(ps, oi) && m.getReturnType() == void.class) return true;
        }
        return false;
    }

    private static boolean hasDispatcherMethod(ClassLoader cl, Class<?> cls) {
        Class<?> pm = safeClass(CLS_PROPERTY_MODEL, cl);
        Class<?> wc = safeClass(CLS_WEB_CONTENTS, cl);
        if (cls == null || pm == null || wc == null) return false;
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (isDispatcherShape(ps, pm, wc)) return true;
        }
        return false;
    }

    /**
     * v2.5.0: 把解析状态回传给设置页展示(best-effort)。
     *
     * <p>走 ConfigProvider 的 {@code status.*} 白名单写入(该 Provider 运行在模块进程)。
     * MIUI 可能拦截 Chrome 拉起模块进程 → 失败静默忽略, 权威记录仍在 LSPosed 日志与
     * Chrome 侧缓存文件里。
     */
    private static void pushStatus(android.content.Context ctx, long versionCode, int cachedCount,
                                   Set<Capability> missing, String source) {
        if (ctx == null) return;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put("status.caps.chrome_version_code", String.valueOf(versionCode));
            cv.put("status.caps.engine", String.valueOf(HookEntry.engineVersion));
            cv.put("status.caps.source", source == null ? "" : source);
            cv.put("status.caps.cached_count", String.valueOf(cachedCount));
            cv.put("status.caps.missing",
                    missing == null || missing.isEmpty() ? "" : missing.toString());
            cv.put("status.caps.sources", sCaps.sourceSummary());
            cv.put("status.caps.updated_at", String.valueOf(System.currentTimeMillis()));
            cv.put("status.caps.runtime_version_code", String.valueOf(versionCode));
            cv.put("status.caps.runtime_version_name",
                    String.valueOf(CapsCache.installedVersionName(ctx, CHROME_PKG)));
            cv.put("status.caps.runtime_updated_at", String.valueOf(System.currentTimeMillis()));
            ctx.getContentResolver().update(
                    android.net.Uri.parse("content://" + ConfigProvider.AUTHORITY + "/config"),
                    cv, null, null);
        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log(HookEntry.TAG
                    + ": [capabilities] status push failed (best-effort) -> " + t);
        }
    }
    /** 设置页写入的重扫请求是否比当前缓存新(决定强制重扫)。 */
    private static boolean isRescanRequested(android.content.Context ctx, long versionCode) {
        try {
            long requested = HookEntry.readPrefLong(HookEntry.KEY_RESCAN_REQUEST_AT, 0L);
            if (requested <= 0L) return false;
            return requested > CapsCache.resolvedAt(ctx, versionCode);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String value(String value) {
        return value == null ? "UNAVAILABLE" : value;
    }

    private static String methodName(Method method) {
        return method == null ? "UNAVAILABLE" : method.getName();
    }

    private static Class<?> classOf(String name, ClassLoader cl) {
        return name == null ? null : safeClass(name, cl);
    }

    // ------------------------------------------------------------------
    // 1) HomepageManager: PartnerBrowserCustomizations 实例字段中, 类型具备
    //    (boolean...)->GURL 的 getter 者。145=jza(1参), 152=w5c(2参)。
    // ------------------------------------------------------------------
    private static String resolveHomepageManager(ClassLoader cl) {
        Class<?> pbc = safeClass(CLS_PARTNER_CUSTOMIZATIONS, cl);
        if (pbc == null) return null;
        Class<?> gurl = safeClass(CLS_GURL, cl);
        Set<String> matches = new LinkedHashSet<>();
        for (Field f : pbc.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> ft = f.getType();
            if (ft.isPrimitive() || ft.getName().startsWith("java.")) continue;
            for (Method m : ft.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (gurl == null || m.getReturnType() != gurl) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 1) continue;
                boolean allBool = true;
                for (Class<?> p : ps) {
                    if (p != boolean.class && p != Boolean.class) { allBool = false; break; }
                }
                if (allBool) matches.add(ft.getName());
            }
        }
        return unique(matches);
    }

    // ------------------------------------------------------------------
    // 2) TabCreator: ChromeTabbedActivity 的返回类型中, 具备
    //    (LoadUrlParams,...)->Tab 方法者(createNewTab)。145=l04, 152=iq4。
    // ------------------------------------------------------------------
    private static String resolveTabCreator(ClassLoader cl) {
        Class<?> cta = safeClass(CLS_CHROME_TABBED_ACTIVITY, cl);
        Class<?> lup = safeClass(CLS_LOAD_URL_PARAMS, cl);
        if (cta == null || lup == null) return null;
        Set<String> matches = new LinkedHashSet<>();
        Set<String> centralMatches = new LinkedHashSet<>();
        for (Method m : cta.getDeclaredMethods()) {
            if (m.isBridge() || m.isSynthetic()) continue; // v2.5.0: 跳过 bridge, 152 实证会有协变桥方法污染候选

            Class<?> rt = m.getReturnType();
            if (rt.isPrimitive() || rt.getName().startsWith("java.")) continue;
            // Chrome 155 r(boolean) 返回 n2r 接口；接口/抽象类型没有可 hook 的中心建页实现。
            // 拒绝后走 DexKit 语义查询，定位真正的实现类（155 实证为 gu4）。
            if (rt.isInterface() || Modifier.isAbstract(rt.getModifiers())) continue;
            if (isTabCreator(rt, lup)) {
                matches.add(rt.getName());
                // Chrome 152+ 的真实 ChromeTabCreator 具有唯一的七参数中心建页入口。
                // ChromeTabbedActivity 还会返回其他同样含 (LoadUrlParams,...)->Tab
                // 包装方法的类型；只看宽泛签名会在 154 上产生多个候选并失效。
                if (tabCreatorCentralCreateMethod(rt) != null) {
                    centralMatches.add(rt.getName());
                }
            }
        }
        String central = unique(centralMatches);
        if (central != null) return central;
        if (centralMatches.size() > 1 || matches.size() > 1) {
            de.robv.android.xposed.XposedBridge.log(HookEntry.TAG
                    + ": [capabilities] tabCreator candidates broad=" + matches
                    + ", central=" + centralMatches + "; fail-closed");
        }
        return unique(matches);
    }

    private static boolean isTabCreator(Class<?> cand, Class<?> lup) {
        if (cand == null || cand.isInterface() || Modifier.isAbstract(cand.getModifiers())) {
            return false;
        }
        for (Method m : cand.getDeclaredMethods()) {
            if (m.isBridge() || m.isSynthetic()) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length >= 1 && ps[0] == lup && isTab(m.getReturnType())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 3) TabModelSelector: ChromeTabbedActivity 字段中, 具备 (boolean)->TabModel 者。
    //    145=tuo, 152=k3r。
    // ------------------------------------------------------------------
    private static String resolveTabSelector(ClassLoader cl) {
        Class<?> cta = safeClass(CLS_CHROME_TABBED_ACTIVITY, cl);
        Class<?> tm = safeClass(CLS_TAB_MODEL, cl);
        if (cta == null || tm == null) return null;
        Set<String> matches = new LinkedHashSet<>();
        for (Field f : cta.getDeclaredFields()) {
            Class<?> ft = f.getType();
            if (ft.isPrimitive() || ft.getName().startsWith("java.")) continue;
            for (Method m : ft.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length >= 1 && ps.length <= 3 && allBoolean(ps)
                        && tm.isAssignableFrom(m.getReturnType())) {
                    matches.add(ft.getName());
                }
            }
        }
        return unique(matches);
    }

    // ------------------------------------------------------------------
    // 4) 下载消息类: DownloadManagerService 字段中, 具备
    //    (OfflineItem,boolean,boolean,boolean)->void 者。145=je7, 152=ia8。
    // ------------------------------------------------------------------
    private static String resolveDownloadMessage(ClassLoader cl) {
        Class<?> dms = safeClass(CLS_DOWNLOAD_MGR_SERVICE, cl);
        Class<?> oi = safeClass(CLS_OFFLINE_ITEM, cl);
        if (dms == null || oi == null) return null;
        Set<String> matches = new LinkedHashSet<>();
        for (Field f : dms.getDeclaredFields()) {
            Class<?> ft = f.getType();
            if (ft.isPrimitive() || ft.getName().startsWith("java.")) continue;
            for (Method m : ft.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (isOfflineMessageShape(ps, oi) && m.getReturnType() == void.class) {
                    matches.add(ft.getName());
                }
            }
        }
        return unique(matches);
    }

    // ------------------------------------------------------------------
    // 5) 消息分发器: 下载消息类的无参方法返回类型中, 具备
    //    (PropertyModel,WebContents,int,boolean) 者。145=nze, 152=gig。
    // ------------------------------------------------------------------
    private static String resolveMessageDispatcher(ClassLoader cl, Class<?> msgCls) {
        if (msgCls == null) return null;
        Class<?> pm = safeClass(CLS_PROPERTY_MODEL, cl);
        Class<?> wc = safeClass(CLS_WEB_CONTENTS, cl);
        if (pm == null || wc == null) return null;
        Set<String> matches = new LinkedHashSet<>();
        for (Method m : msgCls.getDeclaredMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt.isPrimitive() || rt.getName().startsWith("java.")) continue;
            for (Method dm : rt.getDeclaredMethods()) {
                Class<?>[] ps = dm.getParameterTypes();
                if (isDispatcherShape(ps, pm, wc)) {
                    matches.add(rt.getName());
                }
            }
        }
        return unique(matches);
    }

    private static String unique(Set<String> matches) {
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    // ------------------------------------------------------------------
    // 结果访问(解析失败返回 null → 调用方回退旧短名表)
    // ------------------------------------------------------------------
    public static String homepageManager(ClassLoader cl) {
        ensureResolved(cl);
        return sHomepageManager;
    }

    public static String tabCreator(ClassLoader cl) {
        ensureResolved(cl);
        return sTabCreator;
    }

    public static String tabSelector(ClassLoader cl) {
        ensureResolved(cl);
        return sTabSelector;
    }

    public static String downloadMessage(ClassLoader cl) {
        ensureResolved(cl);
        return sDownloadMessage;
    }

    public static String messageDispatcher(ClassLoader cl) {
        ensureResolved(cl);
        return sMessageDispatcher;
    }

    /**
     * 现代版本的末级兼容回退。结构解析是第一优先级；只有在结构解析没有唯一结果时，
     * 才检查 152/153 已验证的混淆类名，并再次验证目标方法签名。145 不在这些候选中，
     * 因而不会被 future 路由污染。
     */
    public static String homepageManagerOrFallback(ClassLoader cl) {
        String hit = homepageManager(cl);
        if (hit != null) return hit;
        return uniqueValid(cl, new String[]{"n6c", "w5c"}, new ClassValidator() {
            @Override public boolean valid(Class<?> c) { return hasHomepageGetter(c); }
        });
    }

    public static String tabCreatorOrFallback(ClassLoader cl) {
        String hit = tabCreator(cl);
        if (hit != null) return hit;
        return uniqueValid(cl, new String[]{"iq4"}, new ClassValidator() {
            @Override public boolean valid(Class<?> c) { return !tabCreatorCreateMethods(c).isEmpty(); }
        });
    }

    public static String tabSelectorOrFallback(ClassLoader cl) {
        String hit = tabSelector(cl);
        if (hit != null) return hit;
        return uniqueValid(cl, new String[]{"p5r", "k3r", "q3r"}, new ClassValidator() {
            @Override public boolean valid(Class<?> c) {
                return tabSelectorModelGetter(c) != null || tabSelectorCreateMethod(c) != null;
            }
        });
    }

    public static String downloadMessageOrFallback(ClassLoader cl) {
        String hit = downloadMessage(cl);
        if (hit != null) return hit;
        return uniqueValid(cl, new String[]{"sa8", "ia8"}, new ClassValidator() {
            @Override public boolean valid(Class<?> c) { return downloadMessageCompletedMethod(c) != null; }
        });
    }

    public static String messageDispatcherOrFallback(ClassLoader cl) {
        String hit = messageDispatcher(cl);
        if (hit != null) return hit;
        return uniqueValid(cl, new String[]{"bjg", "gig"}, new ClassValidator() {
            @Override public boolean valid(Class<?> c) {
                return messageDispatcherCompactMethod(c) != null
                        || messageDispatcherWebMethod(c) != null;
            }
        });
    }

    private interface ClassValidator { boolean valid(Class<?> c); }

    private static String uniqueValid(ClassLoader cl, String[] names, ClassValidator validator) {
        String hit = null;
        for (String name : names) {
            Class<?> c = safeClass(name, cl);
            if (c == null) continue;
            boolean valid;
            try { valid = validator.valid(c); } catch (Throwable t) { valid = false; }
            if (!valid) continue;
            if (hit != null && !hit.equals(name)) return null;
            hit = name;
        }
        if (hit != null) {
            de.robv.android.xposed.XposedBridge.log(HookEntry.TAG
                    + ": [capabilities] legacy modern fallback selected " + hit);
        }
        return hit;
    }

    private static boolean hasHomepageGetter(Class<?> manager) {
        Class<?> gurl = safeClass(CLS_GURL, manager.getClassLoader());
        for (Method m : manager.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) || ps.length < 1) continue;
            boolean allBool = true;
            for (Class<?> p : ps) if (p != boolean.class && p != Boolean.class) allBool = false;
            if (allBool && (gurl == null || m.getReturnType() == gurl)) return true;
        }
        return false;
    }

    public static boolean hasHomepageManager() { return sHomepageManager != null; }
    public static boolean hasTabCreator() { return sTabCreator != null; }
    public static boolean hasTabSelector() { return sTabSelector != null; }
    public static boolean hasDownloadMessage() { return sDownloadMessage != null; }
    public static boolean hasMessageDispatcher() { return sMessageDispatcher != null; }

    // ------------------------------------------------------------------
    // 版本无关的方法发现(供各 hook 点按结构 hook, 不写死方法字母)
    // ------------------------------------------------------------------

    /** TabCreator 的全部 createNewTab 入口: 首参 LoadUrlParams 且返回 Tab。 */
    public static List<Method> tabCreatorCreateMethods(Class<?> creator) {
        List<Method> out = new ArrayList<>();
        if (creator == null) return out;
        for (Method m : creator.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length >= 1 && ps[0].getName().endsWith("LoadUrlParams")
                    && isTab(m.getReturnType())) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * ChromeTabCreator 的中心创建入口。152/153 的所有包装方法最终都会进入该方法，
     * 第三个参数是 TabLaunchType；只 hook 这一层可避免同一个 LoadUrlParams 被重复处理，
     * 也能可靠区分 Startup(11) 与用户主动新建标签页。
     */
    public static Method tabCreatorCentralCreateMethod(Class<?> creator) {
        if (creator == null) return null;
        // v2.5.0: 先用缓存/DexKit 解析出的方法名直取(免结构扫描), 校验失败再走结构查找。
        String cachedCentral = sTabCreatorCentralCreate;
        if (cachedCentral != null && !cachedCentral.isEmpty()) {
            Method cachedHit = null;
            for (Method m : creator.getDeclaredMethods()) {
                if (!m.getName().equals(cachedCentral) || centralCreateScore(m) <= 0) continue;
                if (cachedHit != null && !sameSignature(cachedHit, m)) return null;
                cachedHit = m;
            }
            if (cachedHit != null) return cachedHit;
        }
        // Chrome 152~155 的中心入口是 7 参；未来若增减可选参数，仍按语义特征定位：
        // LoadUrlParams 开头、返回 Tab，并同时携带 Tab + Intent（旧版还要求第三参 int）。
        // 只 hook 最高分的唯一入口，避免把包装重载当成中心入口造成一次操作执行两遍。
        Method best = null;
        int bestScore = -1;
        boolean tied = false;
        for (Method m : creator.getDeclaredMethods()) {
            int score = centralCreateScore(m);
            if (score <= 0) continue;
            if (score > bestScore) {
                best = m;
                bestScore = score;
                tied = false;
            } else if (score == bestScore && !sameSignature(best, m)) {
                tied = true;
            }
        }
        return tied ? null : best;
    }

    private static boolean sameSignature(Method x, Method y) {
        return x != null && y != null && x.getName().equals(y.getName())
                && java.util.Arrays.equals(x.getParameterTypes(), y.getParameterTypes());
    }

    /** 中心建页入口的跨小版本语义评分；0 = 不是候选项。 */
    private static int centralCreateScore(Method m) {
        if (m == null || m.isBridge() || m.isSynthetic()) return 0;
        Class<?>[] ps = m.getParameterTypes();
        if (ps.length < 6 || !ps[0].getName().endsWith("LoadUrlParams")
                || !isTab(m.getReturnType())) return 0;
        boolean hasTabArg = false;
        boolean hasIntent = false;
        for (int i = 1; i < ps.length; i++) {
            if (isTab(ps[i])) hasTabArg = true;
            if (ps[i] == android.content.Intent.class) hasIntent = true;
        }
        // 包装重载通常只有 LoadUrlParams + 少量控制参数；中心入口同时出现 Tab 与 Intent。
        if (!hasTabArg) return 0;
        int score = 20 + Math.min(ps.length, 10);
        if (hasIntent) score += 40;
        if (ps.length > 2 && ps[2] == int.class) score += 20;
        if (ps[ps.length - 1] == boolean.class) score += 5;
        return hasIntent ? score : (ps.length >= 7 ? score : 0);
    }

    /** TabModelSelector 的 (boolean)->TabModel getter(145=l, 152=k 等)。 */
    public static Method tabSelectorModelGetter(Class<?> selector) {
        if (selector == null) return null;
        Method hit = null;
        for (Method m : selector.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length >= 1 && ps.length <= 3 && allBoolean(ps)
                    && m.getReturnType().getName().endsWith("TabModel")) {
                if (hit != null) return null;
                hit = m;
            }
        }
        return hit;
    }

    /** TabModelSelector 的 NTP 创建入口 B(LoadUrlParams,int,Tab,boolean)。 */
    public static Method tabSelectorCreateMethod(Class<?> selector) {
        if (selector == null) return null;
        Method hit = null;
        for (Method m : selector.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length >= 3 && ps.length <= 6
                    && ps[0].getName().endsWith("LoadUrlParams")
                    && containsType(ps, int.class) && containsTab(ps)
                    && containsType(ps, boolean.class)) {
                if (hit != null) return null;
                hit = m;
            }
        }
        return hit;
    }

    /** 下载完成回调 d(OfflineItem,Z,Z,Z)。 */
    public static Method downloadMessageCompletedMethod(Class<?> msgCls) {
        if (msgCls == null) return null;
        Method hit = null;
        for (Method m : msgCls.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (isOfflineMessageShape(ps, "OfflineItem")
                    && m.getReturnType() == void.class) {
                if (hit != null) return null;
                hit = m;
            }
        }
        return hit;
    }

    /** Message dispatcher 的下载横幅入口 (PropertyModel, boolean)。 */
    public static Method messageDispatcherCompactMethod(Class<?> dispatcher) {
        if (dispatcher == null) return null;
        Method hit = null;
        for (Method m : dispatcher.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length >= 2 && ps.length <= 3
                    && ps[0].getName().endsWith("PropertyModel")
                    && containsType(ps, boolean.class)) {
                if (hit != null) return null;
                hit = m;
            }
        }
        return hit;
    }

    /** Message dispatcher 的 WebContents 横幅入口。 */
    public static Method messageDispatcherWebMethod(Class<?> dispatcher) {
        if (dispatcher == null) return null;
        Method hit = null;
        for (Method m : dispatcher.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length >= 3 && ps.length <= 6
                    && ps[0].getName().endsWith("PropertyModel")
                    && containsTypeName(ps, "WebContents")
                    && containsType(ps, int.class) && containsType(ps, boolean.class)) {
                if (hit != null) return null;
                hit = m;
            }
        }
        return hit;
    }

    private static boolean allBoolean(Class<?>[] ps) {
        for (Class<?> p : ps) {
            if (p != boolean.class && p != Boolean.class) return false;
        }
        return true;
    }

    private static boolean containsType(Class<?>[] ps, Class<?> type) {
        for (Class<?> p : ps) if (p == type) return true;
        return false;
    }

    private static boolean containsTypeName(Class<?>[] ps, String simpleName) {
        for (Class<?> p : ps) if (p.getName().endsWith(simpleName)) return true;
        return false;
    }

    private static boolean containsTab(Class<?>[] ps) {
        for (Class<?> p : ps) if (isTab(p)) return true;
        return false;
    }

    private static boolean isOfflineMessageShape(Class<?>[] ps, Class<?> item) {
        if (ps == null || ps.length < 3 || ps.length > 6 || ps[0] != item) return false;
        int boolCount = 0;
        for (int i = 1; i < ps.length; i++) {
            if (ps[i] == boolean.class || ps[i] == Boolean.class) boolCount++;
        }
        return boolCount >= 2;
    }

    private static boolean isOfflineMessageShape(Class<?>[] ps, String itemSimpleName) {
        if (ps == null || ps.length < 3 || ps.length > 6
                || !ps[0].getName().endsWith(itemSimpleName)) return false;
        int boolCount = 0;
        for (int i = 1; i < ps.length; i++) {
            if (ps[i] == boolean.class || ps[i] == Boolean.class) boolCount++;
        }
        return boolCount >= 2;
    }

    private static boolean isDispatcherShape(Class<?>[] ps, Class<?> pm, Class<?> wc) {
        if (ps == null || ps.length < 2 || ps.length > 6 || ps[0] != pm) return false;
        boolean web = false, integer = false, bool = false;
        for (int i = 1; i < ps.length; i++) {
            if (ps[i] == wc) web = true;
            if (ps[i] == int.class) integer = true;
            if (ps[i] == boolean.class || ps[i] == Boolean.class) bool = true;
        }
        return web && integer && bool;
    }
}
