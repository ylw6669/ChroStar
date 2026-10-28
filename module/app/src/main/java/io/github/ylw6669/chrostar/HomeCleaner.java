package io.github.ylw6669.chrostar;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * ChroStar 清理逻辑 v1.15.0 —— 冷启动兜底管线 + 退出清理 + 新标签页转主页。
 *
 * 混淆类名为根包短名(铁证见 v1.4.0 dex 类表解析): tuo=TabModelSelector,
 * jza=HomepageManager, id4=菜单关闭 Runnable, l04=ChromeTabCreator。
 * 保留混淆无关兜底(签名匹配 + 模型记忆), 双保险。
 *
 * 行为固定项: 退出清理含无痕(v1.9.3 起无开关); 清「关闭的标签页」固定全部时间
 * (v1.9.5 起无天数滑块); 自定义主页 URL 已删除(v1.9.5, 跟随 Chrome 设置主页)。
 */
public final class HomeCleaner {

    /** v2.5.0: 行为差异走 ProfileTraits(145 旧主页路径), 不在功能类里直接比较版本字符串。 */
    private static boolean isChrome145() {
        return ProfileTraits.current().legacyHomePath;
    }

    private static boolean isChrome153() {
        return "chrome153".equals(HookEntry.engineVersion);
    }

    private static boolean isSymbolic() {
        return "chrome_symbolic".equals(HookEntry.engineVersion);
    }

    private static boolean is152Layout() {
        return "chrome152".equals(HookEntry.engineVersion) || isChrome153()
                || "chrome_symbolic".equals(HookEntry.engineVersion);
    }

    /** 152 之后的 modern Chrome 统一使用结构探测路径。 */
    private static boolean isModernLayout() {
        return !isChrome145();
    }

    private static String CLS_SELECTOR(ClassLoader cl) {
        if (isChrome145()) return "tuo";
        String s = ChromeSymbols.tabSelectorOrFallback(cl);
        if (s != null) return s;
        return null;
    }

    private static String CLS_HOME_MGR(ClassLoader cl) {
        if (isChrome145()) return "jza";
        String s = ChromeSymbols.homepageManagerOrFallback(cl);
        if (s != null) return s;
        return null;
    }
    private static String CLS_MENU_RUNNABLE(ClassLoader cl) {
        return isChrome153() ? "q35" : ("chrome152".equals(HookEntry.engineVersion) ? "q35" : "id4");
    } // 菜单关闭 Runnable(根包)
    private static final String CLS_TAB_MODEL_JNI_BRIDGE =
            "org.chromium.chrome.browser.tabmodel.TabModelJniBridge";
    private static final String CLS_GURL = "org.chromium.url.GURL";
    private static final String CLS_PROFILE_MANAGER =
            "org.chromium.chrome.browser.profiles.ProfileManager";
    // 1B-2: 公开接口/类锚点(版本无关), 用于弱化对混淆类名 k3r/q3r/tuo 的精确依赖。
    private static final String CLS_TAB_MODEL_SELECTOR =
            "org.chromium.chrome.browser.tabmodel.TabModelSelector";
    private static final String CLS_TAB_MODEL =
            "org.chromium.chrome.browser.tabmodel.TabModel";

    private static final int BROWSING_DATA_TYPE_TABS = 8;
    private static final int METHOD_ID_REMOVE_BROWSING_DATA = 0;
    private static final int TAB_LAUNCH_TYPE_RESTORE = 3;
    private static final int TAB_LAUNCH_TYPE_STARTUP = 11;
    private static final int HOME_SELECTION_UNKNOWN = 0;
    private static final int HOME_SELECTION_DEFAULT_NTP = 1;
    private static final int HOME_SELECTION_CUSTOM = 2;
    private static final int HOME_SELECTION_DISABLED = 3;
    private static final int MAX_CLEAR_ATTEMPTS = 4;
    private static final int MAX_COLD_ROUNDS = 4;
    private static final int MAX_FAST_HOME_ATTEMPTS = 20;
    private static final java.util.Map<Class<?>, Field> LOAD_URL_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Field>();

    /** 记忆的模型实例(WeakReference) */
    private static final List<WeakReference<Object>> sModels = new ArrayList<WeakReference<Object>>();
    private static final Object sModelsLock = new Object();

    /** 缓存的 selector 字段 */
    // m15: volatile —— 与 sLateHooksInstalled 一致, 多线程(hook 回调/晚装)读写下保证可见性, 零成本。
    private static volatile Field sSelectorField;

    private HomeCleaner() {
    }

    /** 记忆一个 TabModel 实例(hook getCount 回调) */
    public static void rememberModel(Object model) {
        if (model == null) {
            return;
        }
        synchronized (sModelsLock) {
            Iterator<WeakReference<Object>> it = sModels.iterator();
            while (it.hasNext()) {
                WeakReference<Object> ref = it.next();
                Object m = ref.get();
                if (m == null) {
                    it.remove();
                } else if (m == model) {
                    return;
                }
            }
            sModels.add(new WeakReference<Object>(model));
            XposedBridge.log(HookEntry.TAG + ": remembered TabModel "
                    + model.getClass().getName() + " (incognito=" + isIncognito(model) + ")");
        }
    }

    private static List<Object> getModels() {
        List<Object> out = new ArrayList<Object>();
        synchronized (sModelsLock) {
            Iterator<WeakReference<Object>> it = sModels.iterator();
            while (it.hasNext()) {
                WeakReference<Object> ref = it.next();
                Object m = ref.get();
                if (m == null) {
                    it.remove();
                } else {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** Modern Chrome: preserve the native restore snapshot only for a valid custom homepage.
     *  Default/disabled homepage keeps the established no-restore-state behavior so an old
     *  page cannot leak into startup. The decision is capability-based, not version-based. */
    static boolean canUseFastHomeRestore153(ClassLoader cl) {
        if (!isModernLayout() || "chrome152".equals(HookEntry.engineVersion)
                || chromeHomepageSelection(cl) != HOME_SELECTION_CUSTOM) {
            return false;
        }
        return isLoadableHome(homeUrlViaPrefs(cl));
    }

    /** Reuse Chrome 153's restored foreground tab, preserving its immediately visible snapshot
     *  while the custom homepage begins loading. No tab is created, so this cannot produce the
     *  historical double-home navigation. The method name is retained for existing call sites. */
    static void fastHomeRestore153(final Activity activity, final int attempt) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || attempt >= MAX_FAST_HOME_ATTEMPTS) {
            return;
        }
        try {
            ClassLoader cl = activity.getClass().getClassLoader();
            String home = homeUrlViaPrefs(cl);
            if (!canUseFastHomeRestore153(cl) || !isLoadableHome(home)) {
                XposedBridge.log(HookEntry.TAG
                        + ": [modern-fast-home] custom homepage unavailable; keep native state");
                return;
            }
            Object selector = findTabModelSelector(activity);
            Object regular = selector != null ? getRegularModel(selector) : getRegularModelByMemory();
            if (regular == null || modelCount(regular) == 0) {
                scheduleFastHomeRetry153(activity, attempt);
                return;
            }
            rememberModel(regular);
            Object incognito = selector != null ? getModel(selector, true) : null;
            if (incognito != null) rememberModel(incognito);

            Object tab = modelCurrentTab(regular);
            if (tab == null) {
                scheduleFastHomeRetry153(activity, attempt);
                return;
            }
            String current = tabUrl(tab);
            boolean alreadyHome = isEquivalentHomepage(current, home);
            if (!alreadyHome && !loadTabUrl153(tab, home, cl)) {
                XposedBridge.log(HookEntry.TAG
                        + ": [modern-fast-home] in-place navigation unavailable; keep restored tabs");
                return;
            }
            closeAllTabsExcept(tab);
            XposedBridge.log(HookEntry.TAG + ": [modern-fast-home] reused restored tab, "
                    + "from=" + current + " -> " + home + ", reload=" + !alreadyHome);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [modern-fast-home] attempt " + attempt
                    + " failed -> " + t);
            scheduleFastHomeRetry153(activity, attempt);
        }
    }

    private static void scheduleFastHomeRetry153(final Activity activity, final int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                fastHomeRestore153(activity, attempt + 1);
            }
        }, 100L);
    }

    /**
     * Modern Chrome 独立执行「打开主页删除关闭标签页历史」。
     *
     * <p>v2.5.8: 152/153/154 已移除旧 coldStartCleanup 主路径，但关闭标签页历史清理仍需在
     * MAIN 冷启动时执行。此处只调度历史清理，不创建/切换/关闭任何标签，因此不会重新
     * 引入双主页或冷启动双导航。
     */
    public static void scheduleClosedTabsClear(final Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (!HookEntry.readPrefBoolean(HookEntry.KEY_CLEAR_TABS, true)) return;
        final ClassLoader cl = activity.getClass().getClassLoader();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                clearClosedTabs(activity, cl, 0);
            }
        }, 1200L);
    }

    /** 冷启动兜底管线(多轮复查) */
    public static void coldStartCleanup(final Activity activity, final int round) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        final boolean lastRound = round >= MAX_COLD_ROUNDS - 1;
        try {
            Object selector = findTabModelSelector(activity);
            Object regular = selector != null ? getRegularModel(selector) : getRegularModelByMemory();
            if (regular == null) {
                XposedBridge.log(HookEntry.TAG + ": [cold] round " + round
                        + ": regular TabModel not found yet");
                if (!lastRound) {
                    scheduleColdRetry(activity, round);
                }
                return;
            }
            ClassLoader cl = activity.getClass().getClassLoader();
            String home = resolveHomeUrl(cl);
            // 152/symbolic 继续使用仅 http/https 的出站白名单。145 恢复 v2.0.0 语义，
            // 允许 jza.b(false) 返回 NTP 并交给已验证的两参 openHomeTab 路径处理。
            if (!isChrome145() && !isLoadableHome(home)) {
                XposedBridge.log(HookEntry.TAG + ": [cold] round " + round
                        + ": [diag] home not loadable (" + home
                        + "), skip open-home, keep existing tabs (fail-closed)");
                if (!lastRound) {
                    scheduleColdRetry(activity, round);
                }
                return;
            }
            Object homeTab = null;
            int count = modelCount(regular);
            if (isChrome145()) {
                // 145 冷启动由 Chrome 原生创建首个 NTP，l04.l 负责单向改写为主页。
                // 这里禁止主动 openHomeTab，否则会与原生 NTP 创建叠加，产生两次主页导航。
                for (int i = 0; i < count; i++) {
                    Object tab = modelGetTabAt(regular, i);
                    if (tab != null && isHomeTab(tab, home, cl)) {
                        homeTab = tab;
                        break;
                    }
                }
                if (homeTab == null) {
                    XposedBridge.log(HookEntry.TAG + ": [cold] round " + round
                            + ": 145 waiting for native NTP -> home hook, no programmatic home open");
                    if (!lastRound) {
                        scheduleColdRetry(activity, round);
                    }
                    return;
                }
            }
            if (count == 1) {
                Object only = modelGetTabAt(regular, 0);
                if (only != null && isHomeTab(only, home, cl)) {
                    homeTab = only;
                }
            }
            if (homeTab == null) {
                homeTab = openHomeTab(regular, home, cl);
                if (homeTab == null) {
                    XposedBridge.log(HookEntry.TAG + ": [cold] round " + round
                            + ": open home failed, keep existing tabs");
                    if (!lastRound) {
                        scheduleColdRetry(activity, round);
                    }
                    return;
                }
            }
            closeAllTabsExcept(homeTab);
            int after = modelCount(regular);
            boolean settled = after <= 1;
            if (after == 1) {
                Object t = modelGetTabAt(regular, 0);
                settled = t == homeTab || (t != null && isHomeTab(t, home, cl));
            }
            if (settled || lastRound) {
                XposedBridge.log(HookEntry.TAG + ": [cold] round " + round
                        + ": settled (" + after + " tab(s))");
                if ("chrome145".equals(HookEntry.engineVersion)) {
                    // 给关闭旧标签后的零标签判定回调留出短尾窗，再释放 jza.k() 保护。
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            HookEntry.setColdStartupProtect(false);
                        }
                    }, 500L);
                } else {
                    HookEntry.setColdStartupProtect(false);
                }
                clearClosedTabs(activity, cl, 0);
            } else {
                XposedBridge.log(HookEntry.TAG + ": [cold] round " + round
                        + ": not settled (" + after + " tab(s)), next round");
                scheduleColdRetry(activity, round);
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [cold] round " + round + " failed -> " + t);
            if (!lastRound) {
                scheduleColdRetry(activity, round);
            }
        }
    }

    private static void scheduleColdRetry(final Activity activity, final int round) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                coldStartCleanup(activity, round + 1);
            }
        }, 2000L);
    }

    /** 退出清理: 关闭全部模型(含无痕)全部标签 + 菜单路径 */
    public static void exitCleanup(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            Object selector = findTabModelSelector(activity);
            // 含无痕固定开启(v1.9.3 起无开关); 菜单「关闭所有标签页」路径(根包 id4.run, 含无痕)
            if (selector != null) {
                // 菜单「关闭所有标签页」原始路径(根包 id4.run → jd4.a, 含无痕)
                menuCloseAllTabs(selector, activity.getClass().getClassLoader());
            }
            List<Object> models = getModels();
            int closed = 0;
            for (Object model : models) {
                try {
                    int count = modelCount(model);
                    for (int i = count - 1; i >= 0; i--) {
                        Object tab = modelGetTabAt(model, i);
                        if (tab != null) {
                            closeTab(model, tab);
                            closed++;
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(HookEntry.TAG + ": [exit] close failed on "
                            + model.getClass().getName() + " -> " + t);
                }
            }
            XposedBridge.log(HookEntry.TAG + ": [exit] cleanup done, closed " + closed
                    + " tab(s) across " + models.size() + " model(s)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [exit] cleanup failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // v1.10.5: 新标签页(+) 打开主页开关。
    // 用户点地址栏+号打开的是 Chrome 默认新标签页(NTP),
    // 而左下角小房子是主页按钮(走 HomepageManager) —— 两者是不同入口。
    // hook TabModelJniBridge.openNewTab(保留名, 新建标签页的统一入口,
    // 参数[1]=GURL), 当 URL 是 NTP 且开关开时替换为主页 URL。
    // target=_blank 等具体 URL 不是 NTP, 不受影响。
    // ------------------------------------------------------------------
    public static void hookOpenNewTab(XC_LoadPackage.LoadPackageParam lpparam) {
        hookTabCreator(lpparam);
        hookOpenNewTabJni(lpparam);
    }

    /** Chrome 145 legacy-only: only hook the proven plus/new-tab entry l04.l. */
    public static void hookOpenNewTab145Legacy(XC_LoadPackage.LoadPackageParam lpparam) {
        hookTabCreator(lpparam);
    }

    // ------------------------------------------------------------------
    // v1.10.6: 主入口 —— l04(ChromeTabCreator).l(LoadUrlParams,...) = createNewTab。
    // + 号按钮的真实路径(TraceEvent "ChromeTabCreator.createNewTab" 实锤),
    // LoadUrlParams.a = URL字符串(保留名字段), 是 NTP 且开关开时直接替换。
    // ------------------------------------------------------------------
    private static void hookTabCreator(XC_LoadPackage.LoadPackageParam lpparam) {
        // 145 固定复用 v2.0.0 的 l04.l；152/symbolic 才使用 ChromeSymbols
        // 结构签名发现 TabCreator 与 createNewTab(LoadUrlParams,...)->Tab 方法。
        ClassLoader cl = lpparam.classLoader;
        boolean is145 = isChrome145();
        boolean is152 = is152Layout();
        try {
            Class<?> creator = null;
            String creatorName = is145 ? "l04" : ChromeSymbols.tabCreatorOrFallback(cl);
            if (creatorName != null) creator = safeClass(creatorName, cl);
            if (creator == null && is152) {
                creatorName = "iq4";
                try { creator = XposedHelpers.findClass(creatorName, cl); } catch (Throwable ignored) {}
            }
            final XC_MethodHook creatorHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        boolean sw;
                        String source;
                        int launchType = extractLaunchType(
                                param.method instanceof Method ? (Method) param.method : null,
                                param.args);
                        boolean mainIntent = hasMainIntent(param.args);
                        if (isModernLayout() && launchType != Integer.MIN_VALUE) {
                            boolean startup = launchType == TAB_LAUNCH_TYPE_RESTORE
                                    || launchType == TAB_LAUNCH_TYPE_STARTUP;
                            sw = HookEntry.readPrefBoolean(startup
                                    ? HookEntry.KEY_CLEAN_START : HookEntry.KEY_NEWTAB_HOME, true);
                            source = startup ? "startup" : "newtab(type=" + launchType + ")";
                        } else if (isModernLayout() && mainIntent) {
                            sw = HookEntry.readPrefBoolean(HookEntry.KEY_CLEAN_START, true);
                            source = "startup(intent)";
                        } else {
                            sw = HookEntry.readPrefBoolean(HookEntry.KEY_NEWTAB_HOME, true);
                            source = "newtab";
                        }
                        Object params = (param.args != null && param.args.length > 0)
                                ? param.args[0] : null;
                        if (params == null) {
                            return;
                        }
                        String spec = loadUrlSpec(params);
                        if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                                + ": l04.l called, sw=" + sw + ", url=" + spec);
                        LogTail.addVerbose("Home", "createNewTab " + source
                                + " sw=" + sw + " url=" + spec);
                        if (!sw) return;
                        // Chrome 145 的 l04.l 同时承载冷启动异步 NTP 创建和用户 + 号。
                        // 零标签自关闭已由 jza.k() 冷启动保护拦截，因此冷启动期间也要把
                        // 异步 NTP 直接替换成自定义主页，避免先到主页后又回到 Chrome NTP。
                        if (spec == null || !isNtp(spec)) return;
                        String home = resolveHomeUrl(
                                param.thisObject.getClass().getClassLoader());
                        if (!isLoadableHome(home)) {
                            LogTail.addVerbose("Home-ERR", "home unavailable or not loadable");
                            return;
                        }
                        if (!setLoadUrlSpec(params, spec, home)) {
                            LogTail.addVerbose("Home-ERR", "LoadUrlParams rewrite failed");
                            return;
                        }
                        XposedBridge.log(HookEntry.TAG
                                + ": createNewTab " + source + " NTP -> home " + home);
                        LogTail.add("Home", source + " NTP -> " + home);
                    } catch (Throwable t) {
                        XposedBridge.log(HookEntry.TAG
                                + ": hookTabCreator error -> " + t);
                        LogTail.addError("Home-ERR", "hookTabCreator -> " + t);
                    LogTail.push(HookEntry.getAppContext(null));
                    }
                }
            };
            int activityCreatorCount = isModernLayout()
                    ? hookActivityTabCreators(cl, creatorHook) : 0;
            if (activityCreatorCount > 0) {
                XposedBridge.log(HookEntry.TAG + ": launch-aware ChromeTabbedActivity creator count="
                        + activityCreatorCount + "; retaining TabCreator newtab channel");
            }
            if (creator == null) {
                XposedBridge.log(HookEntry.TAG
                        + ": [diag] TabCreator unresolved — newtab->home unavailable");
            } else if (is145) {
                // Chrome 145: 保持旧版边界，只挂已实证的 l04.l*。
                // 结构发现出的其它 createNewTab 重载也会被冷启动内部流程调用，不能一并改写 NTP。
                XposedBridge.hookAllMethods(creator, "l", creatorHook);
                XposedBridge.log(HookEntry.TAG + ": hooked " + creator.getName()
                        + ".l* (Chrome 145 legacy newtab->home)");
            } else {
                Method central = ChromeSymbols.tabCreatorCentralCreateMethod(creator);
                if (central != null) {
                    XposedBridge.hookMethod(central, creatorHook);
                    XposedBridge.log(HookEntry.TAG + ": hooked " + creator.getName()
                            + "." + central.getName()
                            + " (ChromeTabCreator central, launch-type aware home routing)");
                } else {
                    List<Method> createMethods = ChromeSymbols.tabCreatorCreateMethods(creator);
                    if (isModernLayout() && createMethods.size() != 1) {
                        XposedBridge.log(HookEntry.TAG + ": [diag] central TabCreator method unresolved and "
                                + createMethods.size() + " wrapper candidate(s) found; future route keeps "
                                + "Chrome native new-tab behavior");
                    } else if (!createMethods.isEmpty()) {
                        for (Method cm : createMethods) {
                            try { XposedBridge.hookMethod(cm, creatorHook); } catch (Throwable ignored) {}
                        }
                        XposedBridge.log(HookEntry.TAG + ": [diag] central TabCreator method unresolved; hooked "
                                + createMethods.size() + " wrapper(s), startup/newtab separation unavailable");
                    } else {
                        if (isModernLayout()) {
                            XposedBridge.log(HookEntry.TAG + ": [diag] no unique TabCreator method; "
                                    + "future route keeps Chrome native new-tab behavior");
                        } else {
                            for (String nm : new String[]{"m", "l"}) {
                                XposedBridge.hookAllMethods(creator, nm, creatorHook);
                            }
                            XposedBridge.log(HookEntry.TAG + ": [diag] central TabCreator method unresolved; "
                                    + "using by-name fallback, startup/newtab separation unavailable");
                        }
                    }
                }

            // v2.3.1(PR#1): 152 k3r.B(TabModelSelector 的 NTP 创建入口)第二保险
            // m10: 旧实现写死 findClass("k3r") —— .82 运行时 TabModelSelector 混淆名漂移为 q3r,
            //   findClass("k3r") 抛异常被吞 → 二次保险静默失效(与"精确名优先+fallback"纪律相悖)。
            //   改为候选名按序解析(CLS_SELECTOR() 精确名优先, 再补已知漂移名 q3r), 命中即 hook;
            //   全落空则 [diag] 记录并明确依赖主通道 l04.l(不静默失效)。
            if (is152Layout() && ChromeSymbols.tabCreatorCentralCreateMethod(creator) == null) {
                Class<?> selector = null;
                String selName = null;
                String resolvedSel = ChromeSymbols.tabSelectorOrFallback(cl);
                String[] selectorCandidates = new String[]{resolvedSel, "p5r", "q3r", "k3r"};
                for (String cand : selectorCandidates) {
                    if (cand == null) continue;
                    try {
                        selector = XposedHelpers.findClass(cand, cl);
                        selName = cand;
                        break;
                    } catch (Throwable ignored) {
                    }
                }
                if (selector == null) {
                    XposedBridge.log(HookEntry.TAG
                            + ": [diag] secondary newtab insurance skipped: TabModelSelector unresolved"
                            + " (tried " + CLS_SELECTOR(cl) + "/q3r/k3r) — relying on primary TabCreator channel");
                } else {
                    final String hookedSel = selName;
                    try {
                        Method selCreate = ChromeSymbols.tabSelectorCreateMethod(selector);
                        String selMethodName = selCreate != null ? selCreate.getName() : "B";
                        XposedBridge.hookAllMethods(selector, selMethodName, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    if (!HookEntry.readPrefBoolean(HookEntry.KEY_NEWTAB_HOME, true)) {
                                        return;
                                    }
                                    if (param.args == null || param.args.length == 0
                                            || param.args[0] == null) {
                                        return;
                                    }
                                    Object params = param.args[0];
                                    String spec = loadUrlSpec(params);
                                    if (spec == null || !isNtp(spec)) return;
                                    String home = resolveHomeUrl(
                                            param.thisObject.getClass().getClassLoader());
                                    if (!isLoadableHome(home)) return;
                                    if (!setLoadUrlSpec(params, spec, home)) return;
                                    XposedBridge.log(HookEntry.TAG + ": " + hookedSel + ".B NTP -> home " + home);
                                } catch (Throwable t) {
                                    XposedBridge.log(HookEntry.TAG + ": " + hookedSel + ".B error -> " + t);
                                }
                            }
                        });
                        XposedBridge.log(HookEntry.TAG + ": hooked " + hookedSel + "." + selMethodName
                                + " (newtab, secondary)");
                    } catch (Throwable t) {
                        XposedBridge.log(HookEntry.TAG + ": hook " + hookedSel
                                + " newtab secondary unavailable -> " + t);
                    }
                }
            }
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hookTabCreator(newtab->home) failed -> " + t);
        }
    }

    private static int extractLaunchType(Method method, Object[] args) {
        if (args == null) return Integer.MIN_VALUE;
        // Pick the first int after LoadUrlParams. This covers the 152/153 seven-argument
        // central creator and Chrome 154 R3 without depending on the R8 method name.
        if (method != null) {
            Class<?>[] types = method.getParameterTypes();
            for (int i = 1; i < types.length && i < args.length; i++) {
                if (types[i] == int.class && args[i] instanceof Integer) {
                    return ((Integer) args[i]).intValue();
                }
            }
        }
        for (Object arg : args) {
            if (arg instanceof android.content.Intent) {
                android.content.Intent intent = (android.content.Intent) arg;
                if (intent.hasExtra("org.chromium.chrome.browser.tab_launch_type")) {
                    return intent.getIntExtra(
                            "org.chromium.chrome.browser.tab_launch_type", Integer.MIN_VALUE);
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    private static String loadUrlSpec(Object params) {
        if (params == null) return null;
        if (!isSymbolic()) {
            try {
                Object value = XposedHelpers.getObjectField(params, "a");
                return value instanceof String ? (String) value : null;
            } catch (Throwable t) {
                if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                        + ": [diag] LoadUrlParams.a unavailable -> " + t);
                return null;
            }
        }
        Field cached = LOAD_URL_FIELDS.get(params.getClass());
        if (cached != null) {
            try {
                Object value = cached.get(params);
                return value instanceof String ? (String) value : null;
            } catch (Throwable t) {
                LOAD_URL_FIELDS.remove(params.getClass());
            }
        }
        Field hit = null;
        Class<?> c = params.getClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(params);
                    if (!(value instanceof String) || !isNtp((String) value)) continue;
                    if (hit != null) {
                        XposedBridge.log(HookEntry.TAG
                                + ": [diag] LoadUrlParams URL field ambiguous; keep Chrome URL");
                        return null;
                    }
                    hit = field;
                } catch (Throwable t) {
                    if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                            + ": [diag] LoadUrlParams String field read failed -> " + t);
                }
            }
            c = c.getSuperclass();
        }
        // 现代版本结构扫描失败时，152/153 的 LoadUrlParams 仍通常保留字段 a；
        // 仅在字段类型唯一且值为 NTP 时启用这个末级兼容入口。
        if (hit == null && isModernLayout()) {
            try {
                Field legacy = params.getClass().getDeclaredField("a");
                if (!Modifier.isStatic(legacy.getModifiers()) && legacy.getType() == String.class) {
                    legacy.setAccessible(true);
                    Object value = legacy.get(params);
                    if (value instanceof String && isNtp((String) value)) hit = legacy;
                }
            } catch (Throwable ignored) {
            }
        }
        if (hit == null) return null;
        LOAD_URL_FIELDS.put(params.getClass(), hit);
        try {
            return (String) hit.get(params);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [diag] resolved LoadUrlParams field read failed -> " + t);
            return null;
        }
    }

    private static boolean setLoadUrlSpec(Object params, String oldSpec, String home) {
        if (params == null || oldSpec == null || !isNtp(oldSpec)) return false;
        try {
            if (isSymbolic()) {
                Field field = LOAD_URL_FIELDS.get(params.getClass());
                if (field != null) field.set(params, home);
                else XposedHelpers.setObjectField(params, "a", home);
            } else {
                XposedHelpers.setObjectField(params, "a", home);
            }
            return true;
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [diag] LoadUrlParams URL rewrite failed -> " + t);
            return false;
        }
    }

    private static boolean hasMainIntent(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg instanceof android.content.Intent) {
                android.content.Intent intent = (android.content.Intent) arg;
                return android.content.Intent.ACTION_MAIN.equals(intent.getAction())
                        && intent.getData() == null;
            }
        }
        return false;
    }

    private static int hookActivityTabCreators(ClassLoader cl, XC_MethodHook hook) {
        try {
            Class<?> activity = XposedHelpers.findClass(
                    "org.chromium.chrome.browser.ChromeTabbedActivity", cl);
            Class<?> params = XposedHelpers.findClass(
                    "org.chromium.content_public.browser.LoadUrlParams", cl);
            Class<?> tab = XposedHelpers.findClass(
                    "org.chromium.chrome.browser.tab.Tab", cl);
            List<Method> candidates = new ArrayList<>();
            for (Method method : activity.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (types.length < 2 || types[0] != params || types[1] != int.class
                        || method.getReturnType() != tab) {
                    continue;
                }
                candidates.add(method);
            }
            if (isSymbolic() && candidates.size() != 1) {
                XposedBridge.log(HookEntry.TAG + ": [diag] ChromeTabbedActivity launch-aware creator "
                        + "ambiguous/unresolved (candidates=" + candidates.size()
                        + "); future route keeps native activity behavior");
                return 0;
            }
            int count = 0;
            for (Method method : candidates) {
                XposedBridge.hookMethod(method, hook);
                count++;
            }
            XposedBridge.log(HookEntry.TAG + ": hooked " + count
                    + " ChromeTabbedActivity launch-aware tab creator method(s)");
            return count;
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG
                    + ": [diag] ChromeTabbedActivity structural creator unavailable -> " + t);
            return 0;
        }
    }

    // 兼容旧入口(TabModelJniBridge.openNewTab, target=_blank 等)
    private static void hookOpenNewTabJni(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> bridge = XposedHelpers.findClass(CLS_TAB_MODEL_JNI_BRIDGE,
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(bridge, "openNewTab", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!HookEntry.readPrefBoolean(HookEntry.KEY_NEWTAB_HOME, true)) {
                            return;
                        }
                        if (param.args == null || param.args.length < 2) {
                            return;
                        }
                        Object gurl = param.args[1];
                        if (gurl == null) {
                            return;
                        }
                        // 1B-2: GURL 混淆 j() → 公开 getSpec()(j()/字段a 作 fallback)
                        String spec = gurlSpec(gurl);
                        if (spec == null) {
                            return;
                        }
                        if (!isNtp(spec)) {
                            return; // 不是 NTP(如 target=_blank 具体 URL), 不替换
                        }
                        String home = resolveHomeUrl(
                                param.thisObject.getClass().getClassLoader());
                        if (!isLoadableHome(home)) {
                            return;
                        }
                        Class<?> gurlClass = Class.forName(CLS_GURL, false,
                                param.thisObject.getClass().getClassLoader());
                        Object newGurl = gurlClass.getConstructor(String.class)
                                .newInstance(home);
                        param.args[1] = newGurl;
                        XposedBridge.log(HookEntry.TAG
                                + ": newtab -> home " + home);
                    } catch (Throwable t) {
                        XposedBridge.log(HookEntry.TAG
                                + ": openNewTab hook error -> " + t);
                    }
                }
            });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked TabModelJniBridge.openNewTab (newtab->home)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook openNewTab failed -> " + t);
        }
    }

    // ---- 精确路径(根包类名) ----

    /** 找 TabModelSelector —— 1B-2: 混淆类名(k3r/tuo)精确匹配优先(保证 145 行为不变),
     *  公开接口 TabModelSelector 匹配作 fallback(抵御 .82 上运行时实例漂移为 q3r、
     *  而写死 k3r 匹配不到的问题)。两者都取不到才返回 null。 */
    private static Object findTabModelSelector(Activity activity) {
        try {
            ClassLoader cl = activity.getClass().getClassLoader();
            final Class<?> selectorClass = safeClass(CLS_SELECTOR(cl), cl);
            final Class<?> publicSel = isChrome145() ? null : safeClass(CLS_TAB_MODEL_SELECTOR, cl);
            if (selectorClass == null && publicSel == null) {
                XposedBridge.log(HookEntry.TAG
                        + ": selector class unavailable (obfuscated & public both missing)");
                return null;
            }
            if (sSelectorField != null) {
                try {
                    Object cached = sSelectorField.get(activity);
                    if (cached != null && isSelectorInstance(cached, selectorClass, publicSel)) {
                        return cached;
                    }
                } catch (Throwable ignored) {
                }
            }
            Object publicMatch = null;
            Field publicMatchField = null;
            Class<?> c = activity.getClass();
            while (c != null && c != Object.class) {
                try {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) {
                            continue;
                        }
                        try {
                            f.setAccessible(true);
                            Object v = f.get(activity);
                            if (v == null) {
                                continue;
                            }
                            // 优先精确混淆类名命中(与旧实现一致, 145 行为不变)
                            if (selectorClass != null && selectorClass.isInstance(v)) {
                                sSelectorField = f;
                                XposedBridge.log(HookEntry.TAG + ": TabModelSelector field = "
                                        + c.getName() + "." + f.getName());
                                return v;
                            }
                            // 记录第一个公开接口命中作为 fallback(不在本轮立即返回)
                            if (publicMatch == null && publicSel != null
                                    && publicSel.isInstance(v)) {
                                publicMatch = v;
                                publicMatchField = f;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
                c = c.getSuperclass();
            }
            // 混淆类名全表未命中 → 用公开接口 fallback(q3r 等漂移实现)
            if (publicMatch != null) {
                sSelectorField = publicMatchField;
                XposedBridge.log(HookEntry.TAG + ": TabModelSelector field (public iface) = "
                        + (publicMatchField != null ? publicMatchField.getName() : "?")
                        + " type " + publicMatch.getClass().getName());
                return publicMatch;
            }
            XposedBridge.log(HookEntry.TAG + ": selector field not found on activity");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": findTabModelSelector error -> " + t);
        }
        return null;
    }

    /** 1B-2: selector 实例判定 —— 公开接口或混淆类名任一命中即是 */
    private static boolean isSelectorInstance(Object v, Class<?> selectorClass, Class<?> publicSel) {
        if (v == null) return false;
        if (publicSel != null && publicSel.isInstance(v)) return true;
        if (selectorClass != null && selectorClass.isInstance(v)) return true;
        return false;
    }

    /** 常规模型 —— 145 固定 selector.l(false) → 记忆兜底；其余引擎使用四通道:
     *  公开接口 getModel(false) → 结构签名(单 boolean 参 + 返回 TabModel)
     *  → 混淆 selector.l(false) → 记忆兜底。
     *  .82 上 selector.l(Boolean) → NoSuchMethodError(混淆名漂移/实例为 q3r), 通道③失效;
     *  通道①②不依赖精确方法名。fail-closed。 */
    private static Object getRegularModel(Object selector) {
        if (selector == null) {
            return getRegularModelByMemory();
        }
        if (isChrome145()) {
            try {
                Object m = XposedHelpers.callMethod(selector, "l", Boolean.FALSE);
                if (m != null) {
                    return m;
                }
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG + ": selector.l(false) failed -> " + t);
            }
            return getRegularModelByMemory();
        }
        ClassLoader cl = selector.getClass().getClassLoader();
        // 通道①: 公开接口 TabModelSelector.getModel(boolean)
        try {
            Class<?> selIf = safeClass(CLS_TAB_MODEL_SELECTOR, cl);
            if (selIf != null && selIf.isInstance(selector)) {
                Method gm = selIf.getMethod("getModel", boolean.class);
                Object m = gm.invoke(selector, Boolean.FALSE);
                if (m != null) {
                    return m;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": getRegularModel public getModel failed -> " + t);
        }
        // 通道②: 结构签名 —— 单 boolean 参 + 返回类型是 TabModel(公开)或其子类, 名字无关
        try {
            Class<?> tmCls = safeClass(CLS_TAB_MODEL, cl);
            Method m = findBooleanModelGetter(selector, tmCls);
            if (m != null) {
                m.setAccessible(true);
                Object r = m.invoke(selector, Boolean.FALSE);
                if (r != null) {
                    return r;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": getRegularModel signature failed -> " + t);
        }
        // 通道③: 现有混淆 selector.l(false)
        try {
            Object m = XposedHelpers.callMethod(selector, "l", Boolean.FALSE);
            if (m != null) {
                return m;
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": selector.l(false) failed -> " + t);
        }
        // 通道④: 记忆兜底(最终防线)
        return getRegularModelByMemory();
    }

    /** Resolve either regular or incognito model without depending on the current R8 name. */
    private static Object getModel(Object selector, boolean incognito) {
        if (!incognito) return getRegularModel(selector);
        if (selector == null) return null;
        ClassLoader cl = selector.getClass().getClassLoader();
        try {
            Class<?> selIf = safeClass(CLS_TAB_MODEL_SELECTOR, cl);
            if (selIf != null && selIf.isInstance(selector)) {
                Method gm = selIf.getMethod("getModel", boolean.class);
                Object model = gm.invoke(selector, Boolean.TRUE);
                if (model != null) return model;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> tmCls = safeClass(CLS_TAB_MODEL, cl);
            Method getter = findBooleanModelGetter(selector, tmCls);
            if (getter != null) {
                getter.setAccessible(true);
                return getter.invoke(selector, Boolean.TRUE);
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG
                    + ": [153-fast-home] incognito model lookup failed -> " + t);
        }
        return null;
    }

    /** 1B-2: 结构签名定位 getModel(boolean) —— 单 boolean 参 + 返回 TabModel(或其子类)。
     *  tmCls 不可用(被混淆)时放宽为"任意对象返回类型"(排除 void/基本类型/String/包装类)。 */
    private static Method findBooleanModelGetter(Object selector, Class<?> tmCls) {
        // R2-m8: 先收集全部结构吻合候选(单 boolean 参 + 返回 TabModel/对象), 再两段式选名 ——
        //   避免 getDeclaredMethods 无序导致 selectOrCreateModel(boolean) 这类结构吻合但有副作用
        //   的方法先被 return/invoke。
        List<Method> structOk = new ArrayList<Method>();
        Class<?> c = selector.getClass();
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 1) {
                    continue;
                }
                if (pts[0] != boolean.class && pts[0] != Boolean.class) {
                    continue;
                }
                Class<?> rt = m.getReturnType();
                if (tmCls != null) {
                    if (!tmCls.isAssignableFrom(rt)) {
                        continue;
                    }
                } else if (rt == void.class || rt.isPrimitive() || rt == String.class
                        || rt == Boolean.class || rt == Integer.class) {
                    continue;
                }
                structOk.add(m);
            }
            c = c.getSuperclass();
        }
        // 白名单优先: 只在 get/find/is/current/peek 开头的只读候选中选。
        for (Method m : structOk) {
            if (isReadOnlyName(m.getName())) return m;
        }
        // 黑名单兜底: 白名单全空才放开到"非黑名单"候选(单字母混淆名 b/l/d 仍放行)。
        for (Method m : structOk) {
            if (!hasSideEffectName(m.getName())) return m;
        }
        if (!structOk.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Method m : structOk) sb.append(m.getName()).append(' ');
            XposedBridge.log(HookEntry.TAG
                    + ": [diag] findBooleanModelGetter: no read-only/non-side-effect candidate among ["
                    + sb.toString().trim() + "], fail-closed null");
        }
        return null;
    }

    /** 兜底: 从记忆模型里找非无痕 */
    private static Object getRegularModelByMemory() {
        for (Object m : getModels()) {
            if (!isIncognito(m)) {
                return m;
            }
        }
        return null;
    }

    /** 主页 URL —— M3: 按引擎决定通道顺序。
     *  145: 公开 HomepageManager 被混淆(通道①→null), 启发式通道②(getDeclaredMethods 无序 +
     *    allBool 对零参真空为真 + 会实际 invoke)可能先命中错误方法(如 NTP getter), 把已验证的
     *    精确混淆路径 jza.b(false) 挤后 → 145 直开主页回归。故 145 先走精确混淆通道③, 命中即返回,
     *    仅当抛异常/返回空才回落 公开API/启发式(与 findTabModelSelector"精确混淆名优先"纪律一致)。
     *  152/unknown: 保持 公开API → 启发式签名 → 混淆硬编码 → null(fail-closed, 不再交出 WebUI)。 */
    private static String resolveHomeUrl(ClassLoader cl) {
        boolean is145 = "chrome145".equals(HookEntry.engineVersion);
        // v2.4.2(145 回归旧法): 适配 152 前 145 仅用精确混淆通道 jza.b(false) 直读主页,
        //   不套"可加载优先"白名单、不读 prefs 通道④。根因: 通道④会把 145 也解析成真实
        //   自定义主页(如 go.itab.link), 而 145 的 jza getter 在 UseNTP=true 时返回 NTP ——
        //   prefs 直读让 145 第一次拿到非 NTP 主页 → openHomeTab(真实 URL) + closeAllTabsExcept
        //   触发 Chrome 145 shouldCloseAppWithZeroTabs 零标签自关闭 → 冷启动闪退。
        //   旧法下 145 拿到的是 NTP, 由调用方 isLoadableHome 白名单 fail-closed 保留 Chrome
        //   原生 NTP 行为(与适配 152 前的 v2.0.0 完全一致), 不再闪退。
        if (is145) {
            String legacy = homeUrlViaObfuscated(cl);
            if (legacy != null && !legacy.isEmpty()) {
                return legacy;
            }
            XposedBridge.log(HookEntry.TAG + ": [diag] resolveHomeUrl(145 legacy) -> null,"
                    + " fail-closed (keep Chrome native NTP)");
            return null;
        }
        int selection = chromeHomepageSelection(cl);
        if (selection == HOME_SELECTION_DEFAULT_NTP) {
            XposedBridge.log(HookEntry.TAG + ": resolveHomeUrl: Chrome default NTP selected; "
                    + "keep native NTP (ignore retained custom URL)");
            return null;
        }
        if (selection == HOME_SELECTION_DISABLED) {
            XposedBridge.log(HookEntry.TAG + ": resolveHomeUrl: Chrome homepage disabled; keep native NTP");
            return null;
        }
        // 非 http/https 的中间候选(如 NTP chrome-native://newtab/): 仅当所有通道都给不出可加载主页时才兜底返回。
        //   v2.3.22: 改为"可加载优先"—— 早期通道返回 NTP(如 152 w5c.b 在 UseNTP=true 时)不再 short-circuit,
        //   继续尝试后续通道直到拿到 http/https, 根治"设置了自定义主页却不生效"。
        String fallback = null;
        // 未来路由优先使用结构定位的 HomepageManager。PartnerBrowserCustomizations
        // 代表合作方默认主页，不能抢在用户自定义主页之前返回。
        if (isModernLayout()) {
            try {
                String sig = homeUrlViaSignature(cl);
                if (isLoadableHome(sig)) return sig;
                if (sig != null && !sig.isEmpty()) fallback = sig;
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG
                        + ": resolveHomeUrl symbolic signature failed -> " + t);
            }
        }
        // 通道①: 公开 HomepageManager getter(best-effort, 类被混淆时优雅跳过)
        try {
            String pub = homeUrlViaPublicApi(cl);
            if (isLoadableHome(pub)) return pub;
            if (fallback == null && pub != null && !pub.isEmpty()) fallback = pub;
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": resolveHomeUrl public-api failed -> " + t);
        }
        // 通道②: 结构签名(返回 GURL/String + 参数全 boolean), 不写死方法名
        if (isModernLayout()) {
            try {
                String sig = homeUrlViaSignature(cl);
                if (isLoadableHome(sig)) return sig;
                if (fallback == null && sig != null && !sig.isEmpty()) fallback = sig;
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG + ": resolveHomeUrl signature failed -> " + t);
            }
        }
        // 通道③: 混淆硬编码(152/unknown 在此尝试; 145 已在入口走旧法提前返回)。
        if (!is145) {
            String obf = homeUrlViaObfuscated(cl);
            if (isLoadableHome(obf)) return obf;
            if (fallback == null && obf != null && !obf.isEmpty()) fallback = obf;
        }
        // 通道④(v2.3.22 新增): Chrome 默认 SharedPreferences 直读 —— 版本无关、不依赖任何混淆类。
        //   152(.82) 实证: w5c.b(false,false) 在 homepage_is_newtabpage/UseNTP=true 时返回 NTP 而非
        //   用户设置的自定义主页 → 前三条通道都被 isLoadableHome 白名单拒掉 → 冷启动/＋号开主页整体
        //   fail-closed(用户"设置了别的网址主页却不生效")。真实主页存在 default SharedPreferences 的
        //   "Chrome.Homepage.CustomGurl"(序列化 GURL, 末段为完整 URL)/"homepage_custom_uri"(旧式裸 URL)。
        try {
            String pref = homeUrlViaPrefs(cl);
            if (isLoadableHome(pref)) {
                XposedBridge.log(HookEntry.TAG + ": resolveHomeUrl channel4(prefs) -> " + pref);
                return pref;
            }
            if (fallback == null && pref != null && !pref.isEmpty()) fallback = pref;
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": resolveHomeUrl prefs failed -> " + t);
        }
        // 所有通道都拿不到 http/https: 返回中间候选(诊断用; 调用方 isLoadableHome 仍会 fail-closed)。
        if (fallback != null) return fallback;
        // 通道④(加固2/Minor-2): 不再返回任何 WebUI 兜底 —— 旧死常量 DEFAULT_NTP(chrome-native://
        //   特权 scheme, 无法普通 loadUrl, 是"+号打开主页变 chrome-native:// 无法访问"根因)已删除。
        //   无可加载主页(http/https)时返回 null, 由所有消费路径经 isLoadableHome 白名单 fail-closed
        //   跳过替换, 保留 Chrome 原生 NTP 行为。
        XposedBridge.log(HookEntry.TAG
                + ": [diag] resolveHomeUrl: no loadable homepage resolved (all channels empty),"
                + " fail-closed null (keep Chrome native NTP, no WebUI fallback)");
        return null;
    }

    /** M3: 通道③抽取为独立 helper —— 精确混淆硬编码(152:w5c.b(b,b) / 145:jza.b(b)) + gurlSpec。
     *  145 上此为已验证的精确路径, resolveHomeUrl 对 145 优先调用它。异常/空 → 返回 null 由调用方回落。 */
    private static String homeUrlViaObfuscated(ClassLoader cl) {
        try {
            Class<?> jzaClass = Class.forName(CLS_HOME_MGR(cl), false, cl);
            Object singleton = XposedHelpers.callStaticMethod(jzaClass, "d");
            if (singleton != null) {
                Object gurl = is152Layout()
                        ? XposedHelpers.callMethod(singleton, "b", Boolean.FALSE, Boolean.FALSE)
                        : XposedHelpers.callMethod(singleton, "b", Boolean.FALSE);
                String urlText = gurlSpec(gurl);
                if (urlText != null && !urlText.isEmpty()) {
                    return urlText;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [diag] homeUrlViaObfuscated("
                    + CLS_HOME_MGR(cl) + ") failed -> " + t);
        }
        return null;
    }

    /** 1B-2 / 152 修复: GURL 取 URL 完整字符串。
     *  152(.82)实证: org.chromium.url.GURL 无 getSpec() 公开方法; 混淆 j() 返回的是
     *    `<scheme>`(Parsed.a..b 切片, 例 "http"), 并非完整 spec。旧实现 getSpec→j→a 的顺序
     *    在 152 上会先命中 j() 拿到 "http" → 被 isLoadableHome 白名单拒掉(日志
     *    `home not loadable (http)`), 导致"启动直开主页/＋号开主页"在 152 全失效。
     *  修正: 字段 a 是 GURL 的原始 spec 存储(构造/init 直写 this.a=str), 跨版本语义稳定,
     *    改为**优先读字段 a**; getSpec()/j() 仅作降级兜底(145 上仍可命中)。
     *  返回 null 表示各通道都取不到非空字符串(fail-closed, 调用方据此跳过)。 */
    private static String gurlSpec(Object gurl) {
        if (gurl == null) return null;
        // ① 优先: 原始 spec 字段 a(152/145 均直存完整 URL 字符串)
        if (!isSymbolic() || isModernLayout()) {
            try {
                Object s = XposedHelpers.getObjectField(gurl, "a");
                if (s instanceof String && !((String) s).isEmpty()) return (String) s;
            } catch (Throwable t) {
                if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                        + ": [diag] GURL.a unavailable -> " + t);
            }
        }
        // ② 降级: 公开 getSpec()(部分版本保留)
        try {
            Object s = XposedHelpers.callMethod(gurl, "getSpec");
            if (s instanceof String && !((String) s).isEmpty()) return (String) s;
        } catch (Throwable t) {
            if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                    + ": [diag] GURL.getSpec unavailable -> " + t);
        }
        if (isSymbolic()) {
            String hit = null;
            Class<?> c = gurl.getClass();
            while (c != null && c != Object.class) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                    try {
                        field.setAccessible(true);
                        Object value = field.get(gurl);
                        if (!(value instanceof String) || !looksLikeFullUrl((String) value)) continue;
                        if (hit != null && !hit.equals(value)) return null;
                        hit = (String) value;
                    } catch (Throwable t) {
                        if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                                + ": [diag] GURL String field read failed -> " + t);
                    }
                }
                c = c.getSuperclass();
            }
            if (hit != null) return hit;
        } else {
            // ③ 末位兜底: 混淆 j()(仅当结果像完整 URL 才采纳)
            try {
                Object s = XposedHelpers.callMethod(gurl, "j");
                if (s instanceof String && looksLikeFullUrl((String) s)) return (String) s;
            } catch (Throwable t) {
                if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                        + ": [diag] GURL.j unavailable -> " + t);
            }
        }
        return null;
    }

    /** 1B-2 / 152 修复(v2.3.22): 通道④ —— 直读 Chrome 默认 SharedPreferences 的自定义主页。
     *  Chrome 把用户设置的主页存在偏好存储中(packageName 的默认 SharedPreferences):
     *    · "Chrome.Homepage.CustomGurl" —— 序列化 GURL。格式: "1\0<b>\0<parsed>\0<url>",
     *      URL 为末段(GURL.n() 序列化 / a() 反序列化实证); 该串以 "1\0" 或 "73\0" 开头。
     *    · "homepage_custom_uri"       —— 旧式裸 URL(152 已基本不用, 作兼容)。
     *  取值通过公开 PreferenceManager.getDefaultSharedPreferences(context)(与 Chrome 内部 xa6.a 同源),
     *  不依赖任何混淆类/方法, 版本无关。无 context 时回退 ActivityThread 同进程读取。
     *  返回 null 表示不存在; 返回 "" 不可能(空则视为无)。调用方统一 isLoadableHome 过滤。 */
    private static String homeUrlViaPrefs(ClassLoader cl) {
        try {
            android.content.Context ctx = HookEntry.getAppContext(cl);
            if (ctx == null) {
                // 允许传入的 ClassLoader 不是 Context 来源时, 再试 ActivityThread(同进程, 纯本地读取)
                ctx = HookEntry.getAppContext(null);
            }
            if (ctx == null) {
                return null;
            }
            android.content.SharedPreferences sp =
                    android.preference.PreferenceManager.getDefaultSharedPreferences(ctx);
            // ① 新版: 序列化 GURL
            String ser = sp.getString("Chrome.Homepage.CustomGurl", null);
            String fromSer = extractUrlFromSerializedGurl(ser);
            if (fromSer != null && !fromSer.isEmpty()) {
                return fromSer;
            }
            // ② 兼容: 旧式裸 URL
            String raw = sp.getString("homepage_custom_uri", null);
            if (raw != null) {
                raw = raw.trim();
                if (!raw.isEmpty()) {
                    return raw;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [diag] homeUrlViaPrefs failed -> " + t);
        }
        return null;
    }

    /**
     * 只读 Chrome 自身主页选择。Chrome 会在选择默认 NTP 后保留旧 CustomGurl，
     * 因此必须先读 UseNTP，不能仅凭 CustomGurl 存在就认定用户仍选择自定义主页。
     */
    private static int chromeHomepageSelection(ClassLoader cl) {
        try {
            android.content.Context ctx = HookEntry.getAppContext(cl);
            if (ctx == null) ctx = HookEntry.getAppContext(null);
            if (ctx == null) return HOME_SELECTION_UNKNOWN;
            android.content.SharedPreferences sp =
                    android.preference.PreferenceManager.getDefaultSharedPreferences(ctx);
            if (sp.contains("homepage_switch") && !sp.getBoolean("homepage_switch", true)) {
                return HOME_SELECTION_DISABLED;
            }
            if (sp.contains("Chrome.Homepage.UseNTP")) {
                return sp.getBoolean("Chrome.Homepage.UseNTP", false)
                        ? HOME_SELECTION_DEFAULT_NTP : HOME_SELECTION_CUSTOM;
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [diag] Chrome homepage selection read failed -> " + t);
        }
        return HOME_SELECTION_UNKNOWN;
    }

    /** 从 GURL 序列化串中提取 URL 末段。格式 "<len>\0<version>\0<b>\0<parsed...>\0<url>",
     *  URL 恒为最后一个 \0 之后(含)的内容, 或整串即裸 URL(无 \0 时)。无 \0 则原样返回。 */
    private static String extractUrlFromSerializedGurl(String ser) {
        if (ser == null) {
            return null;
        }
        String s = ser;
        int idx = s.lastIndexOf('\u0000');
        if (idx >= 0) {
            s = s.substring(idx + 1);
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /** 形状护栏: 判断字符串是否像完整 URL(含 scheme 分隔符, 或常见无 // scheme 前缀)。
     *  用于拒绝 152 GURL.j() 返回的裸 scheme(如 "http")。 */
    private static boolean looksLikeFullUrl(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.contains("://")) return true;
        String l = s.toLowerCase();
        return l.startsWith("about:") || l.startsWith("data:") || l.startsWith("mailto:")
                || l.startsWith("javascript:") || l.startsWith("file:")
                || l.startsWith("content:") || l.startsWith("blob:");
    }

    /** 1B-2: Class.forName 不抛异常封装(类被混淆/不存在时返回 null, 供多通道优雅降级) */
    private static Class<?> safeClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 1B-2: 当前 Profile —— 公开 getLastUsedProfile() 优先, 混淆 b() fallback
     *  (与既有 clearClosedTabs 的 ProfileManager.b() 一致)。取不到返回 null。 */
    private static Object currentProfile(ClassLoader cl) {
        Class<?> pmClass = safeClass(CLS_PROFILE_MANAGER, cl);
        if (pmClass == null) return null;
        String[] names = {"getLastUsedProfile", "b"};
        for (String n : names) {
            try {
                Method m = pmClass.getMethod(n);
                Object p = m.invoke(null);
                if (p != null) return p;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 1B-2 通道①: 公开 HomepageManager.getInstance(profile).getCustomHomepage()/getHomepage()。
     *  HomepageManager 在多数构建被混淆(→w5c/jza), 此通道 best-effort, 取不到返回 null 由后续通道接管。 */
    private static String homeUrlViaPublicApi(ClassLoader cl) {
        // Chrome 154 moved the homepage holder back to this stable public class.
        // Resolve singleton and homepage getter by type so later R8 renames are harmless.
        try {
            Class<?> pbc = safeClass(
                    "org.chromium.chrome.browser.partnercustomizations.PartnerBrowserCustomizations", cl);
            if (pbc != null) {
                Method singletonMethod = null;
                for (Method method : pbc.getDeclaredMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())
                            || method.getParameterTypes().length != 0
                            || !pbc.isAssignableFrom(method.getReturnType())) continue;
                    if (singletonMethod != null) {
                        singletonMethod = null;
                        break;
                    }
                    singletonMethod = method;
                }
                Object singleton = singletonMethod == null ? null : singletonMethod.invoke(null);
                if (singleton != null) {
                    Class<?> gurl = safeClass(CLS_GURL, cl);
                    String hit = null;
                    for (Method method : singleton.getClass().getDeclaredMethods()) {
                        if (Modifier.isStatic(method.getModifiers())
                                || method.getParameterTypes().length != 0
                                || gurl == null || !gurl.isAssignableFrom(method.getReturnType())
                                || hasSideEffectName(method.getName())) continue;
                        method.setAccessible(true);
                        String url = gurlSpec(method.invoke(singleton));
                        if (!isLoadableHome(url)) continue;
                        if (hit != null && !hit.equals(url)) {
                            XposedBridge.log(HookEntry.TAG
                                    + ": [diag] PartnerBrowserCustomizations homepage ambiguous");
                            return null;
                        }
                        hit = url;
                    }
                    if (hit != null) return hit;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [diag] PartnerBrowserCustomizations homepage read failed -> " + t);
        }
        Object profile = currentProfile(cl);
        if (profile == null) return null;
        String[] mgrNames = {
                "org.chromium.chrome.browser.homepage.settings.HomepageManager",
                "org.chromium.chrome.browser.homepage.HomepageManager",
        };
        Class<?> profileCls = safeClass("org.chromium.chrome.browser.profiles.Profile", cl);
        for (String mgrName : mgrNames) {
            Class<?> mgrCls = safeClass(mgrName, cl);
            if (mgrCls == null) continue;
            Object mgr = null;
            try {
                if (profileCls != null) {
                    Method gi = mgrCls.getMethod("getInstance", profileCls);
                    mgr = gi.invoke(null, profile);
                }
            } catch (Throwable ignored) {
            }
            if (mgr == null) {
                try {
                    Method gi0 = mgrCls.getMethod("getInstance");
                    mgr = gi0.invoke(null);
                } catch (Throwable ignored) {
                }
            }
            if (mgr == null) continue;
            String url = homepageGetter(mgr, "getCustomHomepage");
            if (url == null) url = homepageGetter(mgr, "getHomepage");
            if (url != null && !url.isEmpty()) return url;
        }
        return null;
    }

    /** 调 mgr 的无参 getter, 结果可能是 String 或 GURL → 统一取字符串 */
    private static String homepageGetter(Object mgr, String method) {
        try {
            Method m = mgr.getClass().getMethod(method);
            Object res = m.invoke(mgr);
            if (res instanceof String) return (String) res;
            return gurlSpec(res);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 1B-2 通道②: 在主页管理器单例上按结构签名(返回 GURL/String + 参数全 boolean)动态定位
     *  getter, 用全 false 调用 —— 不写死 b(boolean,boolean), 抵御混淆方法名/参数个数漂移。
     *  M3: 加只读护栏 —— 先收集全部候选(不立即 invoke), 排除零参真空与副作用方法名,
     *  命中多个时 [diag] 记录并按 get* 优先排序再逐个 invoke。 */
    private static String homeUrlViaSignature(ClassLoader cl) {
        Class<?> mgrCls = safeClass(CLS_HOME_MGR(cl), cl);
        if (mgrCls == null) return null;
        Object singleton = null;
        try {
            Method getter = null;
            for (Method method : mgrCls.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        || method.getParameterTypes().length != 0
                        || !mgrCls.isAssignableFrom(method.getReturnType())) continue;
                if (getter != null) {
                    getter = null;
                    break;
                }
                getter = method;
            }
            if (getter != null) {
                getter.setAccessible(true);
                singleton = getter.invoke(null);
            } else {
                singleton = XposedHelpers.callStaticMethod(mgrCls, "d");
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [diag] homepage singleton resolution failed -> " + t);
            return null;
        }
        if (singleton == null) return null;
        Class<?> gurlCls = safeClass(CLS_GURL, cl);
        // R2-m8: 白名单优先 + 黑名单兜底两段式 —— 本通道会实际 invoke, 必须避免副作用方法被选中。
        //   先收集全部结构吻合候选(仅结构护栏, 不按名过滤), 再分区: stage1=只读白名单
        //   (get/find/is/current/peek), stage2=非黑名单(含单字母混淆名); 黑名单方法绝不 invoke。
        List<Method> structOk = new ArrayList<Method>();
        Class<?> c = singleton.getClass();
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                Class<?> rt = m.getReturnType();
                boolean retOk = (gurlCls != null && gurlCls.isAssignableFrom(rt))
                        || rt == String.class;
                if (!retOk) continue;
                Class<?>[] pts = m.getParameterTypes();
                // 护栏①: 排除零参方法 —— allBool 对空参列表真空为真, 零参 getter 可能是 NTP/其他 URL。
                if (pts.length < 1) continue;
                boolean allBool = true;
                for (Class<?> p : pts) {
                    if (p != boolean.class && p != Boolean.class) { allBool = false; break; }
                }
                if (!allBool) continue;
                structOk.add(m);
            }
            c = c.getSuperclass();
        }
        List<Method> stage1 = new ArrayList<Method>();
        List<Method> stage2 = new ArrayList<Method>();
        for (Method m : structOk) {
            String nm = m.getName();
            if (isReadOnlyName(nm)) stage1.add(m);
            else if (!hasSideEffectName(nm)) stage2.add(m);
            // 黑名单方法(如 selectOrCreate*) 两段都不入 → 绝不 invoke。
        }
        if (structOk.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (Method m : structOk) sb.append(m.getName()).append(' ');
            XposedBridge.log(HookEntry.TAG + ": [diag] homeUrlViaSignature candidates("
                    + structOk.size() + "): " + sb.toString().trim()
                    + " — whitelist=" + stage1.size() + " fallback=" + stage2.size() + ", prefer get*");
            if (isModernLayout()) {
                XposedBridge.log(HookEntry.TAG
                        + ": [diag] future homepage getter is ambiguous; keep Chrome native homepage");
                return null;
            }
        }
        // get* 优先(getDeclaredMethods 无序, 稳定地把 get 前缀排前), 段内排序。
        java.util.Comparator<Method> getFirst = new java.util.Comparator<Method>() {
            @Override
            public int compare(Method a, Method b) {
                boolean ga = a.getName().toLowerCase().startsWith("get");
                boolean gb = b.getName().toLowerCase().startsWith("get");
                if (ga == gb) return 0;
                return ga ? -1 : 1;
            }
        };
        java.util.Collections.sort(stage1, getFirst);
        java.util.Collections.sort(stage2, getFirst);
        // 先试只读白名单, 无果再退回非黑名单; 黑名单方法绝不 invoke。
        String spec = invokeFirstNonEmpty(singleton, stage1);
        if (spec != null && !spec.isEmpty()) return spec;
        spec = invokeFirstNonEmpty(singleton, stage2);
        if (spec != null && !spec.isEmpty()) return spec;
        return null;
    }

    /** R2-m8: 按序 invoke 候选(全 false 参), 返回首个非空 URL spec; 单个候选 invoke 失败
     *  为探测正常信号(错候选会抛异常), 不中断其余候选。 */
    private static String invokeFirstNonEmpty(Object target, List<Method> methods) {
        for (Method m : methods) {
            try {
                m.setAccessible(true);
                Class<?>[] pts = m.getParameterTypes();
                Object[] args = new Object[pts.length];
                for (int i = 0; i < pts.length; i++) args[i] = Boolean.FALSE;
                Object res = m.invoke(target, args);
                String spec = (res instanceof String) ? (String) res : gurlSpec(res);
                if (spec != null && !spec.isEmpty()) return spec;
            } catch (Throwable t) {
                // Minor-3(AGENTS.md 硬纪律): 禁空 catch 吞异常 —— 多候选逐个探测时须能定位哪个
                //   候选抛错("选错 getter 致 145 直开主页回归"正是 M3 治理的缺陷类别)。不中断其余候选。
                XposedBridge.log(HookEntry.TAG + ": [diag] homeUrl candidate "
                        + m.getName() + " invoke failed -> " + t);
            }
        }
        return null;
    }

    /** R2-Minor-5: camelCase 词边界前缀匹配 —— name 保留原始大小写, prefix 全小写。
     *  仅当 name 以 prefix 开头且 prefix 后紧跟"词边界"(串尾, 或非小写字母: 大写字母/数字/下划线)
     *  时才算命中, 避免 startsWith 把 "isolate"/"once"/"handler" 等更长单词误判为 "is"/"on"/"handle" 前缀。
     *  不能用 toLowerCase 后判断: 会抹掉 camelCase 边界, 使 "isValid"→"isvalid" 也被误拒。
     *  单字母混淆名(b/l/d)长度不足任何前缀 → 一律不命中(白名单落空/黑名单放行), 行为不变;
     *  真实 Chromium camelCase 方法(getUrl/onLoad/selectOrCreateModel)前缀后紧跟大写 → 仍正确命中。 */
    private static boolean startsWithWord(String name, String prefix) {
        if (name == null) return false;
        int pl = prefix.length();
        if (name.length() < pl) return false;
        for (int i = 0; i < pl; i++) {
            if (Character.toLowerCase(name.charAt(i)) != prefix.charAt(i)) return false;
        }
        if (name.length() == pl) return true;
        return !Character.isLowerCase(name.charAt(pl));
    }

    /** R2-m8: 只读方法名白名单 —— 结构签名通道会实际 invoke, 优先只在明确只读语义的名字中选,
     *  避免 getDeclaredMethods 无序时直接 invoke 首个命中副作用方法(如 selectOrCreateModel)。
     *  R2-Minor-5: 用 camelCase 词边界匹配, 使 "isolate"/"issue" 不被误判为 "is" 只读前缀。 */
    private static boolean isReadOnlyName(String name) {
        if (name == null) return false;
        return startsWithWord(name, "get") || startsWithWord(name, "find")
                || startsWithWord(name, "is") || startsWithWord(name, "current")
                || startsWithWord(name, "peek");
    }

    /** R2-m8: 方法名副作用前缀黑名单(兜底) —— 结构签名通道会实际 invoke 命中方法, 须排除写/
     *  事件/创建类副作用方法, 只保留只读 getter 语义(混淆单字母名如 "l"/"b"/"d" 不匹配任何前缀 → 放行)。
     *  R2-Minor-5: 用 camelCase 词边界匹配, 使 "once"/"only" 不被误判为 "on" 副作用前缀(避免误伤)。
     *  null 名视为有副作用(fail-safe 排除)。 */
    private static boolean hasSideEffectName(String name) {
        if (name == null) return true;
        return startsWithWord(name, "set") || startsWithWord(name, "clear")
                || startsWithWord(name, "reset") || startsWithWord(name, "on")
                || startsWithWord(name, "notify") || startsWithWord(name, "add")
                || startsWithWord(name, "remove") || startsWithWord(name, "put")
                || startsWithWord(name, "save") || startsWithWord(name, "update")
                || startsWithWord(name, "handle") || startsWithWord(name, "apply")
                || startsWithWord(name, "commit") || startsWithWord(name, "write")
                || startsWithWord(name, "delete") || startsWithWord(name, "restore")
                || startsWithWord(name, "show") || startsWithWord(name, "open")
                || startsWithWord(name, "close") || startsWithWord(name, "send")
                || startsWithWord(name, "post")
                // R2-m8: 补 Chromium 真实变更类前缀 —— 尤其 selectOrCreateModel(boolean)(单 boolean 参
                //   + 返回 TabModel, 精确吻合 findBooleanModelGetter 结构过滤, 语义"选择或创建"有副作用)。
                || startsWithWord(name, "select") || startsWithWord(name, "create")
                || startsWithWord(name, "ensure") || startsWithWord(name, "load")
                || startsWithWord(name, "navigate") || startsWithWord(name, "toggle")
                || startsWithWord(name, "switch") || startsWithWord(name, "init")
                || startsWithWord(name, "register") || startsWithWord(name, "observe")
                || startsWithWord(name, "cancel") || startsWithWord(name, "start")
                || startsWithWord(name, "stop") || startsWithWord(name, "enable")
                || startsWithWord(name, "disable") || startsWithWord(name, "mark")
                || startsWithWord(name, "attach") || startsWithWord(name, "bind");
    }

    /** 菜单「关闭所有标签页」原始路径: 根包 id4(O=selector,P=false,Q=false).run() → jd4.a */
    private static void menuCloseAllTabs(Object selector, ClassLoader cl) {
        // v2.1.0: 152 无 id4(类职责已换), 直接遍历记忆模型关闭全部
        if (is152Layout()) {
            int closed = 0;
            for (Object model : getModels()) {
                try {
                    int count = modelCount(model);
                    for (int i = count - 1; i >= 0; i--) {
                        Object tab = modelGetTabAt(model, i);
                        if (tab != null) {
                            closeTab(model, tab);
                            closed++;
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(HookEntry.TAG + ": [152] close model failed -> " + t);
                }
            }
            XposedBridge.log(HookEntry.TAG + ": [152] close-all done, closed=" + closed);
            return;
        }
        // v2.3.23: 145 的 id4 是 R8 合成的 lambda 类, 其 <init>()V 未在 dex 中保留
        //   (ChromeTabbedActivity 用 invoke-direct Object.<init>()V 直接构造该实例),
        //   故反射 getConstructor()/getDeclaredConstructor() 必抛 NoSuchMethodException
        //   —— 145.0.7632.218 真机日志实证:
        //   `menu close-all-tabs failed -> java.lang.NoSuchMethodException: id4.<init> []`。
        //   改为直接调用 id4.run() 在 P=false 分支所委托的静态方法 jd4.a(selector, false)
        //   (Ljd4;->a(Ltuo;Z)V, 见 145 smali), 语义等价且无需实例化任何对象。
        //   失败则 [diag] 记录, 由 exitCleanup 的模型遍历兜底(确保退出仍能关掉全部标签)。
        try {
            Class<?> jd4Cls = Class.forName("jd4", false, cl);
            // 用声明参数类型(tuo, boolean)定位, 避免 selector 为 tuo 的具体子类时
            //   XposedHelpers.callStaticMethod 按运行时类型匹配失败。
            Class<?> tuoCls = Class.forName(CLS_SELECTOR(cl), false, cl); // 结构解析优先, 回退 145 tuo
            java.lang.reflect.Method m =
                    jd4Cls.getDeclaredMethod("a", tuoCls, boolean.class);
            m.setAccessible(true);
            m.invoke(null, selector, Boolean.FALSE);
            XposedBridge.log(HookEntry.TAG + ": menu close-all-tabs invoked (jd4.a)");
            return;
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG
                    + ": [diag] jd4.a close-all unavailable -> " + t
                    + ", relying on model-iteration fallback");
        }
        // 兼容回退: 若某构建仍保留可实例化的 id4 路径, 继续尝试(与旧实现一致)。
        try {
            Class<?> id4Cls = Class.forName(CLS_MENU_RUNNABLE(cl), false, cl);
            Object r;
            try {
                r = id4Cls.getConstructor().newInstance();
            } catch (Throwable t) {
                java.lang.reflect.Constructor<?> ctor = id4Cls.getDeclaredConstructor();
                ctor.setAccessible(true);
                r = ctor.newInstance();
            }
            XposedHelpers.setObjectField(r, "O", selector);
            XposedHelpers.setBooleanField(r, "P", false);
            XposedHelpers.setBooleanField(r, "Q", false);
            ((Runnable) r).run();
            XposedBridge.log(HookEntry.TAG + ": menu close-all-tabs invoked (id4.run)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": menu close-all-tabs failed -> " + t);
        }
    }

    /** 原生删除「关闭的标签页」: J.N.VIOOOOOOO(0, period, profile, null, {8}, ...) */
    private static void clearClosedTabs(final Activity activity, final ClassLoader cl,
                                        final int attempt) {
        try {
            if (!HookEntry.readPrefBoolean(HookEntry.KEY_CLEAR_TABS, true)) {
                return;
            }
            // v2.3.1(PR#1): 152 走 VIOOOOO 语义通道(BrowsingDataBridge native 桥)
            if (is152Layout()) {
                clearClosedTabs152(activity, cl, attempt);
                return;
            }
            int period = 4; // 全部时间(v1.9.5 起固定, 原 mapDaysToPeriod(0)=4)
            Class<?> pmClass = Class.forName(CLS_PROFILE_MANAGER, false, cl);
            Method pmB = pmClass.getMethod("b");
            Object profile = pmB.invoke(null);
            if (profile == null) {
                XposedBridge.log(HookEntry.TAG + ": profile not ready (attempt " + attempt + ")");
                if (attempt < MAX_CLEAR_ATTEMPTS) {
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (activity.isFinishing() || activity.isDestroyed()) {
                                return;
                            }
                            clearClosedTabs(activity, cl, attempt + 1);
                        }
                    }, 1000L);
                }
                return;
            }
            Class<?> nClass = Class.forName(HookEntry.CLS_J_N, false, cl);
            Method m = nClass.getMethod("VIOOOOOOO", int.class, int.class, Object.class,
                    Object.class, Object.class, Object.class, Object.class, Object.class,
                    Object.class);
            m.invoke(null, Integer.valueOf(METHOD_ID_REMOVE_BROWSING_DATA),
                    Integer.valueOf(period), profile, null,
                    new int[]{BROWSING_DATA_TYPE_TABS},
                    new String[0], new int[0], new String[0], new int[0]);
            XposedBridge.log(HookEntry.TAG + ": native clear closed tabs OK "
                    + "(type=" + BROWSING_DATA_TYPE_TABS + ", period=" + period + ")");
            LogTail.addAndPush(activity, "Home", "closed-tabs history cleared");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": clearClosedTabs failed -> " + t);
            LogTail.addError("Home-ERR", "clearClosedTabs -> " + t);
            LogTail.push(activity);
        }
    }


    // ---- 混淆无关路径(签名匹配 + 保留名, 兜底) ----

    private static boolean isIncognito(Object model) {
        // TabModel.isIncognito() 是 Chromium 保留的公开接口方法。未来版本必须优先按名调用；
        // 154 的实现 krq 同时有 isActiveModel/isClosingAllTabs/
        // isSessionRestoreInProgress 等多个无参 boolean 方法，按返回类型扫描会产生歧义，
        // 旧逻辑因而把普通模型误判为隐私模型，跳过主页复用。
        try {
            Object value = XposedHelpers.callMethod(model, "isIncognito");
            if (value instanceof Boolean) {
                return ((Boolean) value).booleanValue();
            }
        } catch (Throwable t) {
            if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                    + ": [diag] named isIncognito unavailable -> " + t);
        }
        try {
            Method m = findBySignature(model, boolean.class);
            if (m != null) {
                return Boolean.TRUE.equals(m.invoke(model));
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": isIncognito failed, treat as incognito -> " + t);
        }
        return true;
    }

    private static int modelCount(Object model) {
        // 152 修复: 公开 API 名 getCount() 未被混淆(TabModel 接口), 优先按名取。
        //   旧实现 findBySignature(model,int.class) 取"首个 0 参返回 int 方法", 在 152 上
        //   可能误命中 index()/R()(返回索引而非数量) → 退出清理读到 0 个标签 → 全失效。
        try {
            Object c = XposedHelpers.callMethod(model, "getCount");
            if (c instanceof Integer) {
                return ((Integer) c).intValue();
            }
        } catch (Throwable ignored) {
        }
        try {
            Method m = findBySignature(model, int.class);
            if (m != null) {
                Object c = m.invoke(model);
                return c instanceof Integer ? ((Integer) c).intValue() : 0;
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": getCount failed -> " + t);
        }
        return 0;
    }

    private static Object modelGetTabAt(Object model, int i) {
        // 152 修复: 公开 API 名 getTabAt(int) 优先(未被混淆)
        try {
            return XposedHelpers.callMethod(model, "getTabAt", Integer.valueOf(i));
        } catch (Throwable ignored) {
        }
        try {
            Method m = findBySignature(model, Object.class, int.class);
            if (m != null) {
                return m.invoke(model, Integer.valueOf(i));
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": getTabAt failed -> " + t);
        }
        return null;
    }

    private static Object modelCurrentTab(Object model) {
        int count = modelCount(model);
        if (count <= 0) return null;
        int index = 0;
        try {
            Object value = XposedHelpers.callMethod(model, "index");
            if (value instanceof Integer) index = ((Integer) value).intValue();
        } catch (Throwable ignored) {
        }
        if (index < 0 || index >= count) index = 0;
        return modelGetTabAt(model, index);
    }

    private static String tabUrl(Object tab) {
        try {
            return gurlSpec(XposedHelpers.callMethod(tab, "getUrl"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isEquivalentHomepage(String current, String configured) {
        if (configured == null || current == null) return false;
        if (configured.equals(current)) return true;
        try {
            java.net.URI a = java.net.URI.create(current);
            java.net.URI b = java.net.URI.create(configured);
            String as = a.getScheme();
            String bs = b.getScheme();
            if (!("http".equalsIgnoreCase(as) || "https".equalsIgnoreCase(as))
                    || !("http".equalsIgnoreCase(bs) || "https".equalsIgnoreCase(bs))) {
                return false;
            }
            String ah = normalizedHost(a.getHost());
            String bh = normalizedHost(b.getHost());
            if (ah == null || !ah.equals(bh)) return false;
            if (!isDefaultPort(a, as) || !isDefaultPort(b, bs)) return false;
            String ap = a.getPath();
            String bp = b.getPath();
            if (ap == null || ap.isEmpty()) ap = "/";
            if (bp == null || bp.isEmpty()) bp = "/";
            if (!ap.equals(bp)) return false;
            String aq = a.getRawQuery();
            String bq = b.getRawQuery();
            return aq == null ? bq == null : aq.equals(bq);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String normalizedHost(String host) {
        if (host == null) return null;
        String value = host.toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("www.") ? value.substring(4) : value;
    }

    private static boolean isDefaultPort(java.net.URI uri, String scheme) {
        int port = uri.getPort();
        return port == -1 || ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
    }

    private static boolean loadTabUrl153(Object tab, String url, ClassLoader cl) {
        try {
            Class<?> paramsClass = Class.forName(
                    "org.chromium.content_public.browser.LoadUrlParams", false, cl);
            Object params = paramsClass.getConstructor(String.class, int.class)
                    .newInstance(url, Integer.valueOf(67108864));
            if (!isSymbolic()) {
                try {
                    XposedHelpers.callMethod(tab, "l", params);
                    return true;
                } catch (Throwable t) {
                    if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                            + ": [diag] known tab load method unavailable -> " + t);
                }
            }
            Method hit = null;
            for (Method method : tab.getClass().getMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers()) && types.length == 1
                        && types[0] == paramsClass) {
                    if (hit != null) {
                        XposedBridge.log(HookEntry.TAG
                                + ": [modern-fast-home] load method ambiguous; keep restored tab");
                        return false;
                    }
                    hit = method;
                }
            }
            if (hit != null) {
                hit.invoke(tab, params);
                XposedBridge.log(HookEntry.TAG + ": [modern-fast-home] structural load method="
                        + hit.getName());
                return true;
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [modern-fast-home] in-place load failed -> " + t);
        }
        return false;
    }

    private static void closeTab(Object model, Object tab) {
        // 152 修复: 公开 API 名 closeTab(Tab) 优先(未被混淆)
        try {
            XposedHelpers.callMethod(model, "closeTab", tab);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Method m = findBySignature(model, void.class, Object.class);
            if (m != null) {
                m.invoke(model, tab);
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": closeTab failed -> " + t);
        }
    }

    private static void closeAllTabsExcept(Object keepTab) {
        for (Object model : getModels()) {
            try {
                int count = modelCount(model);
                int closed = 0;
                for (int i = count - 1; i >= 0; i--) {
                    Object tab = modelGetTabAt(model, i);
                    if (tab == null || tab == keepTab) {
                        continue;
                    }
                    closeTab(model, tab);
                    closed++;
                }
                XposedBridge.log(HookEntry.TAG + ": closed " + closed + "/" + count
                        + " tab(s) in model " + model.getClass().getName());
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG + ": close-all failed on "
                        + model.getClass().getName() + " -> " + t);
            }
        }
    }

    private static boolean isHomeTab(Object tab, String homeUrl, ClassLoader cl) {
        try {
            Object gurl = XposedHelpers.callMethod(tab, "getUrl");
            if (gurl == null) {
                return false;
            }
            // 1B-2: GURL 混淆 j() → 公开 getSpec()(j()/字段a 作 fallback)
            String url = gurlSpec(gurl);
            if (url == null) {
                return false;
            }
            return isNtp(url) || url.equals(homeUrl);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object openHomeTab(Object model, String url, ClassLoader cl) {
        try {
            Class<?> bridgeClass = Class.forName(CLS_TAB_MODEL_JNI_BRIDGE, false, cl);
            if (!bridgeClass.isInstance(model)) {
                XposedBridge.log(HookEntry.TAG + ": model is not TabModelJniBridge (actual "
                        + model.getClass().getName() + ")");
                return null;
            }
            Class<?> gurlClass = Class.forName(CLS_GURL, false, cl);
            Object gurl = gurlClass.getConstructor(String.class).newInstance(url);
            // 修复①(历史): 早期 findBySignature param0 用 Object 通配, 在 .82 上误匹配到
            //   qiq.M(形参顺序与调用意图不符) → invoke(gurl, Integer) 抛 IllegalArgumentException。
            //   故此处形参用严格类型 gurlClass 锚定。
            // 152(.82)实证: openTabProgrammatically(GURL, int disposition, boolean z) 为 **3 参**
            //   (jadx: return b(...).a(new LoadUrlParams(gurl), z?31:34, null, i));
            //   145 为 (GURL, int) 2 参。旧代码仅按 2 参匹配 → 152 上永不命中 →
            //   "no method matching signature (GURL, int)" → 冷启动 open-home 全败。
            //   修正: 先按 3 参匹配(152), 未命中再退回 2 参(145); 都无则 fail-closed(保留现有标签)。
            final int disposition = 2;
            Method m = isChrome145() ? null
                    : findBySignature(model, Object.class, gurlClass, int.class, boolean.class);
            Object tab;
            if (m != null) {
                XposedBridge.log(HookEntry.TAG + ": [diag] openHomeTab matched "
                        + m.getDeclaringClass().getName() + "." + m.getName()
                        + java.util.Arrays.toString(m.getParameterTypes())
                        + " -> invoke(gurl=" + url + ", disposition=" + disposition + ", z=false)");
                tab = m.invoke(model, gurl, Integer.valueOf(disposition), Boolean.FALSE);
            } else {
                m = findBySignature(model, Object.class, gurlClass, int.class);
                if (m == null) {
                    XposedBridge.log(HookEntry.TAG
                            + ": [diag] openHomeTab: no method matching signature (GURL, int[, boolean]) on "
                            + model.getClass().getName()
                            + ", fail-closed (keep existing tabs, no force-open)");
                    return null;
                }
                XposedBridge.log(HookEntry.TAG + ": [diag] openHomeTab matched "
                        + m.getDeclaringClass().getName() + "." + m.getName()
                        + java.util.Arrays.toString(m.getParameterTypes())
                        + " -> invoke(gurl=" + url + ", disposition=" + disposition + ")");
                tab = m.invoke(model, gurl, Integer.valueOf(disposition));
            }
            XposedBridge.log(HookEntry.TAG + ": opened home tab -> "
                    + (tab == null ? "null" : tab.getClass().getName()));
            return tab;
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": openHomeTab failed -> " + t);
            return null;
        }
    }

    /** 按"返回类型+参数类型"匹配方法(名字无关), 从实际类向上查 */
    private static Method findBySignature(Object model, Class<?> returnType,
                                          Class<?>... paramTypes) {
        try {
            // v1.9.0: 修复返回类型精确匹配失效问题。
            // 之前用 findMethodsByExactParameters(c, Object.class, ...) 精确匹配返回
            // 类型, 但 Chrome 145 真实方法返回具体类型
            // (Tab openTabProgrammatically(GURL,int) / Tab getTabAt(int) 等),
            // 导致永远匹配不到。改为手动遍历:
            // returnType == Object.class 表示接受任意返回类型;
            // 参数中 Object.class 表示接受任意实参类型。
            Method hit = null;
            Class<?> c = model.getClass();
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) {
                        continue;
                    }
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length != paramTypes.length) {
                        continue;
                    }
                    boolean paramsOk = true;
                    for (int i = 0; i < pts.length; i++) {
                        Class<?> want = paramTypes[i];
                        if (want == Object.class) {
                            continue; // 任意参数类型
                        }
                        if (!pts[i].equals(want) && !pts[i].isAssignableFrom(want)) {
                            paramsOk = false;
                            break;
                        }
                    }
                    if (!paramsOk) {
                        continue;
                    }
                    if (returnType != null && returnType != Object.class
                            && !m.getReturnType().equals(returnType)) {
                        continue;
                    }
                    if (!isSymbolic()) return m;
                    if (hit != null) {
                        XposedBridge.log(HookEntry.TAG + ": [diag] future method signature ambiguous on "
                                + model.getClass().getName() + "; keep Chrome native behavior");
                        return null;
                    }
                    hit = m;
                }
                c = c.getSuperclass();
            }
            return hit;
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": findBySignature(" + returnType + ") failed -> " + t);
        }
        return null;
    }

    /** 入站黑名单: 判"被新开的标签是否 NTP(需替换成主页)"。加固1(Minor-1)补全 chrome-search://
     *  (Chromium NtpUtils.isNtpUrl 承认的 chrome-search://local-ntp 形态)与 chrome-untrusted://,
     *  使这些 NTP 形态也被识别为需替换。注意: 与 isLoadableHome(出站白名单)用途不同, 勿混用。 */
    static boolean isNtp(String url) {
        return url != null && (url.startsWith("chrome://") || url.startsWith("chrome-native://")
                || url.startsWith("chrome-search://") || url.startsWith("chrome-untrusted://"));
    }

    /** 出站白名单(加固1/Minor-1): 仅 http/https 视为可安全普通导航的主页(Chrome 自定义主页只接受
     *  http/https)。其余(null/空/chrome://、chrome-native://、chrome-search://、chrome-untrusted://、
     *  devtools://、about:、data:、file: 等特权/内部 scheme)一律 false → 消费路径 fail-closed 跳过替换,
     *  根治整类"WebUI scheme 无法访问"故障。用于"解析出的主页能否导航"(出站); isNtp 用于"入站标签
     *  是否 NTP"(黑名单), 两者用途不同, 勿混用。 */
    static boolean isLoadableHome(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /** v2.3.1(PR#1): Chrome 152 的关闭标签页历史清理 — N.VIOOOOO 语义通道 */
    private static void clearClosedTabs152(final Activity activity, final ClassLoader cl,
                                            final int attempt) {
        try {
            int period = 4; // 全部时间段
            Class<?> pmClass = Class.forName(CLS_PROFILE_MANAGER, false, cl);
            Class<?> profileClass = Class.forName(
                    "org.chromium.chrome.browser.profiles.Profile", false, cl);
            Method profileGetter = uniqueStaticMethod(pmClass, profileClass);
            if (profileGetter == null && !isSymbolic()) profileGetter = pmClass.getMethod("b");
            if (profileGetter == null) {
                XposedBridge.log(HookEntry.TAG + ": [modern] profile getter unresolved; skip history cleanup");
                retryClosedTabsClear(activity, cl, attempt);
                return;
            }
            Object profile = profileGetter.invoke(null);
            if (profile == null) {
                XposedBridge.log(HookEntry.TAG + ": [152] profile not ready, skip");
                retryClosedTabsClear(activity, cl, attempt);
                return;
            }
            Class<?> bridgeClass = Class.forName(
                    "org.chromium.chrome.browser.browsing_data.BrowsingDataBridge",
                    false, cl);
            Method bridgeFactory = uniqueStaticMethod(bridgeClass, bridgeClass, profileClass);
            if (bridgeFactory == null && !isSymbolic()) {
                bridgeFactory = bridgeClass.getMethod("a", profileClass);
            }
            if (bridgeFactory == null) {
                XposedBridge.log(HookEntry.TAG + ": [modern] BrowsingDataBridge factory unresolved");
                retryClosedTabsClear(activity, cl, attempt);
                return;
            }
            Object bridge = bridgeFactory.invoke(null, profile);
            Object nativeBridge = null;
            for (Field field : bridgeClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !profileClass.isAssignableFrom(field.getType())) continue;
                if (nativeBridge != null) {
                    XposedBridge.log(HookEntry.TAG
                            + ": [modern] BrowsingDataBridge profile field ambiguous");
                    retryClosedTabsClear(activity, cl, attempt);
                    return;
                }
                field.setAccessible(true);
                nativeBridge = field.get(bridge);
            }
            if (nativeBridge == null) {
                retryClosedTabsClear(activity, cl, attempt);
                return;
            }
            Class<?> listenerClass = Class.forName(
                    "org.chromium.chrome.browser.browsing_data.BrowsingDataBridge$OnClearBrowsingDataListener",
                    false, cl);
            Object listener = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class<?>[]{listenerClass}, (proxy, method, args) -> null);
            Class<?> nClass = Class.forName(HookEntry.CLS_J_N, false, cl);
            Method current = nClass.getMethod("VIOOOOO", int.class, Object.class,
                    Object.class, Object.class, Object.class, Object.class);
            current.invoke(null, Integer.valueOf(period), nativeBridge, listener,
                    new int[]{BROWSING_DATA_TYPE_TABS}, new String[0], new String[0]);
            XposedBridge.log(HookEntry.TAG + ": [modern] closed-tabs history cleared (VIOOOOO)");
            LogTail.addAndPush(activity, "Home", "closed-tabs history cleared (modern)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [modern] history cleanup failed -> " + t);
            LogTail.addError("Home-ERR", "history cleanup -> " + t);
            LogTail.push(activity);
            retryClosedTabsClear(activity, cl, attempt);
        }
    }

    private static void retryClosedTabsClear(final Activity activity, final ClassLoader cl,
                                             final int attempt) {
        if (attempt >= MAX_CLEAR_ATTEMPTS) return;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                clearClosedTabs(activity, cl, attempt + 1);
            }
        }, 1000L);
    }

    private static Method uniqueStaticMethod(Class<?> owner, Class<?> returnType,
                                              Class<?>... parameterTypes) {
        Method hit = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    || !returnType.isAssignableFrom(method.getReturnType())) continue;
            Class<?>[] actual = method.getParameterTypes();
            if (actual.length != parameterTypes.length) continue;
            boolean same = true;
            for (int i = 0; i < actual.length; i++) {
                if (actual[i] != parameterTypes[i]) {
                    same = false;
                    break;
                }
            }
            if (!same) continue;
            if (hit != null) return null;
            method.setAccessible(true);
            hit = method;
        }
        return hit;
    }
}
