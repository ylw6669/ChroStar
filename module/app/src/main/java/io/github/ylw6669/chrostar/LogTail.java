package io.github.ylw6669.chrostar;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * v2.5.4: hook 侧事件环形缓冲 + 回传。
 *
 * <p>动机: hook 运行在 Chrome 进程内, 其日志走 logcat 的 HomeLauncherLSP 标签。但 Android 7+
 * 起, 非 shell/非 root 进程只能读到自己 uid 的日志 —— 设置页因此读不到 Chrome 进程里的 hook 日志,
 * 除非授予 root。为了让调试页在<b>无 root</b> 时仍有可用信息, 这里把最有诊断价值的事件
 * (能力解析、Hook 装载、弹窗绕过、横幅拦截、自动打开、错误)记入一个进程内环形缓冲,
 * 并通过 {@link ConfigProvider} 的 {@code status.*} 白名单通道回传给设置页展示。
 *
 * <p>该通道是 best-effort: MIUI 可能拦截 Chrome 拉起模块进程, 失败时静默忽略 ——
 * 此时 root logcat 仍是完整来源。
 */
final class LogTail {

    /** 与设置页读取的键保持一致 */
    static final String KEY_TAIL = "status.log.tail";
    static final String KEY_UPDATED = "status.log.updated_at";

    private static final int MAX_EVENTS = 60;
    /** 回传节流: 至少间隔这么久才写一次 provider, 避免高频事件打爆跨进程调用 */
    private static final long PUSH_INTERVAL_MS = 3000L;

    private static final Deque<String> S_EVENTS = new ArrayDeque<>();
    private static long sLastPush;
    /** 推送失败后的退避时刻: 不再永久放弃 —— 设置页打开时模块进程是活的, 届时应能成功 */
    private static long sPushBackoffUntil;
    private static final Handler S_RETRY_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile boolean sRetryScheduled;
    private static int sRetryAttempts;

    private LogTail() {
    }

    /** 记录一条事件(线程安全; 超出上限丢弃最旧)。 */
    static void add(String tag, String message) {
        if (!HookEntry.readDebugEvents()) return;
        // 过滤噪音: isolated-split Chrome 的 base loader 天生看不到 Chrome 类,
        // 这些 ClassNotFound 属预期降级(全量趟会成功), 留在 logcat 即可, 不进事件尾部。
        if (isExpectedNoise(message)) return;
        String line = android.os.SystemClock.elapsedRealtime() / 1000L + "s [" + tag + "] " + message;
        synchronized (S_EVENTS) {
            S_EVENTS.addLast(line);
            while (S_EVENTS.size() > MAX_EVENTS) S_EVENTS.pollFirst();
        }
    }

    /** v2.5.6: 详细事件。仅 debug_verbose=true 时进入无 root 尾部, XposedBridge.log 仍保留完整历史。 */
    static void addVerbose(String tag, String message) {
        if (!HookEntry.readDebugVerbose()) return;
        add(tag, message);
    }

    /** v2.5.6: 错误事件不受总开关影响, 关闭调试时仍保留最后失败原因。 */
    static void addError(String tag, String message) {
        // isolated-split Chrome 的 base loader 无法看到 split 内 Chrome 类，
        // 这是晚装前的预期降级，不是功能失败；错误通道同样必须过滤。
        if (isExpectedNoise(message)) return;
        String line = android.os.SystemClock.elapsedRealtime() / 1000L + "s [" + tag + "] " + message;
        synchronized (S_EVENTS) {
            S_EVENTS.addLast(line);
            while (S_EVENTS.size() > MAX_EVENTS) S_EVENTS.pollFirst();
        }
    }

    private static boolean isExpectedNoise(String message) {
        return message != null && message.contains("ClassNotFound");
    }

    /** v2.5.6: 详细事件 + 立即回传, 用于单次复现时定位具体退出点。 */
    static void addVerboseAndPush(Context ctx, String tag, String message) {
        if (!HookEntry.readDebugVerbose()) return;
        addAndPush(ctx, tag, message);
    }

    static synchronized String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (String s : S_EVENTS) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 把事件尾部推给设置页(best-effort, 带节流)。必须在 Chrome 进程内调用。
     * 失败一次后不再反复尝试, 避免无谓的跨进程开销。
     */
    static void push(Context ctx) {
        if (ctx == null) return;
        long nowMs = System.currentTimeMillis();
        if (nowMs < sPushBackoffUntil) return;
        long now = System.currentTimeMillis();
        if (now - sLastPush < PUSH_INTERVAL_MS) return;
        String tail;
        synchronized (S_EVENTS) {
            if (S_EVENTS.isEmpty()) return;
            tail = snapshot();
        }
        sLastPush = now;
        try {
            ContentValues cv = new ContentValues();
            cv.put(KEY_TAIL, tail);
            cv.put(KEY_UPDATED, String.valueOf(now));
            // 引擎和 versionCode 与事件同步回传，避免设置页长期展示上一版 Chrome 的解析状态。
            cv.put("status.caps.engine", HookEntry.engineVersion);
            cv.put("status.caps.runtime_updated_at", String.valueOf(now));
            try {
                android.content.pm.PackageInfo info = ctx.getPackageManager()
                        .getPackageInfo("com.android.chrome", 0);
                if (info != null) {
                    cv.put("status.caps.runtime_version_code",
                            String.valueOf(info.getLongVersionCode()));
                    cv.put("status.caps.runtime_version_name",
                            String.valueOf(info.versionName));
                }
            } catch (Throwable ignored) {
                // 版本信息只是诊断附加项；包可见性失败不影响事件尾部回传，但保留可见记录。
                de.robv.android.xposed.XposedBridge.log(HookEntry.TAG
                        + ": [logtail] runtime version unavailable -> " + ignored);
            }
            try {
                long vc = CapsCache.installedVersionCode(ctx, "com.android.chrome");
                if (ChromeSymbols.hasCurrentCapabilityStatus()) {
                    cv.put("status.caps.chrome_version_code", String.valueOf(vc));
                    cv.put("status.caps.cached_count", String.valueOf(ChromeSymbols.currentCapabilityCount()));
                    cv.put("status.caps.source", "LIVE");
                    cv.put("status.caps.missing", ChromeSymbols.currentCapabilityMissing());
                    cv.put("status.caps.sources", ChromeSymbols.currentCapabilitySources());
                } else {
                    java.util.Map<String, String> caps = CapsCache.load(ctx, vc);
                    if (caps.isEmpty()) return;
                    StringBuilder sources = new StringBuilder();
                    for (String id : caps.keySet()) {
                        if (sources.length() > 0) sources.append(',');
                        sources.append(id).append('=').append(ResolvedCaps.SRC_CACHE);
                    }
                    cv.put("status.caps.chrome_version_code", String.valueOf(vc));
                    cv.put("status.caps.cached_count", String.valueOf(caps.size()));
                    cv.put("status.caps.source", "CACHE");
                    cv.put("status.caps.missing", "");
                    cv.put("status.caps.sources", sources.toString());
                    long resolvedAt = CapsCache.resolvedAt(ctx, vc);
                    if (resolvedAt > 0L) {
                        cv.put("status.caps.updated_at", String.valueOf(resolvedAt));
                    }
                }
            } catch (Throwable t) {
                de.robv.android.xposed.XposedBridge.log(HookEntry.TAG
                        + ": [logtail] capability snapshot attach failed -> " + t);
            }
            int updated = ctx.getContentResolver().update(
                    Uri.parse("content://" + ConfigProvider.AUTHORITY + "/config"), cv, null, null);
            if (updated <= 0) {
                sPushBackoffUntil = System.currentTimeMillis() + 20000L;
                scheduleRetry(ctx);
            } else {
                sRetryAttempts = 0;
            }
        } catch (Throwable t) {
            // 模块进程未拉起时 ContentResolver 会抛 "Unknown URI"; 退避 20s 后重试,
            // 因为设置页一旦打开, 同进程的 provider 就可用了。
            sPushBackoffUntil = System.currentTimeMillis() + 20000L;
            scheduleRetry(ctx);
            de.robv.android.xposed.XposedBridge.log(HookEntry.TAG
                    + ": [logtail] push deferred (设置页打开后会自动恢复) -> " + t);
        }
    }

    /** Provider 未拉起时进行一次真正的延迟重试，而不是等待下一个业务事件。 */
    private static void scheduleRetry(final Context ctx) {
        if (ctx == null || sRetryScheduled || sRetryAttempts >= 6) return;
        sRetryAttempts++;
        long delayMs = Math.min(300000L, 20000L * sRetryAttempts);
        sRetryScheduled = true;
        S_RETRY_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                sRetryScheduled = false;
                sPushBackoffUntil = 0L;
                push(ctx);
            }
        }, delayMs);
    }

    /** 记录 + 立即尝试回传, 用于低频但关键的事件(解析完成、装载完成)。 */
    static void addAndPush(Context ctx, String tag, String message) {
        add(tag, message);
        sLastPush = 0L;   // 绕过节流
        push(ctx);
    }
}
