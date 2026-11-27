package moe.ouom.neriplayer.ui.screen.tab.settings.appearance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dock
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingActionBarStyle
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingButtonStyle
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingProgressStyle
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingStyle
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

/**
 * 播放页样式选择弹窗当前打开的项目。
 */
private enum class NowPlayingStyleDialogTarget {
    TEMPLATE,
    PROGRESS,
    CONTROL,
    ACTION_BAR
}

/**
 * 播放页外观设置行组。
 *
 * 播放页模板、进度条、播放键、底部功能键四类外观与「播放页控制」同处一张卡片，
 * 每行都是「图标 + 标题 + 说明 + 当前值」的统一格式，点击后用统一的选项弹窗切换。
 *
 * 本组件只输出行与弹窗，不自带卡片容器，由调用方（播放页控制卡片）提供。
 */
@Composable
internal fun SettingsNowPlayingStyleRows(modifier: Modifier = Modifier) {
    val repo = AppContainer.settingsRepo
    val scope = rememberCoroutineScope()
    var dialogTarget by remember { mutableStateOf<NowPlayingStyleDialogTarget?>(null) }

    val template by repo.nowPlayingStyleFlow.collectAsStateWithLifecycle(
        initialValue = NowPlayingStyle.Classic
    )
    val progress by repo.nowPlayingProgressStyleFlow.collectAsStateWithLifecycle(
        initialValue = NowPlayingProgressStyle.WAVE
    )
    val control by repo.nowPlayingControlStyleFlow.collectAsStateWithLifecycle(
        initialValue = NowPlayingButtonStyle.CLASSIC
    )
    val actionBar by repo.nowPlayingActionBarStyleFlow.collectAsStateWithLifecycle(
        initialValue = NowPlayingActionBarStyle.DOCK
    )

    Column(modifier = modifier) {
        NowPlayingStyleSettingRow(
            icon = Icons.Outlined.Layers,
            title = stringResource(CoreCommonR.string.settings_nowplaying_style),
            description = stringResource(CoreCommonR.string.settings_nowplaying_style_desc),
            value = stringResource(nowPlayingTemplateLabelRes(template)),
            onClick = { dialogTarget = NowPlayingStyleDialogTarget.TEMPLATE }
        )
        NowPlayingStyleSettingRow(
            icon = Icons.Outlined.Tune,
            title = stringResource(CoreCommonR.string.settings_nowplaying_progress_style),
            description = stringResource(CoreCommonR.string.settings_nowplaying_progress_style_desc),
            value = stringResource(nowPlayingProgressStyleLabelRes(progress)),
            onClick = { dialogTarget = NowPlayingStyleDialogTarget.PROGRESS }
        )
        NowPlayingStyleSettingRow(
            icon = Icons.Outlined.PlayCircle,
            title = stringResource(CoreCommonR.string.settings_nowplaying_control_style),
            description = stringResource(CoreCommonR.string.settings_nowplaying_control_style_desc),
            value = stringResource(nowPlayingControlStyleLabelRes(control)),
            onClick = { dialogTarget = NowPlayingStyleDialogTarget.CONTROL }
        )
        NowPlayingStyleSettingRow(
            icon = Icons.Outlined.Dock,
            title = stringResource(CoreCommonR.string.settings_nowplaying_action_bar_style),
            description = stringResource(CoreCommonR.string.settings_nowplaying_action_bar_style_desc),
            value = stringResource(nowPlayingActionBarStyleLabelRes(actionBar)),
            onClick = { dialogTarget = NowPlayingStyleDialogTarget.ACTION_BAR }
        )
    }

    when (dialogTarget) {
        NowPlayingStyleDialogTarget.TEMPLATE -> NowPlayingStyleDialog(
            title = stringResource(CoreCommonR.string.settings_nowplaying_style),
            onDismiss = { dialogTarget = null },
            options = NowPlayingStyle.entries.map {
                stringResource(nowPlayingTemplateLabelRes(it)) to
                    stringResource(nowPlayingTemplateDescriptionRes(it))
            },
            selectedIndex = template.ordinal,
            onSelect = { index ->
                NowPlayingStyle.entries.getOrNull(index)?.let {
                    scope.launch { repo.setNowPlayingStyle(it) }
                }
                dialogTarget = null
            }
        )

        NowPlayingStyleDialogTarget.PROGRESS -> NowPlayingStyleDialog(
            title = stringResource(CoreCommonR.string.settings_nowplaying_progress_style),
            onDismiss = { dialogTarget = null },
            options = NowPlayingProgressStyle.entries.map {
                stringResource(nowPlayingProgressStyleLabelRes(it)) to
                    stringResource(nowPlayingProgressStyleDescriptionRes(it))
            },
            selectedIndex = progress.ordinal,
            onSelect = { index ->
                NowPlayingProgressStyle.entries.getOrNull(index)?.let {
                    scope.launch { repo.setNowPlayingProgressStyle(it) }
                }
                dialogTarget = null
            }
        )

        NowPlayingStyleDialogTarget.CONTROL -> NowPlayingStyleDialog(
            title = stringResource(CoreCommonR.string.settings_nowplaying_control_style),
            onDismiss = { dialogTarget = null },
            options = NowPlayingButtonStyle.entries.map {
                stringResource(nowPlayingControlStyleLabelRes(it)) to
                    stringResource(nowPlayingControlStyleDescriptionRes(it))
            },
            selectedIndex = control.ordinal,
            onSelect = { index ->
                NowPlayingButtonStyle.entries.getOrNull(index)?.let {
                    scope.launch { repo.setNowPlayingControlStyle(it) }
                }
                dialogTarget = null
            }
        )

        NowPlayingStyleDialogTarget.ACTION_BAR -> NowPlayingStyleDialog(
            title = stringResource(CoreCommonR.string.settings_nowplaying_action_bar_style),
            onDismiss = { dialogTarget = null },
            options = NowPlayingActionBarStyle.entries.map {
                stringResource(nowPlayingActionBarStyleLabelRes(it)) to
                    stringResource(nowPlayingActionBarStyleDescriptionRes(it))
            },
            selectedIndex = actionBar.ordinal,
            onSelect = { index ->
                NowPlayingActionBarStyle.entries.getOrNull(index)?.let {
                    scope.launch { repo.setNowPlayingActionBarStyle(it) }
                }
                dialogTarget = null
            }
        )

        null -> Unit
    }
}

@Composable
private fun NowPlayingStyleSettingRow(
    icon: ImageVector,
    title: String,
    description: String,
    value: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.settingsItemClickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/**
 * 统一样式选择弹窗：标题 + 选项列表（标题 + 说明 + 选中标记）+ 关闭按钮。
 */
@Composable
private fun NowPlayingStyleDialog(
    title: String,
    onDismiss: () -> Unit,
    options: List<Pair<String, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    MiuixSettingsChoiceRow(
                        title = option.first,
                        subtitle = option.second,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) }
                    )
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(
                onClick = onDismiss,
                text = { Text(stringResource(CoreCommonR.string.action_close)) }
            )
        }
    )
}

internal fun nowPlayingTemplateLabelRes(style: NowPlayingStyle): Int = when (style) {
    NowPlayingStyle.Classic -> CoreCommonR.string.nowplaying_style_classic
    NowPlayingStyle.Expressive -> CoreCommonR.string.nowplaying_style_expressive
    NowPlayingStyle.AppleMusic -> CoreCommonR.string.nowplaying_style_apple_music
}

internal fun nowPlayingTemplateDescriptionRes(style: NowPlayingStyle): Int = when (style) {
    NowPlayingStyle.Classic -> CoreCommonR.string.nowplaying_style_classic_desc
    NowPlayingStyle.Expressive -> CoreCommonR.string.nowplaying_style_expressive_desc
    NowPlayingStyle.AppleMusic -> CoreCommonR.string.nowplaying_style_apple_music_desc
}

internal fun nowPlayingProgressStyleLabelRes(style: NowPlayingProgressStyle): Int = when (style) {
    NowPlayingProgressStyle.WAVE -> CoreCommonR.string.nowplaying_progress_style_wave
    NowPlayingProgressStyle.SEGMENTED -> CoreCommonR.string.nowplaying_progress_style_segmented
    NowPlayingProgressStyle.CAPSULE -> CoreCommonR.string.nowplaying_progress_style_capsule
}

internal fun nowPlayingProgressStyleDescriptionRes(style: NowPlayingProgressStyle): Int = when (style) {
    NowPlayingProgressStyle.WAVE -> CoreCommonR.string.nowplaying_progress_style_wave_desc
    NowPlayingProgressStyle.SEGMENTED -> CoreCommonR.string.nowplaying_progress_style_segmented_desc
    NowPlayingProgressStyle.CAPSULE -> CoreCommonR.string.nowplaying_progress_style_capsule_desc
}

internal fun nowPlayingControlStyleLabelRes(style: NowPlayingButtonStyle): Int = when (style) {
    NowPlayingButtonStyle.CLASSIC -> CoreCommonR.string.nowplaying_control_style_classic
    NowPlayingButtonStyle.RING -> CoreCommonR.string.nowplaying_control_style_ring
    NowPlayingButtonStyle.FLAT -> CoreCommonR.string.nowplaying_control_style_flat
}

internal fun nowPlayingControlStyleDescriptionRes(style: NowPlayingButtonStyle): Int = when (style) {
    NowPlayingButtonStyle.CLASSIC -> CoreCommonR.string.nowplaying_control_style_classic_desc
    NowPlayingButtonStyle.RING -> CoreCommonR.string.nowplaying_control_style_ring_desc
    NowPlayingButtonStyle.FLAT -> CoreCommonR.string.nowplaying_control_style_flat_desc
}

internal fun nowPlayingActionBarStyleLabelRes(style: NowPlayingActionBarStyle): Int = when (style) {
    NowPlayingActionBarStyle.DOCK -> CoreCommonR.string.nowplaying_action_bar_style_dock
    NowPlayingActionBarStyle.LABEL -> CoreCommonR.string.nowplaying_action_bar_style_label
    NowPlayingActionBarStyle.GLASS -> CoreCommonR.string.nowplaying_action_bar_style_glass
}

internal fun nowPlayingActionBarStyleDescriptionRes(style: NowPlayingActionBarStyle): Int = when (style) {
    NowPlayingActionBarStyle.DOCK -> CoreCommonR.string.nowplaying_action_bar_style_dock_desc
    NowPlayingActionBarStyle.LABEL -> CoreCommonR.string.nowplaying_action_bar_style_label_desc
    NowPlayingActionBarStyle.GLASS -> CoreCommonR.string.nowplaying_action_bar_style_glass_desc
}
