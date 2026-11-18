package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.presentation.shouldHideRemoteDownloadAction
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadTask
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.ui.component.lyrics.resolveLyricSeekPosition
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType

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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying/NowPlayingLayoutPolicy
 * Created: 2026/11/11
 *
 * 播放页的布局策略：纯函数 + 配套常量。
 * 与 NowPlayingScreen 拆开是为了让本文件保持可单测的纯逻辑，
 * 同时把播放页主文件压回体积上限以内。
 */

internal const val LyricsPageTransitionDurationMs = 300
internal const val CoverSourceBadgeRevealBufferMs = 120
internal const val LyricsBackCancelMinimumDurationMs = 160
internal const val CoverSourceBadgeRevealDelayMs =
    LyricsPageTransitionDurationMs + CoverSourceBadgeRevealBufferMs
internal const val HighUiDensityScaleThreshold = 1.1f
internal const val CompactNowPlayingPortraitMaxHeightDp = 600f
internal const val PlaybackActionToolbarItemCount = 5
internal val PlaybackActionToolbarMinimumTouchTarget = 48.dp
internal val PlaybackActionToolbarSmallSlotThreshold = 40.dp
internal val NowPlayingMainControlsMinimumSpacing = 4.dp

internal enum class NowPlayingWideLyricsMode {
    NO_LYRICS,
    ADVANCED,
    SYNCED
}

internal fun resolveNowPlayingWideLyricsMode(
    hasLyrics: Boolean,
    advancedLyricsEnabled: Boolean
): NowPlayingWideLyricsMode = when {
    !hasLyrics -> NowPlayingWideLyricsMode.NO_LYRICS
    advancedLyricsEnabled -> NowPlayingWideLyricsMode.ADVANCED
    else -> NowPlayingWideLyricsMode.SYNCED
}

internal fun shouldUseCompactNowPlayingPortraitLayout(
    isLandscape: Boolean,
    availableHeightDp: Float,
    uiDensityScale: Float
): Boolean {
    if (isLandscape) {
        return false
    }
    return uiDensityScale >= HighUiDensityScaleThreshold ||
            (availableHeightDp > 0f && availableHeightDp <= CompactNowPlayingPortraitMaxHeightDp)
}

internal fun shouldShowNowPlayingCoverLyrics(
    coverLyricsEnabled: Boolean,
    useCompactPortraitLayout: Boolean
): Boolean = coverLyricsEnabled && !useCompactPortraitLayout

internal fun shouldUseNowPlayingToolbarDock(
    toolbarDockEnabled: Boolean,
    useCompactPortraitLayout: Boolean,
    controlsAtBottom: Boolean = false
): Boolean = toolbarDockEnabled && !useCompactPortraitLayout && !controlsAtBottom

internal data class NowPlayingCoverPagePresentation(
    val beforeCoverSpacing: Dp,
    val afterCoverSpacing: Dp,
    val preferredCoverSize: Dp?,
    val trackIdentityLayout: NowPlayingTrackIdentityLayout,
    val lyricsAdjustBehavior: Boolean
)

internal fun resolveNowPlayingCoverPagePresentation(
    immersiveMode: Boolean,
    lyricsAdjustBehavior: Boolean
): NowPlayingCoverPagePresentation = if (immersiveMode) {
    NowPlayingCoverPagePresentation(
        16.dp, 28.dp, NowPlayingImmersiveCoverSize, NowPlayingTrackIdentityLayout.LargeCover, false
    )
} else {
    NowPlayingCoverPagePresentation(
        8.dp, 16.dp, null, NowPlayingTrackIdentityLayout.Expanded, lyricsAdjustBehavior
    )
}

internal data class PlaybackActionToolbarLayout(
    val horizontalPadding: Dp,
    val minimumInteractiveComponentSize: Dp,
    val iconSize: Dp,
    val useEqualWidthSlots: Boolean
)

internal fun resolvePlaybackActionToolbarLayout(
    availableWidth: Dp,
    preferredHorizontalPadding: Dp,
    defaultIconSize: Dp,
    preferredMinimumTouchTarget: Dp = PlaybackActionToolbarMinimumTouchTarget
): PlaybackActionToolbarLayout {
    val minimumTouchTarget = preferredMinimumTouchTarget.coerceAtLeast(0.dp)
    val preferredSlotWidth = (
            (availableWidth - preferredHorizontalPadding * 2) / PlaybackActionToolbarItemCount
            ).coerceAtLeast(0.dp)
    if (preferredSlotWidth >= minimumTouchTarget) {
        return PlaybackActionToolbarLayout(
            horizontalPadding = preferredHorizontalPadding,
            minimumInteractiveComponentSize = minimumTouchTarget,
            iconSize = defaultIconSize,
            useEqualWidthSlots = false
        )
    }

    val compactSlotWidth = (availableWidth / PlaybackActionToolbarItemCount).coerceAtLeast(0.dp)
    return PlaybackActionToolbarLayout(
        horizontalPadding = 0.dp,
        minimumInteractiveComponentSize = minOf(
            minimumTouchTarget,
            compactSlotWidth
        ),
        iconSize = if (compactSlotWidth < PlaybackActionToolbarSmallSlotThreshold) {
            18.dp
        } else {
            defaultIconSize
        },
        useEqualWidthSlots = true
    )
}

internal data class NowPlayingMainControlsLayout(
    val secondaryButtonSize: Dp,
    val primaryButtonSize: Dp,
    val spacing: Dp
)

internal fun resolveNowPlayingMainControlsLayout(
    availableWidth: Dp,
    secondaryButtonSize: Dp,
    primaryButtonSize: Dp,
    preferredSpacing: Dp
): NowPlayingMainControlsLayout {
    val gapCount = PlaybackActionToolbarItemCount - 1
    val requestedButtonWidth = secondaryButtonSize * 4 + primaryButtonSize
    val minimumSpacing = minOf(
        NowPlayingMainControlsMinimumSpacing,
        availableWidth / gapCount
    )
    val availableButtonWidth = (availableWidth - minimumSpacing * gapCount)
        .coerceAtLeast(0.dp)
    val buttonScale = if (
        requestedButtonWidth.value > 0f && requestedButtonWidth > availableButtonWidth
    ) {
        (availableButtonWidth.value / requestedButtonWidth.value).coerceIn(0f, 1f)
    } else {
        1f
    }
    val resolvedSecondaryButtonSize = secondaryButtonSize * buttonScale
    val resolvedPrimaryButtonSize = primaryButtonSize * buttonScale
    val maximumSpacing = (
            (availableWidth - resolvedSecondaryButtonSize * 4 - resolvedPrimaryButtonSize) /
                    gapCount
            ).coerceAtLeast(0.dp)
    return NowPlayingMainControlsLayout(
        secondaryButtonSize = resolvedSecondaryButtonSize,
        primaryButtonSize = resolvedPrimaryButtonSize,
        spacing = minOf(preferredSpacing, maximumSpacing)
    )
}

internal fun shouldHideDownloadActionForSong(
    hasLocalDownload: Boolean,
    currentTask: DownloadTask?
): Boolean = shouldHideRemoteDownloadAction(hasLocalDownload, currentTask)

internal fun resolveNowPlayingPlaybackSourceType(
    isLocalSong: Boolean,
    isYouTubeMusicSong: Boolean,
    isFromNeteaseTag: Boolean,
    isFromBiliTag: Boolean,
    currentMediaUrl: String?,
    playbackAudioSource: PlaybackAudioSource?,
    isNeteaseLocalFallback: Boolean = false
): PlaybackSourceType? {
    if (isLocalSong || isNeteaseLocalFallback) return PlaybackSourceType.LOCAL

    when (playbackAudioSource) {
        PlaybackAudioSource.SUBSONIC -> return PlaybackSourceType.SUBSONIC
        PlaybackAudioSource.NETEASE -> return PlaybackSourceType.NETEASE
        PlaybackAudioSource.BILIBILI -> return PlaybackSourceType.BILIBILI
        PlaybackAudioSource.YOUTUBE_MUSIC -> return PlaybackSourceType.YOUTUBE_MUSIC
        PlaybackAudioSource.LX -> return PlaybackSourceType.LX
        PlaybackAudioSource.LOCAL,
        null -> Unit
    }

    if (isYouTubeMusicSong) return PlaybackSourceType.YOUTUBE_MUSIC

    val isFromNeteaseUrl = currentMediaUrl?.contains("music.126.net", ignoreCase = true) == true
    val isFromBiliUrl = currentMediaUrl?.contains("bilivideo.", ignoreCase = true) == true
    return when {
        isFromBiliTag || (!isFromNeteaseTag && isFromBiliUrl) -> PlaybackSourceType.BILIBILI
        isFromNeteaseTag || (!isFromBiliTag && isFromNeteaseUrl) -> PlaybackSourceType.NETEASE
        else -> null
    }
}

internal fun hasPublishedManagedDownload(
    song: SongItem,
    catalogLookup: (SongItem) -> Boolean = { candidate ->
        GlobalDownloadManager.hasDownloadedSongCached(candidate)
    }
): Boolean = catalogLookup(song)

internal fun hasCachedLocalDownload(song: SongItem): Boolean {
    return hasPublishedManagedDownload(song)
}

/**
 * 跳转到歌词位置。
 *
 * 播放时长与歌曲时长可能不一致，取已知较大值作为基准，避免跳到区间外。
 */
internal fun seekToLyricSafely(
    positionMs: Long,
    playbackDurationMs: Long,
    songDurationMs: Long
) {
    val knownDurationMs = maxOf(playbackDurationMs, songDurationMs)
    resolveLyricSeekPosition(positionMs, knownDurationMs)?.let(PlayerManager::seekTo)
}
