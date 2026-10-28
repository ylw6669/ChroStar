package io.github.ylw6669.chrostar;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the small JNI event constants used by Chrome's public download bridges.
 *
 * R8 changes Java names between builds, but the bridge classes are retained and
 * their generated call to J.N.VJO/VJJZ/VJOZ contains the native event id.  This
 * parser is deliberately narrow: an id is returned only when the target bridge
 * contains exactly one unambiguous call to the requested native method.
 */
final class DexNativeIdResolver {
    private static final String JNI_OWNER = "LJ/N;";
    private static final String CLS_DANGEROUS =
            "org.chromium.chrome.browser.download.DangerousDownloadDialogBridge";
    private static final String CLS_INSECURE =
            "org.chromium.chrome.browser.download.InsecureDownloadDialogBridge";
    private static final String CLS_DUPLICATE =
            "org.chromium.chrome.browser.download.DuplicateDownloadDialogBridge";
    private static final String CLS_POLICY =
            "org.chromium.chrome.browser.download.PolicyWarningDownloadDialogBridge";
    private static final String CLS_LOCATION =
            "org.chromium.chrome.browser.download.DownloadDialogBridge";
    private static final String CLS_OPEN =
            "org.chromium.chrome.browser.download.OpenDownloadDialogBridge";

    private DexNativeIdResolver() {}

    static Map<String, Integer> resolveDownloadDialogIds(ClassLoader cl) {
        try {
            Context context = HookEntry.getAppContext(cl);
            if (context == null) return emptyIds();

            // 版本档案优先，且不再让字节码扫描覆盖已验证结果。
            // 145/152 等桥类的“无 Activity”兜底调用 ID 常常正好是取消 ID，
            // 例如 145 Dangerous: allow=124, cancel=125；直接把 125 当 allow 会破坏
            // Chromium 原生下载状态并触发 SIGTRAP。
            Map<String, Integer> known =
                    knownAllowIds(ProfilePrefs.installedMajor(context, "com.android.chrome"));
            if (known != null) return known;

            Map<String, Integer> out = emptyIds();
            Set<String> apkPaths = new LinkedHashSet<>();
            String base = context.getApplicationInfo().sourceDir;
            if (base != null) apkPaths.add(base);
            String[] splits = context.getApplicationInfo().splitSourceDirs;
            if (splits != null) {
                for (String split : splits) if (split != null) apkPaths.add(split);
            }
            for (String path : apkPaths) {
                scanApk(path, out);
            }
            // 未知版本不能安全推断 VJO 的 allow/cancel 方向：
            // 桥类内部通常只保留 cancel/error 兜底 ID。危险与策略弹窗 fail-closed，
            // 宁可保留 Chrome 原生弹窗，也不能误调用 native cancel。
            out.put("dangerous", -1);
            out.put("policy", -1);
            return out;
        } catch (Throwable ignored) {
            return emptyIds();
        }
    }

    private static Map<String, Integer> emptyIds() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("dangerous", -1);
        out.put("insecure", -1);
        out.put("duplicate", -1);
        out.put("policy", -1);
        out.put("location", -1);
        out.put("open", -1);
        return out;
    }

    /**
     * 已验证版本的 allow 回调 ID。此表是版本隔离边界，优先于任何动态扫描。
     */
    private static Map<String, Integer> knownAllowIds(String major) {
        if (major == null) return null;
        Map<String, Integer> out = emptyIds();
        switch (major) {
            case "145":
                put(out, 124, 3, 2, 129, 14, 15);
                return out;
            case "152":
                put(out, 43, 2, 1, 47, 14, 9);
                return out;
            case "153":
                put(out, 45, 2, 1, 49, 14, 9);
                return out;
            case "154":
            case "155":
                put(out, 46, 2, 1, 50, 8, 9);
                return out;
            default:
                return null;
        }
    }

    private static void put(Map<String, Integer> out, int dangerous, int insecure,
                            int duplicate, int policy, int location, int open) {
        out.put("dangerous", dangerous);
        out.put("insecure", insecure);
        out.put("duplicate", duplicate);
        out.put("policy", policy);
        out.put("location", location);
        out.put("open", open);
    }

    private static void scanApk(String apkPath, Map<String, Integer> out) {
        if (apkPath == null || apkPath.isEmpty()) return;
        try (ZipFile zip = new ZipFile(apkPath)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.matches("classes(\\d+)?\\.dex")) continue;
                byte[] dex = readAll(zip.getInputStream(entry));
                DexFileView view = new DexFileView(dex);
                if (!view.valid()) continue;
                resolveOne(view, CLS_DANGEROUS, "VJO", "dangerous", out);
                resolveOne(view, CLS_INSECURE, "VJJZ", "insecure", out);
                resolveOne(view, CLS_DUPLICATE, "VJJZ", "duplicate", out);
                resolveOne(view, CLS_POLICY, "VJO", "policy", out);
                resolveOne(view, CLS_LOCATION, "VJOZ", "location", out);
                resolveOne(view, CLS_OPEN, "VJOZ", "open", out);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void resolveOne(DexFileView view, String className, String nativeName,
                                   String key, Map<String, Integer> out) {
        Integer value = view.findUniqueNativeId(className, nativeName);
        if (value != null) {
            Integer old = out.get(key);
            if (old == null || old.intValue() == -1) {
                out.put(key, value);
            } else if (old.intValue() >= 0 && old.intValue() != value.intValue()) {
                out.put(key, Integer.valueOf(-2));
            }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static final class DexFileView {
        private final byte[] data;
        private final int stringIdsSize;
        private final int stringIdsOff;
        private final int typeIdsSize;
        private final int typeIdsOff;
        private final int methodIdsSize;
        private final int methodIdsOff;
        private final int classDefsSize;
        private final int classDefsOff;

        DexFileView(byte[] data) {
            this.data = data;
            if (data == null || data.length < 112 || data[0] != 'd' || data[1] != 'e'
                    || data[2] != 'x' || data[3] != '\n') {
                stringIdsSize = stringIdsOff = typeIdsSize = typeIdsOff = methodIdsSize =
                        methodIdsOff = classDefsSize = classDefsOff = -1;
                return;
            }
            stringIdsSize = u32(56);
            stringIdsOff = u32(60);
            typeIdsSize = u32(64);
            typeIdsOff = u32(68);
            methodIdsSize = u32(88);
            methodIdsOff = u32(92);
            classDefsSize = u32(96);
            classDefsOff = u32(100);
        }

        boolean valid() {
            return stringIdsSize >= 0 && inRange(stringIdsOff, stringIdsSize, 4)
                    && inRange(typeIdsOff, typeIdsSize, 4)
                    && inRange(methodIdsOff, methodIdsSize, 8)
                    && inRange(classDefsOff, classDefsSize, 32);
        }

        Integer findUniqueNativeId(String className, String nativeName) {
            if (!valid()) return null;
            String descriptor = "L" + className.replace('.', '/') + ";";
            int classIndex = -1;
            for (int i = 0; i < typeIdsSize; i++) {
                if (descriptor.equals(typeDescriptor(i))) {
                    classIndex = i;
                    break;
                }
            }
            if (classIndex < 0) return null;
            Set<Integer> ids = new LinkedHashSet<>();
            for (int i = 0; i < classDefsSize; i++) {
                int off = classDefsOff + i * 32;
                if (u32(off) != classIndex) continue;
                int classDataOff = u32(off + 24);
                scanClassData(classDataOff, nativeName, ids);
            }
            return ids.size() == 1 ? ids.iterator().next() : null;
        }

        private void scanClassData(int off, String nativeName, Set<Integer> ids) {
            if (!inRange(off, 1, 1)) return;
            int[] cursor = {off};
            int staticCount = uleb(cursor);
            int instanceCount = uleb(cursor);
            int directCount = uleb(cursor);
            int virtualCount = uleb(cursor);
            for (int i = 0; i < staticCount + instanceCount; i++) {
                uleb(cursor); // field index diff
                uleb(cursor); // access flags
            }
            scanMethods(cursor, directCount, nativeName, ids);
            scanMethods(cursor, virtualCount, nativeName, ids);
        }

        private void scanMethods(int[] cursor, int count, String nativeName, Set<Integer> ids) {
            int methodIndex = 0;
            for (int i = 0; i < count; i++) {
                methodIndex += uleb(cursor);
                uleb(cursor); // access flags
                int codeOff = uleb(cursor);
                if (methodIndex < 0 || methodIndex >= methodIdsSize) continue;
                if (codeOff == 0) continue;
                inspectCode(codeOff, nativeName, ids);
            }
        }

        private void inspectCode(int codeOff, String nativeName, Set<Integer> ids) {
            if (!inRange(codeOff, 16, 1)) return;
            int insnsSize = u32(codeOff + 12);
            int insnsOff = codeOff + 16;
            if (!inRange(insnsOff, insnsSize, 2)) return;
            for (int i = 0; i + 2 < insnsSize; i++) {
                int unit = u16(insnsOff + i * 2);
                int opcode = unit & 0xff;
                if (opcode != 0x71 && opcode != 0x77) continue; // invoke-static / range
                int methodIndex = u16(insnsOff + (i + 1) * 2);
                if (methodIndex < 0 || methodIndex >= methodIdsSize) continue;
                if (!JNI_OWNER.equals(methodOwner(methodIndex))
                        || !nativeName.equals(methodName(methodIndex))) continue;
                int register = opcode == 0x71
                        ? (u16(insnsOff + (i + 2) * 2) & 0x0f)
                        : u16(insnsOff + (i + 2) * 2);
                Integer id = findRecentConst(insnsOff, i, register);
                if (id != null && id.intValue() >= 0 && id.intValue() <= 4096) {
                    ids.add(id);
                }
            }
        }

        private Integer findRecentConst(int insnsOff, int invokeIndex, int register) {
            int first = Math.max(0, invokeIndex - 32);
            int tracked = register;
            for (int i = invokeIndex - 1; i >= first; i--) {
                int unit = u16(insnsOff + i * 2);
                int opcode = unit & 0xff;
                if (opcode == 0x12) { // const/4 vA, #+B
                    int reg = (unit >>> 8) & 0x0f;
                    if (reg == tracked) return Integer.valueOf((unit << 16) >> 28);
                } else if (opcode == 0x13 && i + 1 < invokeIndex) { // const/16
                    int reg = (unit >>> 8) & 0xff;
                    if (reg == tracked) return Integer.valueOf((short) u16(insnsOff + (i + 1) * 2));
                } else if (opcode == 0x14 && i + 2 < invokeIndex) { // const
                    int reg = (unit >>> 8) & 0xff;
                    if (reg == tracked) return Integer.valueOf(u32(insnsOff + (i + 1) * 2));
                } else if (opcode == 0x15 && i + 1 < invokeIndex) { // const/high16
                    int reg = (unit >>> 8) & 0xff;
                    if (reg == tracked) return Integer.valueOf((short) u16(insnsOff + (i + 1) * 2) << 16);
                } else if (opcode == 0x01) { // move vA, vB
                    int dest = (unit >>> 8) & 0x0f;
                    int src = (unit >>> 12) & 0x0f;
                    if (dest == tracked) tracked = src;
                } else if ((opcode == 0x02 || opcode == 0x05 || opcode == 0x08)
                        && i + 1 < invokeIndex) { // move/from16 variants
                    int dest = (unit >>> 8) & 0xff;
                    if (dest == tracked) tracked = u16(insnsOff + (i + 1) * 2);
                } else if ((opcode == 0x03 || opcode == 0x06 || opcode == 0x09)
                        && i + 2 < invokeIndex) { // move/16 variants
                    int dest = u16(insnsOff + (i + 1) * 2);
                    if (dest == tracked) tracked = u16(insnsOff + (i + 2) * 2);
                }
            }
            return null;
        }

        private String methodOwner(int methodIndex) {
            return typeDescriptor(u16(methodIdsOff + methodIndex * 8));
        }

        private String methodName(int methodIndex) {
            return string(u32(methodIdsOff + methodIndex * 8 + 4));
        }

        private String typeDescriptor(int typeIndex) {
            if (typeIndex < 0 || typeIndex >= typeIdsSize) return null;
            return string(u32(typeIdsOff + typeIndex * 4));
        }

        private String string(int index) {
            if (index < 0 || index >= stringIdsSize) return null;
            int off = u32(stringIdsOff + index * 4);
            if (!inRange(off, 1, 1)) return null;
            int[] cursor = {off};
            uleb(cursor); // UTF-16 length
            int start = cursor[0];
            int end = start;
            while (end < data.length && data[end] != 0) end++;
            return new String(data, start, Math.max(0, end - start), java.nio.charset.StandardCharsets.UTF_8);
        }

        private int uleb(int[] cursor) {
            int result = 0;
            int shift = 0;
            while (cursor[0] < data.length && shift < 35) {
                int b = data[cursor[0]++] & 0xff;
                result |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return result;
        }

        private int u16(int off) {
            if (off < 0 || off + 1 >= data.length) return 0;
            return (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8);
        }

        private int u32(int off) {
            if (off < 0 || off + 3 >= data.length) return 0;
            return (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8)
                    | ((data[off + 2] & 0xff) << 16) | ((data[off + 3] & 0xff) << 24);
        }

        private boolean inRange(int off, int count, int unit) {
            if (off < 0 || count < 0 || unit < 1) return false;
            long end = (long) off + (long) count * unit;
            return end <= data.length;
        }
    }
}
