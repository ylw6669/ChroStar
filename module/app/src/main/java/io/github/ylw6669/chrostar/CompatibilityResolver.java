// ChroStar v2.3.0 — 兼容性解析器(务实版)
// 职责:
//   1) 引擎版本判定结果的语义化描述(145/152/unknown)
//   2) unknown 版本的能力降级路由: 哪些 hook 仍可用(公开类), 哪些缺失(混淆类)
//   3) 诊断 JSON 输出(供 LocationAndDiagnostics)
// 设计原则: 绝不误 hook; 失效能力明确报告而非静默。
package io.github.ylw6669.chrostar;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CompatibilityResolver {

    /** 能力 -> 所需符号类型(公开类=全自动可用, 短名=需符号表) */
    public enum Cap {
        CLEAN_START("启动直开主页(防恢复)", true),      // 145: oo4 / 152+: 公开 CommandLine
        TAB_MEMORY("Tab 模型记忆", true),              // TabModelJniBridge 公开
        ON_START("启动时序", true),                    // ChromeTabbedActivity 公开
        NTP_OPEN("+号开主页", false),                  // 145 l04 / 152 iq4 (短名)
        HOME_URL("主页 URL 解析", false),              // 145 jza / 152 w5c
        DOWNLOAD_BYPASS("下载弹窗绕过", true),          // 六桥公开类(枚举 id 需版本表, 默认值兜底)
        AUTO_INSTALL("下载完成自动打开(APK/扩展类型)", true), // 145 je7+c9o+zkg / 152 ia8+ud4; unknown: DownloadController.onDownloadCompleted 公开类兜底(部分可用)
        BANNER_TOAST("横幅转 Toast", false),           // 145 nze / 152 gig
        TRANSLATE_HIDE("翻译横幅隐藏", true),           // TranslateMessage 公开
        CLOSE_ALL("退出清空标签", false);              // 145 id4 / 152 q35+直接遍历(152 已绕过)

        public final String label;
        public final boolean worksOnUnknown;

        Cap(String label, boolean worksOnUnknown) {
            this.label = label;
            this.worksOnUnknown = worksOnUnknown;
        }
    }

    private CompatibilityResolver() {}

    /** unknown 引擎时可用的能力集合(公开类路径) */
    public static boolean worksOnUnknown(Cap cap) {
        return cap.worksOnUnknown;
    }

    /** 诊断: 引擎版本 -> 各能力状态 */
    public static Map<String, String> capabilityReport(String engineVersion) {
        Map<String, String> out = new LinkedHashMap<>();
        boolean symbolic = "chrome_symbolic".equals(engineVersion);
        boolean unknown = !symbolic && !"chrome145".equals(engineVersion)
                && !"chrome152".equals(engineVersion)
                && !"chrome153".equals(engineVersion);
        for (Cap cap : Cap.values()) {
            String state;
            if (symbolic) {
                state = symbolicState(cap);
            } else if (!unknown) {
                state = "OK (symbol table: " + engineVersion + ")";
            } else if (cap.worksOnUnknown) {
                state = "PARTIAL (public-class path only)";
            } else {
                state = "UNAVAILABLE (needs symbol table for this Chrome version)";
            }
            out.put(cap.label, state);
        }
        return out;
    }

    private static String symbolicState(Cap cap) {
        switch (cap) {
            case NTP_OPEN:
                return ChromeSymbols.hasTabCreator()
                        ? "OK (runtime structural resolver)"
                        : "PARTIAL (activity launch path only)";
            case HOME_URL:
                return ChromeSymbols.hasHomepageManager()
                        ? "OK (runtime structural resolver)"
                        : "PARTIAL (Chrome preferences fallback)";
            case AUTO_INSTALL:
                return ChromeSymbols.hasDownloadMessage()
                        ? "OK (structural + public callback)"
                        : "PARTIAL (public callback only)";
            case BANNER_TOAST:
                if (ChromeSymbols.hasDownloadMessage()
                        && ChromeSymbols.hasMessageDispatcher()) {
                    return "OK (runtime structural resolver)";
                }
                return "PARTIAL (public completion callback only)";
            default:
                return "OK (public/structural path)";
        }
    }

    /** JSON 输出(供 LocationAndDiagnostics.exportDiagnostics) */
    public static String diagnosticsJson(String engineVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("    \"engine\": \"").append(engineVersion).append("\",\n");
        sb.append("    \"capabilities\": {\n");
        Map<String, String> rep = capabilityReport(engineVersion);
        int i = 0;
        int n = rep.size();
        for (Map.Entry<String, String> e : rep.entrySet()) {
            sb.append("      \"").append(e.getKey()).append("\": \"")
              .append(e.getValue()).append('"');
            if (++i < n) sb.append(',');
            sb.append('\n');
        }
        sb.append("    }");
        return sb.toString();
    }
}
