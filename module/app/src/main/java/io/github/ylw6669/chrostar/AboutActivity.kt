package io.github.ylw6669.chrostar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ylw6669.chrostar.effect.BgEffectBackground
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 关于页 —— AGSL 流光背景(BgEffectBackground, Android 13+) + KernelSU 式标题流光(textureBlur/layerBackdrop) +
 * 滚动时背景淡出 + 半透明圆角卡片 + ArrowPreference 信息行。版本号引用 BuildConfig(v1.15.0 单源)。
 */
class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val navOwner = rememberNavigationEventDispatcherOwner(enabled = true, parent = null)
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navOwner) {
                MiuixTheme(controller = controller) {
                    AboutScreen { finish() }
                }
            }
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lazyListState = rememberLazyListState()
    var logoHeightPx by remember { mutableIntStateOf(0) }
    var showSupportedVersions by remember { mutableStateOf(false) }

    // 滚动进度 0..1 (滚过 Logo 区域为 1), 驱动背景淡出
    val scrollProgress by remember {
        derivedStateOf {
            if (logoHeightPx <= 0) {
                0f
            } else {
                val index = lazyListState.firstVisibleItemIndex
                val offset = lazyListState.firstVisibleItemScrollOffset
                if (index > 0) 1f else (offset.toFloat() / logoHeightPx).coerceIn(0f, 1f)
            }
        }
    }

    val backdrop = rememberLayerBackdrop()
    Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            BgEffectBackground(
                dynamicBackground = true,
                modifier = Modifier.fillMaxSize(),
                bgModifier = Modifier.layerBackdrop(backdrop),
                isFullSize = true,
                effectBackground = true,
                alpha = { 1f - 0.25f * scrollProgress },
            ) {
                AboutContent(
                    backdrop = backdrop,
                    onBack = onBack,
                    lazyListState = lazyListState,
                    onLogoHeightChanged = { logoHeightPx = it },
                    onShowSupportedVersions = { showSupportedVersions = true },
                )
            }
        }

        OverlayDialog(
            show = showSupportedVersions,
            title = "适配 Chrome 版本",
            summary = "当前安装与已验证版本",
            onDismissRequest = { showSupportedVersions = false }
        ) {
            val current = CapsCache.installedVersionName(context, "com.android.chrome")
            Text(
                text = buildString {
                    if (!current.isNullOrBlank()) {
                        append("当前安装：Chrome ").append(current).append("\n\n")
                    }
                    append("已验证版本\n")
                    append("145.0.7632.218\n")
                    append("152.0.7977.82\n")
                    append("153.0.8010.47\n")
                    append("154.0.8037.49 / 154.0.8037.126\n")
                    append("155.0.8059.84")
                },
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = { showSupportedVersions = false }) {
                    Text(text = "知道了")
                }
            }
        }
    }
}

@Composable
private fun AboutContent(
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop,
    onBack: () -> Unit,
    lazyListState: LazyListState,
    onLogoHeightChanged: (Int) -> Unit,
    onShowSupportedVersions: () -> Unit,
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val effectBackground = remember(isDark) { isRuntimeShaderSupported() }

    // 照抄 KernelSU: 深浅色两套混合链, 把背景实况以纹理混合"溢彩"到 Logo/文字
    val logoBlend = remember(isDark) {
        if (isDark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }

    // 标题与卡片共用同一个 LazyColumn：上滑时标题自然移出屏幕，下滑时重新出现。
    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "logo") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
                    .onSizeChanged { size -> onLogoHeightChanged(size.height) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(196.dp))
                Text(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .then(
                            if (effectBackground) {
                                Modifier.textureBlur(
                                    backdrop = backdrop,
                                    shape = RoundedCornerShape(0.dp),
                                    blurRadius = 150f,
                                    colors = BlurColors(blendColors = logoBlend),
                                    contentBlendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                                    enabled = true,
                                )
                            } else Modifier
                        ),
                    text = "ChroStar",
                    color = if (isDark) Color(0xFFF2F2F7) else Color(0xFF1E110D),
                    fontWeight = FontWeight.Bold,
                    fontSize = 35.sp,
                )
            }
        }
        item(key = "about") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    if (isDark) Color(0xCC1E2430) else Color(0xB3FFFFFF),
                    Color.Transparent,
                ),
            ) {
                ArrowPreference(
                    title = "版本 " + BuildConfig.VERSION_NAME,
                    onClick = { },
                )
                ArrowPreference(
                    title = "适配 Chrome 版本",
                    summary = "点击查看完整适配列表",
                    onClick = onShowSupportedVersions,
                )
                ArrowPreference(
                    title = "作者 星辰",
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.coolapk.com/u/3110354"))
                        )
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
