package moe.ouom.neriplayer.lyrics.parser

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software. If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * 「这首歌没有收录歌词」的占位歌词识别。
 *
 * 网易云对未收录歌词的歌曲会返回
 * `{"uncollected":true,...,"lrc":{"lyric":"[00:00.00]暂无歌词"}}`，
 * 其它平台也有类似的占位语。这**不是**真歌词，必须在解析层就丢掉：
 *
 * - 播放页会把「暂无歌词」当成一行歌词画出来（用户看到的就是字面的「暂无歌词」）；
 * - 平台歌词「非空」会挡住后面的兜底链路（LX 脚本、多源高置信匹配），
 *   于是「用 LX 音源播放汽水 / 咪咕这类平台的歌」永远读不到歌词。
 */
private val PlaceholderLyricTexts = setOf(
    "暂无歌词",
    "暂无歌词。",
    "暂无歌词.",
    "暂無歌詞",
    "纯音乐，请欣赏",
    "純音樂，請欣賞",
    "此歌曲为没有填词的音乐",
    "此歌曲為沒有填詞的音樂",
    "This song is not available yet",
    "No lyrics available"
)

private val LyricLineTimestampPrefixRegex =
    Regex("""^\s*(?:\[[^\]]{1,60}\]|\([^\)]{1,60}\))+\s*""")

/**
 * 去掉行首时间戳后，文本是否就是「没有歌词」的占位语。
 *
 * 时间戳前缀要一并去掉，因为网易云的占位语是 `[00:00.00]暂无歌词`。
 */
fun isPlaceholderLyricLine(text: String?): Boolean {
    val stripped = text?.trim().orEmpty()
        .replace(LyricLineTimestampPrefixRegex, "")
        .trim()
    if (stripped.isEmpty()) return false
    return PlaceholderLyricTexts.any { it.equals(stripped, ignoreCase = true) }
}

/**
 * 整段歌词是否只有占位语（网易云返回的 `[00:00.00]暂无歌词` 就是这种）。
 *
 * 空串不算占位 —— 空歌词本来就表示「没有歌词」，由调用方自己处理。
 */
fun isPlaceholderLyricContent(content: String?): Boolean {
    if (content.isNullOrBlank()) return false
    val lines = content.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()
    if (lines.isEmpty()) return false
    return lines.all { isPlaceholderLyricLine(it) }
}
