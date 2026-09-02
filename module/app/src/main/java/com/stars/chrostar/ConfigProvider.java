package com.stars.chrostar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Map;

/**
 * v1.10.4: 配置跨进程读取通道。
 *
 * 背景: XSharedPreferences 在 Android 11+ 作用域存储下,
 * Chrome 进程读不到模块 App 的 prefs 文件(无 MANAGE_EXTERNAL_STORAGE),
 * 所有开关回退默认值 —— 用户开启的开关在 hook 侧读不到(hide=false 实锤)。
 *
 * 方案: exported ContentProvider, ContentResolver 跨应用可读(系统自动拉起模块进程),
 * 不依赖文件权限。HookEntry 读取时: XSharedPreferences 优先(万一可用),
 * 失败则查询本 Provider, 带 5 秒缓存。
 */
public final class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.stars.chrostar.config";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(
                HookEntry.PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> all = prefs.getAll();
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
        return "vnd.android.cursor.dir/vnd.chrome.homelauncher.config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
