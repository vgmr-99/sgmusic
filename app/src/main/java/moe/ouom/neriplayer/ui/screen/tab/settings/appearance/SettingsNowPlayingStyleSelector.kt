package moe.ouom.neriplayer.ui.screen.tab.settings.appearance

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingStyle
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriMotion

/**
 * 播放页样式选择器。
 *
 * 三个选项对应 Now Playing 的三套视觉外壳，切换后立即生效。
 */
@Composable
internal fun SettingsNowPlayingStyleSelector(modifier: Modifier = Modifier) {
    val repo = AppContainer.settingsRepo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentStyle by repo.nowPlayingStyleFlow.collectAsStateWithLifecycle(
        initialValue = NowPlayingStyle.Classic
    )
    Column(modifier = modifier.fillMaxWidth()) {
        MiuixSettingsSectionIntro(
            title = stringResource(CoreCommonR.string.settings_nowplaying_style),
            description = stringResource(CoreCommonR.string.settings_nowplaying_style_desc)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NowPlayingStyle.entries.forEach { option ->
                NowPlayingStyleTile(
                    style = option,
                    selected = option == currentStyle,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (option != currentStyle) {
                            context.performHapticFeedback()
                            scope.launch { repo.setNowPlayingStyle(option) }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun NowPlayingStyleTile(
    style: NowPlayingStyle,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val selection by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = NeriMotion.smooth(),
        label = "now_playing_style_tile_selection"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.outlineVariant,
        animationSpec = NeriMotion.smooth(),
        label = "now_playing_style_tile_border"
    )
    val tileShape = ContinuousRoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .clip(tileShape)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .graphicsLayer {
                    val scale = 0.97f + 0.03f * selection
                    scaleX = scale
                    scaleY = scale
                }
                .border(BorderStroke((1f + 1.5f * selection).dp, borderColor), tileShape)
                .padding(5.dp)
        ) {
            when (style) {
                NowPlayingStyle.Classic -> ClassicStyleMock()
                NowPlayingStyle.Expressive -> ExpressiveStyleMock()
                NowPlayingStyle.AppleMusic -> AppleMusicStyleMock()
            }
            if (selection > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .graphicsLayer {
                            alpha = selection
                            scaleX = 0.6f + 0.4f * selection
                            scaleY = 0.6f + 0.4f * selection
                        }
                        .clip(CircleShape)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = colors.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(style.labelRes()),
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) colors.primary else colors.onSurface
        )
        Text(
            text = stringResource(style.descriptionRes()),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            minLines = 2,
            maxLines = 3,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

private fun NowPlayingStyle.labelRes(): Int = when (this) {
    NowPlayingStyle.Classic -> CoreCommonR.string.nowplaying_style_classic
    NowPlayingStyle.Expressive -> CoreCommonR.string.nowplaying_style_expressive
    NowPlayingStyle.AppleMusic -> CoreCommonR.string.nowplaying_style_apple_music
}

private fun NowPlayingStyle.descriptionRes(): Int = when (this) {
    NowPlayingStyle.Classic -> CoreCommonR.string.nowplaying_style_classic_desc
    NowPlayingStyle.Expressive -> CoreCommonR.string.nowplaying_style_expressive_desc
    NowPlayingStyle.AppleMusic -> CoreCommonR.string.nowplaying_style_apple_music_desc
}

/** 传统风格：浅色画布 + 顶部封面方块 + 底部控制条。 */
@Composable
private fun ClassicStyleMock() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.8f)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceContainer)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MockBar(0.55f, colors.onSurface.copy(alpha = 0.7f), 8)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primaryContainer)
        )
        MockBar(0.8f, colors.onSurfaceVariant.copy(alpha = 0.5f), 6)
        MockBar(0.6f, colors.onSurfaceVariant.copy(alpha = 0.35f), 6)
    }
}

/** 强调风格：顶部强调色渐变 + 更大的封面方块。 */
@Composable
private fun ExpressiveStyleMock() {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.8f)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.primary.copy(alpha = 0.35f),
                        colors.surfaceContainer
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MockBar(0.5f, colors.onSurface.copy(alpha = 0.7f), 8)
            Box(
                Modifier
                    .fillMaxWidth(0.8f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.primary.copy(alpha = 0.75f))
            )
            MockBar(0.7f, colors.onSurfaceVariant.copy(alpha = 0.45f), 6)
        }
    }
}

/** Apple Music 风格：深色渐变底 + 居中封面 + 歌词横条。 */
@Composable
private fun AppleMusicStyleMock() {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.8f)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF0B0B0F), Color(0xFF1A1A2E), Color(0xFF08080C))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.primary.copy(alpha = 0.85f))
            )
            Spacer(Modifier.height(2.dp))
            MockBar(0.72f, Color.White.copy(alpha = 0.85f), 5)
            MockBar(0.55f, Color.White.copy(alpha = 0.35f), 4)
            MockBar(0.62f, Color.White.copy(alpha = 0.22f), 4)
        }
    }
}

@Composable
private fun MockBar(widthFraction: Float, color: Color, height: Int) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height.dp)
            .clip(CircleShape)
            .background(color)
    )
}
