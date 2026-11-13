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
 * File: moe.ouom.neriplayer.platform.search.api.client.mint/MintMiguBrowseApi
 * Created: 2026/11/13
 */

import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseKind
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseSupport
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 咪咕音乐的发现页数据源。
 *
 * 咪咕的接口没有分类标签，官方只提供「歌单广场」和「榜单」，
 * 所以这里的分类只有「热门歌单」与「排行榜」两项，
 * 分类歌单直接复用热门歌单（和 Mint 的行为一致）。
 *
 * 歌单广场返回的是一棵嵌套树，歌单藏在任意层级里，
 * 这里按 `resType == 2021` 递归遍历整棵树。
 *
 * 端点：
 * - 歌单广场 `GET https://app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0`
 * - 榜单详情 `GET https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do`
 * - 歌单曲目 `GET https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0`
 * - 歌单信息 `GET https://c.musicapp.migu.cn/MIGUM3.0/resource/playlist/v2.0`
 */
class MintMiguBrowseApi(
    http: MintHttp = MintHttp()
) : MintBrowseApiBase(http) {

    override val providerCode: String = "mg"

    private val hotCategory: String =
        MintBrowseCategory.encode(providerCode, MintBrowseKind.HOT, "", "")

    private val chartCategory: String =
        MintBrowseCategory.encode(providerCode, MintBrowseKind.CHART, "", "")

    override val categories: List<String> = listOf(hotCategory, chartCategory)

    override fun categoryLabel(category: String): String = when (category) {
        hotCategory -> "热门歌单"
        chartCategory -> "排行榜"
        else -> MintBrowseCategory.decode(category)?.tagId.orEmpty()
    }

    override suspend fun browse(category: String): List<PlatformBrowseCard> {
        val ref = MintBrowseCategory.decode(category) ?: return emptyList()
        return when (ref.kind) {
            MintBrowseKind.HOT -> hotPlaylists()
            MintBrowseKind.CHART -> leaderboards()
            // 咪咕没有分类标签，分类歌单直接等于热门歌单
            MintBrowseKind.TAG -> hotPlaylists()
        }
    }

    override suspend fun tracks(card: PlatformBrowseCard): List<PlatformSearchResult> {
        val id = rawCardId(card)
        if (id.isEmpty()) return emptyList()
        return when (card.kind) {
            PlatformBrowseKind.CHART -> chartTracks(id)
            PlatformBrowseKind.PLAYLIST -> playlistTracks(id)
        }
    }

    /**
     * 歌单信息（作者、播放量、简介）。
     *
     * 歌单广场返回的卡片没有这些字段，需要单独问一次；
     * 接线方想显示播放量时可以调这个方法。
     */
    suspend fun playlistDetail(card: PlatformBrowseCard): PlatformBrowseCard? {
        val id = rawCardId(card)
        if (id.isEmpty()) return null
        val root = MintJson.parseObject(http.get("$PLAYLIST_INFO_URL?playlistId=$id", MIGU_HEADERS))
            ?: return null
        val data = MintJson.obj(root, "data") ?: return null
        val cover = MintJson.obj(data, "imgItem")?.optString("img", "")?.trim()
        val playCount = MintJson.obj(data, "opNumItem")?.opt("playNum")
        return card.copy(
            title = MintJson.string(data, "title") ?: card.title,
            subtitle = MintJson.string(data, "summary") ?: card.subtitle,
            coverUrl = cover?.ifBlank { null } ?: card.coverUrl,
            playCount = PlatformBrowseSupport.parsePlayCount(
                formatPlayCount(playCount?.toString()?.toLongOrNull() ?: 0L)
            )
        )
    }

    // ── 热门歌单 ────────────────────────────────────────────────

    private suspend fun hotPlaylists(): List<PlatformBrowseCard> {
        val root = MintJson.parseObject(http.get(SQUARE_URL, MIGU_HEADERS)) ?: return emptyList()
        val contents = MintJson.array(root, "contents") ?: return emptyList()
        val cards = ArrayList<PlatformBrowseCard>()
        val seen = HashSet<String>()
        collectSquarePlaylists(contents, cards, seen)
        return cards.take(MAX_PLAYLISTS)
    }

    /**
     * 递归遍历歌单广场的树形结构。
     *
     * 歌单节点的 `resType`（或 `logEvent.contentType`）是 2021。
     */
    private fun collectSquarePlaylists(
        node: Any?,
        cards: MutableList<PlatformBrowseCard>,
        seen: MutableSet<String>
    ) {
        when (node) {
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    collectSquarePlaylists(node.opt(index), cards, seen)
                }
            }

            is JSONObject -> {
                val logEvent = node.optJSONObject("logEvent")
                val resType = node.opt("resType")?.toString()
                val logContentType = logEvent?.opt("contentType")?.toString()
                val rawId = node.opt("resId")?.toString()
                    ?: logEvent?.opt("contentId")?.toString()
                    ?: node.opt("contentId")?.toString()
                if ((resType == PLAYLIST_CONTENT_TYPE || logContentType == PLAYLIST_CONTENT_TYPE) &&
                    !rawId.isNullOrEmpty() &&
                    seen.add(rawId)
                ) {
                    val title = node.opt("txt")?.toString()
                        ?: logEvent?.opt("contentName")?.toString()
                        ?: ""
                    cards.add(
                        PlatformBrowseCard(
                            id = rawId,
                            title = decodeHtmlEntities(title),
                            subtitle = decodeHtmlEntities(node.opt("txt2")?.toString().orEmpty()),
                            coverUrl = node.opt("img")?.toString()?.takeIf { it.isNotBlank() },
                            trackCount = 0,
                            playCount = 0L,
                            webUrl = null,
                            kind = PlatformBrowseKind.PLAYLIST,
                            provider = cardProvider
                        )
                    )
                }
                collectSquarePlaylists(node.opt("contents"), cards, seen)
                collectSquarePlaylists(node.opt("content"), cards, seen)
                collectSquarePlaylists(node.opt("itemList"), cards, seen)
            }
        }
    }

    // ── 排行榜 ──────────────────────────────────────────────────

    private suspend fun leaderboards(): List<PlatformBrowseCard> = CHARTS.map { chart ->
        PlatformBrowseCard(
            id = chart.id,
            title = chart.title,
            subtitle = "",
            coverUrl = null,
            trackCount = 0,
            playCount = 0L,
            webUrl = null,
            kind = PlatformBrowseKind.CHART,
            provider = cardProvider
        )
    }

    // ── 榜单详情 ────────────────────────────────────────────────

    private suspend fun chartTracks(columnId: String): List<PlatformSearchResult> {
        val url = "$COLUMN_URL?columnId=$columnId&needAll=0"
        val root = MintJson.parseObject(http.get(url, MIGU_HEADERS)) ?: return emptyList()
        val columnInfo = MintJson.obj(root, "columnInfo") ?: return emptyList()
        val contents = columnInfo.optJSONArray("contents") ?: return emptyList()
        val songs = ArrayList<JSONObject>()
        for (index in 0 until contents.length()) {
            val info = contents.optJSONObject(index)?.optJSONObject("objectInfo") ?: continue
            songs.add(info)
        }
        return parseSongs(songs)
    }

    // ── 歌单详情 ────────────────────────────────────────────────

    private suspend fun playlistTracks(playlistId: String): List<PlatformSearchResult> {
        val url = "$PLAYLIST_SONG_URL?pageNo=1&pageSize=100&playlistId=$playlistId"
        val root = MintJson.parseObject(http.get(url, MIGU_HEADERS)) ?: return emptyList()
        val list = MintJson.array(root, "songList") ?: return emptyList()
        val songs = ArrayList<JSONObject>()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            songs.add(item)
        }
        return parseSongs(songs)
    }

    // ── 曲目解析 ────────────────────────────────────────────────

    private fun parseSongs(items: List<JSONObject>): List<PlatformSearchResult> = buildList {
        for (item in items) {
            val id = item.opt("copyrightId")?.toString()
                ?: item.opt("songId")?.toString()
                ?: item.opt("id")?.toString()
                ?: item.opt("resourceId")?.toString()
                ?: continue
            if (id.isEmpty()) continue
            val title = item.opt("songName")?.toString()
                ?: item.opt("name")?.toString()
                ?: item.opt("title")?.toString()
                ?: continue
            add(
                PlatformSearchResult(
                    type = PlatformSearchType.SONG,
                    id = id,
                    name = decodeHtmlEntities(title),
                    subtitle = decodeHtmlEntities(parseArtist(item)),
                    album = decodeHtmlEntities(
                        item.opt("albumName")?.toString() ?: item.opt("album")?.toString().orEmpty()
                    ),
                    durationMs = parseDurationMillis(item),
                    coverUrl = parseCover(item),
                    extraCount = 0L,
                    webUrl = null,
                    extras = mapOf("copyrightId" to id),
                    provider = cardProvider
                )
            )
        }
    }

    private fun parseArtist(item: JSONObject): String {
        item.opt("singerName")?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        item.opt("singer")?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val singerList = item.optJSONArray("singerList") ?: return ""
        val names = ArrayList<String>()
        for (index in 0 until singerList.length()) {
            val name = singerList.optJSONObject(index)?.optString("name", "")?.trim().orEmpty()
            if (name.isNotEmpty()) names.add(name)
        }
        return names.joinToString("、")
    }

    /** 咪咕的时长可能是秒，也可能是 `mm:ss`。 */
    private fun parseDurationMillis(item: JSONObject): Long {
        val raw = item.opt("duration") ?: item.opt("length") ?: return 0L
        val text = raw.toString().trim()
        if (text.isEmpty()) return 0L
        if (text.contains(':')) {
            val seconds = text.split(':').fold(0L) { total, part ->
                total * 60 + (part.trim().toLongOrNull() ?: 0L)
            }
            return seconds * 1000L
        }
        return (text.toLongOrNull() ?: 0L) * 1000L
    }

    /** 咪咕的封面字段只给路径时要补上域名。 */
    private fun parseCover(item: JSONObject): String? {
        val raw = item.opt("img3")?.toString()
            ?: item.opt("img2")?.toString()
            ?: item.opt("img1")?.toString()
            ?: item.opt("img")?.toString()
            ?: item.opt("albumImg")?.toString()
            ?: return null
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("http://") || text.startsWith("https://")) return text
        return "$MIGU_CDN_HOST$text"
    }

    private companion object {

        const val SQUARE_URL =
            "https://app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0?templateVersion=2&pageNo=1"
        const val COLUMN_URL =
            "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do"
        const val PLAYLIST_SONG_URL =
            "https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0"
        const val PLAYLIST_INFO_URL =
            "https://c.musicapp.migu.cn/MIGUM3.0/resource/playlist/v2.0"

        const val MIGU_CDN_HOST = "http://d.musicapp.migu.cn"

        /** 歌单节点的类型标记。 */
        const val PLAYLIST_CONTENT_TYPE = "2021"

        /** 歌单广场最多取多少个。 */
        const val MAX_PLAYLISTS = 30

        val MIGU_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1",
            "Referer" to "https://m.music.migu.cn/"
        )

        /** 咪咕的榜单是固定的这几个。 */
        val CHARTS = listOf(
            FallbackChart(id = "27553319", title = "新歌榜"),
            FallbackChart(id = "27186466", title = "热歌榜"),
            FallbackChart(id = "27553408", title = "原创榜"),
            FallbackChart(id = "75959118", title = "音乐风向榜"),
            FallbackChart(id = "76557036", title = "彩铃分贝榜"),
            FallbackChart(id = "76557745", title = "会员挚爱榜"),
            FallbackChart(id = "23189800", title = "港台榜"),
            FallbackChart(id = "23189399", title = "内地榜"),
            FallbackChart(id = "19190036", title = "欧美榜"),
            FallbackChart(id = "83176390", title = "国风金曲榜")
        )
    }

    /** 榜单条目。 */
    private data class FallbackChart(val id: String, val title: String)
}
