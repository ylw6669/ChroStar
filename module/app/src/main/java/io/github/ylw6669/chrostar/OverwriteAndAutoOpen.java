// ChroStar v2.3.13 — 下载自动打开类型扩展
// 纯公开类字段判定, 不依赖混淆名, Chrome 145/152 通用。
// v2.3.13: 移除「同名覆盖保留原文件」功能(hookOverwriteDuplicate/armBackup/scheduleRestore),
//          本类现仅保留「下载完成自动打开类型扩展」策略判定。类名沿用历史名称以兼容
//          proguard keep 与既有调用点, 不再包含同名覆盖逻辑。
package io.github.ylw6669.chrostar;

import de.robv.android.xposed.XposedBridge;

public final class OverwriteAndAutoOpen {

    private OverwriteAndAutoOpen() {}

    // ------------------------------------------------------------------
    // 下载自动打开类型扩展
    // AutoInstallApk 的 isApk 判定点替换为 MIME 白名单(键: auto_open_*)
    // ------------------------------------------------------------------
    public static boolean shouldAutoOpen(String mime, String name) {
        boolean apkSw = HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_INSTALL_APK, true);
        boolean extSw = HookEntry.readExtAutoOpenGate();
        XposedBridge.log(HookEntry.TAG + ": [diag] shouldAutoOpen ENTER mime=[" + mime
                + "] name=[" + name + "] apkSw=" + apkSw + " extSw=" + extSw);
        LogTail.addVerbose("Auto", "shouldAutoOpen mime=" + mime
                + " name=" + name + " apkSw=" + apkSw + " extSw=" + extSw);
        boolean r = computeShouldAutoOpen(mime, name, apkSw, extSw);
        XposedBridge.log(HookEntry.TAG + ": [diag] shouldAutoOpen RESULT=" + r);
        LogTail.addVerbose("Auto", "shouldAutoOpen result=" + r);
        return r;
    }

    private static boolean computeShouldAutoOpen(String mime, String name, boolean apkSw, boolean extSw) {
        String lowerName = name == null ? "" : name.toLowerCase();
        // 自定义后缀从属于「下载完成自动打开(扩展类型)」总开关。
        // 用户填写列表只定义额外匹配范围，不能绕过 auto_open_ext 门禁。
        boolean customHit = matchesCustomExt(lowerName);
        boolean customEnabled = !customList().isEmpty();

        // APK 保持原键(auto_install_apk)；自定义列表包含 apk 时，同样必须先开启扩展总开关。
        if (HookEntry.isApk(mime, name)) {
            boolean v = apkSw || (extSw && customHit);
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=APK -> " + v
                    + (customHit ? " (custom gated by auto_open_ext)" : ""));
            return v;
        }
        // 总开关关闭时，内置分类和自定义后缀都不得生效。
        if (!extSw) {
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=EXT-OFF (auto_open_ext=false)"
                    + (customEnabled ? " [custom configured but gated]" : "") + " -> false");
            return false;
        }
        String m = mime == null ? "" : mime.toLowerCase();
        String n = lowerName;

        // 总开关开启后，自定义后缀优先于内置分类，避免被 MIME 对应分类的子开关抢先拦截。
        if (customHit) {
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=CUSTOM -> true (n=[" + n + "])");
            return true;
        }

        if (m.startsWith("image/") || isExt(n, ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp")) {
            boolean v = HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_OPEN_IMAGE, false);
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=IMAGE sw=" + v); return v;
        }
        // Minor-2: m3u8/HLS 播放列表 —— application/vnd.apple.mpegurl、application/x-mpegurl
        //   不匹配任何分支(除非恰为 audio|video/x-mpegurl) → 落 NO-MATCH。补 mpegurl MIME 兜底
        //   + .m3u/.m3u8 扩展名, 使实现与注释名副其实。VIDEO 早于 AUDIO, 故 mpegurl 兜底加
        //   !startsWith("audio/") 守卫: application/*mpegurl 归 VIDEO, audio/mpegurl 仍落 AUDIO(不被抢走)。
        if (m.startsWith("video/") || (m.contains("mpegurl") && !m.startsWith("audio/"))
                || isExt(n, ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m3u", ".m3u8")) {
            boolean v = HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_OPEN_VIDEO, false);
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=VIDEO sw=" + v); return v;
        }
        if (m.startsWith("audio/") || isExt(n, ".mp3", ".flac", ".ogg", ".wav", ".m4a")) {
            boolean v = HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_OPEN_AUDIO, false);
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=AUDIO sw=" + v); return v;
        }
        if (m.equals("application/pdf") || n.endsWith(".pdf")) {
            boolean v = HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_OPEN_PDF, false);
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=PDF sw=" + v); return v;
        }
        if (isExt(n, ".zip", ".7z", ".rar", ".tar", ".gz", ".tgz", ".bz2", ".xz") || isArchiveMime(m)) {
            boolean v = HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_OPEN_ARCHIVE, false);
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=ARCHIVE sw=" + v); return v;
        }
        // Minor-1: 补宏 Word/模板扩展名 .docm/.dotx/.dotm(旧列表缺失 → 宏文档无名场景 NO-MATCH)。
        if (isExt(n, ".doc", ".docx", ".odt", ".rtf", ".docm", ".dotx", ".dotm") || isDocMime(m)) {
            boolean v = HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_OPEN_DOCUMENT, false);
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=DOC sw=" + v); return v;
        }
        // Minor-1: 补 .xlsb/.xlsm/.xlt(.csv 已在列表)。
        if (isExt(n, ".xls", ".xlsx", ".csv", ".ods", ".xlsb", ".xlsm", ".xlt") || isSheetMime(m)) {
            boolean v = HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_OPEN_SPREADSHEET, false);
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=SHEET sw=" + v); return v;
        }
        // Minor-1: 补 .pptm/.pps/.ppsx/.pot。
        if (isExt(n, ".ppt", ".pptx", ".odp", ".pptm", ".pps", ".ppsx", ".pot") || isPptMime(m)) {
            boolean v = HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_OPEN_PRESENTATION, false);
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=PPT sw=" + v); return v;
        }
        if (m.startsWith("text/") || isExt(n, ".txt", ".md", ".log")) {
            boolean v = HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_OPEN_TEXT, false);
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=TEXT sw=" + v); return v;
        }
        if (isExt(n, ".epub", ".mobi") || isEbookMime(m)) {
            boolean v = HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_OPEN_EBOOK, false);
            XposedBridge.log(HookEntry.TAG + ": [diag]   branch=EBOOK sw=" + v); return v;
        }
        XposedBridge.log(HookEntry.TAG + ": [diag]   branch=NO-MATCH (n=[" + n + "] m=[" + m + "])");
        return false;
    }

    /** v2.3.20: 规范化后的自定义后缀列表(去点/小写/去空白 token)。 */
    private static java.util.List<String> customList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String raw = HookEntry.readPrefString(HookEntry.KEY_AUTO_OPEN_CUSTOM, "");
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String tok : raw.split(",")) {
            String e = normalizeExt(tok);
            if (!e.isEmpty()) out.add(e);
        }
        return out;
    }

    /** v2.3.14: 名称是否命中用户自定义后缀列表(键 auto_open_custom, 逗号分隔)。
     *  规范化: 去首尾空白/去前导点/转小写/去空串。列表为空或无有效 token → 不命中
     *  (防误匹配全部文件)。 */
    private static boolean matchesCustomExt(String lowerName) {
        if (lowerName == null || lowerName.isEmpty()) return false;
        for (String e : customList()) {
            if (lowerName.endsWith("." + e)) return true;
        }
        return false;
    }

    /** 规范化单个后缀 token: 去空白、去前导点、转小写。
     *  仅允许字母/数字/'.'/'+'/'-'/'_'(支持 tar.gz 这类多段后缀), 含其它字符(含空格/斜杠)
     *  或去点后为空 → 返回 ""(视为无效, 不参与匹配)。 */
    public static String normalizeExt(String tok) {
        if (tok == null) return "";
        String e = tok.trim().toLowerCase();
        while (e.startsWith(".")) e = e.substring(1);
        while (e.endsWith(".")) e = e.substring(0, e.length() - 1);
        if (e.isEmpty()) return "";
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '+' || c == '-' || c == '_';
            if (!ok) return "";
        }
        return e;
    }

    private static boolean isExt(String name, String... exts) {
        for (String e : exts) {
            if (name.endsWith(e)) return true;
        }
        return false;
    }

    /**
     * RC3(c) 修复: archive 分支的 MIME 兜底。与 image/video/audio/pdf 分支的
     * m.startsWith(...)/m.contains(...) 兜底对称: 若完成回调传入的 name 不含扩展名,
     * 但真实 MIME 是压缩包类型, 仍应识别为 archive。
     * 仅用明确的压缩包 MIME, 不包含 octet-stream(避免把非压缩包误判)。
     * 传入的 m 已在 computeShouldAutoOpen 内 toLowerCase。
     */
    private static boolean isArchiveMime(String m) {
        if (m == null || m.isEmpty()) return false;
        // R2-M2: epub 的 MIME 是 application/epub+zip, 含子串 "zip" 会被本分支(早于 EBOOK)抢先
        //   命中 → 新增的 isEbookMime("epub+zip") 永远到不了。epub 语义是电子书非压缩包,
        //   显式排除, 使其落到后面的 EBOOK 分支。
        if (m.contains("epub")) return false;
        return m.contains("zip")                 // application/zip, application/x-zip-compressed
                || m.contains("x-7z-compressed") // application/x-7z-compressed
                || m.contains("rar")             // application/x-rar-compressed, application/vnd.rar
                || m.contains("x-tar")           // application/x-tar
                || m.contains("gzip")            // application/gzip, application/x-gzip
                || m.contains("x-bzip2")         // application/x-bzip2
                || m.contains("x-xz")            // application/x-xz
                || m.contains("x-compress");     // application/x-compress(ed)
    }

    /** R2-M2②: DOC/SHEET/PPT/EBOOK 四分支的 MIME 兜底 —— 与 isArchiveMime 同范式。
     *  背景: Chrome 从 Content-Type 得知具体 MIME 时, hasDecidableIdentity(R2-M2① 修复后)仍会
     *  因有 uri 无名而触发补名; 但若 ContentResolver 也读不到 DISPLAY_NAME(name 仍空), 仅靠
     *  isExt(n,…) 会 NO-MATCH。补 MIME 兜底后 docx/xlsx/odt/rtf/csv/ods/pptx/odp/epub/mobi
     *  即使无名也能凭具体 MIME 命中(m3u8 归 VIDEO 分支的 mpegurl 兜底, 不在此四分支)。
     *  传入的 m 已在 computeShouldAutoOpen 内 toLowerCase。
     *  注意: 只用明确的文档 MIME, 不含 octet-stream(避免把非文档误判)。 */
    private static boolean isDocMime(String m) {
        if (m == null || m.isEmpty()) return false;
        return m.contains("msword")                       // application/msword
                || m.contains("ms-word")                  // Minor-1: application/vnd.ms-word.document.macroenabled.12
                || m.contains("wordprocessingml")         // application/vnd...wordprocessingml.document
                || m.contains("opendocument.text")        // application/vnd.oasis.opendocument.text
                || m.contains("rtf");                     // application/rtf, text/rtf
    }

    private static boolean isSheetMime(String m) {
        if (m == null || m.isEmpty()) return false;
        return m.contains("spreadsheetml")                // application/vnd...spreadsheetml.sheet
                || m.contains("ms-excel")                 // application/vnd.ms-excel
                || m.contains("opendocument.spreadsheet") // application/vnd.oasis.opendocument.spreadsheet
                || m.contains("text/csv");                // csv 也可能是 text/csv
    }

    private static boolean isPptMime(String m) {
        if (m == null || m.isEmpty()) return false;
        return m.contains("presentationml")               // application/vnd...presentationml.presentation
                || m.contains("ms-powerpoint")            // application/vnd.ms-powerpoint
                || m.contains("opendocument.presentation");// application/vnd.oasis.opendocument.presentation
    }

    private static boolean isEbookMime(String m) {
        if (m == null || m.isEmpty()) return false;
        return m.contains("epub+zip")                     // application/epub+zip
                || m.contains("x-mobipocket-ebook");      // application/x-mobipocket-ebook
    }
}
