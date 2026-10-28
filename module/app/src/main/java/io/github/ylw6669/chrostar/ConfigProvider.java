package io.github.ylw6669.chrostar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Map;

/**
 * 配置跨进程读取通道(v1.10.4 引入, v2.3.19 降级为兜底)。
 *
 * <p>背景: XSharedPreferences 在 Android 11+ 作用域存储下, Chrome 进程读不到模块 prefs
 * 文件; 本 Provider(exported)经 ContentResolver 跨应用可读, 不依赖文件权限。但 MIUI
 * 自启管控(WakePathChecker)可能拒绝 Chrome 拉起模块进程 —— 因此它<strong>只是兜底</strong>,
 * 主通道是 LSPosed New XSharedPreferences(见 {@link ConfigManager})。
 *
 * <p><b>禁止</b>在本类引用 {@link HookEntry}(含 Xposed API)。模块自身进程没有 Xposed 运行时,
 * 引用其非编译期常量成员会在类加载期抛 {@code NoClassDefFoundError}(此为 v2.3.17 闪退真因)。
 * 故本类一律经纯 Android 的 {@link PrefsStore} 访问配置。
 */
public final class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.ylw6669.chrostar.config";
    /** v2.5.0: 状态回传白名单前缀(仅此前缀可写) */
    private static final String STATUS_PREFIX = "status.";
    /** v2.5.0: 单个状态值长度上限(防异常写入撑爆 prefs) */
    private static final int MAX_STATUS_VALUE_LEN = 8192;

    @Override
    public boolean onCreate() {
        try {
            Context ctx = getContext();
            if (ctx != null) {
                PrefsStore.migrateLegacy(ctx);
            }
        } catch (Throwable t) {
            android.util.Log.w("HomeLauncherLSP", "ConfigProvider migrate failed -> " + t);
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        // 设置界面在应用私有 prefs 中提交最新值；safe-zone 仅为 New XSP
        // 兼容副本，可能保留旧值。Provider 必须优先返回实际设置页的快照。
        Map<String, ?> all = PrefsStore.readPrivateSnapshot(ctx);
        if (all.isEmpty()) {
            SharedPreferences prefs = PrefsStore.open(ctx);
            all = prefs.getAll();
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{"key", "value"});
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getValue() != null) {
                cursor.addRow(new Object[]{e.getKey(), String.valueOf(e.getValue())});
            }
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.ylw6669.chrostar.config";
    }


    /**
     * v2.5.0: 只接受 {@code status.*} 白名单键的写入。
     *
     * <p>用途: Chrome 进程里的 hook 解析/扫描完成后, 把"已适配版本、缺失槽位、上次解析时间"
     * 等状态回传给设置页展示(ConfigProvider 运行在模块进程, 可以写模块自己的 prefs)。
     * <b>这是 best-effort 通道</b> —— MIUI 自启管控可能拦截 Chrome 拉起模块进程, 失败时以
     * LSPosed 日志为准。除 {@code status.} 前缀外的任何键一律拒绝, 避免外部进程改写用户配置。
     */
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        Context ctx = getContext();
        if (ctx == null || values == null || values.size() == 0) return 0;
        SharedPreferences prefs = PrefsStore.open(ctx);
        SharedPreferences.Editor editor = prefs.edit();
        int accepted = 0;
        for (String key : values.keySet()) {
            if (key == null || !key.startsWith(STATUS_PREFIX)) continue;
            Object raw = values.get(key);
            if (raw == null) continue;
            String value = String.valueOf(raw);
            if (value.length() > MAX_STATUS_VALUE_LEN) {
                value = value.substring(0, MAX_STATUS_VALUE_LEN);
            }
            editor.putString(key, value);
            accepted++;
        }
        if (accepted > 0) {
            editor.commit();
            android.util.Log.i("HomeLauncherLSP",
                    "ConfigProvider accepted " + accepted + " status key(s)");
        }
        return accepted;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return update(uri, values, null, null) > 0 ? uri : null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
}
