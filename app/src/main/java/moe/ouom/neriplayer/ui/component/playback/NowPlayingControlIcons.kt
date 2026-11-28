package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 播放页次级/导航按钮图标，按 [NowPlayingIconStyle] 分派。
 *
 * OUTLINED 分支与改动前图标完全一致（Shuffle/SkipPrevious/SkipNext 用 Outlined，
 * Repeat 沿用项目原有的 Outlined.Repeat + Filled.RepeatOne 组合）。
 * FILLED 分支为 Expressive / AppleMusic 主题提供实心图标，形成可见差异。
 */
internal object NowPlayingControlIcons {

    fun shuffle(style: NowPlayingIconStyle): ImageVector =
        if (style == NowPlayingIconStyle.FILLED) Icons.Filled.Shuffle else Icons.Outlined.Shuffle

    fun previous(style: NowPlayingIconStyle): ImageVector =
        if (style == NowPlayingIconStyle.FILLED) Icons.Filled.SkipPrevious else Icons.Outlined.SkipPrevious

    fun next(style: NowPlayingIconStyle): ImageVector =
        if (style == NowPlayingIconStyle.FILLED) Icons.Filled.SkipNext else Icons.Outlined.SkipNext

    /**
     * 循环图标。repeatOne 时项目原本用 Filled.RepeatOne（保持不变）；
     * FILLED 风格下非 repeatOne 用实心 Repeat，OUTLINED 保持原有 Outlined.Repeat。
     */
    fun repeat(repeatOne: Boolean, style: NowPlayingIconStyle): ImageVector =
        if (repeatOne) {
            Icons.Filled.RepeatOne
        } else if (style == NowPlayingIconStyle.FILLED) {
            Icons.Filled.Repeat
        } else {
            Icons.Outlined.Repeat
        }
}
