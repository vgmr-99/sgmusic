package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingStyle
import moe.ouom.neriplayer.ui.theme.LocalNeriTargetColorScheme
import moe.ouom.neriplayer.ui.theme.NeriDesign

/**
 * 播放页视觉外壳，按 [NowPlayingStyle] 渲染不同的背景与配色。
 *
 * Classic 跟随应用主题；Expressive 用强调色铺一层渐变；AppleMusic 使用深色渐变 + 动态取色背景。
 * 内容槽由播放页现有的封面、歌词与控制条填充。
 */
@Composable
internal fun NowPlayingStyleShell(
    style: NowPlayingStyle,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colorScheme = LocalNeriTargetColorScheme.current
    when (style) {
        NowPlayingStyle.Classic -> ClassicNowPlayingShell(modifier = modifier, content = content)
        NowPlayingStyle.Expressive -> ExpressiveNowPlayingShell(
            modifier = modifier,
            accentColor = colorScheme.primary,
            content = content
        )
        NowPlayingStyle.AppleMusic -> AppleMusicNowPlayingShell(
            modifier = modifier,
            accentColor = colorScheme.primary,
            content = content
        )
    }
}

/** 传统布局：完全跟随应用主题，不额外覆盖背景。 */
@Composable
private fun ClassicNowPlayingShell(
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (!NeriDesign.isClean) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )
        }
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** 强调风格：顶部铺一层强调色渐变，底部保持中性，封面与控件形成更强对比。 */
@Composable
private fun ExpressiveNowPlayingShell(
    modifier: Modifier,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    val gradient = Brush.linearGradient(
        colors = listOf(
            accentColor.copy(alpha = 0.22f),
            accentColor.copy(alpha = 0.08f),
            Color.Transparent
        ),
        start = Offset.Zero,
        end = Offset(400f, 1200f)
    )
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
        )
        // 封面取色叠加，让底色跟随当前歌曲变化
        NowPlayingCoverGradientBackground(
            coverAverageColor = LocalNowPlayingCoverPaletteColor.current
        )
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** Apple Music 风格：深色渐变底，歌词与封面成为唯一视觉焦点。 */
@Composable
private fun AppleMusicNowPlayingShell(
    modifier: Modifier,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    val gradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF0B0B0F),
            accentColor.copy(alpha = 0.22f),
            Color(0xFF08080C)
        ),
        start = Offset.Zero,
        end = Offset(600f, 1400f)
    )
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
        )
        // 封面取色叠加，让底色跟随当前歌曲变化
        NowPlayingCoverGradientBackground(
            coverAverageColor = LocalNowPlayingCoverPaletteColor.current
        )
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}
