package moe.ouom.neriplayer.ui.screen.tab.explore

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
 * File: moe.ouom.neriplayer.ui.screen.tab.explore/ExploreSearchTypeBar
 * Created: 2026/11/12
 */

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseExploreSearchType
import moe.ouom.neriplayer.ui.viewmodel.tab.SearchSource
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeExploreSearchType

/**
 * 搜索分类切换栏。
 *
 * 网易云、YouTube 与免登录平台（QQ音乐/酷狗/汽水）各自的分类不同，
 * 这里按当前来源渲染对应的分类行，平台之间切换时用滑动过渡衔接。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ExploreSearchTypeBar(
    source: SearchSource?,
    selectedNeteaseSearchType: NeteaseExploreSearchType,
    selectedYouTubeSearchType: YouTubeExploreSearchType,
    onNeteaseSearchTypeClick: (NeteaseExploreSearchType) -> Unit,
    onYouTubeSearchTypeClick: (YouTubeExploreSearchType) -> Unit,
    selectedPlatformSearchType: PlatformSearchType = PlatformSearchType.SONG,
    platformSearchTypes: List<PlatformSearchType> = emptyList(),
    onPlatformSearchTypeClick: (PlatformSearchType) -> Unit = {},
    selectedAlpha: Float,
    unselectedAlpha: Float,
    borderAlpha: Float
) {
    AnimatedContent(
        targetState = source,
        modifier = Modifier.testTag(EXPLORE_SEARCH_TYPE_BAR_CONTAINER_TAG),
        transitionSpec = {
            val sourceSwap = isExploreSearchTypeBarSourceSwap(initialState, targetState)
            val enter = if (sourceSwap) {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_ENTER_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + slideInVertically(
                    initialOffsetY = { height ->
                        if (targetState == SearchSource.YOUTUBE_MUSIC) {
                            height / EXPLORE_SEARCH_TYPE_BAR_SLIDE_DIVISOR
                        } else {
                            -height / EXPLORE_SEARCH_TYPE_BAR_SLIDE_DIVISOR
                        }
                    },
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_ENTER_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
            } else {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_ENTER_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + expandVertically(
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_SIZE_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
            }
            val exit = if (sourceSwap) {
                fadeOut(
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_EXIT_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + slideOutVertically(
                    targetOffsetY = { height ->
                        if (targetState == SearchSource.YOUTUBE_MUSIC) {
                            -height / EXPLORE_SEARCH_TYPE_BAR_SLIDE_DIVISOR
                        } else {
                            height / EXPLORE_SEARCH_TYPE_BAR_SLIDE_DIVISOR
                        }
                    },
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_EXIT_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
            } else {
                fadeOut(
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_EXIT_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + shrinkVertically(
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_SIZE_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
            }
            enter togetherWith exit using SizeTransform(
                clip = true,
                sizeAnimationSpec = { _, _ ->
                    tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_SIZE_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                }
            )
        },
        label = "explore_search_type_bar"
    ) { displayedSource ->
        when (displayedSource) {
            SearchSource.NETEASE -> {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag(EXPLORE_NETEASE_SEARCH_TYPE_BAR_TAG),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(NeteaseExploreSearchType.entries) { _, type ->
                        ExploreTagChip(
                            label = neteaseSearchTypeLabel(type),
                            icon = neteaseSearchTypeIcon(type),
                            selected = selectedNeteaseSearchType == type,
                            onClick = {
                                if (source == displayedSource) {
                                    onNeteaseSearchTypeClick(type)
                                }
                            },
                            selectedAlpha = selectedAlpha,
                            unselectedAlpha = unselectedAlpha,
                            borderAlpha = borderAlpha
                        )
                    }
                }
            }

            SearchSource.YOUTUBE_MUSIC -> {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag(EXPLORE_YOUTUBE_SEARCH_TYPE_BAR_TAG),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(YouTubeExploreSearchType.entries) { _, type ->
                        ExploreTagChip(
                            label = youtubeSearchTypeLabel(type),
                            icon = youtubeSearchTypeIcon(type),
                            selected = selectedYouTubeSearchType == type,
                            onClick = {
                                if (source == displayedSource) {
                                    onYouTubeSearchTypeClick(type)
                                }
                            },
                            selectedAlpha = selectedAlpha,
                            unselectedAlpha = unselectedAlpha,
                            borderAlpha = borderAlpha
                        )
                    }
                }
            }

            SearchSource.QQ_MUSIC,
            SearchSource.KUGOU,
            SearchSource.QISHUI -> {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag(EXPLORE_PLATFORM_SEARCH_TYPE_BAR_TAG),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(platformSearchTypes) { _, type ->
                        ExploreTagChip(
                            label = platformSearchTypeLabel(type),
                            icon = platformSearchTypeIcon(type),
                            selected = selectedPlatformSearchType == type,
                            onClick = {
                                if (source == displayedSource) {
                                    onPlatformSearchTypeClick(type)
                                }
                            },
                            selectedAlpha = selectedAlpha,
                            unselectedAlpha = unselectedAlpha,
                            borderAlpha = borderAlpha
                        )
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
internal fun neteaseSearchTypeLabel(type: NeteaseExploreSearchType): String {
    return when (type) {
        NeteaseExploreSearchType.SONG -> stringResource(CoreCommonR.string.explore_search_type_song)
        NeteaseExploreSearchType.PLAYLIST -> stringResource(CoreCommonR.string.explore_search_type_playlist)
        NeteaseExploreSearchType.ARTIST -> stringResource(CoreCommonR.string.explore_search_type_artist)
    }
}

internal fun neteaseSearchTypeIcon(type: NeteaseExploreSearchType): ImageVector {
    return when (type) {
        NeteaseExploreSearchType.SONG -> Icons.Outlined.MusicNote
        NeteaseExploreSearchType.PLAYLIST -> Icons.AutoMirrored.Outlined.QueueMusic
        NeteaseExploreSearchType.ARTIST -> Icons.Filled.AccountCircle
    }
}

@Composable
internal fun youtubeSearchTypeLabel(type: YouTubeExploreSearchType): String {
    return when (type) {
        YouTubeExploreSearchType.SONG -> stringResource(CoreCommonR.string.explore_search_type_song)
        YouTubeExploreSearchType.VIDEO -> stringResource(CoreCommonR.string.explore_search_type_video)
        YouTubeExploreSearchType.CREATOR -> stringResource(CoreCommonR.string.explore_search_type_creator)
    }
}

internal fun youtubeSearchTypeIcon(type: YouTubeExploreSearchType): ImageVector {
    return when (type) {
        YouTubeExploreSearchType.SONG -> Icons.Outlined.MusicNote
        YouTubeExploreSearchType.VIDEO -> Icons.Filled.PlayCircle
        YouTubeExploreSearchType.CREATOR -> Icons.Filled.AccountCircle
    }
}

@Composable
internal fun platformSearchTypeLabel(type: PlatformSearchType): String {
    return when (type) {
        PlatformSearchType.SONG -> stringResource(CoreCommonR.string.explore_search_type_song)
        PlatformSearchType.PLAYLIST -> stringResource(CoreCommonR.string.explore_search_type_playlist)
        PlatformSearchType.ARTIST -> stringResource(CoreCommonR.string.explore_search_type_artist)
    }
}

internal fun platformSearchTypeIcon(type: PlatformSearchType): ImageVector {
    return when (type) {
        PlatformSearchType.SONG -> Icons.Outlined.MusicNote
        PlatformSearchType.PLAYLIST -> Icons.AutoMirrored.Outlined.QueueMusic
        PlatformSearchType.ARTIST -> Icons.Filled.AccountCircle
    }
}

/** 当前来源是否需要显示分类切换栏 */
internal fun exploreSearchTypeBarSource(
    selectedSearchSource: SearchSource,
    contentScrolled: Boolean
): SearchSource? {
    if (contentScrolled) return null
    return selectedSearchSource.takeIf { it in EXPLORE_SEARCH_TYPE_BAR_SOURCES }
}

internal fun isExploreSearchTypeBarSourceSwap(
    initialSource: SearchSource?,
    targetSource: SearchSource?
): Boolean {
    return initialSource != targetSource &&
        initialSource in EXPLORE_SEARCH_TYPE_BAR_SOURCES &&
        targetSource in EXPLORE_SEARCH_TYPE_BAR_SOURCES
}

private val EXPLORE_SEARCH_TYPE_BAR_SOURCES = setOf(
    SearchSource.NETEASE,
    SearchSource.YOUTUBE_MUSIC,
    SearchSource.QQ_MUSIC,
    SearchSource.KUGOU,
    SearchSource.QISHUI
)
private const val EXPLORE_SEARCH_TYPE_BAR_ENTER_DURATION_MS = 180
private const val EXPLORE_SEARCH_TYPE_BAR_EXIT_DURATION_MS = 140
private const val EXPLORE_SEARCH_TYPE_BAR_SIZE_DURATION_MS = 220
private const val EXPLORE_SEARCH_TYPE_BAR_SLIDE_DIVISOR = 5
internal const val EXPLORE_SEARCH_TYPE_BAR_CONTAINER_TAG = "explore_search_type_bar"
internal const val EXPLORE_NETEASE_SEARCH_TYPE_BAR_TAG = "explore_netease_search_type_bar"
internal const val EXPLORE_YOUTUBE_SEARCH_TYPE_BAR_TAG = "explore_youtube_search_type_bar"
internal const val EXPLORE_PLATFORM_SEARCH_TYPE_BAR_TAG = "explore_platform_search_type_bar"
