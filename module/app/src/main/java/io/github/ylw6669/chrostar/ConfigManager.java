package io.github.ylw6669.chrostar;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * v2.3.19: 统一配置读取器(仅在 Chrome hook 侧使用)。
 *
 * <p>读取优先级(严格按此顺序, 命中即返回):
 * <ol>
 *   <li><b>PROVIDER</b> —— 设置页私有 prefs 的权威快照。Provider 可用时优先使用，
 *       避免 safe-zone 遗留副本覆盖用户的新设置。</li>
 *   <li><b>NEW_XSP</b> —— LSPosed New XSharedPreferences。模块侧以
 *       {@code MODE_WORLD_READABLE} 打开 prefs, 框架把文件落到 safe-zone 且置为可读;
 *       hook 侧用 {@code new XSharedPreferences(pkg, file)} 直接读文件。无 IPC, 不受 MIUI
 *       自启管控 / SELinux category 隔离影响, 是主通道。</li>
 *   <li><b>OLD_XSP</b> —— 旧式 XSharedPreferences(末位兼容, 不做 canRead 门槛)。</li>
 *   <li><b>DEFAULT</b> —— 三通道全不可用时使用调用方默认值。</li>
 * </ol>
 *
 * <p>缓存: 5 秒内存缓存(整表快照), 避免热路径频繁文件 I/O。UI 修改后 ≤5s 生效。
 *
 * <p>空值语义(严格区分, 见需求第十节):
 * <ul>
 *   <li>{@code null} —— 配置读取失败或该 key 不存在。调用方须按"未读到"处理。</li>
 *   <li>{@code ""}  —— 用户显式选择了空值(如"使用 Chrome 默认下载目录")。</li>
 *   <li>非空字符串 —— 用户设置的真实值。</li>
 * </ul>
 * {@link #getStringRaw} 返回原始值以保留该区分; {@link #getString(String, String)} 则把 null 映射为默认值。
 */
public final class ConfigManager {

    /** 配置来源状态(日志用, 便于区分"读失败"与"用户选了默认") */
    public static final String SRC_NEW_XSP = "NEW_XSP";
    public static final String SRC_PROVIDER = "PROVIDER";
    public static final String SRC_OLD_XSP = "OLD_XSP";
    public static final String SRC_DEFAULT = "DEFAULT";
    public static final String SRC_ERROR = "ERROR";

    private static final long CACHE_TTL_MS = 5000L;

    private static volatile long sCacheTime;
    /** null = 本次快照读取完全失败(DEFAULT/ERROR); 非 null = 命中某来源(可能为空表) */
    private static volatile Map<String, String> sCache;
    private static volatile String sSource = SRC_DEFAULT;
    private static volatile String sLoggedSource;
    /** v2.5.0: 当前 Chrome 大版本档案 id(如 "v154"); null = 未绑定, 走旧全局键 */
    private static volatile String sProfile;

    private ConfigManager() {
    }

    // ------------------------------------------------------------------
    // 对外统一入口(chrome hook 侧统一走此处)
    // ------------------------------------------------------------------
    public static boolean getBoolean(String key, boolean defValue) {
        String v = getStringRaw(key);
        if (v == null) {
            return defValue;
        }
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    public static int getInt(String key, int defValue) {
        String v = getStringRaw(key);
        if (v == null) {
            return defValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return defValue;
        }
    }

    public static String getString(String key, String defValue) {
        String v = getStringRaw(key);
        return v == null ? defValue : v;
    }

    /**
     * 原始值读取: 返回 {@code null} 表示"读取失败或 key 不存在", 返回 {@code ""}
     * 表示"用户显式选择空值"。两者语义不同, 调用方按需区分。
     */
    public static String getStringRaw(String key) {
        Map<String, String> snap = snapshot();
        if (snap == null) return null;
        // rescan_request_at 是全局控制键，不是版本用户配置。旧版本曾错误把它复制进
        // v152/v153/v154 档案，导致某些版本每次启动都被当成“手动重扫”。
        if (HookEntry.KEY_RESCAN_REQUEST_AT.equals(key)) {
            return snap.get(key);
        }
        // v2.5.0: 档案键优先(v154.download_dir), 未命中回落到旧全局键(download_dir)。
        //   旧全局键只读不写, 作为首次建档的迁移来源永久保留。
        String profile = sProfile;
        if (profile != null && !profile.isEmpty()) {
            String scoped = snap.get(profile + "." + key);
            if (scoped != null) return scoped;
        }
        return snap.get(key);
    }

    /**
     * v2.5.0: 绑定当前 Chrome 大版本档案(如 "v154")。由 HookEntry 在引擎判定后调用;
     * 传 null/空表示不启用档案(退回旧全局键语义)。切换档案会作废配置缓存。
     */
    public static void setProfile(String profile) {
        if (profile != null && profile.equals(sProfile)) return;
        sProfile = profile;
        invalidate();
    }

    /** 当前绑定的档案 id(未绑定返回 null), 供日志/诊断。 */
    public static String currentProfile() {
        return sProfile;
    }

    /** 本次(缓存周期)配置来源, 供日志/诊断区分 null 是"读失败"还是"用户选默认"。 */
    public static String lastSource() {
        return sSource;
    }

    /** 强制下次读取刷新缓存(供测试/迁移后调用)。 */
    public static void invalidate() {
        sCacheTime = 0L;
        sCache = null;
    }

    // ------------------------------------------------------------------
    // 快照: 三通道依次尝试 + 5s 缓存
    // ------------------------------------------------------------------
    private static Map<String, String> snapshot() {
        long now = System.currentTimeMillis();
        Map<String, String> c = sCache;
        if (c != null && now - sCacheTime < CACHE_TTL_MS) {
            return c;
        }
        synchronized (ConfigManager.class) {
            now = System.currentTimeMillis();
            c = sCache;
            if (c != null && now - sCacheTime < CACHE_TTL_MS) {
                return c;
            }
            Map<String, String> loaded = null;
            String src;

            // 1) Provider 返回设置页私有 prefs 的权威快照。New XSP safe-zone
            // 副本在模块升级或迁移后可能滞后，不能抢在它之前覆盖新设置。
            loaded = readProvider();
            if (loaded != null) {
                src = SRC_PROVIDER;
            } else {
                // 2) New XSharedPreferences(Provider 被系统拦截时的无 IPC 兜底)
                loaded = readNewXsp();
                if (loaded != null) {
                    src = SRC_NEW_XSP;
                } else {
                    // 3) 旧 XSharedPreferences(末位兼容)
                    loaded = readOldXsp();
                    src = loaded != null ? SRC_OLD_XSP : SRC_DEFAULT;
                }
            }

            sCache = loaded;
            sCacheTime = now;
            sSource = src;

            // 日志: 每个来源首次出现时记录一次, 避免刷屏
            if (!src.equals(sLoggedSource)) {
                sLoggedSource = src;
                XposedBridge.log(HookEntry.TAG + ": config source=" + src
                        + (loaded == null ? " (no store reachable)" : " (keys=" + loaded.size() + ")"));
            }
            return loaded;
        }
    }

    private static Map<String, String> readNewXsp() {
        try {
            XSharedPreferences p = new XSharedPreferences(
                    BuildConfig.APPLICATION_ID, HookEntry.PREFS_NAME);
            p.reload();
            // New XSP 约定: 读前检查文件可读; 不可读视为本通道失败(交由兜底)
            if (p.getFile() == null || !p.getFile().canRead()) {
                return null;
            }
            return flatten(p.getAll());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, String> readOldXsp() {
        try {
            XSharedPreferences p = new XSharedPreferences(
                    BuildConfig.APPLICATION_ID, HookEntry.PREFS_NAME);
            p.reload();
            Map<String, String> m = flatten(p.getAll());
            return m.isEmpty() ? null : m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, String> readProvider() {
        try {
            Context ctx = HookEntry.getAppContext(null);
            if (ctx == null) {
                return null;
            }
            Cursor cur = ctx.getContentResolver().query(
                    Uri.parse("content://" + ConfigProvider.AUTHORITY + "/config"),
                    null, null, null, null);
            if (cur == null) {
                return null;
            }
            try {
                Map<String, String> m = new HashMap<>();
                while (cur.moveToNext()) {
                    String k = cur.getString(0);
                    String v = cur.getString(1);
                    if (k != null && v != null) {
                        m.put(k, v);
                    }
                }
                return m;
            } finally {
                cur.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, String> flatten(Map<String, ?> all) {
        Map<String, String> out = new HashMap<>();
        if (all != null) {
            for (Map.Entry<String, ?> e : all.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
        }
        return out;
    }
}
