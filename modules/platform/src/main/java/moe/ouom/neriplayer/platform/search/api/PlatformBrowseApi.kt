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

    /** 某个分类下的歌单 / 榜单 */
    suspend fun browse(category: String): List<PlatformBrowseCard>

    /** 某个卡片下的曲目 */
    suspend fun tracks(card: PlatformBrowseCard): List<PlatformSearchResult>
}
