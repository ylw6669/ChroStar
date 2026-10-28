package io.github.ylw6669.chrostar;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v2.5.0: 按 Chrome 大版本隔离的配置档案层(零 Xposed 依赖)。
 *
 * <p><b>为什么要档案</b>: 历史上所有 Chrome 版本共用同一份配置键, 为 A 版本调好的
 * 设置会直接作用到 B 版本(例如下载目录、横幅开关), 无法互相隔离。现在每个 Chrome
 * 大版本各自一套键: {@code v154.download_dir}。旧的无前缀键只读不写, 作为首次建档的
 * 迁移来源, 永不删除。
 *
 * <p><b>键格式</b>: {@code v{大版本}.{原键名}}。仍写在同一份 prefs 文件, New
 * XSharedPreferences / safe-zone 机制完全不变。
 *
 * <p><b>建档(seed)链</b>: 同名档已存在 → 最近使用的档({@link #META_LAST_PROFILE}) →
 * 旧全局无前缀键 → 默认值(不写)。建档后写 {@code v{major}.profile.seeded} 标记, 幂等。
 *
 * <p><b>硬约束</b>: 本类绝不引用 {@link HookEntry} 等含 Xposed API 的类(模块进程无
 * Xposed runtime, 引用其方法会抛 NoClassDefFoundError, 见 v2.3.17 事故)。
 */
public final class ProfilePrefs {

    private static final String TAG = "HomeLauncherLSP";

    /** 元数据: 最近使用的档案 id(如 "v154") */
    public static final String META_LAST_PROFILE = "profile.last_used";
    /** 元数据: 最近使用档案的时间戳(ms) */
    public static final String META_LAST_USED_TS = "profile.last_used_ts";
    /** 档案建档标记后缀: v154.profile.seeded */
    public static final String SUFFIX_SEEDED = "profile.seeded";

    /** 不被当作"用户配置"参与跨档复制的元数据前缀 */
    private static final String META_PREFIX = "profile.";
    /** 扫描状态回传键前缀(hook 写、设置页读) */
    private static final String STATUS_PREFIX = "status.";

    /** 界面控制键(如一次性 reload 标记)不属于用户配置, 不参与跨档复制 */
    private static final String UI_PREFIX = "ui.";

    private ProfilePrefs() {
    }

    // ------------------------------------------------------------------
    // 档案 id / 键名
    // ------------------------------------------------------------------

    /** 大版本号 → 档案 id: "154" → "v154"; 非法输入返回 null。 */
    public static String profileOfMajor(String major) {
        if (major == null) return null;
        String m = major.trim();
        if (m.isEmpty()) return null;
        for (int i = 0; i < m.length(); i++) {
            if (!Character.isDigit(m.charAt(i))) return null;
        }
        return "v" + m;
    }

    /** 完整版本名 → 大版本号: "154.0.8037.49" → "154"; 解析失败返回 null。 */
    public static String majorOfVersionName(String versionName) {
        if (versionName == null) return null;
        int end = 0;
        while (end < versionName.length() && Character.isDigit(versionName.charAt(end))) end++;
        return end == 0 ? null : versionName.substring(0, end);
    }

    /** 档案 id + 原键 → 实际存储键: ("v154","download_dir") → "v154.download_dir"。 */
    public static String keyOf(String profile, String key) {
        if (profile == null || profile.isEmpty() || key == null) return key;
        return profile + "." + key;
    }

    /** 从实际存储键反推所属档案 id; 无前缀(旧全局键)返回 null。 */
    public static String profileOfKey(String storedKey) {
        if (storedKey == null || storedKey.length() < 3) return null;
        if (storedKey.charAt(0) != 'v') return null;
        int i = 1;
        while (i < storedKey.length() && Character.isDigit(storedKey.charAt(i))) i++;
        if (i == 1 || i >= storedKey.length() || storedKey.charAt(i) != '.') return null;
        return storedKey.substring(0, i);
    }

    /** 去掉档案前缀; 无前缀返回原键。 */
    public static String stripProfile(String storedKey) {
        String p = profileOfKey(storedKey);
        return p == null ? storedKey : storedKey.substring(p.length() + 1);
    }

    private static boolean isMetaKey(String key) {
        return key != null && (key.startsWith(META_PREFIX) || key.startsWith(STATUS_PREFIX)
                || key.startsWith(UI_PREFIX) || HookEntry.KEY_RESCAN_REQUEST_AT.equals(key));
    }

    // ------------------------------------------------------------------
    // 读取(带降级链: 档案键 → 旧全局键 → 默认)
    // ------------------------------------------------------------------

    /** 原始值读取; null = 档案与旧全局都没有该键。 */
    public static String resolveRaw(SharedPreferences sp, String profile, String key) {
        if (sp == null || key == null) return null;
        String scoped = keyOf(profile, key);
        if (scoped != null && sp.contains(scoped)) {
            Object v = sp.getAll().get(scoped);
            return v == null ? null : String.valueOf(v);
        }
        if (sp.contains(key)) {
            Object v = sp.getAll().get(key);
            return v == null ? null : String.valueOf(v);
        }
        return null;
    }

    public static boolean getBoolean(SharedPreferences sp, String profile, String key, boolean def) {
        String raw = resolveRaw(sp, profile, key);
        if (raw == null) return def;
        return "true".equalsIgnoreCase(raw) || "1".equals(raw);
    }

    public static int getInt(SharedPreferences sp, String profile, String key, int def) {
        String raw = resolveRaw(sp, profile, key);
        if (raw == null) return def;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static String getString(SharedPreferences sp, String profile, String key, String def) {
        String raw = resolveRaw(sp, profile, key);
        return raw == null ? def : raw;
    }

    public static long getLong(SharedPreferences sp, String profile, String key, long def) {
        String raw = resolveRaw(sp, profile, key);
        if (raw == null) return def;
        try {
            return Long.parseLong(raw.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // ------------------------------------------------------------------
    // 当前档案判定
    // ------------------------------------------------------------------

    /** 读取指定包的大版本号; 失败返回 null。 */
    public static String installedMajor(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo info = pm.getPackageInfo(pkg, 0);
            if (info == null) return null;
            return majorOfVersionName(info.versionName);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 当前应使用的档案 id。
     * 优先按已安装 Chrome 大版本; 取不到时回退最近使用的档案; 仍无则返回 null(调用方按旧全局键读)。
     */
    public static String currentProfile(Context ctx, SharedPreferences sp, String chromePkg) {
        String major = installedMajor(ctx, chromePkg);
        String profile = profileOfMajor(major);
        if (profile != null) return profile;
        if (sp != null) {
            String last = sp.getString(META_LAST_PROFILE, null);
            String lastProfile = profileOfKey(last) != null ? last : null;
            if (lastProfile != null) return lastProfile;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 建档 + 记录最近使用
    // ------------------------------------------------------------------

    /**
     * 确保档案已建档(幂等)。
     *
     * @return true = 本次执行了建档复制; false = 之前已建档
     */
    public static boolean ensureSeeded(SharedPreferences sp, String profile) {
        if (sp == null || profile == null || profile.isEmpty()) return false;
        String marker = keyOf(profile, SUFFIX_SEEDED);
        if (sp.getBoolean(marker, false)) return false;

        String source = sp.getString(META_LAST_PROFILE, null);
        if (source != null && source.equals(profile)) source = null;

        Map<String, ?> all = sp.getAll();
        SharedPreferences.Editor e = sp.edit();
        Set<String> written = new HashSet<>();
        int copied = 0;

        // 1) 同名档已存在(理论上不会走到, 防御性): 只补标记
        // 2) 复制最近使用档的键
        if (source != null) {
            String srcPrefix = source + ".";
            for (Map.Entry<String, ?> en : all.entrySet()) {
                String k = en.getKey();
                if (k == null || !k.startsWith(srcPrefix) || en.getValue() == null) continue;
                String bare = k.substring(srcPrefix.length());
                if (isMetaKey(bare)) continue;
                String target = keyOf(profile, bare);
                if (all.containsKey(target) || written.contains(target)) continue;
                putValue(e, target, en.getValue());
                written.add(target);
                copied++;
            }
        }

        // 3) 无可复制来源时, 用旧全局无前缀键初始化
        if (copied == 0) {
            for (Map.Entry<String, ?> en : all.entrySet()) {
                String k = en.getKey();
                if (k == null || en.getValue() == null) continue;
                if (profileOfKey(k) != null) continue;   // 已是带档案前缀的键
                if (isMetaKey(k)) continue;              // 元数据/状态不参与复制
                String target = keyOf(profile, k);
                if (all.containsKey(target) || written.contains(target)) continue;
                putValue(e, target, en.getValue());
                written.add(target);
                copied++;
            }
        }

        e.putBoolean(marker, true);
        e.putString(META_LAST_PROFILE, profile);
        e.putLong(META_LAST_USED_TS, System.currentTimeMillis());
        e.apply();
        android.util.Log.i(TAG, "ProfilePrefs seeded " + profile + " (" + copied + " key(s), source="
                + (source == null ? "legacy-global" : source) + ")");
        return true;
    }

    /** 记录该档案被使用(供"复制最近使用的档"判定)。 */
    public static void markUsed(SharedPreferences sp, String profile) {
        if (sp == null || profile == null || profile.isEmpty()) return;
        sp.edit()
                .putString(META_LAST_PROFILE, profile)
                .putLong(META_LAST_USED_TS, System.currentTimeMillis())
                .apply();
    }

    /**
     * 清理旧版错误复制进版本档案的全局控制键。
     *
     * <p>v2.5.0~v2.5.7 的 seed 逻辑把 {@code rescan_request_at} 当作用户配置复制到
     * {@code v152.*}/{@code v153.*}/{@code v154.*}，会使对应版本长期显示 MANUAL_RESCAN。
     * 该键只允许以无前缀的全局键存在。
     */
    public static int cleanupCopiedControlKeys(SharedPreferences sp) {
        if (sp == null) return 0;
        SharedPreferences.Editor e = sp.edit();
        int removed = 0;
        for (String k : new ArrayList<>(sp.getAll().keySet())) {
            String p = profileOfKey(k);
            if (p == null) continue;
            String bare = stripProfile(k);
            if (HookEntry.KEY_RESCAN_REQUEST_AT.equals(bare)) {
                e.remove(k);
                removed++;
            }
        }
        if (removed > 0) {
            e.apply();
            android.util.Log.i(TAG, "ProfilePrefs cleanup: removed " + removed
                    + " copied global control key(s)");
        }
        return removed;
    }

    /** 列出当前已存在的档案 id(按 discovery 顺序)。 */
    public static List<String> knownProfiles(SharedPreferences sp) {
        List<String> out = new ArrayList<>();
        if (sp == null) return out;
        for (String k : sp.getAll().keySet()) {
            String p = profileOfKey(k);
            if (p != null && !out.contains(p)) out.add(p);
        }
        return out;
    }

    /** 扩展类型自动打开的子开关键(总开关缺失时用于反推意图)。 */
    private static final String[] AUTO_OPEN_TYPE_KEYS = {
            HookEntry.KEY_AUTO_OPEN_PDF, HookEntry.KEY_AUTO_OPEN_ARCHIVE,
            HookEntry.KEY_AUTO_OPEN_DOCUMENT, HookEntry.KEY_AUTO_OPEN_SPREADSHEET,
            HookEntry.KEY_AUTO_OPEN_PRESENTATION, HookEntry.KEY_AUTO_OPEN_TEXT,
            HookEntry.KEY_AUTO_OPEN_IMAGE, HookEntry.KEY_AUTO_OPEN_VIDEO,
            HookEntry.KEY_AUTO_OPEN_AUDIO, HookEntry.KEY_AUTO_OPEN_EBOOK};

    /**
     * v2.5.1: 修复"适配 153/154 时被改坏的旧配置"。
     *
     * <p>根因: 「下载完成自动打开(扩展类型)」总开关是 152/153/154 适配期新增的键。145 时代
     * (以及 v2.2~v2.3.12)的配置里没有它, 只有各类型子开关。若按默认值 false 处理, 老配置里
     * 已经打开的类型开关会全部静默失效。
     *
     * <p>本方法把推断结果**显式落盘**, 使设置页显示与实际生效一致; hook 侧另有
     * {@code HookEntry.readExtAutoOpenGate()} 做同样的意图推断(用户从未打开设置页时也能生效)。
     *
     * <p>只在该键完全不存在(档案键与旧全局键都没有)时才动作; 已有显式值一律不动,
     * 因此 153/154 等新版本配置不会被改写。
     *
     * @return true = 本次写入了显式值
     */
    public static boolean repairExtGateIntent(SharedPreferences sp, String profile) {
        if (sp == null || profile == null || profile.isEmpty()) return false;
        if (resolveRaw(sp, profile, HookEntry.KEY_AUTO_OPEN_EXT) != null) return false;
        boolean intent = false;
        for (String key : AUTO_OPEN_TYPE_KEYS) {
            if (getBoolean(sp, profile, key, false)) {
                intent = true;
                break;
            }
        }
        if (!intent) {
            String custom = getString(sp, profile, HookEntry.KEY_AUTO_OPEN_CUSTOM, "");
            intent = custom != null && !custom.trim().isEmpty();
        }
        if (!intent) return false;
        sp.edit().putBoolean(keyOf(profile, HookEntry.KEY_AUTO_OPEN_EXT), true).apply();
        android.util.Log.i(TAG, "ProfilePrefs.repairExtGateIntent: 旧配置的子开关已开启, "
                + "补写 " + keyOf(profile, HookEntry.KEY_AUTO_OPEN_EXT) + "=true");
        return true;
    }
    /**
     * 重置指定档案: 清除该档案下所有非元数据键(不动旧全局键, 不动其它档案)。
     * 重置后下一次读取自然回落到旧全局键或默认值。
     */
    public static int resetProfile(SharedPreferences sp, String profile) {
        if (sp == null || profile == null || profile.isEmpty()) return 0;
        String prefix = profile + ".";
        SharedPreferences.Editor e = sp.edit();
        int removed = 0;
        for (String k : new ArrayList<>(sp.getAll().keySet())) {
            if (k == null || !k.startsWith(prefix)) continue;
            String bare = k.substring(prefix.length());
            if (isMetaKey(bare)) continue;
            e.remove(k);
            removed++;
        }
        e.remove(keyOf(profile, SUFFIX_SEEDED));
        e.apply();
        return removed;
    }

    private static void putValue(SharedPreferences.Editor e, String key, Object v) {
        if (v instanceof Boolean) {
            e.putBoolean(key, (Boolean) v);
        } else if (v instanceof Integer) {
            e.putInt(key, (Integer) v);
        } else if (v instanceof Long) {
            e.putLong(key, (Long) v);
        } else if (v instanceof Float) {
            e.putFloat(key, (Float) v);
        } else if (v instanceof String) {
            e.putString(key, (String) v);
        } else {
            e.putString(key, String.valueOf(v));
        }
    }
}
