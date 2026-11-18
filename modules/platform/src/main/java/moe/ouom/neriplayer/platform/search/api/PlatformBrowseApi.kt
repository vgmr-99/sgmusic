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
 * File: moe.ouom.neriplayer.platform.search.api/PlatformBrowseApi
 * Created: 2026/11/12
 */

/**
 * 卡片对应的集合类型。
 *
 * 歌单 id 和榜单 id 有时都是数字（QQ 歌单 tid、酷狗榜单 id），
 * 靠 id 形状没法区分，详情分发只能靠这个显式标记。
 */
enum class PlatformBrowseKind {
    PLAYLIST,
    CHART
}

/**
 * 浏览页的一张卡片（歌单或榜单）。
 *
 * @param id 平台内的 id
 * @param title 卡片标题
 * @param subtitle 副标题（歌手 / 简介）
 * @param coverUrl 封面
 * @param trackCount 曲目数
 * @param playCount 播放量
 */
data class PlatformBrowseCard(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    /** 有值表示该集合没有公开的曲目接口，点击时改为打开平台官方页面 */
    val webUrl: String? = null,
    val kind: PlatformBrowseKind = PlatformBrowseKind.PLAYLIST,
    val provider: PlatformSearchProvider
)

/**
 * 平台浏览（探索页默认内容）接口。
 *
 * 网易云默认页是「精品歌单」网格，其它平台则展示各自的榜单，
 * 语义上都是「一组可浏览的集合」，所以统一成同一套模型，
 * 界面才能和网易云用同一套渲染。
 */
interface PlatformBrowseApi {

    /** 所属平台 */
    val provider: PlatformSearchProvider

    /** 顶部分类标签；为空表示该平台没有分类，不显示标签栏 */
    val categories: List<String>

    /**
     * 切到该平台时默认展示的分类。
     *
     * 各实现都把「热门歌单」放在 [categories] 第一位（没有歌单广场的平台放「排行榜」），
     * 所以默认打开就是有内容的歌单/榜单，不需要用户再点一次分类。
     */
    val defaultCategory: String
        get() = categories.firstOrNull().orEmpty()

    /**
     * 分类 key 的展示名。
     *
     * [categories] 放的是稳定 key（数字 id 或「id-digest」组合串），不是中文名，
     * 界面没法直接显示，所以由实现方把 key 翻译成展示名。
     * 默认实现原样返回 key，既有实现（中文分类本身就是 key）不受影响。
     */
    fun categoryLabel(category: String): String = category

    /**
     * 拉取平台真实的分类标签。
     *
     * 有些平台的标签写在代码里（酷狗），有些要调线上接口才有（网易云 / 酷我 / QQ），
     * 后者在首次展示前需要刷新一次；实现方内部负责缓存，重复调用不应重复请求。
     *
     * @return 刷新后的分类 key 列表，与 [categories] 一致；
     *         无法刷新时返回当前已有的 [categories]
     */
    suspend fun refreshCategories(): List<String> = categories

    /** 某个分类下的歌单 / 榜单 */
    suspend fun browse(category: String): List<PlatformBrowseCard>

    /**
     * 搜索结果里的歌单能否在站内直接展开曲目。
     *
     * 浏览页的歌单和搜索结果的歌单 id 语义不一定相同
     * （酷我的浏览 id 是「id-digest」组合串，搜索结果只有 id），
     * 所以这个能力单独标记：只有确认能读到曲目的平台才在搜索态打开站内展开，
     * 其它平台维持「跳官方页面」的既有行为。
     */
    val supportsSearchPlaylistTracks: Boolean
        get() = false

    /** 某个卡片下的曲目 */
    suspend fun tracks(card: PlatformBrowseCard): List<PlatformSearchResult>
}
