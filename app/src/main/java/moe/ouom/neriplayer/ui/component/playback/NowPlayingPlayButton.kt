package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.haptic.HapticFilledIconButton

/**
 * 播放键容器规格。
 *
 * 三款播放键样式的差异全部集中在这里：容器形状、底色、图标颜色与图标放大倍率。
 * 调用方只负责把业务状态（播放中 / 等待 / 静音）传进来。
 */
internal data class NowPlayingPlayButtonSpec(
    val shape: NowPlayingButtonShape,
    val iconStyle: NowPlayingIconStyle,
    /** 容器底色；透明表示不绘制底。 */
    val containerColor: Color,
    /** 图标着色。 */
    val contentColor: Color,
    /** 环形描边颜色；null 表示不描边。 */
    val borderColor: Color?,
    /** 图标相对基准尺寸的放大倍率。 */
    val iconScale: Float
)

/**
 * 解析播放键容器规格。
 *
 * @param accentColor 圆环形态使用的强调色
 * @param primaryColor 实心形态的容器底色（与应用主题主色一致）
 * @param onPrimaryColor 容器底色上的前景色
 * @param plainColor 无底形态使用的常规前景色
 */
internal fun resolveNowPlayingPlayButtonSpec(
    buttonShape: NowPlayingButtonShape,
    iconStyle: NowPlayingIconStyle,
    accentColor: Color,
    primaryColor: Color,
    onPrimaryColor: Color,
    plainColor: Color
): NowPlayingPlayButtonSpec = when (buttonShape) {
    NowPlayingButtonShape.CIRCLE -> NowPlayingPlayButtonSpec(
        shape = buttonShape,
        iconStyle = iconStyle,
        containerColor = primaryColor,
        contentColor = onPrimaryColor,
        borderColor = null,
        iconScale = 1f
    )

    NowPlayingButtonShape.RING -> NowPlayingPlayButtonSpec(
        shape = buttonShape,
        iconStyle = iconStyle,
        containerColor = Color.Transparent,
        contentColor = accentColor,
        borderColor = accentColor,
        iconScale = 1f
    )

    NowPlayingButtonShape.FLAT -> NowPlayingPlayButtonSpec(
        shape = buttonShape,
        iconStyle = iconStyle,
        containerColor = Color.Transparent,
        contentColor = plainColor,
        borderColor = null,
        iconScale = 1.45f
    )
}

/**
 * 播放页主播放键。
 *
 * 形态由 [NowPlayingButtonShape] 决定：实心圆 / 圆环 / 扁平。
 * 等待态与静音态的图标切换仍由 [PlaybackControlIndicator] 负责。
 */
@Composable
internal fun NowPlayingPlayButton(
    onClick: () -> Unit,
    isPlaying: Boolean,
    isPlaybackWaiting: Boolean,
    isAudioRouteMuted: Boolean,
    playContentDescription: String,
    pauseContentDescription: String,
    waitingContentDescription: String,
    restoreVolumeContentDescription: String,
    iconStyle: NowPlayingIconStyle,
    buttonShape: NowPlayingButtonShape,
    accentColor: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true
) {
    val colorScheme = MaterialTheme.colorScheme
    val spec = resolveNowPlayingPlayButtonSpec(
        buttonShape = buttonShape,
        iconStyle = iconStyle,
        accentColor = accentColor,
        primaryColor = colorScheme.primary,
        onPrimaryColor = colorScheme.onPrimary,
        plainColor = LocalContentColor.current
    )
    val resolvedIconSize = (iconSize * spec.iconScale).coerceAtLeast(18.dp)
    val colors: IconButtonColors = IconButtonDefaults.iconButtonColors(
        containerColor = spec.containerColor,
        contentColor = spec.contentColor
    )
    val borderModifier = if (spec.borderColor != null) {
        Modifier.border(width = 2.dp, color = spec.borderColor, shape = CircleShape)
    } else {
        Modifier
    }

    HapticFilledIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = colors,
        modifier = modifier.then(borderModifier)
    ) {
        PlaybackControlIndicator(
            isPlaying = isPlaying,
            isPlaybackWaiting = isPlaybackWaiting,
            isAudioRouteMuted = isAudioRouteMuted,
            playContentDescription = playContentDescription,
            pauseContentDescription = pauseContentDescription,
            waitingContentDescription = waitingContentDescription,
            restoreVolumeContentDescription = restoreVolumeContentDescription,
            modifier = Modifier.size(resolvedIconSize),
            progressIndicatorSize = resolvedIconSize,
            iconStyle = spec.iconStyle
        )
    }
}
