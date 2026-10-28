// ChroStar v2.3.14 — 自动打开类型后缀说明(单一数据源)
// UI(MainActivity 弹窗标题)与逻辑(OverwriteAndAutoOpen 判定)共用本类常量,
// 避免界面文案与实际判定后缀漂移(旧版 Office 标题写 doc/xls/ppt 而实际含 docx/xlsb 等)。
// 纯常量, 无 Android 依赖, Chrome 145/152 通用。
package io.github.ylw6669.chrostar;

public final class AutoOpenTypes {

    private AutoOpenTypes() {}

    // 弹窗「后缀说明」显示串(与 computeShouldAutoOpen 各分支的实际判定保持一致)
    public static final String SUFFIX_PDF = "pdf";
    public static final String SUFFIX_ARCHIVE = "zip/7z/rar";
    public static final String SUFFIX_OFFICE = "doc/xls/ppt";
    public static final String SUFFIX_TEXT = "txt/md/log";
    public static final String SUFFIX_IMAGE = "png/jpg/gif/webp";
    public static final String SUFFIX_VIDEO = "mp4/mkv/webm";
    public static final String SUFFIX_AUDIO = "mp3/flac/ogg/m4a";
    public static final String SUFFIX_EBOOK = "epub/mobi";
}
