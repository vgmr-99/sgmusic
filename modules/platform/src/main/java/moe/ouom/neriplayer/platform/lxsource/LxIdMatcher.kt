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

/**
 * LX 的 songmid/hash 是平台私有 ID，NeriPlayer 的曲目身份是平台化的。
 * 跨源必须做歌名/歌手/时长多级模糊匹配，参考洛雪 src/utils/musicSdk 的 findMusic 思路。
 * 参考 PlayerManagerNeteaseAutoSourceSwitch.scoreNeteaseAutoBiliText 的打分结构。
 */
object LxIdMatcher {

    data class Candidate(
        val songmid: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val picUrl: String? = null,
        val album: String = "",
    )

    fun score(title: String, artist: String, durationMs: Long, candidate: Candidate): Int {
        var s = 0
        val t = normalize(title)
        val c = normalize(candidate.title)
        if (t.isNotBlank() && c.contains(t.replace(" ", ""))) s += 55
        val a = normalize(artist)
        val ca = normalize(candidate.artist)
        if (a.isNotBlank() && ca.contains(a.replace(" ", ""))) s += 25
        s += durationScore(durationMs, candidate.durationMs)
        return s
    }

    fun best(title: String, artist: String, durationMs: Long, candidates: List<Candidate>): Candidate? {
        return candidates.maxByOrNull { score(title, artist, durationMs, it) }
            ?.takeIf { score(title, artist, durationMs, it) >= 70 }
    }

    private fun durationScore(expectedMs: Long, candidateMs: Long): Int {
        if (expectedMs <= 0 || candidateMs <= 0) return 0
        val diff = kotlin.math.abs(candidateMs - expectedMs)
        return when {
            diff <= 8_000 -> 30
            diff <= 20_000 -> 22
            diff <= 45_000 -> 12
            candidateMs > expectedMs * 2 -> -15
            else -> 0
        }
    }

    private val nonText = Regex("[^\\p{L}\\p{N}]+")
    private val ws = Regex("\\s+")
    private fun normalize(v: String): String = ws.replace(nonText.replace(v.lowercase(), " "), " ").trim()
}
