package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.nowplaying/NowPlayingImmersivePortraitLayout
 * Created: 2026/11/11
 */

/**
 * 竖屏沉浸式布局：封面铺满剩余空间，文字区与操作区分别贴在上下。
 *
 * 用自定义 Layout 而不是 Column，是因为封面需要拿走"扣掉上下内容后"的全部高度。
 */
@Composable
internal fun NowPlayingImmersivePortraitLayout(
    modifier: Modifier,
    beforeCoverSpacing: androidx.compose.ui.unit.Dp,
    topBar: @Composable () -> Unit,
    cover: @Composable () -> Unit,
    leadingContent: @Composable ColumnScope.() -> Unit,
    trailingContent: @Composable ColumnScope.() -> Unit
) {
    Layout(
        modifier = modifier,
        content = {
            Box { topBar() }
            Box { cover() }
            Column(horizontalAlignment = Alignment.CenterHorizontally, content = leadingContent)
            Column(horizontalAlignment = Alignment.CenterHorizontally, content = trailingContent)
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val (topBarMeasurable, coverMeasurable, leadingMeasurable, trailingMeasurable) = measurables
        val topBarPlaceable = topBarMeasurable.measure(childConstraints)
        val leadingPlaceable = leadingMeasurable.measure(childConstraints)
        val trailingPlaceable = trailingMeasurable.measure(childConstraints)
        val coverTop = topBarPlaceable.height + beforeCoverSpacing.roundToPx()
        // 先为真实文字和操作区留足高度，再让封面使用剩余空间
        val coverHeight = (height - coverTop - leadingPlaceable.height -
            trailingPlaceable.height).coerceAtLeast(0)
        val coverPlaceable = coverMeasurable.measure(childConstraints.copy(maxHeight = coverHeight))
        layout(width, height) {
            topBarPlaceable.placeRelative((width - topBarPlaceable.width) / 2, 0)
            coverPlaceable.placeRelative((width - coverPlaceable.width) / 2, coverTop)
            leadingPlaceable.placeRelative((width - leadingPlaceable.width) / 2,
                coverTop + coverPlaceable.height)
            trailingPlaceable.placeRelative((width - trailingPlaceable.width) / 2,
                height - trailingPlaceable.height)
        }
    }
}
