// ChroStar v2.3.7 — 1B-1: OfflineItem 语义字段解析器。
// 免符号、版本无关、纯 Java 反射 + 公开 Android API, 无任何原生依赖(不引入 DexKit/.so)。
//
// 背景(adb 运行时取证 · 152.0.7977.82): ia8.d 正确触发(state=2), 但 OfflineItem 的
// 混淆字段语义发生漂移 —— i0=content://media/external/downloads/1000002660(URI 无扩展名)、
// T="Download"(目录名非全路径)、j0=application/octet-stream(通用 MIME)。写死字母读取
// (path=T/name=i0/mime=j0) 因此拿到错误内容 → shouldAutoOpen 落 NO-MATCH → 自动打开失效。
//
// 本解析器不盲信任何固定字段语义, 改为遍历对象全部 String 字段按"内容特征"分类角色,
// 并用 per-engine 硬编码映射(145:m0/e0/f0/P, 152:q0/T/i0/j0)作为优先级 hint(保证 145 等价)。
// content:// URI 的真实文件名/MIME 用 ContentResolver(只读公开 API)在后台线程 + 超时解析,
// 绝不在 hook 回调/主线程同步查询(会 ANR)。任一角色判不出则 fail-closed(安全跳过)。
package io.github.ylw6669.chrostar;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class OfflineItemResolver {

    /** MIME token: 小写字母数字 + RFC 允许的特殊字符(大写视为文件名/路径, 不是 MIME) */
    private static final Pattern TOKEN_RE =
            Pattern.compile("^[a-z0-9][a-z0-9!#$&^_.+-]*$");
    private static final java.util.Map<Class<?>, Field> STATE_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Field>();

    /** R2-m4: 已知文件扩展名集合(均小写) —— looksLikeMime 用它区分"单斜杠相对路径"
     *  (text/notes.txt、image/a.png) 与"真 MIME"(application/vnd.ms-excel)。 */
    private static final java.util.Set<String> KNOWN_FILE_EXT = new java.util.HashSet<String>(
            java.util.Arrays.asList(
                    "doc", "docx", "odt", "rtf", "xls", "xlsx", "csv", "ods",
                    "ppt", "pptx", "odp", "pdf", "txt", "md", "log",
                    "png", "jpg", "jpeg", "gif", "webp", "bmp",
                    "mp4", "mkv", "webm", "avi", "mov", "mp3", "flac", "wav", "ogg", "m4a",
                    "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz",
                    "apk", "json", "xml", "html", "htm", "epub", "mobi", "m3u8"));

    private OfflineItemResolver() {
    }

    // ==================================================================
    // 结果载体
    // ==================================================================

    /** 同步语义分类结果(纯反射, 无 IPC) */
    public static final class Result {
        /** content:// URI(需进一步后台解析真实名/路径) */
        public String uri;
        /** 含 / 且末段有扩展名的完整路径 */
        public String fullPath;
        /** 含扩展名但无 / 的裸文件名 */
        public String fileName;
        /** 最佳 MIME(具体类型优先于通用 octet-stream) */
        public String mime;
        /** 公开 ContentId(字段 S), 交叉校验/事件去重用 */
        public Object contentId;
        /** state 交叉校验(可空; 消费方通常已自行读 q0/m0 门禁) */
        public Integer state;

        /** 最佳文件名: 裸名 > 完整路径 basename > null */
        public String bestName() {
            if (fileName != null && !fileName.isEmpty()) return fileName;
            if (fullPath != null && !fullPath.isEmpty()) return basename(fullPath);
            return null;
        }

        /** 最佳路径线索: 完整路径 > content uri > null */
        public String bestPath() {
            if (fullPath != null && !fullPath.isEmpty()) return fullPath;
            return uri;
        }

        /**
         * 是否可在不查 ContentResolver 的情况下判定文件身份:
         * 文件名带扩展名, 或 MIME 是具体类型(非通用 octet-stream)。
         * false → 需走 content:// 后台解析或 fail-closed 跳过。
         * R2-M2①: 有 uri 但无名时即使 MIME 具体也判不可决定 —— 因为 Chrome 从 Content-Type
         *   得知具体 MIME 时 j0=具体类型, 旧逻辑会直接返回 true → hook152 跳过
         *   resolveContentUri → name 恒 null → computeShouldAutoOpen 的 DOC/SHEET/PPT/EBOOK
         *   分支(仅 isExt(n,…) 无 MIME 兜底)全 false → NO-MATCH 静默失效。必须补名。
         */
        public boolean hasDecidableIdentity() {
            String n = bestName();
            // R2-M2①: 有 uri 而无名 → 身份不可判定, 必须补名(否则后续分支仅靠扩展名会 NO-MATCH)。
            if (uri != null && (n == null || n.isEmpty())) return false;
            if (n != null && hasExtension(n)) return true;
            return mime != null && !isGenericMime(mime);
        }

        @Override
        public String toString() {
            return "uri=[" + uri + "] path=[" + fullPath + "] name=[" + fileName
                    + "] mime=[" + mime + "]";
        }
    }

    /** content:// 后台解析结果 */
    public static final class Resolved {
        public String displayName;
        public String mime;
        /** MediaStore _data 列(最佳努力; 作用域存储下可能为 null) */
        public String realPath;
    }

    // ==================================================================
    // 通道 1: 同步语义分类(可在 hook 回调线程安全调用 —— 仅反射, 无 IPC)
    // ==================================================================
    public static Result classify(Object item, ClassLoader cl) {
        Result r = new Result();
        if (item == null) return r;
        List<String> uris = new ArrayList<String>();
        List<String> paths = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        List<String> specificMimes = new ArrayList<String>();
        List<String> genericMimes = new ArrayList<String>();
        try {
            Class<?> c = item.getClass();
            while (c != null && c != Object.class) {
                Field[] fields;
                try {
                    fields = c.getDeclaredFields();
                } catch (Throwable t) {
                    break;
                }
                for (Field f : fields) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(item);
                    } catch (Throwable t) {
                        continue; // 单个字段读失败不影响其余分类
                    }
                    if (v == null) continue;
                    String vn = v.getClass().getName();
                    if (r.contentId == null
                            && (vn.endsWith(".ContentId") || "S".equals(f.getName()))) {
                        r.contentId = v;
                    }
                    if (!(v instanceof String)) continue;
                    String s = ((String) v).trim();
                    if (s.isEmpty()) continue;
                    // Minor-10: MIME 判定前先规范化(剥 ';' 参数 + toLowerCase), 仅用于 MIME 识别,
                    //   不影响路径/文件名(大小写敏感)的原值。
                    String mimeNorm = mimeCandidate(s);
                    // M1 修复: 分类顺序 content:// → file:// → http/chrome/data 忽略
                    //   → looksLikeMime → hasExtension → 其余忽略。
                    //   looksLikeMime 必须早于 hasExtension: 点分 subtype 的 MIME
                    //   (如 application/vnd...wordprocessingml.document, 末段 ext="document" 8位全字母)
                    //   否则会被 hasExtension 误判为路径 → bestName 垃圾名 + hasDecidableIdentity 误真
                    //   → 击穿 fail-closed(152 自动打开静默失效 / 横幅显示 MIME 乱码)。
                    if (s.startsWith("content://")) {
                        uris.add(s);
                    } else if (s.startsWith("file://")) {
                        String p = Uri.parse(s).getPath();
                        if (p != null && !p.isEmpty()) paths.add(p);
                    } else if (s.startsWith("http://") || s.startsWith("https://")
                            || s.startsWith("chrome://") || s.startsWith("chrome-native://")
                            || s.startsWith("data:")) {
                        // 标题/URL/内联数据 —— 不是文件身份, 忽略。
                        // Minor-9: chrome 收紧为 chrome://+chrome-native://, 避免误丢裸名 chrome.apk/chrome_installer.exe。
                    } else if (looksLikeMime(mimeNorm)) {
                        if (isGenericMime(mimeNorm)) genericMimes.add(mimeNorm);
                        else specificMimes.add(mimeNorm);
                    } else if (hasExtension(s)) {
                        if (s.contains("/")) paths.add(s);
                        else names.add(s);
                    }
                    // 其余(如目录名 "Download"、无扩展名裸串)忽略 —— fail-closed
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [diag] resolver classify error -> " + t);
        }

        // per-engine 硬编码映射作为优先级 hint: 命中同类候选时优先选它(保证 145 行为等价)。
        // All non-145 engines use the modern OfflineItem layout.  In particular,
        // chrome_symbolic must not fall back to the 145 m0/e0/f0 fields.
        boolean is152 = !"chrome145".equals(HookEntry.engineVersion);
        String hintName = safeStr(item, is152 ? "i0" : "e0");
        String hintPath = safeStr(item, is152 ? "T" : "P");
        // Minor-10: hint MIME 也先规范化(剥分号+小写), 与分类结果同形, 使大写/带参 MIME 也能命中。
        String hintMime = mimeCandidate(safeStr(item, is152 ? "j0" : "f0"));

        r.uri = first(uris);
        r.fullPath = containsStr(paths, hintPath) ? hintPath : first(paths);
        r.fileName = containsStr(names, hintName) ? hintName : first(names);
        // MIME: hint 具体 > 任一具体 > hint(通用) > 任一通用
        if (hintMime != null && looksLikeMime(hintMime) && !isGenericMime(hintMime)) {
            r.mime = hintMime;
        }
        if (r.mime == null) r.mime = first(specificMimes);
        if (r.mime == null && hintMime != null && looksLikeMime(hintMime)) r.mime = hintMime;
        if (r.mime == null) r.mime = first(genericMimes);

        r.state = completionState(item);
        return r;
    }

    /**
     * 读取 OfflineItem 状态。已知版本使用已验证字段；未来版本仅在当前对象上恰好有一个
     * int 字段值为 COMPLETE(2) 时接受并缓存该字段，避免把任意 int 字段误当状态。
     */
    public static Integer completionState(Object item) {
        if (item == null) return null;
        String[] hints = "chrome145".equals(HookEntry.engineVersion)
                ? new String[]{"m0"}
                : ("chrome_symbolic".equals(HookEntry.engineVersion)
                    ? new String[]{"q0", "r0"} : new String[]{"q0"});
        for (String hint : hints) {
            try {
                return Integer.valueOf(XposedHelpers.getIntField(item, hint));
            } catch (Throwable t) {
                if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                        + ": [diag] OfflineItem state hint " + hint + " unavailable -> " + t);
            }
        }
        if (!"chrome_symbolic".equals(HookEntry.engineVersion)) return null;
        Field cached = STATE_FIELDS.get(item.getClass());
        if (cached != null) {
            try {
                return Integer.valueOf(cached.getInt(item));
            } catch (Throwable t) {
                STATE_FIELDS.remove(item.getClass());
                if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                        + ": [diag] cached OfflineItem state field failed -> " + t);
            }
        }
        Field hit = null;
        Class<?> c = item.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                try {
                    f.setAccessible(true);
                    if (f.getInt(item) != 2) continue;
                    if (hit != null) {
                        XposedBridge.log(HookEntry.TAG
                                + ": [diag] OfflineItem COMPLETE state field ambiguous; skip");
                        return null;
                    }
                    hit = f;
                } catch (Throwable t) {
                    if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                            + ": [diag] OfflineItem int field read failed -> " + t);
                }
            }
            c = c.getSuperclass();
        }
        if (hit == null) return null;
        STATE_FIELDS.put(item.getClass(), hit);
        XposedBridge.log(HookEntry.TAG + ": [diag] OfflineItem state field resolved structurally -> "
                + hit.getDeclaringClass().getName() + "." + hit.getName());
        return Integer.valueOf(2);
    }

    // ==================================================================
    // 通道 2: content:// 后台解析(只读公开 API + 硬超时, 必须在后台线程调用)
    // ==================================================================
    /**
     * 用 ContentResolver 读 content:// URI 的 DISPLAY_NAME / MIME / _data。
     * 内部另起守护线程执行查询并 join(timeoutMs): 即便调用方已在后台线程, 也用硬超时
     * 防止 ContentProvider 挂起导致安装线程永久阻塞。超时/异常 → 返回 null(fail-closed)。
     */
    public static Resolved resolveContentUri(final Context ctx, final String uriStr,
                                             long timeoutMs) {
        if (ctx == null || uriStr == null || !uriStr.startsWith("content://")) return null;
        // m16: 防 join(0)/负数误用导致永久等待或立即返回 —— 非正超时回落 3000ms 默认硬超时。
        if (timeoutMs <= 0L) timeoutMs = 3000L;
        final Resolved out = new Resolved();
        final boolean[] done = {false};
        Thread w = new Thread(new Runnable() {
            @Override
            public void run() {
                Cursor c = null;
                try {
                    Uri u = Uri.parse(uriStr);
                    try {
                        out.mime = ctx.getContentResolver().getType(u);
                    } catch (Throwable ignored) {
                    }
                    // 先试 DISPLAY_NAME + _data; 某些 provider 无 _data 列会抛异常 → 退化只查名
                    try {
                        c = ctx.getContentResolver().query(u,
                                new String[]{OpenableColumns.DISPLAY_NAME, "_data"},
                                null, null, null);
                        readCursor(c, out, true);
                    } catch (Throwable t) {
                        if (c != null) {
                            try { c.close(); } catch (Throwable ignored) { }
                            c = null;
                        }
                        try {
                            c = ctx.getContentResolver().query(u,
                                    new String[]{OpenableColumns.DISPLAY_NAME},
                                    null, null, null);
                            readCursor(c, out, false);
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(HookEntry.TAG + ": [diag] resolveContentUri error -> " + t);
                } finally {
                    if (c != null) {
                        try { c.close(); } catch (Throwable ignored) { }
                    }
                    synchronized (out) {
                        done[0] = true;
                        out.notifyAll();
                    }
                }
            }
        });
        w.setDaemon(true);
        w.start();
        try {
            w.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (out) {
            if (!done[0]) {
                XposedBridge.log(HookEntry.TAG + ": [diag] resolveContentUri timeout("
                        + timeoutMs + "ms), fail-closed -> " + uriStr);
                return null;
            }
        }
        XposedBridge.log(HookEntry.TAG + ": [diag] resolveContentUri [" + uriStr + "] -> name=["
                + out.displayName + "] mime=[" + out.mime + "] realPath=[" + out.realPath + "]");
        return out;
    }

    private static void readCursor(Cursor c, Resolved out, boolean wantData) {
        if (c == null || !c.moveToFirst()) return;
        int di = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (di >= 0) out.displayName = c.getString(di);
        if (wantData) {
            int pi = c.getColumnIndex("_data");
            if (pi >= 0) out.realPath = c.getString(pi);
        }
    }

    // ==================================================================
    // 内容特征判定 helper(纯函数)
    // ==================================================================

    /** 末段是否带合法扩展名: . 后有 1~8 位字母数字, 且非隐藏文件(.开头)、非点在末尾 */
    static boolean hasExtension(String s) {
        if (s == null) return false;
        // R2-m5: 护栏收窄为 looksLikeMime —— 旧实现只判"首段是顶层类型"会整体丢弃
        //   "application/My Report.docx"(目录名 application + 含空格大写文件名)、"text/README.md" 等。
        //   looksLikeMime 同时要求两段均为小写 token 且 subtype 末段非已知文件扩展名,
        //   仅对真 MIME 返回 true, 不误伤带空格/大写/已知扩展名的相对路径。
        int firstSlash = s.indexOf('/');
        if (firstSlash > 0 && looksLikeMime(s.toLowerCase())) {
            return false;
        }
        String seg = s.contains("/") ? s.substring(s.lastIndexOf('/') + 1) : s;
        int dot = seg.lastIndexOf('.');
        if (dot <= 0 || dot == seg.length() - 1) return false;
        String ext = seg.substring(dot + 1);
        if (ext.length() > 8) return false;
        for (int i = 0; i < ext.length(); i++) {
            if (!Character.isLetterOrDigit(ext.charAt(i))) return false;
        }
        return true;
    }

    /** 是否形如 MIME: 单斜杠、两段均为小写 token、type 是已知顶层类型或 x- 实验类型 */
    static boolean looksLikeMime(String s) {
        if (s == null) return false;
        int slash = s.indexOf('/');
        if (slash <= 0 || slash == s.length() - 1) return false;
        if (s.indexOf('/', slash + 1) >= 0) return false; // 多于一个 / → 不是 MIME
        String type = s.substring(0, slash);
        String sub = s.substring(slash + 1);
        if (!TOKEN_RE.matcher(type).matches() || !TOKEN_RE.matcher(sub).matches()) return false;
        // R2-m4: 单斜杠相对路径首段恰为 MIME 顶层类型(text/notes.txt、image/a.png、
        //   application/settings.json、x-files/report.docx) 会被误判为 MIME → 丢失 path 且污染 r.mime。
        //   若 subtype 形如 name.ext 且 ext 命中已知文件扩展名集合 → 归为相对路径(非 MIME)。
        //   真 MIME 的点分 subtype(application/vnd.ms-excel 等)末段不在集合 → 仍判 MIME。
        //   绝对路径不受影响(slash<=0 已排除; 多段路径第二个 / 已排除)。
        int dot = sub.lastIndexOf('.');
        if (dot > 0 && dot < sub.length() - 1 && isKnownFileExt(sub.substring(dot + 1))) {
            return false;
        }
        return isMimeTopType(type);
    }

    /** R2-m4: ext 是否为已知文件扩展名(入参需已小写)。 */
    private static boolean isKnownFileExt(String ext) {
        return ext != null && KNOWN_FILE_EXT.contains(ext);
    }

    /** 已知 MIME 顶层类型(looksLikeMime 与 hasExtension 护栏共用); type 须已 toLowerCase */
    private static boolean isMimeTopType(String type) {
        return type.equals("application") || type.equals("audio") || type.equals("image")
                || type.equals("text") || type.equals("video") || type.equals("message")
                || type.equals("model") || type.equals("multipart") || type.equals("font")
                || type.equals("chemical") || type.startsWith("x-");
    }

    /** Minor-10: MIME 判定前的规范化 —— 剥掉 ';' 参数(如 "; charset=utf-8")再 trim+toLowerCase。
     *  仅用于 MIME 识别, 不改变路径/文件名原值。null 安全(返回 null)。 */
    private static String mimeCandidate(String s) {
        if (s == null) return null;
        int semi = s.indexOf(';');
        String t = (semi >= 0) ? s.substring(0, semi) : s;
        return t.trim().toLowerCase();
    }

    /** 通用/未知 MIME: 不足以判定文件类型(fail-closed 时需另找线索) */
    static boolean isGenericMime(String s) {
        if (s == null) return true;
        String m = s.toLowerCase();
        return m.equals("application/octet-stream")
                || m.equals("binary/octet-stream")
                || m.equals("application/unknown")
                || m.equals("application/x-unknown")
                || m.equals("content/unknown")
                || m.equals("application/download")
                || m.isEmpty();
    }

    static String basename(String s) {
        if (s == null) return null;
        return s.contains("/") ? s.substring(s.lastIndexOf('/') + 1) : s;
    }

    // ==================================================================
    // R2-M3: ContentId 提取 / 组合标量键构造 helper(事件级去重用)
    // ==================================================================

    /**
     * 从 OfflineItem 提取 ContentId 对象。复用 classify 的**类型优先**探测:
     * 字段运行时类型名以 ".ContentId" 结尾 → 立即返回(抵御混淆字段名跨版本漂移);
     * 字段名 "S"(145/152 已知名)仅作兜底。找不到返回 null。
     * 145(zkg.f) 与 152(hook152) 两路都调本 helper, 不再写死 "S"。
     */
    public static Object extractContentId(Object item) {
        if (item == null) return null;
        Object byName = null;
        try {
            Class<?> c = item.getClass();
            while (c != null && c != Object.class) {
                Field[] fields;
                try {
                    fields = c.getDeclaredFields();
                } catch (Throwable t) {
                    break;
                }
                for (Field f : fields) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(item);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (v == null) continue;
                    // 类型优先: 运行时类型名以 .ContentId 结尾 → 立即返回(不依赖字段名)。
                    if (v.getClass().getName().endsWith(".ContentId")) return v;
                    // 兜底: 字段名 "S"(仅在类型探测未命中时使用)。
                    if (byName == null && "S".equals(f.getName())) byName = v;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [diag] extractContentId error -> " + t);
        }
        return byName;
    }

    /**
     * 构造 ContentId 的**组合标量键**: 反射遍历全部非静态标量字段(String/Number),
     * 拼成 "类名#字段名=值#..."。ContentId = (String namespace, long id) 二元组 ——
     * 同时纳入 namespace 与 id, 避免不同命名空间相同数值 id 撞键。
     * 基本类型 long 反射取值自动装箱为 Long(Number), 被纳入。
     * **禁用 toString() 作回落**: ContentId 未重写 equals/hashCode, toString =类名@identityHash
     * 每实例唯一 → 去重恒失效。无任何标量字段时返回 null(调用方需显式 fail-open)。
     */
    public static String contentIdKey(Object cid) {
        if (cid == null) return null;
        List<String> parts = new ArrayList<String>();
        try {
            Class<?> c = cid.getClass();
            while (c != null && c != Object.class) {
                Field[] fields;
                try {
                    fields = c.getDeclaredFields();
                } catch (Throwable t) {
                    break;
                }
                for (Field f : fields) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(cid);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (v == null) continue;
                    if (v instanceof String || v instanceof Number) {
                        parts.add(f.getName() + "=" + v);
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [diag] contentIdKey error -> " + t);
        }
        if (parts.isEmpty()) return null;
        // 字段名排序 → 键与 getDeclaredFields 返回顺序无关, 跨调用稳定。
        java.util.Collections.sort(parts);
        StringBuilder sb = new StringBuilder(cid.getClass().getName());
        for (String p : parts) sb.append('#').append(p);
        return sb.toString();
    }

    private static String safeStr(Object obj, String field) {
        try {
            Object v = XposedHelpers.getObjectField(obj, field);
            return (v instanceof String) ? ((String) v).trim() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String first(List<String> list) {
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private static boolean containsStr(List<String> list, String s) {
        return s != null && !s.isEmpty() && list != null && list.contains(s);
    }

}
