package io.github.ylw6669.chrostar;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.3.19: 模块 App 侧配置存储(零 Xposed 依赖)。
 *
 * <p><b>关键约束</b>: 本类<strong>绝不引用</strong> {@link HookEntry} 等含 Xposed API 的类。
 * 模块自身进程(main process)没有 Xposed API(runtime), 一旦引用会在类加载期抛
 * {@code NoClassDefFoundError} —— 这正是 v2.3.17 闪退的真因(当时 ConfigProvider.onCreate 调用了
 * {@code HookEntry.migrateLegacyPrefs(...)} 方法)。本类只含纯 Android API, 可在模块进程安全加载。
 *
 * <p><b>New XSharedPreferences 要求</b>: LSPosed 仅在模块侧以 {@link Context#MODE_WORLD_READABLE}
 * 打开 prefs 时, 才把 safe-zone 文件置为 world-readable({@code ContextImpl.checkMode} 被框架 hook
 * 以吞掉 SecurityException, {@code ContextImpl.getPreferencesDir} 被重定向到 safe-zone)。
 * 因此 {@link #open(Context)} 优先 WORLD_READABLE; 框架未启用(抛 SecurityException)时回退
 * {@link Context#MODE_PRIVATE}, 不影响功能(仅 New XSP 失效, 走 ConfigProvider 兜底)。
 */
public final class PrefsStore {

    private static final String TAG = "HomeLauncherLSP";

    /** 配置文件名。历史值保持不变(勿改, 否则用户设置会"看似被重置")。 */
    public static final String PREFS_NAME = "io.github.ylw6669.chrostar_preferences";

    private PrefsStore() {
    }

    /** 打开模块配置: 优先 MODE_WORLD_READABLE(启用 New XSP 共享), 失败回退 MODE_PRIVATE。 */
    public static SharedPreferences open(Context ctx) {
        try {
            SharedPreferences sp =
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
            if (sp != null) {
                return sp;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "PrefsStore.open MODE_WORLD_READABLE failed -> " + t);
        }
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 配置迁移: 当"新位置"(safe-zone, 即 {@link #open} 指向的文件)为空, 而"旧位置"
     * (模块私有目录 shared_prefs)存在且有值时, 把旧值原样拷入新位置。
     *
     * <p>严格满足: 幂等(新配置非空则不动作)/ 不覆盖用户已存在的新配置 / 不删除旧配置 /
     * 不改变 key / 不因升级把设置恢复默认。
     */
    public static void migrateLegacy(Context ctx) {
        try {
            SharedPreferences cur = open(ctx);
            Map<String, ?> curAll = cur.getAll();
            if (curAll != null && !curAll.isEmpty()) {
                return; // 新配置已存在 → 不迁移、不覆盖
            }
            File legacy = new File(ctx.getDataDir(), "shared_prefs/" + PREFS_NAME + ".xml");
            if (!legacy.exists()) {
                return;
            }
            Map<String, Object> old = parseOldXml(legacy);
            if (old.isEmpty()) {
                return;
            }
            SharedPreferences.Editor e = cur.edit();
            for (Map.Entry<String, Object> en : old.entrySet()) {
                Object v = en.getValue();
                if (v instanceof Boolean) {
                    e.putBoolean(en.getKey(), (Boolean) v);
                } else if (v instanceof Integer) {
                    e.putInt(en.getKey(), (Integer) v);
                } else if (v instanceof Long) {
                    e.putLong(en.getKey(), (Long) v);
                } else {
                    e.putString(en.getKey(), String.valueOf(v));
                }
            }
            e.commit();
            android.util.Log.i(TAG, "PrefsStore.migrateLegacy migrated " + old.size() + " key(s)");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "PrefsStore.migrateLegacy failed -> " + t);
        }
    }

    /**
     * 权威配置快照: **必须与设置页写入的存储同源**。
     *
     * <p>v2.5.2 修复: 此前硬编码读私有 {@code shared_prefs}。那份文件在 LSPosed
     * MODE_WORLD_READABLE 重定向生效后就不再被更新, 会长期保留陈旧值 —— 实测 154 设备上
     * Provider 返回旧的 {@code download_dir=/Download/Alarms}, 而设置页显示的是
     * {@code /Download/chrome}, 导致 hook 侧在几百毫秒内被 arm 两次、最终落到错目录。
     *
     * <p>现在与 {@link #open(Context)} 同源(即 LSPosed safe-zone); 仅在 open() 不可用或为空时
     * 才回退旧私有文件(兼容框架未启用的场景)。
     */
    public static Map<String, Object> readPrivateSnapshot(Context ctx) {
        if (ctx == null) return new HashMap<>();
        try {
            Map<String, ?> all = open(ctx).getAll();
            Map<String, Object> out = new HashMap<>();
            if (all != null) {
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        out.put(e.getKey(), e.getValue());
                    }
                }
            }
            if (!out.isEmpty()) return out;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "PrefsStore.readPrivateSnapshot open() failed -> " + t);
        }
        File file = new File(ctx.getDataDir(), "shared_prefs/" + PREFS_NAME + ".xml");
        return parseOldXml(file);
    }

    /** 直接解析旧 SharedPreferences XML(不经 getSharedPreferences, 因重定向后该 API 也指向新位置)。 */
    private static Map<String, Object> parseOldXml(File f) {
        Map<String, Object> out = new HashMap<>();
        InputStream is = null;
        try {
            is = new FileInputStream(f);
            int len = (int) Math.min(f.length(), 1 << 20);
            byte[] buf = new byte[Math.max(len, 1)];
            int n = is.read(buf);
            String xml = new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
            Matcher mb = Pattern.compile(
                    "<boolean\\s+name=\"([^\"]+)\"\\s+value=\"(true|false)\"").matcher(xml);
            while (mb.find()) {
                out.put(mb.group(1), Boolean.parseBoolean(mb.group(2)));
            }
            Matcher ms = Pattern.compile(
                    "<string\\s+name=\"([^\"]+)\">(.*?)</string>").matcher(xml);
            while (ms.find()) {
                out.put(ms.group(1), unescapeXml(ms.group(2)));
            }
            Matcher mi = Pattern.compile(
                    "<int\\s+name=\"([^\"]+)\"\\s+value=\"(-?\\d+)\"").matcher(xml);
            while (mi.find()) {
                out.put(mi.group(1), Integer.parseInt(mi.group(2)));
            }
            Matcher ml = Pattern.compile(
                    "<long\\s+name=\"([^\"]+)\"\\s+value=\"(-?\\d+)\"").matcher(xml);
            while (ml.find()) {
                out.put(ml.group(1), Long.parseLong(ml.group(2)));
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "PrefsStore.parseOldXml failed -> " + t);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Throwable ignored) {
                    // 关闭失败不影响迁移结果
                }
            }
        }
        return out;
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
