package io.github.ylw6669.chrostar;

/**
 * v2.5.0: 版本行为特征表 —— 所有"行为差异"的唯一集中声明处。
 *
 * <p><b>为什么需要它</b>: 此前行为差异散落在各功能类里, 以
 * {@code "chrome145".equals(HookEntry.engineVersion)} 形式重复出现, 并夹杂按版本嵌套的
 * 三元表达式(例如下载弹窗原生 ID)。改动一个版本极易踩到另一个版本。现在: 解析出的
 * 引擎标识 → 一张特征表 → 各功能类只读特征, 不再自行比较版本字符串。
 *
 * <p><b>与"符号解析"的区别</b>: 符号(类名/方法名)由 ChromeSymbols + DexKitResolver + 缓存
 * 动态解析; 本表只承载**语义行为**差异(某版本的平台行为不同, 无法靠符号推导)。
 */
public final class ProfileTraits {

    /** 引擎标识(与 HookEntry.engineVersion 同值域) */
    public final String engine;

    /** 下载横幅: 该版本使用 2.0 的"持久一次性标志"语义(横幅回调可能远晚于完成事件)。 */
    public final boolean legacyBannerOneShot;

    /** 主页: 该版本走 2.0 的旧路径(仅 l04.l 单向改写, 不安装冷启动主动建页)。 */
    public final boolean legacyHomePath;

    /** 主页: 该版本支持"复用原生恢复快照"的快速恢复路径(153+)。 */
    public final boolean fastHomeRestore;

    /** 横幅: 该版本安装"公开完成回调 + MessageContainer 内容级"兜底(153+ 需要, 152 不需要)。 */
    public final boolean inlineBannerFallback;

    /** 下载弹窗原生事件 ID 兜底表(DexKit/已知版本表都解析不到时使用)。 */
    public final int idDangerous;
    public final int idInsecure;
    public final int idDuplicate;
    public final int idPolicy;
    public final int idOpen;
    public final int idLocation;

    private ProfileTraits(String engine, boolean legacyBannerOneShot, boolean legacyHomePath,
                          boolean fastHomeRestore, boolean inlineBannerFallback, int idDangerous, int idInsecure,
                          int idDuplicate, int idPolicy, int idOpen, int idLocation) {
        this.engine = engine;
        this.legacyBannerOneShot = legacyBannerOneShot;
        this.legacyHomePath = legacyHomePath;
        this.fastHomeRestore = fastHomeRestore;
        this.inlineBannerFallback = inlineBannerFallback;
        this.idDangerous = idDangerous;
        this.idInsecure = idInsecure;
        this.idDuplicate = idDuplicate;
        this.idPolicy = idPolicy;
        this.idOpen = idOpen;
        this.idLocation = idLocation;
    }

    // 145: 2.0 旧路径(横幅一次性标志 + 主页只走 l04.l), 弹窗 ID 取 145 校准值。
    private static final ProfileTraits CHROME_145 = new ProfileTraits(
            "chrome145", true, true, false, false, 124, 3, 2, 129, 15, 14);

    // 152: 现代横幅窗口 + 单向 URL 改写; 弹窗 ID 取 152 校准值。
    private static final ProfileTraits CHROME_152 = new ProfileTraits(
            "chrome152", false, false, false, false, 43, 2, 1, 47, 9, 14);

    // 153: 同 152 行为 + 快速主页恢复; 弹窗 ID 取 153 校准值。
    private static final ProfileTraits CHROME_153 = new ProfileTraits(
            "chrome153", false, false, true, true, 45, 2, 1, 49, 9, 14);

    // symbolic(154+): 行为同 153。弹窗 ID 不再在这里设置兜底值：
    // 154/155 由 DexNativeIdResolver 的版本隔离表提供；156+ 若无法验证则 fail-closed，
    // 保留 Chrome 原生弹窗，避免再次把 cancel ID 当作 allow 调用。
    private static final ProfileTraits SYMBOLIC = new ProfileTraits(
            "chrome_symbolic", false, false, true, true, -1, -1, -1, -1, -1, -1);

    // unknown: 全部按最保守的现代行为, 弹窗 ID 缺省 -1(调用方据此保留原弹窗)。
    private static final ProfileTraits UNKNOWN = new ProfileTraits(
            "unknown", false, false, false, false, -1, -1, -1, -1, -1, -1);

    /** 引擎标识 → 特征表(未知输入返回 UNKNOWN, 绝不抛)。 */
    public static ProfileTraits of(String engine) {
        if (engine == null) return UNKNOWN;
        switch (engine) {
            case "chrome145": return CHROME_145;
            case "chrome152": return CHROME_152;
            case "chrome153": return CHROME_153;
            case "chrome_symbolic": return SYMBOLIC;
            default: return UNKNOWN;
        }
    }

    /** 当前进程引擎的特征表。 */
    public static ProfileTraits current() {
        return of(HookEntry.engineVersion);
    }

    /** 按槽位名取弹窗原生 ID 兜底值(-1 = 无兜底, 调用方应保留 Chrome 原生弹窗)。 */
    public int dialogIdFallback(String key) {
        if (key == null) return -1;
        switch (key) {
            case "dangerous": return idDangerous;
            case "insecure": return idInsecure;
            case "duplicate": return idDuplicate;
            case "policy": return idPolicy;
            case "open": return idOpen;
            case "location": return idLocation;
            default: return -1;
        }
    }
}
