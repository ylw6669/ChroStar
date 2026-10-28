package io.github.ylw6669.chrostar;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
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
 *  2) 冷启动: 145/152/153 均只改写 Chrome 原生创建的 NTP，不主动创建第二个主页标签
 *  3) 模型记忆: TabModelJniBridge 无参 int 方法(签名匹配) -> 记模型实例
 *  4) 新标签页(+)/主页: l04.l + TabModelJniBridge.openNewTab 的 NTP 替换为主页 URL
 *  5) 下载六类弹窗绕过(DownloadSafetyBypass) + APK 自动安装(AutoInstallApk 四路径)
 *  6) 横幅控制(BannerController): 下载完成横幅 -> Toast; 翻译横幅双重拦截
 *  7) 退出清理: 关全部标签(含无痕) + 原生删除「关闭的标签页」历史
 *
 * 配置读取(v2.3.19): ConfigManager 统一受理 —— New XSP(主) → ConfigProvider(兜底) → 旧 XSP(末位)。
 * 版本号单源: 日志/关于页均引用 BuildConfig.VERSION_NAME。
 */
public class HookEntry implements IXposedHookLoadPackage {

    public static final String TAG = "HomeLauncherLSP";
    /** 热路径诊断日志开关(编译期常量, 发布版关闭) */
    static final boolean DEBUG = false;
    public static final String PREFS_NAME = "io.github.ylw6669.chrostar_preferences";
    /** Chrome native 桥类(J.N), DownloadSafetyBypass/HomeCleaner 共用 */
    public static final String CLS_J_N = "J.N";

    /** 引擎版本绑定: chrome145 / chrome152 / chrome153, handleLoadPackage 时判定一次 */
    public static volatile String engineVersion = "unknown";
    private static volatile boolean sLateHooksInstalled = false;

    /** 修复D: 降级 hook once-guard(base loader / split loader 两条调用路径共享), 防重复注册公开类 hook */
    private static volatile boolean sDegradedInstalled = false;

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
    // v2.2.0 新功能键(v2.3.13: 移除 KEY_OVERWRITE_DUPLICATE, 不再提供同名覆盖保留原文件)
    public static final String KEY_AUTO_OPEN_EXT = "auto_open_ext";
    public static final String KEY_AUTO_OPEN_PDF = "auto_open_pdf";
    public static final String KEY_AUTO_OPEN_ARCHIVE = "auto_open_archive";
    public static final String KEY_AUTO_OPEN_DOCUMENT = "auto_open_document";
    public static final String KEY_AUTO_OPEN_SPREADSHEET = "auto_open_spreadsheet";
    public static final String KEY_AUTO_OPEN_PRESENTATION = "auto_open_presentation";
    public static final String KEY_AUTO_OPEN_TEXT = "auto_open_text";
    public static final String KEY_AUTO_OPEN_IMAGE = "auto_open_image";
    public static final String KEY_AUTO_OPEN_VIDEO = "auto_open_video";
    public static final String KEY_AUTO_OPEN_AUDIO = "auto_open_audio";
    public static final String KEY_AUTO_OPEN_EBOOK = "auto_open_ebook";
    // v2.3.14: 自定义自动打开后缀(逗号分隔, 已去点/小写)。用户可指定内置类型之外的后缀。
    public static final String KEY_AUTO_OPEN_CUSTOM = "auto_open_custom";
    // v2.3.14: 自定义下载目录(绝对路径)。空=用 Chrome 默认目录。
    public static final String KEY_DOWNLOAD_DIR = "download_dir";
    public static final String KEY_DIAGNOSTIC_MODE = "diagnostic_mode";
    /** v2.5.6: 事件日志总开关(true=无 root 调试页仍展示关键 hook 事件)。 */
    public static final String KEY_DEBUG_EVENTS = "debug_events";
    /** v2.5.6: 详细日志开关(true=保留更容易刷屏的分支/字段级事件)。 */
    public static final String KEY_DEBUG_VERBOSE = "debug_verbose";
    /** v2.5.0: 手动重扫请求时间戳(ms)。设置页写; hook 侧发现比缓存新即强制重扫。此键全局有效, 不分档案。 */
    public static final String KEY_RESCAN_REQUEST_AT = "rescan_request_at";

    private static final String CLS_CHROME_TABBED_ACTIVITY =
            "org.chromium.chrome.browser.ChromeTabbedActivity";
    private static final String CLS_TAB_MODEL_JNI_BRIDGE =
            "org.chromium.chrome.browser.tabmodel.TabModelJniBridge";

    private static final Set<Activity> sColdHandled =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private static final Set<Activity> sHistoryCleared =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());

    /** v2.4.1: 冷启动防 Chrome 145 自关闭防护标志。
     *  hookOnStart 调 coldStartCleanup 时置 true, settled 后 / 超时 8s 清 false。
     *  145 的 jza.k()=(shouldCloseAppWithZeroTabs) 在此窗口返回 false,
     *  防止 closeAllTabsExcept 中间态总标签数=0 触 Chrome 自 finish → 观感闪退。 */
    private static volatile boolean sColdStartupProtect = false;

    static boolean isColdStartupProtected() { return sColdStartupProtect; }
    static void setColdStartupProtect(boolean v) { sColdStartupProtect = v; }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!isChromePackage(lpparam)) {
            return;
        }
        if (lpparam.processName != null && !lpparam.processName.equals(lpparam.packageName)) {
            return;
        }
        // v2.3.1(PR#1 整合): Chrome 152+ 把主类放进 isolated split("chrome"),
        // lpparam.classLoader 只有 base.apk — 先挂三重探测, split loader 就绪后再装主体 hook。
        hookApplicationAttach(lpparam);
        hookSplitContextCreation(lpparam);
        hookActivityCreate(lpparam);

        engineVersion = detectEngineVersion(lpparam);
        XposedBridge.log(TAG + ": engine=" + engineVersion
                + (sLateHooksInstalled ? " (split loader active)" : " (base loader)"));
        bindProfile(lpparam); // v2.5.0: 绑定 Chrome 大版本配置档案

        if (!"chrome145".equals(engineVersion) && !"chrome152".equals(engineVersion)
                && !"chrome153".equals(engineVersion)
                && !"chrome_symbolic".equals(engineVersion)) {
            // v2.3.1: unknown 引擎 — base loader 上先装公开类降级 hook(防 nothing);
            // split loader 就绪后 installLateHooks 会重新 detectEngineVersion 并装完整 hook。
            XposedBridge.log(TAG + ": unknown engine for " + lpparam.packageName
                    + " — degraded public-class hooks on base loader (split retry armed)");
            installDegradedHooks(lpparam);
            return;
        }
        // 1A-1: base loader 已判为 145/152(非 unknown) → 下面的直装即完整安装全部 hook。
        // 置 sLateHooksInstalled=true 使后续 Application.attach / createContextForSplit /
        // callActivityOnCreate 触发的 installLateHooks 成为 no-op, 消除单体 base.apk 上的二次
        // 全量安装: 单体机 isValidSplitLoader(base) 天然为真(只校验 ChromeTabbedActivity +
        // TabModelJniBridge 可加载), 若不置位 → installLateHooks 再装一遍 → ia8.d/zkg.f 被注册
        // 两次 → 一次下载完成被处理两遍(双开/双横幅)。此处置位安全: handleLoadPackage 同步运行
        // 在 Application.attach 之前, installLateHooks 只可能在其返回后被触发, 无并发竞态; 且各
        // hookXxx 内部自带 try-catch 不外抛, 直装必完成。
        // isolated-split 机: base 看不到 Chrome 类 → detectEngineVersion=unknown → 上面已 return,
        // 永不执行到此行 → 不置位 → split loader 就绪后 installLateHooks 晚装路径完整保留。
        // m12: once-guard 置位门禁收紧 —— 与 installLateHooks(split 侧)的 isValidSplitLoader 门禁对称:
        //   仅当 base loader 确能看到公开 Chrome 类(ChromeTabbedActivity + TabModelJniBridge)时才置位并
        //   直装完整 hook。杜绝理论缺口"oo4 短名类在 base 可见、但公开类在 isolated split": 若此时盲目
        //   置位, split loader 就绪后的晚装路径被永久屏蔽, 依赖公开类的 hook 全失效。门禁不过则降级安装
        //   并保留 split 晚装(单体机 isValidSplitLoader(base) 天然为真 → 正常路径行为完全不变)。
        if (!isValidSplitLoader(lpparam.classLoader)) {
            XposedBridge.log(TAG + ": [diag] engine=" + engineVersion
                    + " but base loader lacks public Chrome classes — defer full hooks to split loader"
                    + " (once-guard NOT set)");
            installDegradedHooks(lpparam);
            return;
        }
        sLateHooksInstalled = true;
        if ("chrome153".equals(engineVersion)) {
            installChrome153Hooks(lpparam);
            return;
        }
        if ("chrome152".equals(engineVersion) || "chrome_symbolic".equals(engineVersion)) {
            // 152/153+: 稳定类同名 hook 直接装; 混淆短名 hook 走 ChromeSymbols 结构解析
            //   (解析失败回退 Chrome152 短名表)。chrome_symbolic = 153+ 未重打表路径。
            hookTabModelMemory(lpparam);
            hookExitCleanup(lpparam);
            HomeCleaner.hookOpenNewTab(lpparam);
            DownloadSafetyBypass.hook(lpparam);
            AutoInstallApk.hook(lpparam);
            BannerController.hook(lpparam);
            LocationAndDiagnostics.hookLocationPrefSource(lpparam);
            hookCommandLineFlags152(lpparam);
            hookFastHomeRestore153(lpparam);
            DownloadDirRedirect.hook(lpparam);
            LocationAndDiagnostics.noteHook("152 path hooks", true);
            XposedBridge.log(TAG + ": v" + BuildConfig.VERSION_NAME + " hooks installed ("
                    + ("chrome_symbolic".equals(engineVersion) ? "symbolic+152" : "152")
                    + " path) for " + lpparam.packageName);
            LogTail.addAndPush(getAppContext(lpparam.classLoader), "Hook",
                    "hooks installed engine=" + engineVersion);
            return;
        }
        // 145 原路径
        hookCommandLineFlags(lpparam);
        hookTabModelMemory(lpparam);
        // 145 strictly follows the v2.0.0 plus/new-tab path: do not install
        // onStart -> coldStartCleanup, otherwise startup NTP handling can stack
        // with l04.l URL rewriting and navigate to the custom home twice.
        hookExitCleanup(lpparam);
        HomeCleaner.hookOpenNewTab145Legacy(lpparam);
        DownloadSafetyBypass.hook(lpparam);
        AutoInstallApk.hook(lpparam);
        BannerController.hook(lpparam);
        LocationAndDiagnostics.hookLocationPrefSource(lpparam);
        DownloadDirRedirect.hook(lpparam);
        hookShouldCloseAppWithZeroTabs(lpparam.classLoader);
        LocationAndDiagnostics.noteHook("145 path hooks", true);
        XposedBridge.log(TAG + ": v" + BuildConfig.VERSION_NAME + " hooks installed for " + lpparam.packageName
                + " (process " + lpparam.processName + ")");
        LogTail.addAndPush(getAppContext(lpparam.classLoader), "Hook", "hooks installed engine=" + engineVersion);
    }

    /** 引擎版本判定: 152/153 独立指纹优先，再 145 锚点，最后保留 symbolic 降级。 */
    /** v2.5.0: 依据已安装 Chrome 的大版本号绑定配置档案(如 v154), 使各版本设置互相隔离。
     *  进程早期 Context 可能尚未就绪 -> 此处未绑定成功时, installLateHooks 会再试一次。
     *  绑定失败不启用档案, 退回旧全局键语义(行为与 2.4.x 一致, 不影响既有用户)。 */
    private static volatile boolean sProfileBound = false;

    private static void bindProfile(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sProfileBound) return;
        try {
            android.content.Context ctx = getAppContext(lpparam.classLoader);
            String major = ProfilePrefs.installedMajor(ctx, lpparam.packageName);
            if (major == null) {
                return; // Context 未就绪, 留给 installLateHooks 重试
            }
            String profile = ProfilePrefs.profileOfMajor(major);
            ConfigManager.setProfile(profile);
            sProfileBound = profile != null;
            XposedBridge.log(TAG + ": [profile] config profile="
                    + (profile == null ? "<legacy-global>" : profile)
                    + " (chrome " + lpparam.packageName + " major=" + major + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [profile] bind failed -> " + t);
        }
    }

    private static String detectEngineVersion(XC_LoadPackage.LoadPackageParam lpparam) {
        // Chrome 大版本是最高优先级隔离条件。R8 会在大版本间复用短名，若只靠短名指纹，
        // 未来版本可能偶然同时命中 152/153/145 的旧组合而被错误路由。能读到版本名时不跨档试探。
        String major = null;
        try {
            android.content.Context ctx = getAppContext(lpparam.classLoader);
            major = ProfilePrefs.installedMajor(ctx, lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [engine] installed major unavailable -> " + t);
        }
        try {
            if ((major == null || "152".equals(major))
                    && Chrome152.matches(lpparam.classLoader)) {
                return "chrome152";
            }
            if ((major == null || "153".equals(major))
                    && Chrome153.matches(lpparam.classLoader)) {
                return "chrome153";
            }
        } catch (Throwable ignored) {
        }
        // 1B-3: 145 判定收紧 —— "oo4 存在" + "签名锚点 oo4.c(String)->boolean" 双条件。
        //   旧实现仅凭 oo4 裸类名存在即判 145(过松): 未来 152+ 构建 R8 可能把 oo4
        //   分配给任意无关类 → 假阳性误判为 145 → 走错硬编码路径。锚点校验参照
        //   Chrome152.matches 的方法签名风格, 要求 oo4 具备 ChromeCommandLineFlags 的
        //   hasSwitch 语义签名(c(String)->boolean, hookCommandLineFlags 正依赖它)。
        Class<?> oo4 = null;
        boolean allow145 = major == null || "145".equals(major);
        if (allow145) {
            try {
                oo4 = Class.forName("oo4", false, lpparam.classLoader);
            } catch (Throwable ignored) {
            }
        }
        Boolean anchor = oo4 == null ? Boolean.FALSE : check145Anchor(oo4);
        // fail-safe: 锚点命中(TRUE)或校验异常(null)都判 145 —— 收紧只拦"明确不符",
        //   绝不因反射异常漏判真 145(异常时保守回退到旧行为)。
        if (Boolean.TRUE.equals(anchor)) {
            return "chrome145";
        }
        // oo4 存在但无 c(String)->boolean 锚点 → 疑似假阳性, 收紧判 unknown
        // v2.4.0: 145 判定失败后, 尝试符号解析(153+ 适配) —— 公开锚点齐备即判 chrome_symbolic,
        //   走与 152 同构的"公开类 + 结构解析"路径(HomeCleaner/BannerController/AutoInstallApk 均已支持)。
        if (isSymbolResolved(lpparam.classLoader)) {
            XposedBridge.log(TAG + ": [diag] public capabilities resolved -> chrome_symbolic"
                    + " (major=" + (major == null ? "unknown" : major)
                    + (oo4 == null ? ", oo4 absent" : ", 145 anchor mismatch") + ")");
            return "chrome_symbolic";
        }
        XposedBridge.log(TAG + ": [diag] neither 145 anchor nor modern public capabilities resolved -> unknown");
        return "unknown";
    }

    /** Future-version routing is based on stable public Chrome capabilities.
     *  Individual optional features resolve their own symbols and fail closed; one missing
     *  download/banner symbol must not disable unrelated homepage and tab capabilities. */
    private static boolean isSymbolResolved(ClassLoader cl) {
        try {
            Class.forName(CLS_CHROME_TABBED_ACTIVITY, false, cl);
            Class.forName(CLS_TAB_MODEL_JNI_BRIDGE, false, cl);
            Class.forName("org.chromium.content_public.browser.LoadUrlParams", false, cl);
            Class.forName("org.chromium.chrome.browser.tab.Tab", false, cl);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Chrome 153 独立路径：不安装 onStart 冷启动主动建页，避免与 NTP 改写叠加。 */
    private static void installChrome153Hooks(XC_LoadPackage.LoadPackageParam lpparam) {
        hookTabModelMemory(lpparam);
        hookExitCleanup(lpparam);
        HomeCleaner.hookOpenNewTab(lpparam);
        hookFastHomeRestore153(lpparam);
        DownloadSafetyBypass.hook(lpparam);
        AutoInstallApk.hook(lpparam);
        BannerController.hook(lpparam);
        LocationAndDiagnostics.hookLocationPrefSource(lpparam);
        hookCommandLineFlags152(lpparam);
        DownloadDirRedirect.hook(lpparam);
        LocationAndDiagnostics.noteHook("153 path hooks", true);
        XposedBridge.log(TAG + ": v" + BuildConfig.VERSION_NAME
                + " hooks installed (153 independent path) for " + lpparam.packageName);
        LogTail.addAndPush(getAppContext(lpparam.classLoader), "Hook",
                "hooks installed engine=" + engineVersion);
    }

    /** 1B-3: 145 签名锚点校验(参照 Chrome152.matches 的方法签名校验风格)。
     *  锚点 = oo4(ChromeCommandLineFlags) 具备 c(String)->boolean(hasSwitch)。
     *  返回: TRUE=锚点匹配(确为 145); FALSE=oo4 存在但无该签名(疑似 152+ 假阳性);
     *  null=反射枚举异常(getDeclaredMethods/getParameterTypes 抛出, 保守交调用方判 145)。 */
    private static Boolean check145Anchor(Class<?> oo4) {
        try {
            // m4: findAndHookMethod 会沿父类链查找目标方法, 而 getDeclaredMethods 只返回本类声明的方法。
            //   若 oo4.c(String)->boolean 继承自父类, 仅查 getDeclaredMethods 会漏判 → anchor=FALSE
            //   → detectEngineVersion 误判 unknown → 145 全 hook 不装。改为向上遍历 getSuperclass()
            //   逐层查 declaredMethods(捕获非 public 的继承方法; 到 Object 停止)。
            Class<?> c = oo4;
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (m.getName().equals("c") && ps.length == 1
                            && ps[0] == String.class
                            && (m.getReturnType() == boolean.class
                                || m.getReturnType() == Boolean.class)) {
                        return Boolean.TRUE;
                    }
                }
                c = c.getSuperclass();
            }
            return Boolean.FALSE;
        } catch (Throwable t) {
            return null;
        }
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

    /** Hook 1b(152+): 公开类 CommandLine 的 (String)->boolean 判定方法
     *  (152 hasSwitch 混淆为 c(String); 153+ 方法名可能漂移) → 强制 no-restore-state。
     *  v2.4.0: 改为按结构签名 hook 全部 (String)->boolean 方法, 不再写死方法名。 */
    private static void hookCommandLineFlags152(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> cmd = XposedHelpers.findClass(
                    "org.chromium.base.CommandLine", lpparam.classLoader);
            XC_MethodHook hasSwitchHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!readPrefBoolean(KEY_CLEAN_START, true)) {
                            return;
                        }
                        Object arg = param.args.length > 0 ? param.args[0] : null;
                        if ("no-restore-state".equals(arg)) {
                            if (!"chrome145".equals(engineVersion)
                                    && HomeCleaner.canUseFastHomeRestore153(lpparam.classLoader)) {
                                XposedBridge.log(TAG + ": [modern-fast-home] preserve native restore snapshot");
                                return;
                            }
                            param.setResult(Boolean.TRUE);
                            XposedBridge.log(TAG + ": no-restore-state switch forced ON");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": CommandLine.c hook error -> " + t);
                    }
                }
            };
            int n = 0;
            for (Method m : cmd.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == String.class
                        && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                    try { XposedBridge.hookMethod(m, hasSwitchHook); n++; } catch (Throwable ignored) {}
                }
            }
            if (n == 0) {
                // 结构未命中 → 回退旧方法名 c(152 已验证)
                XposedBridge.hookAllMethods(cmd, "c", hasSwitchHook);
                XposedBridge.log(TAG + ": hooked CommandLine.c(String) [commandline flags, by-name fallback]");
            } else {
                XposedBridge.log(TAG + ": hooked CommandLine (String)->boolean x" + n
                        + " [commandline flags, structural]");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook CommandLine flags failed -> " + t);
        }
    }

    /** Modern Chrome custom-home cold start: keep Chrome's native snapshot, then reuse its
     *  restored foreground tab for the homepage. This avoids both a blank first frame and
     *  the old delayed second-tab navigation. Other engines keep their existing path. */
    private static void hookFastHomeRestore153(XC_LoadPackage.LoadPackageParam lpparam) {
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
                                if (activity == null || activity.isFinishing()
                                        || !readPrefBoolean(KEY_CLEAN_START, true)) {
                                    return;
                                }
                                Intent intent = activity.getIntent();
                                if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction())
                                        || intent.getData() != null) {
                                    return;
                                }
                                if (sHistoryCleared.add(activity)) {
                                    HomeCleaner.scheduleClosedTabsClear(activity);
                                }
                                if (!HomeCleaner.canUseFastHomeRestore153(
                                        activity.getClass().getClassLoader())
                                        || !sColdHandled.add(activity)) return;
                                HomeCleaner.fastHomeRestore153(activity, 0);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": [modern-fast-home] onStart error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked ChromeTabbedActivity.onStart() [modern fast-home restore]");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook modern fast-home restore failed -> " + t);
        }
    }

    /** unknown 引擎: 仅翻译横幅(TranslateMessage 公开类, 跨版本同名) */
    private static void hookTranslateBannerOnly(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> tm = XposedHelpers.findClass(
                    "org.chromium.components.translate.TranslateMessage", lpparam.classLoader);
            XposedBridge.hookAllMethods(tm, "showMessage", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!readPrefBoolean(KEY_HIDE_TRANSLATE_BANNER, true)) return;
                    param.setResult(null);
                    XposedBridge.log(TAG + ": translate banner hidden (unknown-engine path)");
                }
            });
            XposedBridge.log(TAG + ": hooked TranslateMessage.showMessage [unknown engine]");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook TranslateMessage failed -> " + t);
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
                                        HookEntry.setColdStartupProtect(true);
                                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                HookEntry.setColdStartupProtect(false);
                                            }
                                        }, 8000L);
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

    /** Hook 4b(145 only): 拦截 jza.k()=shouldCloseAppWithZeroTabs, 冷启动窗口返回 false,
     *  防 Chrome 145 self-finish。152 上 UseNTP=true 自动不触发。 */
    private static void hookShouldCloseAppWithZeroTabs(ClassLoader cl) {
        try {
            Class<?> jza = XposedHelpers.findClass("jza", cl);
            XposedBridge.hookAllMethods(jza, "k", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isColdStartupProtected()) {
                        param.setResult(false);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked jza.k() (shouldCloseAppWithZeroTabs guard, 145)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook jza.k() failed -> " + t);
        }
    }

    /** Hook 4: ChromeTabbedActivity.onStop() + isFinishing() → 退出清理(关闭全部标签, 含无痕)。
     *  README 声明的"退出清理"功能: Chrome 真正退出时关闭全部标签页。
     *  修复: 此前 HomeCleaner.exitCleanup 已实现却无任何调用点(死代码) → 该功能整体失效。
     *  目标方法选择(152 .82 实证): ChromeTabbedActivity **不声明** onDestroy
     *    (findAndHookMethod 带 Class 形参用 getDeclaredMethod, 只查本类不向上走 →
     *     会报 NoSuchMethodError "...#onDestroy[]#exact"); 而 onStop() 在
     *     ChromeTabbedActivity 本类 4062 行有声明, 可精确 hook。
     *    退出语义 = onStop 且 activity.isFinishing()(用户主动退出/返回结束);
     *    仅切后台(isFinishing()==false)不清理, 避免误关标签。
     *  开关复用 KEY_CLEAR_TABS(与冷启动历史清理同源, 默认 true)。 */
    private static void hookExitCleanup(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_CHROME_TABBED_ACTIVITY,
                    lpparam.classLoader,
                    "onStop",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof Activity)) {
                                    return;
                                }
                                Activity activity = (Activity) param.thisObject;
                                if (!activity.isFinishing()) {
                                    return; // 仅切后台/配置变更, 非真正退出
                                }
                                if (!readPrefBoolean(KEY_CLEAR_TABS, true)) {
                                    return;
                                }
                                HomeCleaner.exitCleanup(activity);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": onStop exit-cleanup error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked ChromeTabbedActivity.onStop()+isFinishing() [exit cleanup]");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ChromeTabbedActivity.onStop() failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // v2.3.19: 配置读取统一委托给 ConfigManager。
    //   通道顺序: New XSharedPreferences(主) → ConfigProvider(兜底) → 旧 XSharedPreferences(末位) → 默认值。
    //   各 hook 不再自行读取/拼接通道; 保持既有 readPrefXxx API 兼容, 内部一行转发。
    //   null 语义: ConfigManager.getStringRaw 返回 null = 读取失败/key 不存在; "" = 用户显式空值。

    static boolean readPrefBoolean(String key, boolean defValue) {
        return ConfigManager.getBoolean(key, defValue);
    }

    static int readPrefInt(String key, int defValue) {
        return ConfigManager.getInt(key, defValue);
    }

    /**
     * v2.5.1: 「下载完成自动打开(扩展类型)」总开关的**意图感知**读取。
     *
     * <p>背景: 该总开关是 152/153/154 适配期新增的键。145 时代(以及 v2.2~v2.3.12)
     * 的配置里没有它, 只有各类型子开关。若按默认值 false 处理, 老配置里已经打开的类型
     * 开关会全部静默失效 —— 用户观感就是"适配新版本后 145 的配置被改坏了"。
     *
     * <p>规则: 键存在(档案键或旧全局键) → 用显式值, 绝不推翻用户选择;
     * 键不存在 → 由各类型子开关与自定义后缀列表反推意图(任一为真即视为开启);
     * 全部关闭才返回 false。
     */
    static boolean readExtAutoOpenGate() {
        String raw = ConfigManager.getStringRaw(KEY_AUTO_OPEN_EXT);
        if (raw != null) {
            return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
        }
        return inferredExtIntent();
    }

    /** 由子开关反推"扩展类型自动打开"意图(总开关键缺失时的兼容推断, 仅推断不落盘)。 */
    static boolean inferredExtIntent() {
        String[] typeKeys = {
                KEY_AUTO_OPEN_PDF, KEY_AUTO_OPEN_ARCHIVE, KEY_AUTO_OPEN_DOCUMENT,
                KEY_AUTO_OPEN_SPREADSHEET, KEY_AUTO_OPEN_PRESENTATION, KEY_AUTO_OPEN_TEXT,
                KEY_AUTO_OPEN_IMAGE, KEY_AUTO_OPEN_VIDEO, KEY_AUTO_OPEN_AUDIO,
                KEY_AUTO_OPEN_EBOOK};
        for (String key : typeKeys) {
            if (readPrefBoolean(key, false)) return true;
        }
        String custom = ConfigManager.getStringRaw(KEY_AUTO_OPEN_CUSTOM);
        return custom != null && !custom.trim().isEmpty();
    }
    static long readPrefLong(String key, long defValue) {
        try {
            String raw = ConfigManager.getStringRaw(key);
            if (raw == null || raw.isEmpty()) return defValue;
            return Long.parseLong(raw.trim());
        } catch (Throwable t) {
            return defValue;
        }
    }

    static String readPrefString(String key, String defValue) {
        return ConfigManager.getString(key, defValue);
    }

    /** 事件日志开关。普通用户默认关闭；开发者可在调试页显式开启。 */
    static boolean readDebugEvents() {
        return readPrefBoolean(KEY_DEBUG_EVENTS, false);
    }

    /** v2.5.6: 详细日志开关。默认关闭, 避免日常使用时事件尾部被高频细节淹没。 */
    static boolean readDebugVerbose() {
        return readPrefBoolean(KEY_DEBUG_VERBOSE, false);
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

    // ------------------------------------------------------------------
    // v2.3.1(PR#1 整合): isolated split 支持
    // ------------------------------------------------------------------

    /** unknown 引擎的公开类降级 hook(仅装一次, 不与完整 hook 冲突) */
    private static synchronized void installDegradedHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        // 修复D: once-guard。installDegradedHooks 有两条调用路径 ——
        //   ① handleLoadPackage 判 unknown 时立即调(base loader, 不设 sLateHooksInstalled);
        //   ② split loader 就绪后 installLateHooks 重判仍 unknown 时再调。
        // 二者无共享守卫时, unknown 场景下公开类 DownloadController.onDownloadCompleted
        // 可能被 findAndHookMethod 注册两次 → 每次下载完成触发双份逻辑(双份 shouldAutoOpen/守护线程)。
        if (sDegradedInstalled) {
            XposedBridge.log(TAG + ": degraded hooks already installed, skip");
            return;
        }
        // 仅当当前 loader 能看到 Chrome 核心类时才视为"有效安装"并置守卫:
        // base loader 在 isolated-split Chrome 上看不到类(各 hook 内部 try-catch 静默失败), 属"防 nothing"占位,
        // 此时不置守卫, 允许 split loader 就绪后重装(真正有效的那次), 避免误锁死后续有效安装。
        boolean canSeeChromeClasses = isValidSplitLoader(lpparam.classLoader);
        try {
            hookTabModelMemory(lpparam);
            hookOnStart(lpparam);
            hookCommandLineFlags152(lpparam);
            DownloadSafetyBypass.hook(lpparam);
            // RC1 修复: unknown 引擎下也必须尝试安装自动打开 hook。
            // AutoInstallApk.hook 内部每个子 hook(c9o/zkg/qe7 短名 + DownloadController 公开类)
            // 各自独立 try-catch: 短名类不存在时 findClass 失败被单独捕获并记录日志, 不会崩溃;
            // 而基于公开类 DownloadController.onDownloadCompleted 的 hookInlineDownloadPath
            // 在 unknown 下仍能装成功 → 提供自动打开的公开类兜底路径。
            AutoInstallApk.hook(lpparam);
            hookTranslateBannerOnly(lpparam);
            LocationAndDiagnostics.hookLocationPrefSource(lpparam);
            DownloadDirRedirect.hook(lpparam);
            LocationAndDiagnostics.noteHook("unknown-engine degraded hooks", true);
            XposedBridge.log(TAG + ": degraded hooks installed (canSeeChromeClasses="
                    + canSeeChromeClasses + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": degraded hooks failed -> " + t);
        }
        // 仅当 loader 能看到 Chrome 核心类(有效安装)时才置守卫; base loader 占位失败时不锁死 split 重装。
        if (canSeeChromeClasses) {
            sDegradedInstalled = true;
        }
    }

    /** split loader 校验: 必须能加载 Chrome 核心类才采用 */
    private static boolean isValidSplitLoader(ClassLoader loader) {
        if (loader == null) return false;
        try {
            Class.forName(CLS_CHROME_TABBED_ACTIVITY, false, loader);
            Class.forName(CLS_TAB_MODEL_JNI_BRIDGE, false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** split loader 就绪后: 重新判定引擎版本并安装完整 hook(一次性) */
    private static synchronized void installLateHooks(
            XC_LoadPackage.LoadPackageParam lpparam, ClassLoader loader, String source) {
        if (sLateHooksInstalled || loader == null) {
            return;
        }
        if (!isValidSplitLoader(loader)) {
            XposedBridge.log(TAG + ": split loader from " + source
                    + " has no Chrome classes, skip");
            return;
        }
        sLateHooksInstalled = true;
        lpparam.classLoader = loader;
        XposedBridge.log(TAG + ": split loader accepted from " + source
                + " — re-detecting engine");
        // 重新判定(152 类指纹现在应该能命中了)
        engineVersion = detectEngineVersion(lpparam);
        XposedBridge.log(TAG + ": engine(re-detect)=" + engineVersion);
        bindProfile(lpparam); // v2.5.0: 绑定 Chrome 大版本配置档案
        // 完整 hook 全装(145/152/symbolic/unknown 四路都重新走)
        if ("chrome153".equals(engineVersion)) {
            installChrome153Hooks(lpparam);
            return;
        }
        if ("chrome152".equals(engineVersion) || "chrome_symbolic".equals(engineVersion)) {
            hookTabModelMemory(lpparam);
            // R2-m13: split 晚装路径此前漏装退出清理(152 直装路径已装) → isolated split 单体机
            //   上「退出清理」整体失效。与直装路径对称补齐(与 145 分支同样补齐)。
            hookExitCleanup(lpparam);
            hookCommandLineFlags152(lpparam);
            hookFastHomeRestore153(lpparam);
            HomeCleaner.hookOpenNewTab(lpparam);
            DownloadSafetyBypass.hook(lpparam);
            AutoInstallApk.hook(lpparam);
            BannerController.hook(lpparam);
            LocationAndDiagnostics.hookLocationPrefSource(lpparam);
            DownloadDirRedirect.hook(lpparam);
            LocationAndDiagnostics.noteHook("152 split hooks", true);
            XposedBridge.log(TAG + ": v" + BuildConfig.VERSION_NAME
                    + " full " + ("chrome_symbolic".equals(engineVersion) ? "symbolic+152" : "152")
                    + " hooks installed (split loader)");
            LogTail.addAndPush(getAppContext(lpparam.classLoader), "Hook", "full hooks installed (split loader) engine=" + engineVersion);
            return;
        }
        if ("chrome145".equals(engineVersion)) {
            hookCommandLineFlags(lpparam);
            hookTabModelMemory(lpparam);
            // 145 split path mirrors the direct path: only l04.l handles new-tab URL rewriting.
            // R2-m13: 与 152 分支对称 —— split 晚装路径补齐退出清理(此前漏装)。
            hookExitCleanup(lpparam);
            HomeCleaner.hookOpenNewTab145Legacy(lpparam);
            DownloadSafetyBypass.hook(lpparam);
            AutoInstallApk.hook(lpparam);
            BannerController.hook(lpparam);
            LocationAndDiagnostics.hookLocationPrefSource(lpparam);
            DownloadDirRedirect.hook(lpparam);
            XposedBridge.log(TAG + ": full 145 hooks installed (split loader)");
            return;
        }
        // split loader 里也不认识 — 降级 hook 但用 split loader(类可见性更好)
        installDegradedHooks(lpparam);
        XposedBridge.log(TAG + ": degraded hooks installed on split loader");
    }

    /** 触发点 1: SplitChromeApplication.createContextForSplit("chrome") */
    private static void hookSplitContextCreation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "org.chromium.chrome.browser.base.SplitChromeApplication",
                    lpparam.classLoader,
                    "createContextForSplit",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String splitName = String.valueOf(param.args[0]);
                                if (!"chrome".equals(splitName)) return;
                                Object result = param.getResult();
                                if (!(result instanceof Context)) return;
                                ClassLoader splitLoader = ((Context) result).getClassLoader();
                                DownloadSafetyBypass.refreshNativeIds(splitLoader);
                                installLateHooks(lpparam, splitLoader, "createContextForSplit");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": split context hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked SplitChromeApplication.createContextForSplit");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook split context creation failed -> " + t);
        }
    }

    /** 触发点 2: Application.attach(部分 split 类此时已加载) */
    private static void hookApplicationAttach(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Context context = (Context) param.args[0];
                                if (context == null) return;
                                ClassLoader loader = context.getClassLoader();
                                DownloadSafetyBypass.refreshNativeIds(loader);
                                if (isValidSplitLoader(loader)) {
                                    installLateHooks(lpparam, loader, "app-attach");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked Application.attach (split retry)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Application.attach failed -> " + t);
        }
    }

    /** 触发点 3: Activity onCreate(最晚兜底) */
    private static void hookActivityCreate(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnCreate",
                    Activity.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Activity activity = (Activity) param.args[0];
                                if (activity == null
                                        || !"com.android.chrome".equals(activity.getPackageName())) {
                                    return;
                                }
                                installLateHooks(lpparam,
                                        activity.getClass().getClassLoader(), "activity");
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked Instrumentation.callActivityOnCreate (late fallback)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Instrumentation failed -> " + t);
        }
    }
}
