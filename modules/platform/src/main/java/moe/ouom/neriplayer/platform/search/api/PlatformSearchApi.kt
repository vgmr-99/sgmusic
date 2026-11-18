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
 * File: moe.ouom.neriplayer.platform.search.api/PlatformSearchApi
 * Created: 2026/11/12
 */

/**
 * 搜索来源平台。
 *
 * 这些平台只用于搜索与浏览：它们没有在本项目的播放链路上打通取流，
 * 搜索结果播放时走 LX 自定义源兜底。所以不与 MusicPlatform 混用，
 * 避免影响歌词、播放等既有按 MusicPlatform 分支的逻辑。
 */
enum class PlatformSearchProvider(
    /** 应用内的 channelId */
    val code: String,
    /** 洛雪自定义源协议里的 source code */
    val lxCode: String
) {
    QQ("qq", "tx"),
    KUGOU("kugou", "kg"),
    QISHUI("qishui", "qs"),
    KUWO("kw", "kw"),
    MIGU("mg", "mg"),
    /**
     * 网易云音乐。
     *
     * 与其它平台不同，网易云在项目里本来就有独立的取流通道，
     * 这里的枚举只用于「发现页浏览数据」这条线（复刻 Mint 的网易云发现页），
     * 所以不能进 [ALL_CHANNELS]——那会让网易云的浏览结果被误判成
     * 「只能靠 LX 兜底、没有直连通道」的搜索结果。
     */
    NETEASE("netease", "wy");

    companion object {
        /**
         * 所有平台的 channelId 集合。
         *
         * 播放链路用它识别"来自这些平台的搜索结果"，这类结果没有取流通道，
         * 需要走 LX 自定义源兜底，因此不能当成网易云歌曲处理。
         *
         * [NETEASE] 不在集合里：网易云有独立的直连取流通道，
         * 它只借用这套枚举来承载发现页的浏览数据。
         */
        val ALL_CHANNELS: Set<String> = entries
            .filterNot { it == NETEASE }
            .mapTo(LinkedHashSet()) { it.code }

        /** 按 channelId 反查平台 */
        fun fromChannel(channelId: String?): PlatformSearchProvider? =
            entries.firstOrNull { it.code == channelId?.lowercase() }

        /** 按洛雪 source code 反查平台 */
        fun fromLxCode(lxCode: String?): PlatformSearchProvider? =
            entries.firstOrNull { it.lxCode == lxCode?.lowercase() }
    }
}

/**
 * 为平台搜索结果生成稳定的本地 id。
 *
 * 用 SHA-256 派生，和网易云的歌曲 id 空间不冲突（网易云 id 都是正数且量级远小于 2^63），
 * 同一个平台的同一首歌在任何设备上都会得到同一个 id。
 */
fun stablePlatformSongId(provider: PlatformSearchProvider, rawId: String): Long {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest("${provider.code}:$rawId".toByteArray(Charsets.UTF_8))
        .take(8)
        .toByteArray()
    var result = 0L
    digest.forEach { byte -> result = (result shl 8) or (byte.toLong() and 0xff) }
    return if (result == 0L) 1L else result
}

/** 搜索分类 */
enum class PlatformSearchType {
    SONG,
    PLAYLIST,
    ARTIST
}

/**
 * 统一的搜索结果
 *
 * @param type 结果分类
 * @param id 平台内唯一 id
 * @param name 歌名 / 歌单名 / 歌手名
 * @param subtitle 歌手等补充信息
 * @param album 所属专辑（歌曲分类用）
 * @param durationMs 时长，毫秒
 * @param coverUrl 封面
 * @param extraCount 播放量或歌曲数等统计
 * @param webUrl 对应页面在官方站点的地址，点击歌单/歌手时用来打开网页
 * @param extras 平台特有的原始字段（如酷狗 hash、咪咕 copyrightId、QQ strMediaMid）。
 *               播放取流时透传给 LX 脚本，能大幅提高换源成功率
 */
data class PlatformSearchResult(
    val type: PlatformSearchType,
    val id: String,
    val name: String,
    val subtitle: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String? = null,
    val extraCount: Long = 0L,
    val webUrl: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val provider: PlatformSearchProvider
)

/**
 * 多平台搜索接口。
 *
 * 与只搜歌曲的 SearchApi 分开：这些平台的歌单/歌手字段和歌曲差异很大，
 * 统一成 PlatformSearchResult 后界面才能按同一套逻辑渲染。
 */
interface PlatformSearchApi {

    /** 所属平台 */
    val provider: PlatformSearchProvider

    /** 该平台匿名可用的搜索分类；平台没开放的分类不会出现在界面里 */
    val supportedTypes: Set<PlatformSearchType>

    /**
     * 搜索
     *
     * @param keyword 搜索词
     * @param type 搜索分类
     * @param page 页码，从 1 开始
     */
    suspend fun search(
        keyword: String,
        type: PlatformSearchType,
        page: Int
    ): List<PlatformSearchResult>
}
