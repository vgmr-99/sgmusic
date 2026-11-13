package moe.ouom.neriplayer.platform.search.api.client.mint

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
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.platform.search.api.client.mint/MintBrowseCategory
 * Created: 2026/11/13
 */

import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider

/**
 * 分类 key 的编解码。
 *
 * 各家平台的标签 id 形状差别很大：网易云用中文标签名、酷狗用数字（11 / 30 / 850）、
 * 酷我用「id-digest」组合串、QQ 用数字 id。直接把这些 id 当分类 key 会让
 * 界面没法显示中文名，所以 key 里额外带上分组，标签名单独维护。
 *
 * key 格式：`providerCode::kind::group::tagId`
 * - providerCode：wy / kg / kw / tx / mg
 * - kind：[MintBrowseKind] 的名字
 * - group：分组名（语种 / 风格 / 热门 / ...），允许为空
 * - tagId：平台侧真实标签 id，允许为空
 */
internal object MintBrowseCategory {

    private const val SEPARATOR = "::"

    /** 拼装分类 key。 */
    fun encode(providerCode: String, kind: MintBrowseKind, group: String, tagId: String): String =
        listOf(providerCode, kind.name, group, tagId).joinToString(SEPARATOR)

    /** 解析分类 key；格式不认识时返回 null。 */
    fun decode(key: String): MintCategoryRef? {
        val parts = key.split(SEPARATOR, limit = 4)
        if (parts.size < 4) return null
        val kind = MintBrowseKind.fromName(parts[1]) ?: return null
        return MintCategoryRef(
            providerCode = parts[0],
            kind = kind,
            group = parts[2],
            tagId = parts[3]
        )
    }
}

/** 分类的种类。 */
internal enum class MintBrowseKind {
    /** 热门歌单。 */
    HOT,

    /** 排行榜。 */
    CHART,

    /** 平台真实分类标签下的歌单。 */
    TAG;

    companion object {
        fun fromName(name: String): MintBrowseKind? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * 解析出来的分类。
 *
 * @param providerCode 来源代号
 * @param kind 分类种类
 * @param group 分组名
 * @param tagId 平台侧标签 id；热门歌单与排行榜为空
 */
internal data class MintCategoryRef(
    val providerCode: String,
    val kind: MintBrowseKind,
    val group: String,
    val tagId: String
)

/**
 * 平台标签。
 *
 * @param id 平台侧标签 id，请求时原样带回
 * @param name 纯中文展示名
 */
internal data class MintTag(
    val id: String,
    val name: String
)

/**
 * 来源代号与仓库既有搜索平台枚举的映射。
 *
 * 网易云的来源代号是 wy，而 [PlatformSearchProvider.NETEASE] 的 lxCode 正是 wy，
 * 所以五源都能命中真实枚举。
 */
internal object MintProviderLookup {

    /** 网易云来源代号。 */
    const val MINT_PROVIDER_NETEASE = "wy"

    /**
     * 按来源代号找对应的搜索平台枚举。
     *
     * 既匹配枚举的 code（netease / kugou / qq / kw / mg），
     * 也匹配它的 lxCode（wy / kg / tx / kw / mg）。
     *
     * 暂时没有对应枚举时退回占位来源，保证卡片与曲目的 provider 字段非空。
     */
    fun providerOf(providerCode: String): PlatformSearchProvider =
        providerOfOrNull(providerCode) ?: PlatformSearchProvider.QISHUI

    /** 按来源代号找对应的搜索平台枚举；没有对应条目时返回 null。 */
    fun providerOfOrNull(providerCode: String): PlatformSearchProvider? =
        PlatformSearchProvider.entries.firstOrNull { it.code == providerCode || it.lxCode == providerCode }

    /** 网易云是否已经能在 [PlatformSearchProvider] 里找到对应枚举。 */
    fun hasNeteaseProvider(): Boolean = providerOfOrNull(MINT_PROVIDER_NETEASE) != null
}
