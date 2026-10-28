package io.github.ylw6669.chrostar;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v2.5.0: 解析结果持久缓存(Chrome 进程私有 files/chrostar/caps-{versionCode}.json)。
 *
 * <p><b>为什么按 versionCode 分文件</b>: Chrome 每次更新 versionCode 必变, 天然构成
 * 缓存失效边界 —— 新版本读不到旧缓存 → 触发一次重扫; 旧缓存文件保留, 回退版本时可直接复用。
 *
 * <p><b>schema</b>: {@code {schemaVersion, chromeVersionCode, chromeVersionName, resolvedAt,
 * source, entries:{slotId:value}}}。{@code schemaVersion} 提升会让所有旧缓存失效(解析逻辑
 * 变化时使用)。
 *
 * <p>本类零 Xposed 依赖, 只使用 Android 公开 API + org.json。
 */
public final class CapsCache {

    private static final String TAG = "HomeLauncherLSP";

    /** 解析结果 schema 版本; 改变解析语义时 +1 使旧缓存全部失效。 */
    public static final int SCHEMA_VERSION = 2;

    private static final String DIR = "chrostar";

    private CapsCache() {
    }

    /** 缓存文件: files/chrostar/caps-{versionCode}.json */
    public static File fileFor(Context ctx, long versionCode) {
        File dir = new File(ctx.getFilesDir(), DIR);
        return new File(dir, "caps-" + versionCode + ".json");
    }

    /** 读取指定 Chrome versionCode 的解析缓存; 无缓存/schema 不符/损坏时返回空表。 */
    public static Map<String, String> load(Context ctx, long versionCode) {
        Map<String, String> out = new LinkedHashMap<>();
        if (ctx == null) return out;
        File f = fileFor(ctx, versionCode);
        if (!f.exists()) return out;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            int len = (int) Math.min(f.length(), 1 << 20);
            byte[] buf = new byte[Math.max(len, 1)];
            int n = in.read(buf);
            String text = new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) return out;
            if (root.optLong("chromeVersionCode", -1L) != versionCode) return out;
            JSONObject entries = root.optJSONObject("entries");
            if (entries == null) return out;
            java.util.Iterator<String> it = entries.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = entries.optString(k, null);
                if (k != null && v != null && !v.isEmpty()) out.put(k, v);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "CapsCache.load failed -> " + t);
            out.clear();
        } finally {
            closeQuietly(in);
        }
        return out;
    }

    /** 写入解析缓存(整表覆盖)。 */
    public static void save(Context ctx, long versionCode, String versionName,
                            Map<String, String> entries, String source) {
        if (ctx == null || entries == null || entries.isEmpty()) return;
        File f = fileFor(ctx, versionCode);
        OutputStream out = null;
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                android.util.Log.w(TAG, "CapsCache.save: mkdirs failed for " + dir);
            }
            JSONObject root = new JSONObject();
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("chromeVersionCode", versionCode);
            root.put("chromeVersionName", versionName == null ? "" : versionName);
            root.put("resolvedAt", System.currentTimeMillis());
            root.put("source", source == null ? "" : source);
            JSONObject e = new JSONObject();
            for (Map.Entry<String, String> en : entries.entrySet()) {
                if (en.getKey() != null && en.getValue() != null && !en.getValue().isEmpty()) {
                    e.put(en.getKey(), en.getValue());
                }
            }
            root.put("entries", e);
            out = new FileOutputStream(f);
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            android.util.Log.i(TAG, "CapsCache saved " + entries.size() + " entr(ies) -> "
                    + f.getName() + " (source=" + source + ")");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "CapsCache.save failed -> " + t);
        } finally {
            closeQuietly(out);
        }
    }

    /** 读取缓存文件里的 source 字段(诊断用); 无缓存返回 null。 */
    public static String sourceOf(Context ctx, long versionCode) {
        if (ctx == null) return null;
        File f = fileFor(ctx, versionCode);
        if (!f.exists()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            int len = (int) Math.min(f.length(), 1 << 20);
            byte[] buf = new byte[Math.max(len, 1)];
            int n = in.read(buf);
            JSONObject root = new JSONObject(new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8));
            return root.optString("source", null);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 缓存写入时间(ms); 无缓存返回 0。用于与"手动重扫请求"时间戳比较。 */
    public static long resolvedAt(Context ctx, long versionCode) {
        if (ctx == null) return 0L;
        File f = fileFor(ctx, versionCode);
        if (!f.exists()) return 0L;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            int len = (int) Math.min(f.length(), 1 << 20);
            byte[] buf = new byte[Math.max(len, 1)];
            int n = in.read(buf);
            JSONObject root = new JSONObject(new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8));
            return root.optLong("resolvedAt", 0L);
        } catch (Throwable t) {
            return 0L;
        } finally {
            closeQuietly(in);
        }
    }
    /** 删除指定版本的缓存(手动重扫/校验失败时调用)。 */
    public static boolean invalidate(Context ctx, long versionCode) {
        if (ctx == null) return false;
        File f = fileFor(ctx, versionCode);
        boolean ok = !f.exists() || f.delete();
        android.util.Log.i(TAG, "CapsCache invalidate(" + versionCode + ") -> " + ok);
        return ok;
    }

    /** 当前已安装 Chrome 的 versionCode; 取不到返回 -1。 */
    public static long installedVersionCode(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return -1L;
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo info = pm.getPackageInfo(pkg, 0);
            if (info == null) return -1L;
            return info.getLongVersionCode();
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 当前已安装 Chrome 的 versionName; 取不到返回 null。 */
    public static String installedVersionName(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return null;
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return info == null ? null : info.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
            // 关闭失败不影响结果
        }
    }
}
