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
 * File: moe.ouom.neriplayer.ui.screen.tab.explore/ExplorePlatformBrowseContent
 * Created: 2026/11/12
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.stablePlatformSongId
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.screen.tab.home.PlaylistCard
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.util.platform.tryStartActivity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 平台浏览页状态。
 *
 * @param cards 歌单 / 榜单卡片
 * @param trackTitle 展开中的榜单标题；非空表示当前展示的是榜单曲目
 */
internal data class PlatformBrowseState(
    val cards: List<PlatformBrowseCard> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val categories: List<String> = emptyList(),
    val selectedCategory: String = "",
    val trackTitle: String = "",
    val tracks: List<PlatformSearchResult> = emptyList(),
    val tracksLoading: Boolean = false,
    /**
     * 展开中歌单的官方页面链接。
     *
     * 正常点开就能在站内展开曲目；只有接口读不到时才用它兜底，
     * 给一个「去官方页面看」的出口。
     */
    val trackWebUrl: String? = null
)

/**
 * 平台浏览页（探索页默认内容）。
 *
 * 和网易云的精品歌单网格同构：上面是卡片网格，点开某个榜单后
 * 在同一页展开它的曲目，曲目可以直接播放。
 */
@Composable
internal fun PlatformBrowseContent(
    browse: PlatformBrowseState,
    onCardClick: (PlatformBrowseCard) -> Unit,
    onCategoryClick: (String) -> Unit,
    /** 加载超时/失败后的重试入口，避免用户只能对着转圈或死文案干等 */
    onRetry: () -> Unit = {},
    onTrackClick: (PlatformSearchResult) -> Unit,
    onCloseTracks: () -> Unit,
    onPlayTrackNow: (PlatformSearchResult) -> Unit,
    /**
     * 分类 key 的展示名。
     *
     * 分类 key 是稳定 id（数字、组合串），没法直接显示，
     * 由调用方把它翻译成中文名；选中判定仍然用 key。
     */
    categoryLabel: (String) -> String = { it },
    tagChipSelectedAlpha: Float = 1f,
    tagChipUnselectedAlpha: Float = 1f,
    tagChipBorderAlpha: Float = 1f,
    isTabletLayout: Boolean = false
) {
    if (browse.trackTitle.isNotEmpty()) {
        PlatformBrowseTrackList(
            browse = browse,
            onTrackClick = onTrackClick,
            onCloseTracks = onCloseTracks,
            onPlayTrackNow = onPlayTrackNow
        )
        return
    }
    PlatformBrowseCardGrid(
        browse = browse,
        onCardClick = onCardClick,
        onCategoryClick = onCategoryClick,
        onRetry = onRetry,
        categoryLabel = categoryLabel,
        tagChipSelectedAlpha = tagChipSelectedAlpha,
        tagChipUnselectedAlpha = tagChipUnselectedAlpha,
        tagChipBorderAlpha = tagChipBorderAlpha,
        isTabletLayout = isTabletLayout
    )
}

@Composable
private fun PlatformBrowseCardGrid(
    browse: PlatformBrowseState,
    onCardClick: (PlatformBrowseCard) -> Unit,
    onCategoryClick: (String) -> Unit,
    onRetry: () -> Unit,
    categoryLabel: (String) -> String,
    tagChipSelectedAlpha: Float,
    tagChipUnselectedAlpha: Float,
    tagChipBorderAlpha: Float,
    isTabletLayout: Boolean
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val gridHorizontalPadding = if (isTabletLayout) 56.dp else 16.dp
    val gridMinCellSize = if (isTabletLayout) 170.dp else 150.dp
    val gridSpacing = if (isTabletLayout) 16.dp else 12.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(gridMinCellSize),
        verticalArrangement = Arrangement.spacedBy(gridSpacing),
        horizontalArrangement = Arrangement.spacedBy(gridSpacing),
        contentPadding = PaddingValues(
            start = gridHorizontalPadding,
            end = gridHorizontalPadding,
            top = 16.dp,
            bottom = 16.dp + miniPlayerHeight
        ),
        modifier = Modifier.exploreCenteredMaxWidth(ExploreBrowseContentMaxWidth)
    ) {
        if (browse.categories.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                // 和网易云的精品歌单页一样：分类横向滚动 + 两端渐隐
                val tagListState = rememberLazyListState()
                val showTagStartFade by remember(tagListState) {
                    derivedStateOf { tagListState.canScrollBackward }
                }
                val showTagEndFade by remember(tagListState) {
                    derivedStateOf { tagListState.canScrollForward }
                }
                LazyRow(
                    state = tagListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .exploreHorizontalEdgeFade(
                            showStartFade = showTagStartFade,
                            showEndFade = showTagEndFade
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 用 LazyListScope 的 items(count) 成员，
                    // 免得和外层网格的 items(List) 重载撞车
                    items(browse.categories.size) { index ->
                        val category = browse.categories[index]
                        ExploreTagChip(
                            // 展示中文名，选中判定仍然用原始 key
                            label = categoryLabel(category),
                            selected = category == browse.selectedCategory,
                            onClick = { onCategoryClick(category) },
                            selectedAlpha = tagChipSelectedAlpha,
                            unselectedAlpha = tagChipUnselectedAlpha,
                            borderAlpha = tagChipBorderAlpha
                        )
                    }
                }
            }
        }
        when {
            browse.loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                // 光一个转圈容易让人以为卡死，带上文案说明正在做什么
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(CoreCommonR.string.explore_platform_browse_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }

            browse.error != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = browse.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = stringResource(CoreCommonR.string.explore_platform_browse_retry),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRetry() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            browse.cards.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(CoreCommonR.string.explore_platform_browse_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp)
                )
            }

            else -> items(browse.cards, key = { it.provider.code + "-" + it.id }) { card ->
                PlaylistCard(
                    playlist = card.toPlaylistSummary(),
                    isFavorite = false,
                    onClick = { onCardClick(card) },
                    offlineMode = false
                )
            }
        }
    }
}

@Composable
internal fun PlatformBrowseTrackList(
    browse: PlatformBrowseState,
    onTrackClick: (PlatformSearchResult) -> Unit,
    onCloseTracks: () -> Unit,
    onPlayTrackNow: (PlatformSearchResult) -> Unit
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val context = LocalContext.current
    val songs = browse.tracks.map { it.toSongItem() }
    LazyColumn(
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            bottom = 16.dp + miniPlayerHeight
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.exploreCenteredMaxWidth(ExploreBrowseContentMaxWidth)
    ) {
        item(key = "platform_browse_track_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onCloseTracks)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(CoreCommonR.string.action_back),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = browse.trackTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (browse.tracksLoading) {
            item(key = "platform_browse_track_loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        } else if (browse.tracks.isEmpty()) {
            item(key = "platform_browse_track_empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(CoreCommonR.string.explore_platform_browse_tracks_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val webUrl = browse.trackWebUrl
                    if (!webUrl.isNullOrBlank()) {
                        Text(
                            text = stringResource(CoreCommonR.string.explore_platform_browse_open_web),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { openPlatformWebPage(context, webUrl) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        } else {
            itemsIndexedCompat(songs) { index, song ->
                SongRow(
                    state = ExploreSongRowState(
                        index = index + 1,
                        song = song,
                        isFavorite = false,
                        favoriteActionEnabled = false,
                        offlineMode = false
                    ),
                    actions = ExploreSongRowActions(
                        onClick = { onTrackClick(browse.tracks[index]) },
                        onPlayNow = { onPlayTrackNow(browse.tracks[index]) },
                        onPlayNext = { },
                        onAddToQueueEnd = { },
                        onDownload = { },
                        onToggleFavorite = { },
                        onCopyInfo = { }
                    )
                )
            }
        }
    }
}

/** LazyListScope 的 indexed items 简化版，避免再引一批 import */
private inline fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexedCompat(
    items: List<T>,
    crossinline itemContent: @Composable androidx.compose.foundation.lazy.LazyItemScope.(index: Int, item: T) -> Unit
) = items(items.size) { index -> itemContent(index, items[index]) }

private fun PlatformBrowseCard.toPlaylistSummary(): PlaylistSummary = PlaylistSummary(
    id = (provider.code + "-" + id).hashCode().toLong().and(0x7FFFFFFFFFFFFFFFL),
    name = title,
    picUrl = coverUrl.orEmpty(),
    playCount = playCount,
    trackCount = trackCount
)

/** 平台搜索结果 -> 通用歌曲条目 */
internal fun PlatformSearchResult.toSongItem(): SongItem = SongItem(
    id = stablePlatformSongId(provider, id),
    name = name,
    artist = subtitle,
    album = album,
    albumId = 0L,
    durationMs = durationMs,
    coverUrl = coverUrl,
    channelId = provider.code,
    audioId = id
)

/** 在浏览器里打开平台页面（站内读不到内容时的兜底出口） */
internal fun openPlatformWebPage(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.tryStartActivity(intent)
}
