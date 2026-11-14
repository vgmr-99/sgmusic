package moe.ouom.neriplayer.platform.lxsource

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
 */

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.common.logging.NPLogger

/**
 * LX 兜底 Resolver：作为 :playback:runtime 的 UrlResolver 链最后一档。
 * 语义鸿沟：LX 只给播放 URL，不给搜索。Neri 的 Explore 仍走网易云/B站/YTM。
 * 因此 resolve 只做 Neri SongItem -> LX songmid 猜测 -> musicUrl。
 * songmid 猜测：阶段 1 用忽略大小写的歌名拼 hash 作为占位，阶段 2 用 LxIdMatcher 接真实搜索（本地脚本自带搜索或直连）。
 * 音质由 inited.qualitys 动态决定，阶段 1 只取 ≤ 目标的最高。
 */
class LxSourceResolver(
    private val repository: LxSourceRepository,
    private val enabledProvider: () -> Boolean = { true },
) {
    suspend fun resolve(song: SongItem, platform: LxPlatform, quality: LxQuality): SongUrlResult? {
        if (!enabledProvider()) return null
        // 来自其它平台的歌曲要用对应平台的 LX 源，
        // 拿网易云源去解析酷狗的 hash 一定失败。
        val effectivePlatform = platformForSong(song) ?: platform
        return when (
            val r = repository.resolveMusicUrlForSong(
                song = song,
                platform = effectivePlatform,
                quality = quality,
            )
        ) {
            is LxResult.Ok -> {
                NPLogger.w("LxSource", "resolved song=${song.name} via LX platform=${effectivePlatform.code} quality=${quality.code}")
                SongUrlResult.Success(
                    url = r.value.url,
                    // 脚本要求带的请求头（部分音源的地址要 Referer 才能拉流）
                    headers = r.value.headers,
                    durationMs = song.durationMs.takeIf { it > 0 },
                    mimeType = when (quality) {
                        LxQuality.FLAC, LxQuality.FLAC24 -> "audio/flac"
                        else -> "audio/mpeg"
                    },
                    audioInfo = PlaybackAudioInfo(
                        source = PlaybackAudioSource.LX,
                        qualityKey = quality.code,
                        qualityLabel = quality.code,
                        mimeType = when (quality) {
                            LxQuality.FLAC, LxQuality.FLAC24 -> "audio/flac"
                            else -> "audio/mpeg"
                        },
                    ),
                    cacheKeyOverride = "lx-${effectivePlatform.code}-${song.name}-${quality.code}",
                )
            }
            is LxResult.Err -> {
                NPLogger.w("LxSource", "resolve err: song=${song.name} code=${r.code} message=${r.message}")
                null
            }
        }
    }

    private companion object {
        /**
         * 歌曲来源平台 -> LX 源平台。
         *
         * 来自其它平台的歌曲要用对应平台的 LX 源，
         * 拿网易云源去解析酷狗的 hash 一定失败。
         * null 表示按默认（网易云）处理。
         */
        fun platformForSong(song: SongItem): LxPlatform? =
            LxPlatform.forChannelId(song.channelId)
    }
}
