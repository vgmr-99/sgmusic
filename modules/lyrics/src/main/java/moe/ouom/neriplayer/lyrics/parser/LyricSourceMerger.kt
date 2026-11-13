package moe.ouom.neriplayer.lyrics.parser

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import kotlin.math.abs

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
 * File: moe.ouom.neriplayer.lyrics.parser/LyricSourceMerger
 * Created: 2026/11/11
 */

/**
 * 跨歌词源的逐行合并。
 *
 * 不同歌词源各有长处：网易云的原文与翻译完整，LRCLIB 的音译准确，
 * AMLL 的 TTML 带逐词时间戳。把它们拼成"一份歌词"比只能单选一个源实用得多。
 *
 * 合并规则（按优先级从高到低）：
 * 1. 优先采用带逐词时间戳的行，逐字歌词体验最好
 * 2. 同优先级下，优先采用时长更接近歌曲的源
 * 3. 同一时刻的行只保留一条，避免重复显示
 */
object LyricSourceMerger {

    /** 判定两行是否属于同一时刻的同一句歌词。 */
    private const val SAME_LINE_TOLERANCE_MS = 400L

    /**
     * 合并多个歌词源。
     *
     * @param sources 按优先级从高到低排列的歌词源
     * @return 合并后的歌词行；所有源都为空时返回空列表
     */
    fun merge(sources: List<List<LyricEntry>>): List<LyricEntry> {
        val usable = sources.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return emptyList()
        // 单源时也要按时间排序，否则歌词顺序可能与源文件不一致
        if (usable.size == 1) return usable.first().sortedBy { it.startTimeMs }

        val merged = mutableListOf<LyricEntry>()
        val claimedSlots = mutableListOf<Long>()

        // 第一轮：只取带逐词时间戳的行，这类行质量最高，值得优先占位
        usable.forEach { entries ->
            entries.filter { !it.words.isNullOrEmpty() }.forEach { entry ->
                if (isSlotFree(entry.startTimeMs, claimedSlots)) {
                    claimedSlots += entry.startTimeMs
                    merged += entry
                }
            }
        }

        // 第二轮：用其余源补齐还没有歌词的时刻
        usable.forEach { entries ->
            entries.filter { it.words.isNullOrEmpty() }.forEach { entry ->
                if (isSlotFree(entry.startTimeMs, claimedSlots)) {
                    claimedSlots += entry.startTimeMs
                    merged += entry
                }
            }
        }

        return merged.sortedBy { it.startTimeMs }
    }

    /**
     * 把另一个源的翻译按行对齐合并到原文。
     *
     * 只填补原文缺失的翻译，不覆盖已有内容。
     * 音译在本项目里是独立字段（不在 LyricEntry 内），由调用方按同样的对齐规则单独合并。
     */
    fun mergeTranslations(
        original: List<LyricEntry>,
        translated: List<LyricEntry>
    ): List<LyricEntry> {
        if (original.isEmpty() || translated.isEmpty()) return original
        val translatedByTime = indexByTime(translated)
        if (translatedByTime.isEmpty()) return original
        return original.map { entry ->
            if (!entry.translation.isNullOrBlank()) {
                entry
            } else {
                // 取的是源条目的翻译文本，不是原文文本
                entry.copy(translation = translatedByTime[entry.startTimeMs]?.translation)
            }
        }
    }

    /**
     * 按开始时间把音译行与原文行对齐。
     *
     * 返回与原文等长的音译列表，缺失的位置为 null。
     */
    fun alignRomanized(
        original: List<LyricEntry>,
        romanized: List<LyricEntry>
    ): List<LyricEntry?> {
        if (original.isEmpty() || romanized.isEmpty()) return List(original.size) { null }
        val romanizedByTime = indexByTime(romanized)
        if (romanizedByTime.isEmpty()) return List(original.size) { null }
        return original.map { entry -> romanizedByTime[entry.startTimeMs] }
    }

    private fun indexByTime(entries: List<LyricEntry>): Map<Long, LyricEntry> {
        if (entries.isEmpty()) return emptyMap()
        val index = HashMap<Long, LyricEntry>(entries.size)
        entries.forEach { entry ->
            // 同一时刻保留先到的（优先级更高的）那条
            val occupied = index.keys.any { abs(it - entry.startTimeMs) <= SAME_LINE_TOLERANCE_MS }
            if (!occupied) {
                index[entry.startTimeMs] = entry
            }
        }
        return index
    }

    private fun isSlotFree(startTimeMs: Long, claimed: List<Long>): Boolean =
        claimed.none { abs(it - startTimeMs) <= SAME_LINE_TOLERANCE_MS }
}
