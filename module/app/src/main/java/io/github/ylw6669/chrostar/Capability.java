package io.github.ylw6669.chrostar;

/**
 * v2.5.0: 能力槽位定义 —— 模块需要的全部"版本相关符号"清单。
 *
 * <p>每个槽位对应 chrome 内部一个被 R8 改名/改结构的符号。解析管线
 * (见 {@link ChromeSymbols} / {@link DexKitResolver}) 按槽位逐个解析:
 * 命中即用, 缺失则 fail-closed 并跳过依赖它的功能, 不影响其它槽位。
 *
 * <p>{@link #critical} = 该槽位缺失时模块核心功能(主页/建页)不可用,
 * 需要显式在日志与诊断里报告, 而不是静默降级。
 */
public enum Capability {

    /** 主页 URL 读取器(PartnerBrowserCustomizations 内的 manager 类) */
    HOMEPAGE_MANAGER("homepage_manager", true),
    /** 标签页创建器(ChromeTabCreator 类) */
    TAB_CREATOR("tab_creator", true),
    /** 中心建页入口方法名(七参, 第三个参数为 TabLaunchType) */
    TAB_CREATOR_CENTRAL_CREATE("tab_creator_central_create", false),
    /** Tab 选择器(boolean -> TabModel) */
    TAB_SELECTOR("tab_selector", false),
    /** 下载完成消息类(OfflineItem,boolean,boolean,boolean -> void) */
    DOWNLOAD_MESSAGE("download_message", false),
    /** 消息横幅分发器(PropertyModel,WebContents,int,boolean) */
    MESSAGE_DISPATCHER("message_dispatcher", false),
    /** 翻译消息 ID 键类(冗余保险, 缺失不影响翻译横幅主通道) */
    TRANSLATE_KEY("translate_key", false);

    /** 缓存/日志用的稳定短名(勿改, 缓存按此键索引) */
    public final String id;
    /** 缺失时是否影响核心功能(主页/建页) */
    public final boolean critical;

    Capability(String id, boolean critical) {
        this.id = id;
        this.critical = critical;
    }

    public static Capability byId(String id) {
        if (id == null) return null;
        for (Capability c : values()) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }
}
