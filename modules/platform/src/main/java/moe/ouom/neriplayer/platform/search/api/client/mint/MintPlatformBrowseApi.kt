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
 * File: moe.ouom.neriplayer.platform.search.api.client.mint/MintPlatformBrowseApi
 * Created: 2026/11/13
 */

import moe.ouom.neriplayer.platform.search.api.PlatformBrowseApi
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult

/**
 * Mint 风格的发现页数据源。
 *
 * 直接继承仓库既有的 [PlatformBrowseApi]，所以探索页那套 UI
 * （分类 chips + 歌单网格 + 同页展开曲目）不用为 Mint 单独写一份。
 *
 * Mint 的发现页是五源平权的（网易云 / 酷狗 / 酷我 / QQ 音乐 / 咪咕），
 * 而 [PlatformSearchProvider] 原本只覆盖搜索用的平台，因此这里保留一个
 * [providerCode] 表达真实来源（wy / kg / kw / tx / mg），
 * 再由 [provider] 把来源代号翻译成平台枚举。
 */
interface MintPlatformBrowseApi : PlatformBrowseApi {

    /** 真实来源代号：wy / kg / kw / tx / mg。 */
    val providerCode: String

    /**
     * 映射到仓库既有的搜索平台枚举。
     *
     * 来源代号既能匹配枚举的 code（netease / kugou / qq / kw / mg），
     * 也能匹配它的 lxCode（wy / kg / tx / kw / mg），因此五源都能命中。
     */
    override val provider: PlatformSearchProvider
        get() = MintProviderLookup.providerOf(providerCode)

    /**
     * 顶部分类标签的稳定 key 列表。
     *
     * 这里放的是 key 而不是展示名，展示名由 [categoryLabel] 单独给出：
     * 各平台的 tagId 往往是数字（酷狗 11 / 30 / 850）或「id-digest」组合串（酷我），
     * 和中文标签对不上，混在一起会让接线方没法直接拿去显示。
     */
    override val categories: List<String>

    /** 把分类 key 翻译成纯中文展示名。 */
    override fun categoryLabel(category: String): String

    /** 某个分类下的歌单 / 榜单。 */
    override suspend fun browse(category: String): List<PlatformBrowseCard>

    /** 某个卡片下的曲目。 */
    override suspend fun tracks(card: PlatformBrowseCard): List<PlatformSearchResult>
}
