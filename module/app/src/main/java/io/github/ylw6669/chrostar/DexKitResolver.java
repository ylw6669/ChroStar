package io.github.ylw6669.chrostar;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.DexKitCacheBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v2.5.0: 基于 DexKit 的运行时混淆符号解析(自动适配新版 Chrome)。
 *
 * <p><b>定位</b>: 解析管线的第 4 环 —— 仅当"锚点结构解析"(ChromeSymbols)对某些槽位
 * 返回 null 时才启动, 用于补齐新版 Chrome 洗牌后锚点推不出的符号。命中结果写入
 * {@link CapsCache}, 同一 Chrome versionCode 后续启动直接读缓存, 不再跑 DEX 查询。
 *
 * <p><b>为什么不写死短名</b>: 查询条件是结构与语义特征(参数类型序列、返回类型、参数个数),
 * 与 R8 改名无关, 因此天然跨版本。
 *
 * <p><b>安全边界</b>: 原生库缺失/加载失败/查询异常 → 整体标记不可用并交回锚点路径与
 * 已知版本表, 绝不抛到 Hook 安装流程; 每个候选都要经反射二次校验(类可加载 + 目标签名
 * 存在)才算命中, 候选不唯一时视为未命中(fail-closed), 避免误 hook 造成崩溃。
 *
 * <p><b>Java 互操作注意</b>: DexKit 是 Kotlin 库, 大量 Kotlin 属性 setter 带
 * ACC_SYNTHETIC, javac 不可见(会报"找不到符号"), 因此这里只用非 synthetic 的
 * 构建器方法(returnType/paramTypes/matcher/paramCount)。
 */
final class DexKitResolver {

    private static final String CLS_GURL = "org.chromium.url.GURL";
    private static final String CLS_TAB = "org.chromium.chrome.browser.tab.Tab";
    private static final String CLS_TAB_MODEL = "org.chromium.chrome.browser.tabmodel.TabModel";
    private static final String CLS_LOAD_URL_PARAMS = "org.chromium.content_public.browser.LoadUrlParams";
    private static final String CLS_OFFLINE_ITEM =
            "org.chromium.components.offline_items_collection.OfflineItem";
    private static final String CLS_PROPERTY_MODEL = "org.chromium.ui.modelutil.PropertyModel";
    private static final String CLS_WEB_CONTENTS = "org.chromium.content_public.browser.WebContents";
    private static final String CLS_PARTNER_CUSTOMIZATIONS =
            "org.chromium.chrome.browser.partnercustomizations.PartnerBrowserCustomizations";
    private static final String CLS_CHROME_TABBED_ACTIVITY =
            "org.chromium.chrome.browser.ChromeTabbedActivity";
    private static final String CLS_DOWNLOAD_MGR_SERVICE =
            "org.chromium.chrome.browser.download.DownloadManagerService";

    private static final Object LOCK = new Object();
    private static volatile DexKitBridge sBridge;
    private static volatile boolean sUnavailable;
    private static volatile String sUnavailableReason;

    private DexKitResolver() {
    }

    /** 原生库是否可用(首次使用失败后会置位不可用)。 */
    static boolean available() {
        return !sUnavailable;
    }

    static String unavailableReason() {
        return sUnavailableReason;
    }

    /** 释放桥接(手动重扫前调用, 幂等)。 */
    static void close() {
        synchronized (LOCK) {
            DexKitBridge b = sBridge;
            sBridge = null;
            if (b != null) {
                try {
                    b.close();
                } catch (Throwable ignored) {
                    // 关闭失败不影响后续重建
                }
            }
        }
    }

    /**
     * 解析缺失槽位(必须在后台线程调用 —— DEX 查询是秒级重活)。
     *
     * @return slotId -> 解析值(仅包含成功且经反射校验的槽位)
     */
    static Map<String, String> resolveMissing(ClassLoader cl, Set<Capability> missing) {
        Map<String, String> out = new LinkedHashMap<>();
        if (cl == null || missing == null || missing.isEmpty()) return out;
        if (sUnavailable) return out;
        DexKitBridge bridge = bridge(cl);
        if (bridge == null) return out;

        for (Capability cap : missing) {
            try {
                String value = resolveOne(bridge, cl, cap);
                if (value != null && !value.isEmpty()) {
                    out.put(cap.id, value);
                    log("resolved " + cap.id + " = " + value);
                } else {
                    log("no unique candidate for " + cap.id);
                }
            } catch (Throwable t) {
                log("query failed for " + cap.id + " -> " + t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 桥接初始化
    // ------------------------------------------------------------------

    private static DexKitBridge bridge(ClassLoader cl) {
        DexKitBridge b = sBridge;
        if (b != null) return b;
        synchronized (LOCK) {
            if (sUnavailable) return null;
            b = sBridge;
            if (b != null) return b;
            try {
                File cache = cacheFile();
                if (cache != null) {
                    DexKitCacheBridge.init(new FileCache(cache));
                }
                b = DexKitBridge.create(cl, false);
                sBridge = b;
                log("bridge created (dexNum=" + safeDexNum(b) + ", cache="
                        + (cache == null ? "off" : cache.getName()) + ")");
                return b;
            } catch (Throwable t) {
                sUnavailable = true;
                sUnavailableReason = String.valueOf(t);
                log("unavailable -> " + t);
                return null;
            }
        }
    }

    private static File cacheFile() {
        try {
            android.content.Context ctx = HookEntry.getAppContext(null);
            if (ctx != null) {
                return new File(new File(ctx.getFilesDir(), "chrostar"), "dexkit-cache.txt");
            }
        } catch (Throwable ignored) {
            // 无 Context 时不启用 DexKit 内部缓存, 仅影响查询速度
        }
        return null;
    }

    private static int safeDexNum(DexKitBridge b) {
        try {
            return b.getDexNum();
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 各槽位解析
    // ------------------------------------------------------------------

    private static String resolveOne(DexKitBridge bridge, ClassLoader cl, Capability cap) {
        switch (cap) {
            case HOMEPAGE_MANAGER:
                return resolveHomepageManager(bridge, cl);
            case TAB_CREATOR:
                return resolveTabCreator(bridge, cl);
            case TAB_CREATOR_CENTRAL_CREATE:
                return resolveTabCreatorCentralCreate(bridge, cl);
            case TAB_SELECTOR:
                return resolveTabSelector(bridge, cl);
            case DOWNLOAD_MESSAGE:
                return resolveDownloadMessage(bridge, cl);
            case MESSAGE_DISPATCHER:
                return resolveMessageDispatcher(bridge, cl);
            default:
                return null;   // TRANSLATE_KEY 无结构特征, 依赖已知版本表
        }
    }

    /** 主页 manager: 返回 GURL 且参数全为 boolean 的方法, 其声明类唯一。 */
    private static String resolveHomepageManager(DexKitBridge bridge, ClassLoader cl) {
        Class<?> gurl = load(cl, CLS_GURL);
        if (gurl == null) return null;
        Set<String> classes = new LinkedHashSet<>();
        for (MethodData m : findMethodsByParamWindow(bridge, gurl, 1, 4)) {
            if (!Modifier.isStatic(m.getModifiers())
                    && allBooleanParams(m) && m.getDeclaredClassName() != null) {
                classes.add(m.getDeclaredClassName());
            }
        }
        String hit = preferNeighborhood(classes,
                fieldTypeNames(load(cl, CLS_PARTNER_CUSTOMIZATIONS)));
        return hit != null && hasBooleanGurlGetter(cl, hit) ? hit : null;
    }

    /** 建页器: 返回 Tab 且首参 LoadUrlParams 的方法, 其声明类唯一。 */
    private static String resolveTabCreator(DexKitBridge bridge, ClassLoader cl) {
        Set<String> classes = new LinkedHashSet<>();
        for (MethodData m : findMethodsReturningTab(bridge, cl)) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            List<String> ps = m.getParamTypeNames();
            if (ps == null || ps.isEmpty()) continue;
            if (!isType(ps.get(0), CLS_LOAD_URL_PARAMS)) continue;
            if (m.getDeclaredClassName() != null) classes.add(m.getDeclaredClassName());
        }
        String hit = preferNeighborhood(classes,
                returnTypeNames(load(cl, CLS_CHROME_TABBED_ACTIVITY)));
        return hit != null && hasCreateNewTab(cl, hit) ? hit : null;
    }

    /** 中心建页入口: 在唯一建页器上用语义评分找最高分入口。 */
    private static String resolveTabCreatorCentralCreate(DexKitBridge bridge, ClassLoader cl) {
        Set<String> owners = new LinkedHashSet<>();
        for (MethodData m : findMethodsReturningTab(bridge, cl)) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            List<String> ps = m.getParamTypeNames();
            if (!isCentralCreateShape(ps)) continue;
            if (!isType(ps.get(0), CLS_LOAD_URL_PARAMS)) continue;
            if (m.getDeclaredClassName() != null) owners.add(m.getDeclaredClassName());
        }
        String owner = preferNeighborhood(owners,
                returnTypeNames(load(cl, CLS_CHROME_TABBED_ACTIVITY)));
        if (owner == null) return null;
        Method central = ChromeSymbols.tabCreatorCentralCreateMethod(load(cl, owner));
        return central == null ? null : central.getName();
    }

    /** Tab 选择器: 返回 TabModel 且单个 boolean 参数的方法, 其声明类唯一。 */
    private static String resolveTabSelector(DexKitBridge bridge, ClassLoader cl) {
        Class<?> tabModel = load(cl, CLS_TAB_MODEL);
        if (tabModel == null) return null;
        Set<String> classes = new LinkedHashSet<>();
        for (MethodData m : findMethodsByParamWindow(bridge, tabModel, 1, 3)) {
            if (!Modifier.isStatic(m.getModifiers())
                    && allBooleanParams(m) && m.getDeclaredClassName() != null) {
                classes.add(m.getDeclaredClassName());
            }
        }
        String hit = preferNeighborhood(classes,
                fieldTypeNames(load(cl, CLS_CHROME_TABBED_ACTIVITY)));
        return hit != null && hasBooleanModelGetter(cl, hit, tabModel) ? hit : null;
    }

    /** 下载完成消息: (OfflineItem,boolean,boolean,boolean) -> void, 声明类唯一。 */
    private static String resolveDownloadMessage(DexKitBridge bridge, ClassLoader cl) {
        Class<?> item = load(cl, CLS_OFFLINE_ITEM);
        if (item == null) return null;
        Set<String> classes = new LinkedHashSet<>();
        for (MethodData m : findMethodsByParamWindow(bridge, void.class, 3, 6)) {
            if (!Modifier.isStatic(m.getModifiers())
                    && isOfflineMessageShape(m, item) && m.getDeclaredClassName() != null) {
                classes.add(m.getDeclaredClassName());
            }
        }
        String hit = preferNeighborhood(classes,
                fieldTypeNames(load(cl, CLS_DOWNLOAD_MGR_SERVICE)));
        return hit != null && hasOfflineMessageMethod(cl, hit, item) ? hit : null;
    }

    /** 横幅分发器: (PropertyModel,WebContents,int,boolean), 声明类唯一。 */
    private static String resolveMessageDispatcher(DexKitBridge bridge, ClassLoader cl) {
        Class<?> pm = load(cl, CLS_PROPERTY_MODEL);
        Class<?> wc = load(cl, CLS_WEB_CONTENTS);
        if (pm == null || wc == null) return null;
        Set<String> classes = new LinkedHashSet<>();
        for (MethodData m : findMethodsByParamWindow(bridge, null, 2, 6)) {
            if (!Modifier.isStatic(m.getModifiers()) && isDispatcherShape(m)) {
                if (m.getDeclaredClassName() != null) classes.add(m.getDeclaredClassName());
            }
        }
        Set<String> neighborhood = new LinkedHashSet<>();
        String messageOwner = resolveDownloadMessage(bridge, cl);
        if (messageOwner != null) {
            neighborhood.addAll(returnTypeNames(load(cl, messageOwner)));
        }
        String hit = preferNeighborhood(classes, neighborhood);
        return hit != null && hasDispatcherMethod(cl, hit, pm, wc) ? hit : null;
    }

    // ------------------------------------------------------------------
    // 查询 + 反射复核工具
    // ------------------------------------------------------------------

    /** 按返回类型 + 精确参数序列查询(返回类型 null = 不限制)。 */
    private static List<MethodData> findMethods(DexKitBridge bridge, Class<?> returnType,
                                                Class<?>[] paramTypes) {
        MethodMatcher matcher = new MethodMatcher();
        if (returnType != null) matcher.returnType(returnType);
        if (paramTypes != null && paramTypes.length > 0) matcher.paramTypes(paramTypes);
        FindMethod query = new FindMethod();
        query.matcher(matcher);
        MethodDataList list = bridge.findMethod(query);
        return toList(list);
    }

    /** 按返回类型 + 参数数量范围查询，随后在 Java 侧做类型语义过滤。 */
    private static List<MethodData> findMethodsByParamWindow(DexKitBridge bridge,
                                                             Class<?> returnType,
                                                             int minCount, int maxCount) {
        MethodMatcher matcher = new MethodMatcher();
        if (returnType != null) matcher.returnType(returnType);
        matcher.paramCount(minCount, maxCount);
        FindMethod query = new FindMethod();
        query.matcher(matcher);
        return toList(bridge.findMethod(query));
    }

    /** 返回类型为 Tab 的方法(参数不限, 调用方按首参过滤)。 */
    private static List<MethodData> findMethodsReturningTab(DexKitBridge bridge, ClassLoader cl) {
        Class<?> tab = load(cl, CLS_TAB);
        if (tab == null) return Collections.emptyList();
        return findMethods(bridge, tab, null);
    }

    private static List<MethodData> toList(MethodDataList list) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<MethodData> out = new ArrayList<>(list.size());
        for (MethodData m : list) out.add(m);
        return out;
    }

    /** 类型名比较: 兼容 Java 名(java.lang.String)与 dex 描述符(Ljava/lang/String;)。 */
    private static boolean isType(String typeName, String javaName) {
        if (typeName == null || javaName == null) return false;
        if (typeName.equals(javaName)) return true;
        String descriptor = "L" + javaName.replace('.', '/') + ";";
        if (typeName.equals(descriptor)) return true;
        int dot = javaName.lastIndexOf('.');
        String simple = dot >= 0 ? javaName.substring(dot + 1) : javaName;
        return typeName.equals(simple) || typeName.endsWith("." + simple) || typeName.endsWith("/" + simple);
    }

    private static String unique(Set<String> set) {
        if (set == null || set.size() != 1) return null;
        return set.iterator().next();
    }

    /**
     * 候选类与"锚点邻域"求交后取唯一: 邻域 = 公开锚点的字段类型/方法返回类型集合
     * (与锚点解析同一视角), 用于压掉全局 DEX 查询可能命中的无关类。
     * 交集唯一则优先; 交集不唯一时退回候选本身唯一; 都不唯一 → null(fail-closed)。
     */
    private static String preferNeighborhood(Set<String> candidates, Set<String> neighborhood) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (neighborhood != null && !neighborhood.isEmpty()) {
            Set<String> intersect = new LinkedHashSet<>(candidates);
            intersect.retainAll(neighborhood);
            String hit = unique(intersect);
            if (hit != null) return hit;
        }
        return unique(candidates);
    }

    /** 类的非静态字段类型名集合(锚点邻域)。 */
    private static Set<String> fieldTypeNames(Class<?> c) {
        Set<String> out = new LinkedHashSet<>();
        if (c == null) return out;
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.isSynthetic()) continue;   // v2.5.0: 合成字段(如 this$0)不是真实锚点邻域
            Class<?> t = f.getType();
            if (t.isPrimitive() || t.getName().startsWith("java.")) continue;
            out.add(t.getName());
        }
        return out;
    }

    /** 类的方法返回类型名集合(锚点邻域)。 */
    private static Set<String> returnTypeNames(Class<?> c) {
        Set<String> out = new LinkedHashSet<>();
        if (c == null) return out;
        for (Method m : c.getDeclaredMethods()) {
            // v2.5.0: 跳过 bridge/synthetic —— 协变返回会留下桥方法, 使邻域集合凭空多出
            //   一个"假返回类型", 把唯一候选变成两候选(fail-closed), 白白丢掉可解析的槽位。
            if (m.isBridge() || m.isSynthetic()) continue;
            Class<?> t = m.getReturnType();
            if (t == null || t.isPrimitive() || t.getName().startsWith("java.")) continue;
            out.add(t.getName());
        }
        return out;
    }

    private static boolean hasBooleanGurlGetter(ClassLoader cl, String className) {
        Class<?> c = load(cl, className);
        Class<?> gurl = load(cl, CLS_GURL);
        if (c == null || gurl == null) return false;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != gurl) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 0) continue;
            boolean allBool = true;
            for (Class<?> p : ps) {
                if (p != boolean.class && p != Boolean.class) {
                    allBool = false;
                    break;
                }
            }
            if (allBool) return true;
        }
        return false;
    }

    private static boolean hasCreateNewTab(ClassLoader cl, String className) {
        Class<?> c = load(cl, className);
        Class<?> lup = load(cl, CLS_LOAD_URL_PARAMS);
        Class<?> tab = load(cl, CLS_TAB);
        if (c == null || lup == null || tab == null) return false;
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length >= 1 && ps[0] == lup && m.getReturnType() == tab) return true;
        }
        return false;
    }

    private static boolean hasBooleanModelGetter(ClassLoader cl, String className,
                                                 Class<?> tabModel) {
        Class<?> c = load(cl, className);
        if (c == null) return false;
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length < 1 || ps.length > 3 || !tabModel.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            boolean allBool = true;
            for (Class<?> p : ps) if (p != boolean.class && p != Boolean.class) allBool = false;
            if (allBool) return true;
        }
        return false;
    }

    private static boolean hasOfflineMessageMethod(ClassLoader cl, String className,
                                                   Class<?> item) {
        Class<?> c = load(cl, className);
        if (c == null) return false;
        for (Method m : c.getDeclaredMethods()) {
            if (m.getReturnType() != void.class) continue;
            if (isOfflineMessageShape(m.getParameterTypes(), item)) return true;
        }
        return false;
    }

    private static boolean hasDispatcherMethod(ClassLoader cl, String className,
                                               Class<?> pm, Class<?> wc) {
        Class<?> c = load(cl, className);
        if (c == null) return false;
        for (Method m : c.getDeclaredMethods()) {
            if (isDispatcherShape(m.getParameterTypes(), pm, wc)) return true;
        }
        return false;
    }

    private static boolean allBooleanParams(MethodData m) {
        List<String> ps = m.getParamTypeNames();
        if (ps == null || ps.isEmpty()) return false;
        for (String p : ps) {
            if (!isBooleanType(p)) return false;
        }
        return true;
    }

    private static boolean isCentralCreateShape(List<String> ps) {
        if (ps == null || ps.size() < 6 || !isType(ps.get(0), CLS_LOAD_URL_PARAMS)) return false;
        boolean hasTab = false;
        boolean hasIntent = false;
        for (int i = 1; i < ps.size(); i++) {
            if (isType(ps.get(i), CLS_TAB)) hasTab = true;
            if (isType(ps.get(i), "android.content.Intent")) hasIntent = true;
        }
        return hasTab && (hasIntent || ps.size() >= 7);
    }

    private static boolean isOfflineMessageShape(MethodData m, Class<?> item) {
        List<String> ps = m.getParamTypeNames();
        if (ps == null || ps.size() < 3 || ps.size() > 6) return false;
        if (!isType(ps.get(0), item.getName())) return false;
        int boolCount = 0;
        for (int i = 1; i < ps.size(); i++) {
            if (isBooleanType(ps.get(i))) boolCount++;
        }
        return boolCount >= 2;
    }

    private static boolean isOfflineMessageShape(Class<?>[] ps, Class<?> item) {
        if (ps == null || ps.length < 3 || ps.length > 6 || ps[0] != item) return false;
        int boolCount = 0;
        for (int i = 1; i < ps.length; i++) {
            if (ps[i] == boolean.class || ps[i] == Boolean.class) boolCount++;
        }
        return boolCount >= 2;
    }

    private static boolean isDispatcherShape(MethodData m) {
        // 类型描述符来自 DEX，无需 ClassLoader。
        List<String> ps = m.getParamTypeNames();
        if (ps == null || ps.size() < 2 || ps.size() > 6) return false;
        if (!isType(ps.get(0), CLS_PROPERTY_MODEL)) return false;
        boolean web = false, integer = false, bool = false;
        for (int i = 1; i < ps.size(); i++) {
            if (isType(ps.get(i), CLS_WEB_CONTENTS)) web = true;
            if (isType(ps.get(i), "int")) integer = true;
            if (isBooleanType(ps.get(i))) bool = true;
        }
        return web && integer && bool;
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

    private static boolean isBooleanType(String typeName) {
        return "Z".equals(typeName) || isType(typeName, "boolean")
                || isType(typeName, "java.lang.Boolean");
    }

    private static Class<?> load(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String msg) {
        de.robv.android.xposed.XposedBridge.log(HookEntry.TAG + ": [dexkit] " + msg);
    }

    // ------------------------------------------------------------------
    // DexKit 内部缓存(键值文件; 仅影响查询速度, 失败不影响正确性)
    // ------------------------------------------------------------------

    private static final class FileCache implements DexKitCacheBridge.Cache {
        private static final char SEP = '\u0002';
        private final File file;

        FileCache(File file) {
            this.file = file;
        }

        @Override
        public String getString(String key, String defValue) {
            String v = store().get(key);
            return v == null ? defValue : v;
        }

        @Override
        public void putString(String key, String value) {
            Map<String, String> m = store();
            m.put(key, value);
            flush(m);
        }

        @Override
        public List<String> getStringList(String key, List<String> defValue) {
            String v = store().get(key);
            if (v == null || v.isEmpty()) return defValue;
            return new ArrayList<>(Arrays.asList(v.split("\u0001")));
        }

        @Override
        public void putStringList(String key, List<String> value) {
            if (value == null) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < value.size(); i++) {
                if (i > 0) sb.append('\u0001');
                sb.append(value.get(i));
            }
            putString(key, sb.toString());
        }

        @Override
        public void remove(String key) {
            Map<String, String> m = store();
            m.remove(key);
            flush(m);
        }

        @Override
        public java.util.Collection<String> getAllKeys() {
            return new ArrayList<>(store().keySet());
        }

        @Override
        public void clearAll() {
            flush(new LinkedHashMap<String, String>());
        }

        private Map<String, String> store() {
            Map<String, String> out = new LinkedHashMap<>();
            if (file == null || !file.exists()) return out;
            java.io.InputStream in = null;
            try {
                in = new java.io.FileInputStream(file);
                int len = (int) Math.min(file.length(), 1 << 20);
                byte[] buf = new byte[Math.max(len, 1)];
                int n = in.read(buf);
                String text = new String(buf, 0, Math.max(n, 0),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (String line : text.split("\n")) {
                    int sep = line.indexOf(SEP);
                    if (sep <= 0) continue;
                    out.put(line.substring(0, sep), line.substring(sep + 1));
                }
            } catch (Throwable t) {
                // 缓存损坏 -> 视为空, 不影响查询正确性
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                        // 关闭失败无影响
                    }
                }
            }
            return out;
        }

        private void flush(Map<String, String> m) {
            if (file == null) return;
            java.io.OutputStream out = null;
            try {
                File dir = file.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : m.entrySet()) {
                    sb.append(e.getKey()).append(SEP).append(e.getValue()).append('\n');
                }
                out = new java.io.FileOutputStream(file);
                out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            } catch (Throwable t) {
                // 写缓存失败不影响查询正确性
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                        // 关闭失败无影响
                    }
                }
            }
        }
    }
}
