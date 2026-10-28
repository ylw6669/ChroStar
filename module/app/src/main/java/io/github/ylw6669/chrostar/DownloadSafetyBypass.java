package io.github.ylw6669.chrostar;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;

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
    private static volatile ClassLoader sResolverLoader;
    private static volatile Map<String, Integer> sNativeIds;

    private static final String TAG = HookEntry.TAG;

    private DownloadSafetyBypass() {
    }

    /** 安装全部 hook(主进程) */
    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        sResolverLoader = lpparam.classLoader;
        resolveNativeIds(lpparam.classLoader);
        hookDangerous(lpparam);
        hookInsecure(lpparam);
        hookDuplicate(lpparam);
        hookPolicyWarning(lpparam);
        hookLocationDialog(lpparam);
        hookOpenDialog(lpparam);
    }

    /** Retry once Application.attach has made Chrome's APK paths available. */
    public static void refreshNativeIds(ClassLoader cl) {
        if (cl != null) sResolverLoader = cl;
        final ClassLoader loader = cl != null ? cl : sResolverLoader;
        Thread scan = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                resolveNativeIds(loader);
            }
        }, "ChroStar-DexIdScan");
        scan.setDaemon(true);
        scan.setPriority(Thread.MIN_PRIORITY);
        scan.start();
    }

    private static boolean bypassOff(String key) {
        return !HookEntry.readPrefBoolean(key, true);
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
        // v2.5.4: 同步进事件环形缓冲并节流回传, 让设置页在无 root 时也能看到弹窗绕过活动
        LogTail.add("Dl", msg);
        LogTail.push(HookEntry.getAppContext(null));
    }

    private static void err(String where, Throwable t) {
        XposedBridge.log(TAG + ": [" + where + "] " + t);
        LogTail.addError("Dl-ERR", where + " -> " + t);
        LogTail.push(HookEntry.getAppContext(null));
    }

    private static int hookShowDialogs(ClassLoader cl, String className, XC_MethodHook callback)
            throws Exception {
        Class<?> bridge = XposedHelpers.findClass(className, cl);
        int count = 0;
        for (Method method : bridge.getDeclaredMethods()) {
            if (!"showDialog".equals(method.getName()) || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            XposedBridge.hookMethod(method, callback);
            count++;
        }
        if (count == 0) throw new NoSuchMethodException(className + ".showDialog");
        return count;
    }

    private static String firstString(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) if (arg instanceof String) return (String) arg;
        return null;
    }

    private static String lastString(Object[] args) {
        if (args == null) return null;
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof String) return (String) args[i];
        }
        return null;
    }

    private static Long lastLong(Object[] args) {
        if (args == null) return null;
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Long) return (Long) args[i];
        }
        return null;
    }

    private static Object firstInstance(Object[] args, Class<?> type) {
        if (args == null || type == null) return null;
        for (Object arg : args) if (arg != null && type.isInstance(arg)) return arg;
        return null;
    }

    /** 反射调用 J.N 的 static 方法 */
    private static Object callNative(Object bridge, String method,
            Class<?>[] types, Object[] args) throws Exception {
        Class<?> nClass = Class.forName(HookEntry.CLS_J_N, false,
                bridge.getClass().getClassLoader());
        Method m = nClass.getMethod(method, types);
        return m.invoke(null, args);
    }

    /**
     * JNI ids are resolved from the target APK's bridge bytecode first.  The
     * version table is retained only as a compatibility fallback for the three
     * previously calibrated engines; symbolic/unknown engines fail closed.
     */
    private static void resolveNativeIds(ClassLoader cl) {
        Map<String, Integer> resolved = DexNativeIdResolver.resolveDownloadDialogIds(cl);
        boolean any = false;
        for (Integer value : resolved.values()) {
            if (value != null && value.intValue() >= 0) {
                any = true;
                break;
            }
        }
        if (any) sNativeIds = resolved;
        log("[diag] native dialog IDs " + resolved + (any ? " (resolved)" : " (pending)"));
    }

    /**
     * v2.5.0: 弹窗原生事件 ID 解析 —— 优先用 DexKit/已知版本表解析出的值,
     * 解析不到时查 {@link ProfileTraits} 的校准兜底表(按引擎取, 不再写嵌套三元表达式)。
     */
    private static int nativeId(String key) {
        Map<String, Integer> ids = sNativeIds;
        if (ids == null || ids.get(key) == null || ids.get(key).intValue() < 0) {
            resolveNativeIds(sResolverLoader);
            ids = sNativeIds;
        }
        Integer value = ids == null ? null : ids.get(key);
        if (value != null && value.intValue() >= 0) return value.intValue();
        int fallback = ProfileTraits.current().dialogIdFallback(key);
        if (fallback >= 0) {
            XposedBridge.log(TAG + ": [diag] native dialog id resolver unavailable; using calibrated fallback id "
                    + fallback + " for " + key + " (engine=" + HookEntry.engineVersion + ")");
        }
        return fallback;
    }

    private static int idDangerous() {
        return nativeId("dangerous");
    }

    private static int idInsecure() {
        return nativeId("insecure");
    }

    private static int idDuplicate() {
        return nativeId("duplicate");
    }

    private static int idPolicy() {
        return nativeId("policy");
    }

    private static int idOpen() {
        return nativeId("open");
    }

    private static int idLocation() {
        return nativeId("location");
    }

    private static boolean nativeIdAvailable(String name, int id) {
        if (id >= 0) return true;
        log("[diag] native dialog id unresolved for " + name + "; keep Chrome dialog");
        return false;
    }

    /** VJO(int, long, Object) */
    private static boolean allowVJO(Object bridge, int id, long ptr, Object arg) {
        try {
            callNative(bridge, "VJO",
                    new Class<?>[]{int.class, long.class, Object.class},
                    new Object[]{Integer.valueOf(id), Long.valueOf(ptr), arg});
            return true;
        } catch (Throwable t) {
            err("VJO/" + id, t);
            return false;
        }
    }

    /** VJJZ(int, long, long, boolean) */
    private static boolean allowVJJZ(Object bridge, int id, long ptr,
            long downloadId, boolean allow) {
        try {
            callNative(bridge, "VJJZ",
                    new Class<?>[]{int.class, long.class, long.class, boolean.class},
                    new Object[]{Integer.valueOf(id), Long.valueOf(ptr),
                            Long.valueOf(downloadId), Boolean.valueOf(allow)});
            return true;
        } catch (Throwable t) {
            err("VJJZ/" + id, t);
            return false;
        }
    }

    /** VJOZ(int, long, String, boolean) */
    private static boolean allowVJOZ(Object bridge, int id, long ptr,
            String path, boolean open) {
        try {
            callNative(bridge, "VJOZ",
                    new Class<?>[]{int.class, long.class, String.class, boolean.class},
                    new Object[]{Integer.valueOf(id), Long.valueOf(ptr), path,
                            Boolean.valueOf(open)});
            return true;
        } catch (Throwable t) {
            err("VJOZ/" + id, t);
            return false;
        }
    }

    private static long nativePtr(Object bridge) throws Exception {
        try {
            return XposedHelpers.getLongField(bridge, "a");
        } catch (Throwable ignored) {
        }
        Field hit = null;
        for (Class<?> c = bridge.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                if (hit != null) throw new NoSuchFieldException("ambiguous native long field");
                hit = field;
            }
        }
        if (hit == null) throw new NoSuchFieldException("native long field");
        hit.setAccessible(true);
        return hit.getLong(bridge);
    }

    // ------------------------------------------------------------------
    // 1A-5(152 适配): 下载位置对话框桥的混淆实例方法名去硬编码。
    //   旧实现写死 145 短名 a(Profile)=默认下载目录 / b(String,boolean)=确认(路径, false);
    //   .82 中桥类为保留名类 DownloadsDialogBridge(org.chromium.chrome.browser.download),
    //   但实例方法名随 R8 漂移 —— 写死名在后续版本可能静默失效或误调。
    //   策略: **优先已知名+严格签名**(命中则行为与旧实现完全一致, 零回归); 未命中再按
    //   签名在继承链内唯一扫描。多义/无匹配 → 返回 null → 调用方 fail-closed(放弃本次绕过,
    //   让 Chrome 走原生对话框), 绝不冒险误调无关方法。
    // ------------------------------------------------------------------
    private static Method resolveDefaultDirMethod(Class<?> cls, Class<?> profileType) {
        // ① 优先已知名 a(Profile) 且签名吻合(静态, 返回 String)
        try {
            Method known = cls.getMethod("a", profileType);
            if (Modifier.isStatic(known.getModifiers()) && known.getReturnType() == String.class) {
                return known;
            }
        } catch (Throwable ignored) {
        }
        // ② 签名扫描: 静态, 单参(Profile 同型), 返回 String, 全继承链唯一
        Method hit = null;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != String.class) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1) continue;
                if (p[0] != profileType) continue;
                if (hit != null && !sameNameAndParams(hit, m)) return null; // 多义 → fail-closed
                hit = m;
            }
        }
        return hit;
    }

    private static Method resolveConfirmMethod(Class<?> cls) {
        // ① 优先已知名 b(String, boolean) 且签名吻合(实例, 返回 void)
        try {
            Method known = cls.getMethod("b", String.class, boolean.class);
            if (!Modifier.isStatic(known.getModifiers()) && known.getReturnType() == void.class) {
                return known;
            }
        } catch (Throwable ignored) {
        }
        // ② 签名扫描: 实例, 双参(String, boolean), 返回 void, 全继承链唯一
        Method hit = null;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 2) continue;
                if (p[0] != String.class || p[1] != boolean.class) continue;
                if (m.getReturnType() != void.class) continue;
                if (hit != null && !sameNameAndParams(hit, m)) return null; // 多义 → fail-closed
                hit = m;
            }
        }
        return hit;
    }

    private static boolean sameNameAndParams(Method x, Method y) {
        return x.getName().equals(y.getName()) && Arrays.equals(x.getParameterTypes(), y.getParameterTypes());
    }

    // ------------------------------------------------------------------
    // 1) 危险下载警告 ("文件可能有害") — 开关 bypass_dangerous
    // ------------------------------------------------------------------
    private static void hookDangerous(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (bypassOff(HookEntry.KEY_BYPASS_DANGEROUS)) return;
                    try {
                        long ptr = nativePtr(param.thisObject);
                        String guid = firstString(param.args);
                        if (guid == null) return;
                        int id = idDangerous();
                        if (!nativeIdAvailable("dangerous", id)) return;
                        if (!allowVJO(param.thisObject, id, ptr, guid)) return;
                        param.setResult(null);
                        log("dangerous download bypassed (VJO " + id + ")");
                    } catch (Throwable t) {
                        err("dangerous", t);
                    }
                }
            };
            // showDialog 的尾部参数在 152/153/154 间变化；公开宿主和方法名稳定，
            // 直接按方法名 hook 全部重载，避免为每个小版本复制签名分支。
            int count = hookShowDialogs(lpparam.classLoader,
                    "org.chromium.chrome.browser.download.DangerousDownloadDialogBridge", callback);
            log("hooked DangerousDownloadDialogBridge.showDialog x" + count + " (structural)");
        } catch (Throwable t) {
            err("hook dangerous", t);
        }
    }

    // ------------------------------------------------------------------
    // 2) 不安全下载警告 ("无法安全地下载") — 开关 bypass_insecure
    // ------------------------------------------------------------------
    private static void hookInsecure(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            int count = hookShowDialogs(lpparam.classLoader,
                    "org.chromium.chrome.browser.download.InsecureDownloadDialogBridge",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (bypassOff(HookEntry.KEY_BYPASS_INSECURE)) return;
                            try {
                                long ptr = nativePtr(param.thisObject);
                                Long downloadIdValue = lastLong(param.args);
                                if (downloadIdValue == null) return;
                                long downloadId = downloadIdValue.longValue();
                                int id = idInsecure();
                                if (!nativeIdAvailable("insecure", id)) return;
                                if (!allowVJJZ(param.thisObject, id, ptr, downloadId, true)) return;
                                param.setResult(null);
                                log("insecure download bypassed (VJJZ " + id + ", true)");
                            } catch (Throwable t) {
                                err("insecure", t);
                            }
                        }
                    });
            log("hooked InsecureDownloadDialogBridge.showDialog x" + count + " (structural)");
        } catch (Throwable t) {
            err("hook insecure", t);
        }
    }

    // ------------------------------------------------------------------
    // 3) 重复下载确认 — 开关 bypass_duplicate
    // ------------------------------------------------------------------
    private static void hookDuplicate(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            int count = hookShowDialogs(lpparam.classLoader,
                    "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // v2.3.13: 移除「同名覆盖保留原文件」分支(备份+自动确认),
                            //   本 hook 现仅负责普通「重复下载」确认绕过。
                            if (bypassOff(HookEntry.KEY_BYPASS_DUPLICATE)) return;
                            try {
                                long ptr = nativePtr(param.thisObject);
                                Long downloadIdValue = lastLong(param.args);
                                if (downloadIdValue == null) return;
                                long downloadId = downloadIdValue.longValue();
                                int id = idDuplicate();
                                if (!nativeIdAvailable("duplicate", id)) return;
                                if (!allowVJJZ(param.thisObject, id, ptr, downloadId, true)) return;
                                param.setResult(null);
                                log("duplicate download bypassed (VJJZ " + id + ", true)");
                            } catch (Throwable t) {
                                err("duplicate", t);
                            }
                        }
                    });
            log("hooked DuplicateDownloadDialogBridge.showDialog x" + count + " (structural)");
        } catch (Throwable t) {
            err("hook duplicate", t);
        }
    }

    // ------------------------------------------------------------------
    // 4) 策略警告下载 — 开关 bypass_policy
    // ------------------------------------------------------------------
    private static void hookPolicyWarning(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            int count = hookShowDialogs(lpparam.classLoader,
                    "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridge",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (bypassOff(HookEntry.KEY_BYPASS_POLICY)) return;
                            try {
                                long ptr = nativePtr(param.thisObject);
                                String guid = firstString(param.args);
                                if (guid == null) return;
                                int id = idPolicy();
                                if (!nativeIdAvailable("policy", id)) return;
                                if (!allowVJO(param.thisObject, id, ptr, guid)) return;
                                param.setResult(null);
                                log("policy warning download bypassed (VJO " + id + ")");
                            } catch (Throwable t) {
                                err("policy", t);
                            }
                        }
                    });
            log("hooked PolicyWarningDownloadDialogBridge.showDialog x" + count + " (structural)");
        } catch (Throwable t) {
            err("hook policy", t);
        }
    }

    // ------------------------------------------------------------------
    // 5) 保存位置/重命名对话框 — 开关 bypass_location
    // ------------------------------------------------------------------
    private static void hookLocationDialog(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> profileClass = XposedHelpers.findClass(
                    "org.chromium.chrome.browser.profiles.Profile",
                    lpparam.classLoader);
            int count = hookShowDialogs(lpparam.classLoader,
                    "org.chromium.chrome.browser.download.DownloadDialogBridge",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (bypassOff(HookEntry.KEY_BYPASS_LOCATION)) return;
                            try {
                                // 修复②: showDialog(WindowAndroid, long, int, int, String suggestedPath, Profile)
                                //   —— args[4] 是 Chrome 传入的建议文件名/完整路径(String)。此前实现丢弃 args[4],
                                //   改用静态 a(Profile) 取默认下载"目录"再喂确认回调 b(String,boolean),
                                //   native 侧把目录 basename("Download")当文件名基干 → 所有下载被命名
                                //   Download.apk/Download.zip/... (真机实证)。
                                //   修复: 消费 args[4] 构造"完整路径"喂 b(), 保留绕过弹窗但带真实文件名。
                                //   DownloadDialogBridge.showDialog 是公开未混淆签名, 145/152 布局与 args[4]
                                //   语义一致, 修复对两版本等价(均传完整建议路径, 不再回退裸目录)。
                                Object profile = firstInstance(param.args, profileClass);
                                if (profile == null) return;
                                Object bridge = param.thisObject;
                                Class<?> cls = bridge.getClass();
                                // 加固3(Minor-3): 读取 args[4] 即 trim, 后续判空与拼接统一用 trim 后的值,
                                //   消除首尾空格隐患(此前判空用 trim 但拼接用未 trim 原值, 不一致)。
                                String suggestedValue = lastString(param.args);
                                String suggested = suggestedValue == null ? null : suggestedValue.trim();
                                // fail-closed: args[4] 缺失/空白 → 绝不拿裸目录调 b()(那正是制造 "Download" 名的行为),
                                //   放弃本次绕过(不 setResult), 让 Chrome 走自身命名与对话框逻辑 + [diag] 记录。
                                if (suggested == null || suggested.isEmpty()) {
                                    log("[diag] location bypass skipped: suggested path (args[4]) null/empty,"
                                            + " fall back to Chrome dialog (fail-closed, avoid naming all files 'Download')");
                                    return;
                                }
                                // 默认下载目录(仅当 args[4] 是纯文件名无 "/" 时用于拼接完整路径)。
                                String defDir = "";
                                try {
                                    // 默认下载目录: 优先已知名 a(Profile), 否则按签名扫描(见 resolveDefaultDirMethod)
                                    // Profile 是接口, 从类加载器按名解析
                                    Class<?> pType = Class.forName(
                                            "org.chromium.chrome.browser.profiles.Profile",
                                            false, cls.getClassLoader());
                                    Method a = resolveDefaultDirMethod(cls, pType);
                                    if (a == null) {
                                        log("[diag] location default-dir method unresolved (fail-closed)");
                                    } else {
                                        Object dir = a.invoke(null, profile);
                                        if (dir instanceof String) defDir = (String) dir;
                                    }
                                } catch (Throwable t) {
                                    // 修复②: 补 [diag](此前为静默 catch, 违反禁吞异常纪律)。
                                    log("[diag] location default-dir a(Profile) failed -> " + t);
                                }
                                // 构造完整目标路径: args[4] 含 "/" 视为完整路径直接用; 否则拼默认目录。
                                String targetPath;
                                if (suggested.contains("/")) {
                                    targetPath = suggested;
                                } else if (defDir != null && !defDir.isEmpty()) {
                                    targetPath = defDir.endsWith("/")
                                            ? defDir + suggested : defDir + "/" + suggested;
                                } else {
                                    // 纯文件名且默认目录取不到 → 无法拼出完整路径, fail-closed 放弃绕过
                                    //   (绝不用裸文件名/裸目录调 b()), 让 Chrome 自身处理命名。
                                    log("[diag] location bypass skipped: suggested is bare filename '"
                                            + suggested + "' but default dir unavailable,"
                                            + " fall back to Chrome dialog (fail-closed)");
                                    return;
                                }
                                // b(String,boolean) 的 String 语义(目录 vs 完整路径)在 .82 无法离线核实,
                                //   以"传完整路径"为准 + log 记录实际传入值供真机核对。
                                //   方法名优先已知 b(String,boolean), 否则按签名扫描(见 resolveConfirmMethod);
                                //   解析失败则 fail-closed(不 setResult, 走 Chrome 原生对话框)。
                                Method b = resolveConfirmMethod(cls);
                                if (b == null) {
                                    log("[diag] location confirm method unresolved (fail-closed)");
                                    return;
                                }
                                if (!nativeIdAvailable("location", idLocation())) return;
                                b.invoke(bridge, targetPath, Boolean.FALSE);
                                param.setResult(null);
                                log("location dialog bypassed (b/full path='" + targetPath + "')");
                            } catch (Throwable t) {
                                err("location", t);
                            }
                        }
                    });
            log("hooked DownloadDialogBridge.showDialog x" + count + " (structural)");
        } catch (Throwable t) {
            err("hook location", t);
        }
    }

    // ------------------------------------------------------------------
    // 6) 打开方式询问 ("要打开此文件吗") — 开关 bypass_open
    // ------------------------------------------------------------------
    private static void hookOpenDialog(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            int count = hookShowDialogs(lpparam.classLoader,
                    "org.chromium.chrome.browser.download.OpenDownloadDialogBridge",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (bypassOff(HookEntry.KEY_BYPASS_OPEN)) return;
                            try {
                                long ptr = nativePtr(param.thisObject);
                                String path = lastString(param.args);
                                if (path == null) return;
                                int id = idOpen();
                                if (!nativeIdAvailable("open", id)) return;
                                if (!allowVJOZ(param.thisObject, id, ptr, path, false)) return;
                                param.setResult(null);
                                log("open dialog bypassed (VJOZ " + id + ", false)");
                            } catch (Throwable t) {
                                err("open", t);
                            }
                        }
                    });
            log("hooked OpenDownloadDialogBridge.showDialog x" + count + " (structural)");
        } catch (Throwable t) {
            err("hook open", t);
        }
    }
}
