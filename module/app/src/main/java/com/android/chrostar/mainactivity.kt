package com.android.chrostar

import android.content.ComponentName
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** "隐藏桌面图标"偏好键 —— 仅设置页读写(hook 侧不读此配置) */
private const val KEY_HIDE_ICON = "hide_icon"

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(HookEntry.PREFS_NAME, MODE_PRIVATE)
        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                SettingsScreen(prefs)
            }
        }
    }
}

@Composable
private fun SettingsScreen(prefs: SharedPreferences) {
    var cleanStart by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_CLEAN_START, true)) }
    var newTabHome by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_NEWTAB_HOME, true)) }
    var bypassDangerous by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_BYPASS_DANGEROUS, true)) }
    var bypassInsecure by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_BYPASS_INSECURE, true)) }
    var bypassDuplicate by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_BYPASS_DUPLICATE, true)) }
    var bypassPolicy by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_BYPASS_POLICY, true)) }
    var bypassLocation by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_BYPASS_LOCATION, true)) }
    var bypassOpen by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_BYPASS_OPEN, true)) }
    var autoInstallApk by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_AUTO_INSTALL_APK, true)) }
    var hideIcon by remember { mutableStateOf(prefs.getBoolean(KEY_HIDE_ICON, false)) }
    var clearTabs by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_CLEAR_TABS, true)) }
    var bannerApkToast by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_BANNER_APK_TOAST, true)) }
    var bannerAllToast by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_BANNER_ALL_TOAST, false)) }
    var hideTranslateBanner by remember { mutableStateOf(prefs.getBoolean(HookEntry.KEY_HIDE_TRANSLATE_BANNER, true)) }

    fun save() {
        prefs.edit()
            .putBoolean(HookEntry.KEY_CLEAN_START, cleanStart)
            .putBoolean(HookEntry.KEY_NEWTAB_HOME, newTabHome)
            .putBoolean(HookEntry.KEY_BYPASS_DANGEROUS, bypassDangerous)
            .putBoolean(HookEntry.KEY_BYPASS_INSECURE, bypassInsecure)
            .putBoolean(HookEntry.KEY_BYPASS_DUPLICATE, bypassDuplicate)
            .putBoolean(HookEntry.KEY_BYPASS_POLICY, bypassPolicy)
            .putBoolean(HookEntry.KEY_BYPASS_LOCATION, bypassLocation)
            .putBoolean(HookEntry.KEY_BYPASS_OPEN, bypassOpen)
            .putBoolean(HookEntry.KEY_AUTO_INSTALL_APK, autoInstallApk)
            .putBoolean(KEY_HIDE_ICON, hideIcon)
            .putBoolean(HookEntry.KEY_CLEAR_TABS, clearTabs)
            .putBoolean(HookEntry.KEY_BANNER_APK_TOAST, bannerApkToast)
            .putBoolean(HookEntry.KEY_BANNER_ALL_TOAST, bannerAllToast)
            .putBoolean(HookEntry.KEY_HIDE_TRANSLATE_BANNER, hideTranslateBanner)
            .apply()
    }

    Scaffold(
        topBar = { TopAppBar(title = "ChroStar") }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
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
        }
    }
}
