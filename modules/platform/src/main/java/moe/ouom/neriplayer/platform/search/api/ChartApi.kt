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
 * File: moe.ouom.neriplayer.platform.search.api/ChartApi
 * Created: 2026/11/12
 */

import moe.ouom.neriplayer.data.model.music.MusicPlatform

/** 热搜榜来源 */
enum class ChartProvider(val code: String) {
    NETEASE("netease"),
    QQ("qq"),
    KUGOU("kugou")
}

/**
 * 热搜榜里的一条记录
 *
 * @param title 歌名或关键词
 * @param subtitle 歌手等补充信息
 * @param score 热度值，平台没有时为 0
 * @param coverUrl 封面
 * @param source 所属平台
 */
data class HotSearchEntry(
    val title: String,
    val subtitle: String = "",
    val score: Long = 0L,
    val coverUrl: String? = null,
    val source: ChartProvider
)

/**
 * 音乐平台的热搜榜服务
 *
 * 与搜索接口一样按平台拆开，各家榜单的字段名和热度口径都不一样，
 * 这里统一成 HotSearchEntry，界面上只关心条目本身。
 */
interface ChartApi {

    /**
     * 拉取当前平台的热搜榜
     *
     * @param limit 最多返回多少条
     */
    suspend fun hotSearch(limit: Int = 30): List<HotSearchEntry>
}
