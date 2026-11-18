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

import org.json.JSONArray
import org.json.JSONObject

/**
 * 洛雪音源返回的歌词包。
 *
 * 洛雪协议的 lyric action 允许脚本返回多种形态：
 * 纯文本 LRC、带逐字的 JSON 对象、翻译/罗马音分开返回等。
 * 这里按 Mint-Music 的 `_parseLyricBundle` 归一化成四个字段。
 *
 * @param lrc 普通歌词（整行时间轴）
 * @param crlyric 逐字歌词（word-by-word）
 * @param tlyric 翻译歌词
 * @param rlyric 罗马音歌词
 */
data class LxLyricBundle(
    val lrc: String? = null,
    val crlyric: String? = null,
    val tlyric: String? = null,
    val rlyric: String? = null,
) {
    /** 有没有拿到任何歌词内容 */
    val isEmpty: Boolean
        get() = listOf(lrc, crlyric, tlyric, rlyric).all { it.isNullOrBlank() }

    /** 优先给逐字歌词，没有则给普通歌词 */
    val best: String?
        get() = crlyric?.takeIf { it.isNotBlank() }
            ?: lrc?.takeIf { it.isNotBlank() }
            ?: tlyric?.takeIf { it.isNotBlank() }
            ?: rlyric?.takeIf { it.isNotBlank() }
}

/**
 * 歌词包解析（对齐 Mint-Music 的 `_parseLyricBundle`）。
 */
object LxLyricParser {

    private val WORD_KEYS = listOf(
        "crlyric", "lxlyric", "yrc", "krc", "qrc", "mrc", "lrcx", "lyricx",
        "wordLyric", "wordlyric", "word", "words", "ttml", "ttmlLyric",
    )
    private val PLAIN_KEYS = listOf("lrc", "lyric", "text", "content", "raw")
    private val TRANSLATION_KEYS = listOf(
        "tlyric", "tlrc", "translation", "translatedLyric", "trans",
    )
    private val ROMAN_KEYS = listOf("rlyric", "rlrc", "roma", "romanLyric", "roman")

    /**
     * 解析脚本返回的歌词内容。
     *
     * 支持：纯文本、多重 JSON 字符串包裹、对象（含 data 嵌套）三种形态。
     */
    fun parse(raw: String?): LxLyricBundle {
        if (raw.isNullOrBlank()) return LxLyricBundle()
        var decoded: Any? = raw
        // JSON 字符串可能被套多层，逐层解开
        repeat(3) {
            if (decoded !is String) return@repeat
            val text = decoded.trim()
            if (text.isEmpty() || text == "null" || text == "undefined") {
                decoded = null
                return@repeat
            }
            decoded = try {
                JSONObject(text)
            } catch (_: Exception) {
                try {
                    JSONArray(text)
                } catch (_: Exception) {
                    text
                }
            }
        }
        return when (decoded) {
            null -> LxLyricBundle()
            is JSONObject -> parseObject(decoded)
            is JSONArray -> LxLyricBundle(lrc = decoded.toString())
            else -> {
                val text = decoded.toString().trim()
                if (text.isEmpty() || text == "null" || text == "undefined") {
                    LxLyricBundle()
                } else if (looksLikeWordLyric(text)) {
                    LxLyricBundle(crlyric = text)
                } else {
                    LxLyricBundle(lrc = text)
                }
            }
        }
    }

    private fun parseObject(obj: JSONObject): LxLyricBundle {
        val word = readFirstText(obj, WORD_KEYS)
        val plain = readFirstText(obj, PLAIN_KEYS)
        val translation = readFirstText(obj, TRANSLATION_KEYS)
        val roman = readFirstText(obj, ROMAN_KEYS)
        if (word != null || plain != null || translation != null || roman != null) {
            return LxLyricBundle(
                lrc = plain,
                crlyric = word,
                tlyric = translation,
                rlyric = roman,
            )
        }
        // 再往 data 嵌一层
        val nested: Any? = obj.opt("data")
        return when (nested) {
            null -> LxLyricBundle()
            is JSONObject -> parseObject(nested)
            is String -> parse(nested)
            else -> LxLyricBundle()
        }
    }

    private fun readFirstText(source: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            val value = readText(source.opt(key))
            if (value != null) return value
        }
        val data = source.optJSONObject("data")
        if (data != null) {
            for (key in keys) {
                val value = readText(data.opt(key))
                if (value != null) return value
            }
        }
        return null
    }

    private fun readText(value: Any?): String? = when (value) {
        null -> null
        is String -> value.trim().takeIf { it.isNotEmpty() && it != "null" && it != "undefined" }
        is JSONArray -> {
            val parts = (0 until value.length()).mapNotNull { index ->
                readText(value.opt(index))
            }
            parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
        is JSONObject -> readFirstText(value, PLAIN_KEYS)
        else -> value.toString().trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 纯文本是不是逐字歌词。
     *
     * 逐字歌词的特征（对齐 Mint 的 `_looksLikeWordLyric`）：
     * - `[00,123]` 形式的时间戳
     * - `[00:00.000]<00:00.000>字` 形式
     * - `<tt` 开头的 TTML
     * - 含 `LyricContent=`
     */
    private fun looksLikeWordLyric(text: String): Boolean {
        if (Regex("""^\[\d+,\d+]""", RegexOption.MULTILINE).containsMatchIn(text)) return true
        if (Regex("""^\[\d{1,2}:\d{2}\.\d{2,3}].*<\d{1,2}:\d{2}\.\d{2,3}>""", RegexOption.MULTILINE)
                .containsMatchIn(text)
        ) return true
        if (text.trimStart().startsWith("<tt")) return true
        return text.contains("LyricContent=")
    }
}
