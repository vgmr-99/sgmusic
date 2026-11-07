package moe.ouom.neriplayer.core.player.resolver.lxsource

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

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.platform.lxsource.LxPlatform
import moe.ouom.neriplayer.platform.lxsource.LxQuality
import moe.ouom.neriplayer.platform.lxsource.LxSourceResolver
import moe.ouom.neriplayer.common.logging.NPLogger

/**
 * LX 兜底：网易云失败 -> Bilibili 匹配失败 -> LX 自定义源兜底（最后一档）。
 * 由 getNeteaseSongUrl 在 tryResolveNeteaseAutoBiliSource 之后调用。
 * 语义：LX 只做 musicUrl 解析，不做搜索；songmid 由 LxSourceResolver 用歌名+歌手猜测（阶段 1 占位）。
 * 阶段 2 用 LxIdMatcher 接真实匹配。
 */
internal suspend fun PlayerManager.tryResolveLxSource(
    song: SongItem,
): SongUrlResult? {
    // LX 总开关已移除：只要有就绪脚本即可兜底
    val resolver = lxSourceResolver() ?: return null
    val platform = LxPlatform.WY
    val quality = when (lxSourceQuality.trim().lowercase()) {
        "128k" -> LxQuality.Q128
        "flac" -> LxQuality.FLAC
        "flac24bit" -> LxQuality.FLAC24
        else -> LxQuality.Q320
    }
    return try {
        val result = resolver.resolve(song, platform, quality)
        if (result != null) {
            NPLogger.w("NERI-PlayerManager", "LX source selected: song=${song.name}, platform=${platform.code}, quality=${quality.code}")
        } else {
            NPLogger.d("NERI-PlayerManager", "LX source not found: song=${song.name}")
        }
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NPLogger.w("NERI-PlayerManager", "LX source resolve failed: ${e.message}")
        null
    }
}

private fun PlayerManager.lxSourceResolver(): LxSourceResolver? {
    return try {
        val repo = PlayerDependencies.repositories.lxSourceRepository ?: return null
        LxSourceResolver(repo)
    } catch (_: Exception) {
        null
    }
}
