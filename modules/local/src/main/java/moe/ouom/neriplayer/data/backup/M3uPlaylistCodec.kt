package moe.ouom.neriplayer.data.backup

import moe.ouom.neriplayer.data.model.SongItem

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
 * File: moe.ouom.neriplayer.data.backup/M3uPlaylistCodec
 * Created: 2026/11/11
 */

/**
 * M3U / M3U8 歌单格式编解码。
 *
 * M3U 是各播放器通用的歌单交换格式，导入时按"歌手 - 歌名"或纯歌名匹配到本地歌曲，
 * 导出时写回可被其它播放器识别的条目。
 */
object M3uPlaylistCodec {

    const val EXTENSION = ".m3u8"
    private const val EXTENDED_HEADER = "#EXTM3U"

    /**
     * 把歌单编码成 M3U8 文本。
     *
     * 条目同时写入 EXTINF 元信息与路径，兼容只解析其中一种的播放器。
     */
    fun encode(songs: List<SongItem>, playlistName: String? = null): String = buildString {
        appendLine(EXTENDED_HEADER)
        playlistName?.takeIf { it.isNotBlank() }?.let {
            appendLine("#PLAYLIST:$it")
        }
        songs.forEach { song ->
            val title = song.displayTitle()
            appendLine("#EXTINF:${song.durationMs / 1000},$title - ${song.displayArtist()}")
            val path = song.exportLocation()
            if (path != null) {
                appendLine(path)
            }
        }
    }

    /**
     * 解析 M3U 文本，返回可导入的条目。
     *
     * 只保留带路径的行；EXTINF 行仅用于补充时长与标题。
     */
    fun decode(text: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        var pendingTitle: String? = null
        var pendingDurationMs: Long? = null
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:") -> {
                    val payload = line.removePrefix("#EXTINF:")
                    val durationText = payload.substringBefore(',').trim()
                    pendingDurationMs = durationText.toLongOrNull()?.takeIf { it >= 0 }
                        ?.times(1000)
                    pendingTitle = payload.substringAfter(',', "").trim().takeIf { it.isNotEmpty() }
                }

                line.startsWith("#") -> Unit
                else -> {
                    entries += M3uEntry(
                        location = line,
                        title = pendingTitle,
                        durationMs = pendingDurationMs
                    )
                    pendingTitle = null
                    pendingDurationMs = null
                }
            }
        }
        return entries
    }

    /**
     * 判断文本是否像 M3U 歌单。
     *
     * 用扩展头或至少一条可解析路径来判定，避免把普通文本误当成歌单。
     */
    fun looksLikeM3u(text: String): Boolean {
        val lines = text.lineSequence().take(64).map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return false
        if (lines.first().startsWith(EXTENDED_HEADER)) return true
        return lines.any { line ->
            !line.startsWith("#") && (line.contains("://") || line.contains('/'))
        }
    }
}

/** M3U 中的一条记录。 */
data class M3uEntry(
    val location: String,
    val title: String? = null,
    val durationMs: Long? = null
) {
    /** 从标题里拆出歌手与歌名，兼容 "歌手 - 歌名" 写法。 */
    fun splitArtistAndTitle(): Pair<String?, String> {
        val raw = title?.takeIf { it.isNotBlank() } ?: location.substringAfterLast('/')
            .substringBeforeLast('.')
        val separatorIndex = raw.indexOf(" - ")
        if (separatorIndex <= 0 || separatorIndex + 3 >= raw.length) return null to raw
        return raw.substring(0, separatorIndex).trim() to raw.substring(separatorIndex + 3).trim()
    }
}

private fun SongItem.displayTitle(): String = customName ?: name

private fun SongItem.displayArtist(): String = customArtist ?: artist

/**
 * 导出时使用的歌曲位置。
 *
 * 本地文件用绝对路径，联网歌曲没有稳定地址时跳过该条目。
 */
private fun SongItem.exportLocation(): String? {
    localFilePath?.takeIf { it.isNotBlank() }?.let { return it }
    mediaUri?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}
