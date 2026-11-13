package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import moe.ouom.neriplayer.ui.theme.LocalNeriTargetColorScheme

/**
 * 当前封面的平均色，供播放页样式壳生成渐变背景。
 */
internal val LocalNowPlayingCoverPaletteColor = staticCompositionLocalOf { null as Int? }

/**
 * 用封面平均色包裹内容。
 */
@Composable
internal fun ProvideNowPlayingCoverPaletteColor(
    coverAverageColor: Int?,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalNowPlayingCoverPaletteColor provides coverAverageColor) {
        content()
    }
}

/**
 * 封面取色渐变背景。
 *
 * 优先用封面平均色生成渐变，没有封面时回落到主题强调色。
 * 供 Apple Music 风格播放页在底色上叠加一层柔和的封面色。
 */
@Composable
internal fun NowPlayingCoverGradientBackground(
    coverAverageColor: Int? = null,
    modifier: Modifier = Modifier
) {
    val colorScheme = LocalNeriTargetColorScheme.current
    val seed = if (coverAverageColor != null) Color(coverAverageColor) else colorScheme.primary

    val gradient = Brush.linearGradient(
        colors = listOf(
            seed.copy(alpha = 0.32f),
            seed.copy(alpha = 0.14f),
            Color.Transparent
        ),
        start = Offset.Zero,
        end = Offset(600f, 1400f)
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradient)
    )
}

/**
 * 从封面位图取平均色。
 *
 * 采样步长随位图大小自适应，避免大图逐像素遍历。
 */
internal fun ImageBitmap.toArgbPaletteColor(): Int? {
    val bitmapWidth = width
    val bitmapHeight = height
    if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
    val stepX = (bitmapWidth / 24).coerceAtLeast(1)
    val stepY = (bitmapHeight / 24).coerceAtLeast(1)
    val sampleWidth = ((bitmapWidth + stepX - 1) / stepX).coerceAtLeast(1)
    val sampleHeight = ((bitmapHeight + stepY - 1) / stepY).coerceAtLeast(1)
    val pixels = IntArray(bitmapWidth * bitmapHeight)
    val read = runCatching {
        readPixels(
            buffer = pixels,
            startX = 0,
            startY = 0,
            width = bitmapWidth,
            height = bitmapHeight,
            bufferOffset = 0,
            stride = bitmapWidth
        )
        true
    }.getOrDefault(false)
    if (!read) return null

    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    var y = 0
    while (y < sampleHeight) {
        var x = 0
        while (x < sampleWidth) {
            val sourceX = (x * stepX).coerceAtMost(bitmapWidth - 1)
            val sourceY = (y * stepY).coerceAtMost(bitmapHeight - 1)
            val color = pixels[sourceY * bitmapWidth + sourceX]
            val alpha = (color ushr 24) and 0xFF
            if (alpha > 24) {
                red += (color shr 16) and 0xFF
                green += (color shr 8) and 0xFF
                blue += color and 0xFF
                count += 1
            }
            x += 1
        }
        y += 1
    }
    if (count == 0L) return null
    return android.graphics.Color.rgb(
        (red / count).toInt(),
        (green / count).toInt(),
        (blue / count).toInt()
    )
}
