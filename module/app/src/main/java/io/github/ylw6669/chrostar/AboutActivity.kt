package io.github.ylw6669.chrostar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
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
        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                AboutScreen { finish() }
            }
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val lazyListState = rememberLazyListState()
    var logoHeightPx by remember { mutableIntStateOf(0) }

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
    Box(modifier = Modifier.fillMaxSize()) {
        BgEffectBackground(
            dynamicBackground = true,
            modifier = Modifier.fillMaxSize(),
            bgModifier = Modifier.layerBackdrop(backdrop),
            isFullSize = true,
            effectBackground = true,
            alpha = { 1f - scrollProgress },
        ) {
            AboutContent(
                backdrop = backdrop,
                onBack = onBack,
                lazyListState = lazyListState,
                onLogoHeightChanged = { logoHeightPx = it },
            )
        }
    }
}

@Composable
private fun AboutContent(
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop,
    onBack: () -> Unit,
    lazyListState: LazyListState,
    onLogoHeightChanged: (Int) -> Unit,
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

    // Logo 区(背景层, 不随列表滚动): 仅标题(图标已按需求移除)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 196.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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

    // 可滚动内容: 顶部透明占位(露出 Logo, 高度决定卡片靠下程度) + 半透明链接卡片
    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "logoSpacer") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(350.dp)
                    .onSizeChanged { size -> onLogoHeightChanged(size.height) },
                contentAlignment = Alignment.TopCenter,
                content = { },
            )
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
                    summary = "145.0.7632.218 / 152.0.7977.76",
                    onClick = { },
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
