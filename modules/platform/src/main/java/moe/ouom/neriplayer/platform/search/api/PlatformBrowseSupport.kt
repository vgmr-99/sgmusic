package moe.ouom.neriplayer.platform.search.api

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
 * File: moe.ouom.neriplayer.platform.search.api/PlatformBrowseSupport
 * Created: 2026/11/13
 */

import java.util.Locale

/**
 * 平台浏览页的公共工具。
 *
 * 各家平台返回的播放量格式五花八门（"1.2万" / "12,345" / "1234次"），
 * 卡片 id 又要带源前缀才能分发详情，这里统一处理，
 * 免得每个 BrowseApi 各写一套补丁。
 */
object PlatformBrowseSupport {

    /** 卡片 id 的源前缀分隔符 */
    private const val ID_PREFIX_SEPARATOR = "_"

    /**
     * 解析播放量。
     *
     * 支持 "12345"、"12,345"、"1.2万"、"3.5亿"、"1234次"、"1234万次" 这类写法。
     * 解析不出来时返回 0，不要把 0 当成"没有播放量"的语义错误。
     */
    fun parsePlayCount(raw: Any?): Long {
        if (raw == null) return 0L
        if (raw is Number) return raw.toLong()
        val text = raw.toString().trim()
        if (text.isEmpty()) return 0L
        val cleaned = text.replace(",", "").replace("，", "")
            .replace("次", "").replace("播放", "").replace("人", "").trim()
        val multiplier = when {
            cleaned.endsWith("亿") -> 100_000_000L
            cleaned.endsWith("万") -> 10_000L
            else -> 1L
        }
        val numberPart = cleaned.trimEnd('亿', '万').trim()
        val value = numberPart.toDoubleOrNull() ?: return 0L
        return (value * multiplier).toLong()
    }

    /** 把播放量格式化成「1.2万」这种紧凑文案 */
    fun formatPlayCount(count: Long): String = when {
        count <= 0L -> ""
        count >= 100_000_000L -> String.format(Locale.US, "%.1f亿", count / 100_000_000.0)
        count >= 10_000L -> String.format(Locale.US, "%.1f万", count / 10_000.0)
        else -> count.toString()
    }

    /**
     * 给卡片 id 加源前缀。
     *
     * 详情分发靠前缀反推来源：同一个数字 id 在不同平台可能指不同的东西。
     */
    fun qualifyId(provider: PlatformSearchProvider, rawId: String): String =
        if (rawId.startsWith("${provider.lxCode}$ID_PREFIX_SEPARATOR")) {
            rawId
        } else {
            "${provider.lxCode}$ID_PREFIX_SEPARATOR$rawId"
        }

    /** 去掉源前缀 */
    fun stripProviderPrefix(id: String): String {
        val index = id.indexOf(ID_PREFIX_SEPARATOR)
        return if (index <= 0) id else id.substring(index + 1)
    }

    /** 从带前缀的 id 反推来源 */
    fun providerOf(id: String): PlatformSearchProvider? {
        val index = id.indexOf(ID_PREFIX_SEPARATOR)
        if (index <= 0) return null
        return PlatformSearchProvider.fromLxCode(id.substring(0, index))
    }

    /**
     * 分类标签：显示名 -> 接口用的 id。
     *
     * 平台的 tagId 往往是数字或者 "id-digest" 这种组合串，
     * 和中文显示名对不上，所以每个源自己维护一张表。
     */
    data class BrowseCategory(
        val label: String,
        val id: String
    )

    /**
     * 三级兜底。
     *
     * 分类接口经常返回空（标签失效、上游改版），这时依次退到
     * 热门内容、前几个热门标签逐个试，保证分类页不会一片空白。
     */
    suspend fun browseWithFallback(
        category: String,
        categories: List<BrowseCategory>,
        primary: suspend (String) -> List<PlatformBrowseCard>,
        fallback: suspend () -> List<PlatformBrowseCard>,
        hotTags: suspend () -> List<BrowseCategory> = { emptyList() }
    ): List<PlatformBrowseCard> {
        primary(category).takeIf { it.isNotEmpty() }?.let { return it }
        fallback().takeIf { it.isNotEmpty() }?.let { return it }
        hotTags().take(3).forEach { tag ->
            primary(tag.id).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }
}
