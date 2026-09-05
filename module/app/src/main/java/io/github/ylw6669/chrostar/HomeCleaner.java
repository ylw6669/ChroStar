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

    private static final String CLS_SELECTOR = "tuo";   // Chrome 145 TabModelSelector
    private static final String CLS_SELECTOR_152 = "k3r"; // Chrome 152 TabModelSelector
    private static final String CLS_HOME_MGR = "jza";   // Chrome 145 HomepageManager
    private static final String CLS_HOME_MGR_152 = "w5c"; // Chrome 152 HomepageManager
    private static final String CLS_MENU_RUNNABLE = "id4"; // 菜单关闭 Runnable(根包)
    private static final String CLS_TAB_MODEL_JNI_BRIDGE =
            "org.chromium.chrome.browser.tabmodel.TabModelJniBridge";
    private static final String CLS_GURL = "org.chromium.url.GURL";
    private static final String CLS_PROFILE_MANAGER =
            "org.chromium.chrome.browser.profiles.ProfileManager";
    private static final String DEFAULT_NTP = "chrome-native://newtab/";

    private static final int BROWSING_DATA_TYPE_TABS = 8;
    private static final int METHOD_ID_REMOVE_BROWSING_DATA = 0;
    private static final int MAX_CLEAR_ATTEMPTS = 4;
    private static final int MAX_COLD_ROUNDS = 4;

    /** 记忆的模型实例(WeakReference) */
    private static final List<WeakReference<Object>> sModels = new ArrayList<WeakReference<Object>>();
    private static final Object sModelsLock = new Object();

    /** 缓存的 selector 字段 */
    private static Field sSelectorField;

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
            Object homeTab = null;
            int count = modelCount(regular);
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

    // ------------------------------------------------------------------
    // v1.10.6: 主入口 —— l04(ChromeTabCreator).l(LoadUrlParams,...) = createNewTab。
    // + 号按钮的真实路径(TraceEvent "ChromeTabCreator.createNewTab" 实锤),
    // LoadUrlParams.a = URL字符串(保留名字段), 是 NTP 且开关开时直接替换。
    // ------------------------------------------------------------------
    private static void hookTabCreator(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> l04 = XposedHelpers.findClass("l04", lpparam.classLoader);
            // v1.10.7: hookAllMethods 覆盖所有 "l" 重载(避免 + 号走别的重载),
            // 仅当 args[0] 是 LoadUrlParams 才处理; 并加诊断日志定位真实 URL/开关值。
            XposedBridge.hookAllMethods(l04, "l", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        boolean sw = HookEntry.readPrefBoolean(
                                HookEntry.KEY_NEWTAB_HOME, true);
                        Object params = (param.args != null && param.args.length > 0)
                                ? param.args[0] : null;
                        if (params == null) {
                            return;
                        }
                        Object urlObj;
                        try {
                            urlObj = XposedHelpers.getObjectField(params, "a");
                        } catch (Throwable t) {
                            urlObj = null; // 不是 LoadUrlParams
                        }
                        String spec = urlObj instanceof String ? (String) urlObj : null;
                        if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                                + ": l04.l called, sw=" + sw + ", url=" + spec);
                        if (!sw) return;
                        if (spec == null || !isNtp(spec)) return;
                        String home = resolveHomeUrl(
                                param.thisObject.getClass().getClassLoader());
                        if (home == null || home.isEmpty() || isNtp(home)) return;
                        XposedHelpers.setObjectField(params, "a", home);
                        XposedBridge.log(HookEntry.TAG
                                + ": createNewTab NTP -> home " + home);
                    } catch (Throwable t) {
                        XposedBridge.log(HookEntry.TAG
                                + ": hookTabCreator error -> " + t);
                    }
                }
            });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked l04.l* (ChromeTabCreator all overloads, newtab->home)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook l04.l unavailable -> " + t);
        }
        try {
            Class<?> selector = XposedHelpers.findClass(CLS_SELECTOR_152,
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(selector, "B", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    replaceNtpWithHome(param);
                }
            });
            XposedBridge.log(HookEntry.TAG + ": hooked k3r.B* (Chrome 152 newtab->home)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook k3r.B unavailable -> " + t);
        }
    }

    private static void replaceNtpWithHome(XC_MethodHook.MethodHookParam param) {
        try {
            if (!HookEntry.readPrefBoolean(HookEntry.KEY_NEWTAB_HOME, true)
                    || param.args == null || param.args.length == 0 || param.args[0] == null) {
                return;
            }
            Object params = param.args[0];
            Object urlObj = XposedHelpers.getObjectField(params, "a");
            String spec = urlObj instanceof String ? (String) urlObj : null;
            if (spec == null || !isNtp(spec)) return;
            String home = resolveHomeUrl(param.thisObject.getClass().getClassLoader());
            if (home == null || home.isEmpty() || isNtp(home)) return;
            XposedHelpers.setObjectField(params, "a", home);
            XposedBridge.log(HookEntry.TAG + ": createNewTab NTP -> home " + home);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": newtab replacement error -> " + t);
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
                        Object specObj = XposedHelpers.callMethod(gurl, "j");
                        if (!(specObj instanceof String)) {
                            return;
                        }
                        String spec = (String) specObj;
                        if (!isNtp(spec)) {
                            return; // 不是 NTP(如 target=_blank 具体 URL), 不替换
                        }
                        String home = resolveHomeUrl(
                                param.thisObject.getClass().getClassLoader());
                        if (home == null || home.isEmpty() || isNtp(home)) {
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

    /** 找 TabModelSelector(根包 tuo): 精确 Class.forName + activity 字段扫描 */
    private static Object findTabModelSelector(Activity activity) {
        try {
            ClassLoader cl = activity.getClass().getClassLoader();
            Class<?> selectorClass;
            try {
                selectorClass = Class.forName(CLS_SELECTOR_152, false, cl);
            } catch (Throwable ignored) {
                selectorClass = Class.forName(CLS_SELECTOR, false, cl);
            }
            if (sSelectorField != null) {
                try {
                    Object cached = sSelectorField.get(activity);
                    if (cached != null && selectorClass.isInstance(cached)) {
                        return cached;
                    }
                } catch (Throwable ignored) {
                }
            }
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
                            if (v != null && selectorClass.isInstance(v)) {
                                sSelectorField = f;
                                XposedBridge.log(HookEntry.TAG + ": TabModelSelector field = "
                                        + c.getName() + "." + f.getName());
                                return v;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
                c = c.getSuperclass();
            }
            XposedBridge.log(HookEntry.TAG + ": selector field not found on activity");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": findTabModelSelector error -> " + t);
        }
        return null;
    }

    /** 常规模型: selector.l(false)(根包方法名) */
    private static Object getRegularModel(Object selector) {
        try {
            Object m = XposedHelpers.callMethod(selector, "l", Boolean.FALSE);
            if (m != null) {
                return m;
            }
        } catch (Throwable t) {
            try {
                Object m = XposedHelpers.callMethod(selector, "k", Boolean.FALSE);
                if (m != null) return m;
            } catch (Throwable ignored) {
            }
            XposedBridge.log(HookEntry.TAG + ": selector model lookup failed -> " + t);
        }
        return getRegularModelByMemory();
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

    /** 主页 URL: home_url 配置 → 根包 jza(主页管理器).d().b(false) → NTP */
    private static String resolveHomeUrl(ClassLoader cl) {
        // Chrome 152: w5c.b(boolean, boolean) is the current HomepageManager API.
        try {
            Class<?> homeMgr = Class.forName(CLS_HOME_MGR_152, false, cl);
            Object singleton = XposedHelpers.callStaticMethod(homeMgr, "d");
            if (singleton != null) {
                Object gurl = XposedHelpers.callMethod(singleton, "b", Boolean.FALSE,
                        Boolean.FALSE);
                if (gurl != null) {
                    Object urlText = XposedHelpers.getObjectField(gurl, "a");
                    if (urlText instanceof String && !((String) urlText).isEmpty()) {
                        return (String) urlText;
                    }
                }
            }
        } catch (Throwable t) {
            if (HookEntry.DEBUG) {
                XposedBridge.log(HookEntry.TAG + ": resolveHomeUrl(w5c) failed -> " + t);
            }
        }
        // Chrome 145 fallback: jza.d().b(boolean).
        try {
            Class<?> jzaClass = Class.forName(CLS_HOME_MGR, false, cl);
            Object singleton = XposedHelpers.callStaticMethod(jzaClass, "d");
            if (singleton != null) {
                Object gurl = XposedHelpers.callMethod(singleton, "b", Boolean.FALSE);
                if (gurl != null) {
                    Object urlText = XposedHelpers.getObjectField(gurl, "a");
                    if (urlText instanceof String && !((String) urlText).isEmpty()) {
                        return (String) urlText;
                    }
                }
            }
        } catch (Throwable t) {
            if (HookEntry.DEBUG) {
                XposedBridge.log(HookEntry.TAG + ": resolveHomeUrl(jza) failed -> " + t);
            }
        }
        return DEFAULT_NTP;
    }

    /** 菜单「关闭所有标签页」原始路径: 根包 id4(O=selector,P=false,Q=false).run() → jd4.a */
    private static void menuCloseAllTabs(Object selector, ClassLoader cl) {
        try {
            Class<?> id4Cls = Class.forName(CLS_MENU_RUNNABLE, false, cl);
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
            try {
                // Chrome 152: ClearBrowsingDataFragment calls
                // N.VIOOOOO(period, BrowsingDataBridge.a(profile), listener,
                //                  dataTypes, allowlistedDomains, excludedDomains).
                Class<?> bridgeClass = Class.forName(
                        "org.chromium.chrome.browser.browsing_data.BrowsingDataBridge",
                        false, cl);
                Object bridge = XposedHelpers.callStaticMethod(bridgeClass, "a", profile);
                Object nativeBridge = XposedHelpers.getObjectField(bridge, "a");
                Class<?> listenerClass = Class.forName(
                        "org.chromium.chrome.browser.browsing_data.BrowsingDataBridge$OnClearBrowsingDataListener",
                        false, cl);
                Object listener = java.lang.reflect.Proxy.newProxyInstance(
                        cl, new Class<?>[]{listenerClass}, (proxy, method, args) -> null);
                Method current = nClass.getMethod("VIOOOOO", int.class, Object.class,
                        Object.class, Object.class, Object.class, Object.class);
                current.invoke(null, Integer.valueOf(period), nativeBridge, listener,
                        new int[]{BROWSING_DATA_TYPE_TABS}, new String[0], new String[0]);
            } catch (Throwable currentFailure) {
                // Chrome 145 fallback: the old generated JNI signature included
                // an extra method-id/period pair and four array arguments.
                Method legacy = nClass.getMethod("VIOOOOOOO", int.class, int.class,
                        Object.class, Object.class, Object.class, Object.class,
                        Object.class, Object.class, Object.class);
                legacy.invoke(null, Integer.valueOf(METHOD_ID_REMOVE_BROWSING_DATA),
                        Integer.valueOf(period), profile, null,
                        new int[]{BROWSING_DATA_TYPE_TABS},
                        new String[0], new int[0], new String[0], new int[0]);
            }
            XposedBridge.log(HookEntry.TAG + ": native clear closed tabs OK "
                    + "(type=" + BROWSING_DATA_TYPE_TABS + ", period=" + period + ")");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": clearClosedTabs failed -> " + t);
        }
    }


    // ---- 混淆无关路径(签名匹配 + 保留名, 兜底) ----

    private static boolean isIncognito(Object model) {
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

    private static void closeTab(Object model, Object tab) {
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
            Object spec = XposedHelpers.callMethod(gurl, "j");
            if (!(spec instanceof String)) {
                return false;
            }
            String url = (String) spec;
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
            Method m = null;
            try {
                m = model.getClass().getSuperclass().getMethod(
                        "openTabProgrammatically", gurlClass, int.class, boolean.class);
            } catch (Throwable ignored) {
                try {
                    Class<?> bridgeType = Class.forName(CLS_TAB_MODEL_JNI_BRIDGE, false, cl);
                    m = bridgeType.getDeclaredMethod(
                            "openTabProgrammatically", gurlClass, int.class, boolean.class);
                } catch (Throwable ignoredAgain) {
                    m = findBySignature(model, Object.class, Object.class, int.class);
                }
            }
            if (m == null) {
                return null;
            }
            Object tab;
            if (m.getParameterTypes().length == 3) {
                tab = m.invoke(model, gurl, Integer.valueOf(2), Boolean.FALSE);
            } else {
                tab = m.invoke(model, gurl, Integer.valueOf(2));
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
                    return m;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": findBySignature(" + returnType + ") failed -> " + t);
        }
        return null;
    }

    static boolean isNtp(String url) {
        return url != null && (url.startsWith("chrome://") || url.startsWith("chrome-native://"));
    }
}
