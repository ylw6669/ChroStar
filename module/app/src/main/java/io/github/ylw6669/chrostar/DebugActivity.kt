package io.github.ylw6669.chrostar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * v2.5.4: 调试与日志二级页。
 *
 * <p>把原先放在主界面的「重新扫描本版本」「重置本版本设置」收进这里, 主界面只留一个入口,
 * 避免日常误触; 同时提供面向开发者的日志能力:
 * <ul>
 *   <li>环境快照: 模块/Chrome 版本、引擎、配置档案、符号解析来源与缺失槽位、生效配置。</li>
 *   <li>日志抓取: hook 运行在 Chrome 进程内, 其 XposedBridge 日志写入 logcat 的
 *       HomeLauncherLSP 标签下 —— 跨进程日志只有 root 能读, 因此优先 {@code su -c logcat},
 *       失败退回本进程 logcat, 并在界面标明当前来源与局限。</li>
 *   <li>关键字过滤 / 自动刷新 / 复制 / 清空缓冲, 便于边复现边定位。</li>
 * </ul>
 */
class DebugActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrefsStore.migrateLegacy(this)
        val prefs = PrefsStore.open(this)
        ProfilePrefs.cleanupCopiedControlKeys(prefs)
        val profile = ProfilePrefs.currentProfile(this, prefs, CHROME_PKG)
        setContent {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                DebugScreen(this, prefs, profile)
            }
        }
    }

    companion object {
        private const val CHROME_PKG = "com.android.chrome"
    }
}

/** 日志抓取结果: 来源说明 + 行列表。 */
private data class LogCapture(val source: String, val lines: List<String>)

private const val KEY_RELOAD_ON_RESUME = "ui.reload_on_resume"
private const val MAX_LOG_CHARS = 400_000
private const val MAX_SHOWN_LINES = 300

@Composable
private fun DebugScreen(context: Context, prefs: SharedPreferences, profile: String?) {
    var env by remember { mutableStateOf(buildEnvText(context, prefs, profile)) }
    var capture by remember { mutableStateOf(LogCapture("未抓取", emptyList())) }
    var busy by remember { mutableStateOf(false) }
    var autoRefresh by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf("") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var developerDetails by remember { mutableStateOf(false) }
    var showLocationGuide by remember { mutableStateOf(false) }
    var debugEvents by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_DEBUG_EVENTS, false)) }
    var debugVerbose by remember { mutableStateOf(ProfilePrefs.getBoolean(prefs, profile, HookEntry.KEY_DEBUG_VERBOSE, false)) }

    /** hook 侧经 status.log.tail 回传的事件尾部(无需 root 即可看到)。 */
    var hookTail by remember { mutableStateOf(prefs.getString("status.log.tail", "").orEmpty()) }

    suspend fun grab() {
        busy = true
        val r = withContext(Dispatchers.IO) { captureLog() }
        capture = r
        hookTail = prefs.getString("status.log.tail", "").orEmpty()
        busy = false
    }

    // 默认只显示精简状态；展开开发者诊断后再抓取日志，避免普通用户进入页面时做重活。
    LaunchedEffect(developerDetails) {
        if (developerDetails) grab()
    }

    // 手动刷新
    LaunchedEffect(refreshTick) {
        if (developerDetails && refreshTick > 0) grab()
    }
    // 自动刷新
    LaunchedEffect(autoRefresh) {
        while (autoRefresh && developerDetails) {
            grab()
            delay(2000)
        }
    }

    fun refreshEnv() { env = buildEnvText(context, prefs, profile) }

    Scaffold(topBar = { TopAppBar(title = "调试与日志") }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SmallTitle(text = "当前配置")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    text = buildEnvironmentSummary(context, prefs, profile),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                ArrowPreference(
                    title = "刷新环境状态",
                    summary = "重新读取版本、配置档案和能力解析结果",
                    onClick = {
                        refreshEnv()
                        toast = "环境状态已刷新"
                    }
                )
                SwitchPreference(
                    title = "开发者诊断模式",
                    summary = "展开符号来源、重扫、重置和完整运行日志",
                    checked = developerDetails,
                    onCheckedChange = {
                        developerDetails = it
                        if (!it) autoRefresh = false
                        toast = if (it) "已展开开发者诊断" else "已收起开发者诊断"
                    }
                )
            }

            if (developerDetails) {
                SmallTitle(text = "高级环境快照")
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = env,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }

                SmallTitle(text = "维护操作")
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    ArrowPreference(
                        title = "重新扫描本版本符号",
                        summary = "强制作废缓存，下次启动 Chrome 时重新解析",
                        onClick = {
                            prefs.edit()
                                .putLong(HookEntry.KEY_RESCAN_REQUEST_AT, System.currentTimeMillis())
                                .commit()
                            toast = "已请求重扫, 下次启动 Chrome 生效"
                            refreshEnv()
                        }
                    )
                    ArrowPreference(
                        title = "重置本版本设置",
                        summary = "仅清除当前 Chrome 版本档案",
                        onClick = {
                            if (profile == null) {
                                toast = "未识别到 Chrome 版本, 无可重置档案"
                            } else {
                                val n = ProfilePrefs.resetProfile(prefs, profile)
                                prefs.edit().putBoolean(KEY_RELOAD_ON_RESUME, true).commit()
                                toast = "已清除 $profile 档案的 $n 项设置"
                                refreshEnv()
                            }
                        }
                    )
                    ArrowPreference(
                        title = "复制诊断信息",
                        summary = "复制环境快照与当前日志",
                        onClick = {
                            val text = env + "\n\n" + capture.lines.joinToString("\n")
                            copyToClipboard(context, text)
                            toast = "已复制 ${text.length} 字符"
                        }
                    )
                    SwitchPreference(
                        title = "记录功能事件日志",
                        summary = "默认关闭；关闭时仍保留关键错误",
                        checked = debugEvents,
                        onCheckedChange = {
                            debugEvents = it
                            saveDebugSwitch(prefs, profile, HookEntry.KEY_DEBUG_EVENTS, it)
                            toast = if (it) "功能事件日志已开启" else "功能事件日志已关闭"
                        }
                    )
                    SwitchPreference(
                        title = "记录详细事件日志",
                        summary = "记录分支和字段级细节",
                        checked = debugVerbose,
                        onCheckedChange = {
                            debugVerbose = it
                            saveDebugSwitch(prefs, profile, HookEntry.KEY_DEBUG_VERBOSE, it)
                            if (it) {
                                debugEvents = true
                                saveDebugSwitch(prefs, profile, HookEntry.KEY_DEBUG_EVENTS, true)
                            }
                            toast = if (it) "详细事件日志已开启" else "详细事件日志已关闭"
                        }
                    )
                    ArrowPreference(
                        title = "清空日志缓冲区",
                        summary = "执行 logcat -c",
                        onClick = {
                            toast = if (clearLogcat()) "日志缓冲已清空" else "清空失败(可能需要 root)"
                        }
                    )
                    ArrowPreference(
                        title = "快速定位说明",
                        summary = if (showLocationGuide) "点击收起" else "按功能标签定位异常",
                        onClick = { showLocationGuide = !showLocationGuide }
                    )
                    if (showLocationGuide) {
                        Text(
                            text = buildLocationGuide(),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }

                SmallTitle(text = "运行日志")
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { refreshTick++ }) {
                            Text(text = if (busy) "抓取中…" else "刷新日志")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { capture = LogCapture(capture.source, emptyList()) }) {
                            Text(text = "清空显示")
                        }
                        Button(
                            onClick = {
                                prefs.edit().remove("status.log.tail").remove("status.log.updated_at").commit()
                                hookTail = ""
                                toast = "已清空 hook 事件尾部"
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(text = "清空事件")
                        }
                    }
                    SwitchPreference(
                        title = "自动刷新",
                        summary = "每 2 秒抓取一次",
                        checked = autoRefresh,
                        onCheckedChange = { autoRefresh = it }
                    )
                    TextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = "关键字过滤",
                        singleLine = true,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Text(
                        text = "来源: ${capture.source} · ${capture.lines.size} 行" +
                            if (filter.isNotBlank()) " · 过滤: $filter" else "",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    val shown = remember(capture, filter) {
                        val src = if (filter.isBlank()) capture.lines
                        else capture.lines.filter { it.contains(filter, ignoreCase = true) }
                        src.takeLast(MAX_SHOWN_LINES)
                    }
                    if (shown.isEmpty()) {
                        Text(
                            text = "（暂无日志。若来源为本进程 logcat，说明未授予 root。）",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    } else {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            shown.forEach { line ->
                                Text(
                                    text = line,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                SmallTitle(text = "hook 事件尾部")
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    val tailLines = hookTail.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    Text(
                        text = "回传时间: " + (prefs.getString("status.log.updated_at", null)?.let {
                            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it.toLong()))
                        } ?: "无") + " · ${tailLines.size} 条",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    if (tailLines.isEmpty()) {
                        Text(
                            text = "（暂无回传。启动 Chrome 并触发功能后刷新。）",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    } else {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            tailLines.takeLast(80).forEach { line ->
                                Text(
                                    text = line,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            if (toast.isNotBlank()) {
                Text(
                    text = toast,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.width(1.dp).height(24.dp))
        }
    }
}

// ------------------------------------------------------------------
// 环境快照
// ------------------------------------------------------------------

private fun buildEnvironmentSummary(context: Context, prefs: SharedPreferences, profile: String?): String {
    val chromeVer = CapsCache.installedVersionName(context, CHROME_PKG_NAME) ?: "未检测到"
    val major = ProfilePrefs.majorOfVersionName(chromeVer)
    val runtimeCode = prefs.getString("status.caps.runtime_version_code", null)
    val chromeCode = CapsCache.installedVersionCode(context, CHROME_PKG_NAME)
    val runtimeCurrent = runtimeCode?.toLongOrNull()?.let { it == chromeCode } == true
    val engine = if (runtimeCurrent) {
        prefs.getString("status.caps.engine", "未回传")
    } else {
        inferEngine(major) + "（按版本推断，启动 Chrome 后刷新）"
    }
    val missing = prefs.getString("status.caps.missing", "").orEmpty()
        .let { if (it == "[]") "" else it }
    val resolveState = when {
        !runtimeCurrent -> "等待 Chrome 运行后回传"
        missing.isBlank() -> "核心符号已解析"
        else -> "缺失: $missing"
    }
    return buildString {
        append("模块：").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("Chrome：").append(chromeVer)
            .append(" (").append(chromeCode).append(")\n")
        append("当前正在编辑：").append(
            if (profile == null) "未检测到 Chrome，使用旧全局配置"
            else "Chrome " + profile.removePrefix("v") + "（档案 " + profile + "）"
        ).append("\n")
        append("适配引擎：").append(engine).append("\n")
        append("解析状态：").append(resolveState)
    }
}

private fun buildEnvText(context: Context, prefs: SharedPreferences, profile: String?): String {
    val sb = StringBuilder()
    sb.append("模块版本: ").append(BuildConfig.VERSION_NAME)
        .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
    val chromeVer = CapsCache.installedVersionName(context, CHROME_PKG_NAME) ?: "?"
    val chromeCode = CapsCache.installedVersionCode(context, CHROME_PKG_NAME)
    sb.append("Chrome: ").append(chromeVer).append(" (").append(chromeCode).append(")\n")
    val runtimeCode = prefs.getString("status.caps.runtime_version_code", null)
    val runtimeCurrent = runtimeCode?.toLongOrNull()?.let { it == chromeCode } == true
    val capsCode = prefs.getString("status.caps.chrome_version_code", null)
    val capsStale = capsCode?.toLongOrNull()?.let { it != chromeCode } == true
    val engine = prefs.getString("status.caps.engine", "未回传")
    if (runtimeCurrent) {
        sb.append("引擎(当前会话): ").append(engine).append("\n")
    } else {
        val major = ProfilePrefs.majorOfVersionName(chromeVer)
        sb.append("引擎(未回传/已过期): ").append(engine).append("\n")
        sb.append("引擎(按当前版本推断): ").append(inferEngine(major)).append("\n")
    }
    sb.append("配置档案: ").append(profile ?: "未识别(用旧全局键)").append("\n")
    sb.append("能力解析快照: ")
        .append(if (capsStale) "已过期(对应 Chrome " + capsCode + ") " else "")
        .append("缓存 ").append(prefs.getString("status.caps.cached_count", "?"))
        .append(" 项 · 来源 ").append(prefs.getString("status.caps.source", "?")).append("\n")
    sb.append("缺失槽位: ").append(prefs.getString("status.caps.missing", "?") ?: "?").append("\n")
    sb.append("解析明细: ").append(prefs.getString("status.caps.sources", "?") ?: "?").append("\n")
    val at = prefs.getString("status.caps.updated_at", null)?.toLongOrNull()
    if (at != null && at > 0) {
        sb.append("上次解析: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at)))
            .append("\n")
    } else {
        sb.append("上次解析: 无(启动一次 Chrome 后回传)\n")
    }
    val runtimeAt = prefs.getString("status.caps.runtime_updated_at", null)?.toLongOrNull()
    if (runtimeAt != null && runtimeAt > 0) {
        sb.append("运行状态回传: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(runtimeAt)))
            .append("\n")
    }
    sb.append("--- 生效配置 ---\n")
    sb.append("download_dir = ")
        .append(ProfilePrefs.getString(prefs, profile, HookEntry.KEY_DOWNLOAD_DIR, "(空)")).append("\n")
    val extRaw = ProfilePrefs.resolveRaw(prefs, profile, HookEntry.KEY_AUTO_OPEN_EXT)
    sb.append("auto_open_ext = ")
        .append(if (extRaw == null) "缺失(按子开关推断)" else extRaw).append("\n")
    val onTypes = AUTO_OPEN_KEYS.filter {
        ProfilePrefs.getBoolean(prefs, profile, it, false)
    }
    sb.append("已开自动打开类型 = ").append(onTypes.ifEmpty { "无" }).append("\n")
    sb.append("custom 后缀 = ")
        .append(ProfilePrefs.getString(prefs, profile, HookEntry.KEY_AUTO_OPEN_CUSTOM, "(空)"))
        .append("\n")
    return sb.toString().trimEnd()
}

private fun inferEngine(major: String?): String = when (major) {
    "145" -> "chrome145"
    "152" -> "chrome152"
    "153" -> "chrome153"
    null -> "未识别"
    else -> "chrome_symbolic"
}

private const val CHROME_PKG_NAME = "com.android.chrome"

private val AUTO_OPEN_KEYS = listOf(
    HookEntry.KEY_AUTO_OPEN_PDF, HookEntry.KEY_AUTO_OPEN_ARCHIVE,
    HookEntry.KEY_AUTO_OPEN_DOCUMENT, HookEntry.KEY_AUTO_OPEN_SPREADSHEET,
    HookEntry.KEY_AUTO_OPEN_PRESENTATION, HookEntry.KEY_AUTO_OPEN_TEXT,
    HookEntry.KEY_AUTO_OPEN_IMAGE, HookEntry.KEY_AUTO_OPEN_VIDEO,
    HookEntry.KEY_AUTO_OPEN_AUDIO, HookEntry.KEY_AUTO_OPEN_EBOOK
)

private fun saveDebugSwitch(prefs: SharedPreferences, profile: String?, key: String, value: Boolean) {
    prefs.edit()
        .putBoolean(ProfilePrefs.keyOf(profile, key), value)
        .commit()
}

private fun buildLocationGuide(): String {
    return "主页/新标签异常 → 过滤 [Home]，再看 [profile] 和 [capabilities]。\n" +
        "下载弹窗未绕过 → 过滤 [Dl]，并看 dangerous/insecure/duplicate 等具体步骤。\n" +
        "横幅或 Toast 异常 → 过滤 [Banner]，确认 suppressed 是否出现。\n" +
        "自动打开未触发 → 过滤 [Auto] 或 shouldAutoOpen，检查 APK/扩展分支。\n" +
        "模块能力缺失 → 过滤 [Caps]，看 missing 槽位和解析来源。\n" +
        "整包无 Hook → 过滤 Hook，确认 engine 与 hooks installed 是否完整。"
}

// ------------------------------------------------------------------
// 日志抓取
// ------------------------------------------------------------------

private const val LOG_TAG = "HomeLauncherLSP"

private fun captureLog(): LogCapture {
    // 1) LSPosed 日志文件: 最完整(含全部 XposedBridge.log 与历史会话), 需 root。
    val newest = runShell(
        listOf("su", "-c", "ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1"), 6000
    )
    val path = newest.second.trim().lines().firstOrNull { it.startsWith("/") }
    if (newest.first == 0 && path != null) {
        val dump = runShell(
            listOf("su", "-c", "grep -h HomeLauncherLSP '$path' | tail -n 500"), 10000
        )
        if (dump.first == 0 && dump.second.isNotBlank()) {
            return LogCapture("LSPosed 日志文件（完整历史）", tailLines(dump.second))
        }
    }
    // 2) root logcat: 当前缓冲内的 hook 日志
    val root = runShell(
        listOf("su", "-c", "logcat -d -v time -s $LOG_TAG:V"), 6000
    )
    if (root.first == 0 && root.second.isNotBlank()) {
        val lines = tailLines(root.second)
        if (lines.isNotEmpty()) {
            return LogCapture("su logcat（含 Chrome 进程内 hook 日志）", lines)
        }
    }
    // 3) 无 root: 只能读本进程(设置页)日志
    val own = runShell(listOf("logcat", "-d", "-v", "time", "-s", "$LOG_TAG:V"), 5000)
    val ownLines = tailLines(own.second)
    val note = if (own.first == 0) {
        "本进程 logcat（未授予 root，读不到 Chrome 进程日志）"
    } else {
        "logcat 不可用: ${own.second.take(120)}"
    }
    return LogCapture(note, ownLines)
}

private fun tailLines(text: String): List<String> {
    val all = text.split('\n').map { it.trimEnd() }.filter { it.isNotEmpty() }
    if (all.isEmpty()) return emptyList()
    return all.takeLast(MAX_SHOWN_LINES * 3)
}

private fun clearLogcat(): Boolean {
    val r = runShell(listOf("su", "-c", "logcat -c"), 4000)
    if (r.first == 0) return true
    val r2 = runShell(listOf("logcat", "-c"), 4000)
    return r2.first == 0
}

private fun runShell(cmd: List<String>, timeoutMs: Long): Pair<Int, String> {
    return try {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val sb = StringBuilder()
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    var n = r.read(buf)
                    while (n > 0) {
                        if (sb.length < MAX_LOG_CHARS) sb.append(buf, 0, n)
                        n = r.read(buf)
                    }
                }
            } catch (ignored: Throwable) {
                // 读流失败时返回已捕获部分
            }
        }
        reader.isDaemon = true
        reader.start()
        if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            proc.destroy()
            return Pair(-1, "命令超时(${timeoutMs}ms)")
        }
        reader.join(500)
        Pair(proc.exitValue(), sb.toString())
    } catch (t: Throwable) {
        Pair(-2, "执行失败: ${t.message}")
    }
}

private fun copyToClipboard(context: Context, text: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ChroStar 诊断", text))
    } catch (t: Throwable) {
        android.util.Log.w(LOG_TAG, "copy to clipboard failed -> $t")
    }
}
