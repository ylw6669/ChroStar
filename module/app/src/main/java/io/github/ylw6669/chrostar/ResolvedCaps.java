package io.github.ylw6669.chrostar;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * v2.5.0: 解析结果载体 —— "槽位 → 取值 + 来源标记", 外加缺失集合。
 *
 * <p>来源标记回答的是运维问题: <b>这个符号是怎么来的</b> ——
 * {@link #SRC_CACHE}(磁盘缓存命中) / {@link #SRC_ANCHOR}(公开锚点结构推导) /
 * {@link #SRC_DEXKIT}(DexKit 特征查询) / {@link #SRC_KNOWN}(已知版本表) /
 * {@link #SRC_FALLBACK}(兜底)。日志、诊断导出与设置页状态都读它。
 *
 * <p>缺失槽位不静默: {@link #missingIds()} 会连同 {@link #impactSummary()} 一起写进
 * 日志, 明确列出"本会话哪些功能不可用", 而不是让功能悄悄失效。
 */
public final class ResolvedCaps {

    public static final String SRC_CACHE = "CACHE";
    public static final String SRC_ANCHOR = "ANCHOR";
    public static final String SRC_DEXKIT = "DEXKIT";
    public static final String SRC_KNOWN = "KNOWN";
    public static final String SRC_FALLBACK = "FALLBACK";

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Set<String> missing = new LinkedHashSet<>();

    /** 记录一个已解析槽位。value 为空视为未解析(不记录)。 */
    public void put(Capability cap, String value, String source) {
        if (cap == null || value == null || value.isEmpty()) return;
        values.put(cap.id, value);
        sources.put(cap.id, source == null ? SRC_FALLBACK : source);
        missing.remove(cap.id);
    }

    /** 标记槽位解析失败(所有来源都没命中)。 */
    public void markMissing(Capability cap) {
        if (cap == null) return;
        missing.add(cap.id);
    }

    public String value(Capability cap) {
        return cap == null ? null : values.get(cap.id);
    }

    public String sourceOf(Capability cap) {
        return cap == null ? null : sources.get(cap.id);
    }

    public Set<String> missingIds() {
        return Collections.unmodifiableSet(missing);
    }

    public Map<String, String> values() {
        return Collections.unmodifiableMap(values);
    }

    public Map<String, String> sources() {
        return Collections.unmodifiableMap(sources);
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** 紧凑来源摘要, 形如 {@code homepage_manager=ANCHOR,tab_creator=CACHE}。 */
    public String sourceSummary() {
        if (sources.isEmpty()) return "<none>";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 缺失槽位对应的功能影响(降级日志/状态回传用)。 */
    public String impactSummary() {
        if (missing.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String id : missing) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(id).append(" -> ").append(impactOf(id));
        }
        return sb.toString();
    }

    /** 槽位缺失时受影响的功能(用于明确报告, 不改变任何执行路径)。 */
    public static String impactOf(String slotId) {
        if (slotId == null) return "unknown";
        switch (slotId) {
            case "homepage_manager": return "启动/加号 主页 URL 读取";
            case "tab_creator": return "启动/加号 建页改写";
            case "tab_creator_central_create": return "中心建页入口定位";
            case "tab_selector": return "标签模型定位";
            case "download_message": return "下载完成自动打开 + 横幅转 Toast";
            case "message_dispatcher": return "消息横幅拦截";
            case "translate_key": return "翻译横幅消息 ID 冗余保险";
            default: return "unknown";
        }
    }
}
