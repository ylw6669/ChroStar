package io.github.ylw6669.chrostar;

import java.lang.reflect.Method;
import java.lang.reflect.Field;

/** Chrome 153.0.8010.47 的独立指纹与布局定义。 */
public final class Chrome153 {
    private static final String OFFLINE_ITEM =
            "org.chromium.components.offline_items_collection.OfflineItem";
    private static final String GURL = "org.chromium.url.GURL";

    private Chrome153() {}

    /**
     * 153 的稳定组合指纹：
     *  - OfflineItem 保留 q0:int 完成状态；
     *  - sa8.d(OfflineItem,boolean,boolean,boolean) 下载完成消息；
     *  - n6c.b(boolean,boolean)->GURL 主页 getter；
     *  - DangerousDownloadDialogBridge.showDialog 为 7 参数。
     * 这些条件与 152 的 ia8/w5c 组合区分，避免三版本串路由。
     */
    public static boolean matches(ClassLoader cl) {
        try {
            Class<?> item = Class.forName(OFFLINE_ITEM, false, cl);
            Field state = item.getDeclaredField("q0");
            if (state.getType() != int.class) return false;
            Class<?> msg = Class.forName("sa8", false, cl);
            if (!hasMessageMethod(msg, item)) return false;

            Class<?> home = Class.forName("n6c", false, cl);
            Class<?> gurl = Class.forName(GURL, false, cl);
            boolean homeOk = false;
            for (Method m : home.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("b") && p.length == 2
                        && p[0] == boolean.class && p[1] == boolean.class
                        && m.getReturnType() == gurl) {
                    homeOk = true;
                    break;
                }
            }
            if (!homeOk) return false;

            Class<?> window = Class.forName(
                    "org.chromium.ui.base.WindowAndroid", false, cl);
            Class<?> danger = Class.forName(
                    "org.chromium.chrome.browser.download.DangerousDownloadDialogBridge",
                    false, cl);
            for (Method m : danger.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("showDialog") && p.length == 7
                        && p[0] == window && p[1] == String.class
                        && p[2] == String.class && p[3] == long.class
                        && p[4] == String.class && p[5] == int.class
                        && p[6] == boolean.class) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasMessageMethod(Class<?> msg, Class<?> item) {
        for (Method m : msg.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("d") && p.length == 4
                    && p[0] == item && p[1] == boolean.class
                    && p[2] == boolean.class && p[3] == boolean.class
                    && m.getReturnType() == void.class) {
                return true;
            }
        }
        return false;
    }
}
