package io.github.ylw6669.chrostar

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.ThemeController
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment

/** v2.3.14: 规范化用户输入的后缀列表。按逗号分隔, 每个 token 去空白/去前导点/转小写,
 *  仅保留 [a-z0-9.+_-] 字符的合法后缀, 去重后以逗号连接。全空/无合法 token → 返回空串
 *  (即不启用自定义后缀, 防误匹配全部文件)。 */
private fun normalizeExtList(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val out = LinkedHashSet<String>()
    for (tok in raw.split(',')) {
        var e = tok.trim().lowercase()
        while (e.startsWith(".")) e = e.substring(1)
        while (e.endsWith(".")) e = e.substring(0, e.length - 1)
        if (e.isEmpty()) continue
        var ok = true
        for (c in e) {
            if (!(c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '+' || c == '-' || c == '_')) {
                ok = false; break
            }
        }
        if (ok) out.add(e)
    }
    return out.joinToString(",")
}

/** v2.3.14: 需求1 —— 单行显示时的预截断。miuix preference 的 title/summary 不接受
 *  overflow/maxLines(反编译实证, 无法在组件内部省略), 故对过长文案手动截断并加省略号,
 *  保证在 preference 行内保持单行。maxLen 按字符计, <=0 或未超长则原样返回。 */
private fun truncateOneLine(s: String, maxLen: Int): String {
    if (maxLen <= 0 || s.length <= maxLen) return s
    return s.substring(0, maxLen - 1) + "\u2026"
}

/** v2.3.16: 规范化自定义下载目录。去首尾空白与末尾多余斜杠; 空/仅斜杠 → 空串(不启用重定向)。 */
private fun normalizeDir(raw: String?): String {
    if (raw == null) return ""
    var d = raw.trim()
    while (d.length > 1 && d.endsWith("/")) d = d.substring(0, d.length - 1)
    return if (d == "/") "" else d
}

/** "隐藏桌面图标"偏好键 —— 仅设置页读写(hook 侧不读此配置) */
private const val KEY_HIDE_ICON = "hide_icon"

/** v2.5.0: 配置档案跟随的 Chrome 包名 */
private const val CHROME_PKG = "com.android.chrome"

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    /** v2.5.0: 当前编辑的配置档案 id(如 "v154"); null = 未识别到 Chrome 版本 */
    private var profile: String? = null

    /**
     * v2.5.1: 配置自检 —— 打印当前档案的生效值与关键开关状态。
     * 用于定位"适配 152/153/154 时把 145 老配置改坏"这类跨版本配置问题。
     */
    private fun logConfigAudit(profile: String) {
        try {
            val typeKeys = listOf(
                HookEntry.KEY_AUTO_OPEN_PDF, HookEntry.KEY_AUTO_OPEN_ARCHIVE,
                HookEntry.KEY_AUTO_OPEN_DOCUMENT, HookEntry.KEY_AUTO_OPEN_SPREADSHEET,
                HookEntry.KEY_AUTO_OPEN_PRESENTATION, HookEntry.KEY_AUTO_OPEN_TEXT,
                HookEntry.KEY_AUTO_OPEN_IMAGE, HookEntry.KEY_AUTO_OPEN_VIDEO,
                HookEntry.KEY_AUTO_OPEN_AUDIO, HookEntry.KEY_AUTO_OPEN_EBOOK
            )
            val onTypes = typeKeys.filter { ProfilePrefs.getBoolean(prefs, profile, it, false) }
            val extRaw = ProfilePrefs.resolveRaw(prefs, profile, HookEntry.KEY_AUTO_OPEN_EXT)
            android.util.Log.i(
                "HomeLauncherLSP",
                "[config-audit] profile=$profile extGateRaw=$extRaw onTypeSwitches=$onTypes " +
                    "downloadDir='" + ProfilePrefs.getString(prefs, profile, HookEntry.KEY_DOWNLOAD_DIR, "") + "' " +
                    "custom='" + ProfilePrefs.getString(prefs, profile, HookEntry.KEY_AUTO_OPEN_CUSTOM, "") + "'"
            )
        } catch (t: Throwable) {
            android.util.Log.w("HomeLauncherLSP", "[config-audit] failed -> $t")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v2.3.19: 模块侧以 MODE_WORLD_READABLE 打开配置 —— 这是 LSPosed New XSharedPreferences 生效的
        //   硬前提(框架仅此时把 safe-zone 文件置为可读)。优先开 WORLD_READABLE, 框架未启用时 PrefsStore 内部
        //   回退 MODE_PRIVATE。先做一次幂等迁移(旧私有目录 → 新位置), 不覆盖用户新值、不删旧值。
        PrefsStore.migrateLegacy(this)
        prefs = PrefsStore.open(this)
        ProfilePrefs.cleanupCopiedControlKeys(prefs)
        // v2.5.0: 配置按 Chrome 大版本隔离。自动跟随已安装的 Chrome 版本建档;
        //   首次遇到该版本时按 seed 链复制(最近使用档 -> 旧全局键 -> 默认值), 幂等。
        profile = ProfilePrefs.currentProfile(this, prefs, CHROME_PKG)
        profile?.let {
            ProfilePrefs.ensureSeeded(prefs, it)
            ProfilePrefs.markUsed(prefs, it)
            // v2.5.1: 修复"适配 153/154 时被改坏的旧配置" —— 145 时代配置没有扩展类型总开关,
            //   但类型子开关可能已打开。此处按意图补写显式值, 使设置页显示与实际生效一致。
            //   已有显式值的配置(153/154 等)不受影响。
            ProfilePrefs.repairExtGateIntent(prefs, it)
            // v2.5.1: 配置自检日志 —— 定位"适配新版本后旧版本配置被改写"类问题(adb logcat -s HomeLauncherLSP)
            logConfigAudit(it)
        }
        setContent {
            // [弹窗多选根治 v4] owner provider 提到 setContent 最顶层, 包住 MiuixTheme/Scaffold/SettingsScreen。
            // 反编译 miuix-ui 0.9.4-rc01 + navigationevent-compose 1.1.2 实证根因:
            //   OverlayDialog 仅把内容注册进 DialogState; 真正渲染在 MiuixPopupHost(ComposableSingletons$ScaffoldKt:177 → Scaffold.kt:87),
            //   它在 Scaffold 的 CompositionLocalProvider(ScaffoldKt:572, 只 provide 弹窗状态列表) 子树内, 经 startMovableGroup(movableContent) 移入渲染。
            //   DialogContentLayout → NavigationBackHandler → NavigationEventHandler(NavigationEventHandlerKt:137-139) 读
            //   LocalNavigationEventDispatcherOwner.current, 为 null 即抛 "No NavigationEventDispatcher was provided..."。
            //   fixed/navowner 把 provide 放在 Column 内(OverlayDialog 调用点), 但 movableContent 渲染点 MiuixPopupHost 在 Scaffold 子树、
            //   是 Column 的兄弟节点, 继承不到 Column 内 provide → 崩。根治: provide 置于 composition 最顶层, 令任何渲染点必继承非 null owner。
            //   rememberNavigationEventDispatcherOwner(parent=null) 显式传 null 走"造根 dispatcher"分支(不读 local, 不抛), 返回非 null owner。
            val navOwner = rememberNavigationEventDispatcherOwner(enabled = true, parent = null)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navOwner) {
                val controller = remember { ThemeController(ColorSchemeMode.System) }
                MiuixTheme(controller = controller) {
                    SettingsScreen(prefs, profile)
                }
            }
        }
    }

    /**
     * v2.5.4: 调试页「重置本版本设置」清空档案后, 主界面开关需按降级链(档案键 -> 旧全局键 -> 默认)
     * 重新读取。用一次性标记驱动 recreate(), 避免把刷新逻辑散进各个 Composable。
     */
    override fun onResume() {
        super.onResume()
        try {
            if (::prefs.isInitialized && prefs.getBoolean("ui.reload_on_resume", false)) {
                prefs.edit().remove("ui.reload_on_resume").commit()
                recreate()
            }
        } catch (t: Throwable) {
            android.util.Log.w("HomeLauncherLSP", "reload flag check failed -> $t")
        }
    }
}

@Composable
private fun SettingsScreen(prefs: SharedPreferences, profile: String?) {
    var cleanStart by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_CLEAN_START, true)) }
    var newTabHome by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_NEWTAB_HOME, true)) }
    var bypassDangerous by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_BYPASS_DANGEROUS, true)) }
    var bypassInsecure by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_BYPASS_INSECURE, true)) }
    var bypassDuplicate by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_BYPASS_DUPLICATE, true)) }
    var bypassPolicy by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_BYPASS_POLICY, true)) }
    var bypassLocation by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_BYPASS_LOCATION, true)) }
    var bypassOpen by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_BYPASS_OPEN, true)) }
    var autoInstallApk by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_AUTO_INSTALL_APK, true)) }
    var hideIcon by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, KEY_HIDE_ICON, false)) }
    var clearTabs by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_CLEAR_TABS, true)) }
    var bannerApkToast by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_BANNER_APK_TOAST, true)) }
    var bannerAllToast by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_BANNER_ALL_TOAST, false)) }
    var hideTranslateBanner by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_HIDE_TRANSLATE_BANNER, true)) }
    var autoOpenExt by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_AUTO_OPEN_EXT, false)) }
    var autoOpenPdf by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_AUTO_OPEN_PDF, false)) }
    var autoOpenArchive by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_AUTO_OPEN_ARCHIVE, false)) }
    var autoOpenDoc by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_AUTO_OPEN_DOCUMENT, false)) }
    var autoOpenSheet by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_AUTO_OPEN_SPREADSHEET, false)) }
    var autoOpenSlide by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_AUTO_OPEN_PRESENTATION, false)) }
    var autoOpenText by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_AUTO_OPEN_TEXT, false)) }
    var autoOpenImage by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_AUTO_OPEN_IMAGE, false)) }
    var autoOpenVideo by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_AUTO_OPEN_VIDEO, false)) }
    var autoOpenAudio by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_AUTO_OPEN_AUDIO, false)) }
    var autoOpenEbook by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_AUTO_OPEN_EBOOK, false)) }
    var autoOpenCustom by remember { mutableStateOf(ProfilePrefs.getString(prefs, profile, HookEntry.KEY_AUTO_OPEN_CUSTOM, "") ?: "") }
    var showAutoOpenDialog by remember { mutableStateOf(false) }
    var showCustomExtDialog by remember { mutableStateOf(false) }
    var customExtInput by remember { mutableStateOf("") }
    // v2.3.16: 自定义下载目录
    var downloadDir by remember { mutableStateOf(ProfilePrefs.getString(prefs, profile, HookEntry.KEY_DOWNLOAD_DIR, "") ?: "") }
    var showDirDialog by remember { mutableStateOf(false) }
    var dirInput by remember { mutableStateOf("") }

    fun save() {
        prefs.edit()
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_CLEAN_START), cleanStart)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_NEWTAB_HOME), newTabHome)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_BYPASS_DANGEROUS), bypassDangerous)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_BYPASS_INSECURE), bypassInsecure)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_BYPASS_DUPLICATE), bypassDuplicate)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_BYPASS_POLICY), bypassPolicy)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_BYPASS_LOCATION), bypassLocation)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_BYPASS_OPEN), bypassOpen)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_INSTALL_APK), autoInstallApk)
            .putBoolean(ProfilePrefs.keyOf(profile, KEY_HIDE_ICON), hideIcon)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_CLEAR_TABS), clearTabs)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_BANNER_APK_TOAST), bannerApkToast)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_BANNER_ALL_TOAST), bannerAllToast)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_HIDE_TRANSLATE_BANNER), hideTranslateBanner)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_OPEN_EXT), autoOpenExt)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_OPEN_PDF), autoOpenPdf)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_OPEN_ARCHIVE), autoOpenArchive)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_OPEN_DOCUMENT), autoOpenDoc)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_OPEN_SPREADSHEET), autoOpenSheet)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_OPEN_PRESENTATION), autoOpenSlide)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_OPEN_TEXT), autoOpenText)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_OPEN_IMAGE), autoOpenImage)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_OPEN_VIDEO), autoOpenVideo)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_OPEN_AUDIO), autoOpenAudio)
            .putBoolean(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_OPEN_EBOOK), autoOpenEbook)
            .putString(ProfilePrefs.keyOf(profile, HookEntry.KEY_AUTO_OPEN_CUSTOM), autoOpenCustom)
            .putString(ProfilePrefs.keyOf(profile, HookEntry.KEY_DOWNLOAD_DIR), downloadDir)
            .commit()
    }

    // [弹窗多选根治 v4] owner provider 已上移到 MainActivity.onCreate 的 setContent 顶层(包住 MiuixTheme),
    // SettingsScreen 及其 Scaffold/OverlayDialog/MiuixPopupHost 全在该 provider 子树内, 必继承非 null owner。
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // 标题跟随设置内容一起滚动：上滑移出屏幕，下滑重新出现。
            Text(
                text = "ChroStar",
                modifier = Modifier.padding(
                    start = 16.dp,
                    top = 18.dp,
                    end = 16.dp,
                    bottom = 10.dp
                ),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )

            // ── 启动行为 ──
            SmallTitle(text = "启动行为")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                SwitchPreference(
                    title = "启动浏览器打开主页",
                    summary = "冷启动禁止恢复历史标签",
                    checked = cleanStart,
                    onCheckedChange = { cleanStart = it; save() }
                )
                SwitchPreference(
                    title = "新标签页(+) 打开主页",
                    summary = "点击+号打开自定义主页而非默认新标签页",
                    checked = newTabHome,
                    onCheckedChange = { newTabHome = it; save() }
                )
            }

            // ── 下载 ──
            SmallTitle(text = "下载")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                val context = LocalContext.current
                SwitchPreference(
                    title = "去除「文件可能有害」警告",
                    summary = "危险文件警告，直接保留下载",
                    checked = bypassDangerous,
                    onCheckedChange = { bypassDangerous = it; save() }
                )
                SwitchPreference(
                    title = "去除「无法安全地下载」警告",
                    summary = "不安全连接警告，直接保留下载",
                    checked = bypassInsecure,
                    onCheckedChange = { bypassInsecure = it; save() }
                )
                SwitchPreference(
                    title = "去除「重复下载」确认",
                    summary = "同名文件再次下载时直接保留",
                    checked = bypassDuplicate,
                    onCheckedChange = { bypassDuplicate = it; save() }
                )
                SwitchPreference(
                    title = "去除「策略警告」弹窗",
                    summary = "企业策略警告，直接保留下载",
                    checked = bypassPolicy,
                    onCheckedChange = { bypassPolicy = it; save() }
                )
                SwitchPreference(
                    title = "去除「保存位置/重命名」对话框",
                    summary = "存默认下载目录、不改文件名",
                    checked = bypassLocation,
                    onCheckedChange = { bypassLocation = it; save() }
                )
                SwitchPreference(
                    title = "去除「要打开此文件吗」询问",
                    summary = "仅保留下载，不自动打开",
                    checked = bypassOpen,
                    onCheckedChange = { bypassOpen = it; save() }
                )
                SwitchPreference(
                    title = "APK 下载完成自动打开安装",
                    summary = "下载完成后自动调起系统安装器",
                    checked = autoInstallApk,
                    onCheckedChange = { autoInstallApk = it; save() }
                )
                // v2.3.21: 合并「下载完成自动打开(扩展类型)」与「自动打开文件类型」——
                //   原为两行入口, 语义重复。现为单行: 文字区域点击→进入类型勾选弹窗; 右侧开关→仅开/关。
                //   miuix SwitchPreference 的整行与开关共用同一 onCheckedChange(反编译实证), 无法分离,
                //   故用 BasicComponent 自组行: onClick 只绑文字区, 开关用 Switch 单独挂回调。
                BasicComponent(
                    title = "下载完成自动打开(扩展类型)",
                    summary = "点击此处设置扩展类型; 右侧开关开启/关闭",
                    onClick = { showAutoOpenDialog = true },
                    endActions = {
                        Switch(
                            checked = autoOpenExt,
                            onCheckedChange = { checked -> autoOpenExt = checked; save() }
                        )
                    }
                )
                // v2.3.16: 自定义下载目录(空 = 用 Chrome 默认目录)
                // v2.3.22: 提醒——受 Android 分区存储限制, 自定义目录只能位于公共 Download 之下
                //   (Chrome 未声明 MANAGE_EXTERNAL_STORAGE, 无法写入 Download 以外的目录)。
                ArrowPreference(
                    title = "自定义下载目录",
                    summary = if (downloadDir.isBlank())
                        "默认(公共 Download), 点击自定义\n仅可设置在 Download 文件夹下, 如 Download/chrome"
                              else truncateOneLine("已设置: " + downloadDir, 28),
                    onClick = { dirInput = downloadDir; showDirDialog = true }
                )
            }

            // ── 历史标签删除 ──
            SmallTitle(text = "历史标签删除")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                SwitchPreference(
                    title = "打开主页删除「关闭的标签页」历史",
                    summary = "清除全部时间段的关闭标签记录",
                    checked = clearTabs,
                    onCheckedChange = { clearTabs = it; save() }
                )
            }

            // ── 横幅 ──
            SmallTitle(text = "横幅")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                SwitchPreference(
                    title = "APK 下载完成横幅用 Toast 代替",
                    summary = "只影响 APK，下载完成后弹系统提示",
                    checked = bannerApkToast,
                    onCheckedChange = { bannerApkToast = it; save() }
                )
                SwitchPreference(
                    title = "所有下载完成横幅用 Toast 代替",
                    summary = "所有文件下载完成都不显示横幅",
                    checked = bannerAllToast,
                    onCheckedChange = { bannerAllToast = it; save() }
                )
                SwitchPreference(
                    title = "隐藏翻译横幅",
                    summary = "不显示「翻译此页」提示",
                    checked = hideTranslateBanner,
                    onCheckedChange = { hideTranslateBanner = it; save() }
                )
            }

            // ── 模块 ──
            SmallTitle(text = "模块")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                val context = LocalContext.current
                SwitchPreference(
                    title = "隐藏桌面图标",
                    summary = if (hideIcon) "已隐藏，LSPosed 中仍可打开设置" else "在桌面隐藏本模块图标",
                    checked = hideIcon,
                    onCheckedChange = { checked ->
                        hideIcon = checked
                        save()
                        val newState = if (checked) {
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        } else {
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        }
                        try {
                            context.packageManager.setComponentEnabledSetting(
                                ComponentName(context, ".LauncherAlias"),
                                newState,
                                PackageManager.DONT_KILL_APP
                            )
                        } catch (t: Throwable) {
                            android.util.Log.e(HookEntry.TAG, "setComponentEnabledSetting failed", t)
                        }
                    }
                )
                ArrowPreference(
                    title = "调试与日志",
                    summary = "仅开发者使用",
                    onClick = {
                        try {
                            context.startActivity(
                                android.content.Intent(context, DebugActivity::class.java)
                            )
                        } catch (t: Throwable) {
                            android.util.Log.e(HookEntry.TAG, "open debug page failed", t)
                        }
                    }
                )
            }

            // ── 关于（v1.11.2: 入口行, 点击进入全屏二级页） ──
            SmallTitle(text = "关于")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                val context = LocalContext.current
                ArrowPreference(
                    title = "关于",
                    onClick = {
                        try {
                            context.startActivity(
                                android.content.Intent(context, AboutActivity::class.java)
                            )
                        } catch (t: Throwable) {
                            android.util.Log.e(HookEntry.TAG, "open about failed", t)
                        }
                    },
                )
            }

            // ── 弹窗多选: 内容经 movableContent 移到 Scaffold 子树内的 MiuixPopupHost 渲染;
            //    owner 已在 MainActivity.setContent 顶层 provide(见 onCreate), 此处直接声明弹窗即可 ──
                OverlayDialog(
                    show = showAutoOpenDialog,
                    title = "自动打开类型",
                    summary = "勾选下载完成后自动打开的文件类型",
                    onDismissRequest = { showAutoOpenDialog = false }
                ) {
                    CheckboxPreference(
                        title = "PDF 文档 (" + AutoOpenTypes.SUFFIX_PDF + ")",
                        checked = autoOpenPdf,
                        onCheckedChange = { autoOpenPdf = it; save() }
                    )
                    CheckboxPreference(
                        title = "压缩包 (" + AutoOpenTypes.SUFFIX_ARCHIVE + ")",
                        checked = autoOpenArchive,
                        onCheckedChange = { autoOpenArchive = it; save() }
                    )
                    CheckboxPreference(
                        title = "Office 文档 (" + AutoOpenTypes.SUFFIX_OFFICE + ")",
                        checked = autoOpenDoc || autoOpenSheet || autoOpenSlide,
                        onCheckedChange = { autoOpenDoc = it; autoOpenSheet = it; autoOpenSlide = it; save() }
                    )
                    CheckboxPreference(
                        title = "文本文件 (" + AutoOpenTypes.SUFFIX_TEXT + ")",
                        checked = autoOpenText,
                        onCheckedChange = { autoOpenText = it; save() }
                    )
                    CheckboxPreference(
                        title = "图片 (" + AutoOpenTypes.SUFFIX_IMAGE + ")",
                        checked = autoOpenImage,
                        onCheckedChange = { autoOpenImage = it; save() }
                    )
                    CheckboxPreference(
                        title = "视频 (" + AutoOpenTypes.SUFFIX_VIDEO + ")",
                        checked = autoOpenVideo,
                        onCheckedChange = { autoOpenVideo = it; save() }
                    )
                    CheckboxPreference(
                        title = "音频 (" + AutoOpenTypes.SUFFIX_AUDIO + ")",
                        checked = autoOpenAudio,
                        onCheckedChange = { autoOpenAudio = it; save() }
                    )
                    CheckboxPreference(
                        title = "电子书 (" + AutoOpenTypes.SUFFIX_EBOOK + ")",
                        checked = autoOpenEbook,
                        onCheckedChange = { autoOpenEbook = it; save() }
                    )
                    // v2.3.14: 自定义打开后缀(置于电子书下方)。点击打开输入弹窗。
                    // 自定义后缀从属于「自动打开(扩展类型)」总开关，避免设置说明与运行时门禁不一致。
                    // 需求1: 后缀说明过长时单行省略 —— miuix preference 的 title/summary 不接受
                    //   overflow/maxLines(反编译实证), 无法在其内部省略, 故此处对拼接串预截断。
                    ArrowPreference(
                        title = "自定义打开后缀",
                        summary = if (autoOpenCustom.isBlank()) "填写后需开启上面的总开关, 如 bat/apk/psd/torrent"
                                  else truncateOneLine("已设置: " + autoOpenCustom, 28),
                        onClick = { customExtInput = autoOpenCustom; showCustomExtDialog = true }
                    )
                    // v2.3.4: 弹窗底部"完成"按钮 —— 勾选已即时 save(), 此按钮提供明确的保存/返回入口。
                    // miuix OverlayDialog/DialogContentLayout 无内建 confirm/dismiss 按钮 slot(反编译实证), 故自置。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = { showAutoOpenDialog = false }) {
                            Text(text = "完成")
                        }
                    }
                }

                // v2.3.14: 自定义后缀输入弹窗(单行文本)。
                OverlayDialog(
                    show = showCustomExtDialog,
                    title = "自定义打开后缀",
                    summary = "多个后缀用逗号分隔, 无需加点(如 bat,psd,torrent)\n填写后需开启上面的总开关",
                    onDismissRequest = { showCustomExtDialog = false }
                ) {
                    TextField(
                        value = customExtInput,
                        onValueChange = { customExtInput = it },
                        label = "后缀列表",
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = { showCustomExtDialog = false }) {
                            Text(text = "取消")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            autoOpenCustom = normalizeExtList(customExtInput)
                            save()
                            showCustomExtDialog = false
                        }) {
                            Text(text = "保存")
                        }
                    }
                }

                // v2.3.16: 自定义下载目录输入弹窗(单行)。
                // v2.3.22: 明确提示只可设在公共 Download 文件夹下。
                OverlayDialog(
                    show = showDirDialog,
                    title = "自定义下载目录",
                    summary = "仅可设置在 Download 文件夹下(如 Download/chrome)\n留空 = 使用 Chrome 默认目录",
                    onDismissRequest = { showDirDialog = false }
                ) {
                    TextField(
                        value = dirInput,
                        onValueChange = { dirInput = it },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = { showDirDialog = false }) {
                            Text(text = "取消")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            downloadDir = normalizeDir(dirInput)
                            save()
                            showDirDialog = false
                        }) {
                            Text(text = "保存")
                        }
                    }
                }
        }
    }
}
