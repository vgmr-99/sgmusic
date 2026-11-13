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
 * File: moe.ouom.neriplayer.platform.search.api.client.mint/MintQqBrowseApi
 * Created: 2026/11/13
 */

import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseKind
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseSupport
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * QQ 音乐的发现页数据源。
 *
 * QQ 音乐的接口统一走 `musicu.fcg`，请求参数是一个 JSON 字符串塞在 `data` 里，
 * 键序要跟官方一致，所以请求体统一用 [MiniJson] 编码。
 *
 * 端点：
 * - 歌单广场 / 分类歌单 `GET https://u.y.qq.com/cgi-bin/musicu.fcg`
 * - 分类标签           `GET https://u.y.qq.com/cgi-bin/musicu.fcg`（get_all_categories）
 * - 热门标签           `GET https://c.y.qq.com/node/pc/wk_v15/category_playlist.html`
 * - 排行榜             `GET https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg`
 * - 榜单详情           `POST https://u.y.qq.com/cgi-bin/musicu.fcg`
 * - 歌单详情           `GET https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg`
 */
class MintQqBrowseApi(
    http: MintHttp = MintHttp()
) : MintBrowseApiBase(http), MintRefreshableCategories {

    override val providerCode: String = "tx"

    /** 分类 key -> 中文标签名。 */
    private val tagLabels = LinkedHashMap<String, String>()

    /** 热门标签，用于第三层兜底。 */
    private var hotTagsCache: List<MintTag> = emptyList()

    private val hotCategory: String =
        MintBrowseCategory.encode(providerCode, MintBrowseKind.HOT, "", "")

    private val chartCategory: String =
        MintBrowseCategory.encode(providerCode, MintBrowseKind.CHART, "", "")

    override val categories: List<String>
        get() = buildList {
            add(hotCategory)
            add(chartCategory)
            addAll(tagLabels.keys)
        }

    override fun categoryLabel(category: String): String = when (category) {
        hotCategory -> "热门歌单"
        chartCategory -> "排行榜"
        else -> tagLabels[category]
            ?: MintBrowseCategory.decode(category)?.tagId.orEmpty()
    }

    override suspend fun refreshCategories(): List<String> {
        loadCategories()
        hotTagsCache = loadHotTags()
        return categories
    }

    override suspend fun browse(category: String): List<PlatformBrowseCard> {
        val ref = MintBrowseCategory.decode(category) ?: return emptyList()
        return when (ref.kind) {
            MintBrowseKind.HOT -> hotPlaylists()
            MintBrowseKind.CHART -> leaderboards()
            MintBrowseKind.TAG -> categoryPlaylists(ref.tagId)
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

    // ── 热门歌单 ────────────────────────────────────────────────

    /** 热门歌单的三层兜底：热门推荐 → 热门分类 → 前 3 个热门标签。 */
    private suspend fun hotPlaylists(): List<PlatformBrowseCard> {
        fetchPlazaPlaylists(page = 1, size = HOT_RECOMMEND_LIMIT)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        fetchPlazaPlaylists(page = 1, size = HOT_LIMIT)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        for (tag in hotTags().take(HOT_TAG_FALLBACK_COUNT)) {
            val tagged = fetchCategoryPlaylists(tagId = tag.id, page = 1)
            if (tagged.isNotEmpty()) return tagged
        }
        return emptyList()
    }

    // ── 分类歌单 ────────────────────────────────────────────────

    private suspend fun categoryPlaylists(tagId: String): List<PlatformBrowseCard> {
        val merged = collectPages(PAGE_COUNT) { page ->
            if (tagId.isEmpty()) {
                fetchPlazaPlaylists(page = page, size = HOT_LIMIT)
            } else {
                fetchCategoryPlaylists(tagId = tagId, page = page)
            }
        }
        if (merged.isNotEmpty()) return merged
        if (tagId.isNotEmpty()) return emptyList()

        hotPlaylists().takeIf { it.isNotEmpty() }?.let { return it.take(HOT_LIMIT) }

        for (tag in hotTags().take(HOT_TAG_FALLBACK_COUNT)) {
            val tagged = fetchCategoryPlaylists(tagId = tag.id, page = 1)
            if (tagged.isNotEmpty()) return tagged
        }
        return emptyList()
    }

    /** 歌单广场：无标签时按热度取。 */
    private suspend fun fetchPlazaPlaylists(page: Int, size: Int): List<PlatformBrowseCard> {
        val data = linkedMapOf<String, Any?>(
            "comm" to linkedMapOf<String, Any?>("cv" to 1602, "ct" to 20),
            "playlist" to linkedMapOf<String, Any?>(
                "method" to "get_playlist_by_tag",
                "param" to linkedMapOf<String, Any?>(
                    "id" to 10000000,
                    "sin" to size * (page - 1),
                    "size" to size,
                    "order" to ORDER_HOT,
                    "cur_page" to page
                ),
                "module" to "playlist.PlayListPlazaServer"
            )
        )
        val root = musicuGet(data) ?: return emptyList()
        val list = MintJson.obj(MintJson.obj(root, "playlist"), "data")
            ?.let { MintJson.array(it, "v_playlist") }
            ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val tid = MintJson.string(item, "tid") ?: continue
                val title = MintJson.string(item, "title") ?: continue
                add(
                    PlatformBrowseCard(
                        id = tid,
                        title = decodeHtmlEntities(title),
                        subtitle = decodeHtmlEntities(MintJson.string(item, "desc").orEmpty()),
                        coverUrl = MintJson.string(item, "cover_url_medium"),
                        trackCount = parseSongCount(item.opt("song_ids")),
                        playCount = PlatformBrowseSupport.parsePlayCount(
                            formatPlayCount(MintJson.long(item, "access_num"), strictGreater = true)
                        ),
                        webUrl = null,
                        kind = PlatformBrowseKind.PLAYLIST,
                        provider = cardProvider
                    )
                )
            }
        }
    }

    /** 分类歌单：有标签时走 get_category_content。 */
    private suspend fun fetchCategoryPlaylists(
        tagId: String,
        page: Int
    ): List<PlatformBrowseCard> {
        val id = tagId.toIntOrNull() ?: DEFAULT_CATEGORY_ID
        val data = linkedMapOf<String, Any?>(
            "comm" to linkedMapOf<String, Any?>("cv" to 1602, "ct" to 20),
            "playlist" to linkedMapOf<String, Any?>(
                "method" to "get_category_content",
                "param" to linkedMapOf<String, Any?>(
                    "titleid" to id,
                    "caller" to "0",
                    "category_id" to id,
                    "size" to HOT_LIMIT,
                    "page" to page - 1,
                    "use_page" to 1
                ),
                "module" to "playlist.PlayListCategoryServer"
            )
        )
        val root = musicuGet(data) ?: return emptyList()
        val content = MintJson.obj(MintJson.obj(root, "playlist"), "data")
            ?.let { MintJson.obj(it, "content") }
            ?: return emptyList()
        val list = content.optJSONArray("v_item") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val basic = item.optJSONObject("basic") ?: continue
                val tid = MintJson.string(basic, "tid") ?: continue
                val title = MintJson.string(basic, "title") ?: continue
                val cover = MintJson.obj(basic, "cover")
                add(
                    PlatformBrowseCard(
                        id = tid,
                        title = decodeHtmlEntities(title),
                        subtitle = decodeHtmlEntities(MintJson.string(basic, "desc").orEmpty()),
                        coverUrl = cover?.optString("medium_url", "")?.takeIf { it.isNotBlank() }
                            ?: cover?.optString("default_url", "")?.takeIf { it.isNotBlank() },
                        trackCount = parseSongCount(
                            basic.opt("song_cnt") ?: basic.opt("song_ids") ?: basic.opt("song_num")
                        ),
                        playCount = PlatformBrowseSupport.parsePlayCount(
                            formatPlayCount(MintJson.long(basic, "play_cnt"), strictGreater = true)
                        ),
                        webUrl = null,
                        kind = PlatformBrowseKind.PLAYLIST,
                        provider = cardProvider
                    )
                )
            }
        }
    }

    // ── 排行榜 ──────────────────────────────────────────────────

    private suspend fun leaderboards(): List<PlatformBrowseCard> {
        val root = MintJson.parseObject(http.get(TOPLIST_URL))
        val list = MintJson.array(MintJson.obj(root, "data"), "topList")
        val cards = if (list == null) {
            emptyList()
        } else {
            buildList {
                for (index in 0 until list.length()) {
                    val item = list.optJSONObject(index) ?: continue
                    // 201 是 MV 榜，不属于榜单列表
                    if (MintJson.int(item, "id", 0) == 201) continue
                    val id = MintJson.string(item, "id") ?: continue
                    val title = MintJson.stringOf(item, "topTitle", "title") ?: continue
                    add(
                        PlatformBrowseCard(
                            id = id,
                            title = decodeHtmlEntities(title).replace("巅峰榜·", ""),
                            subtitle = "",
                            coverUrl = MintJson.string(item, "picUrl"),
                            playCount = PlatformBrowseSupport.parsePlayCount(
                                formatPlayCount(MintJson.long(item, "listenCount"), strictGreater = true)
                            ),
                            webUrl = null,
                            kind = PlatformBrowseKind.CHART,
                            provider = cardProvider
                        )
                    )
                }
            }
        }
        return cards.ifEmpty { fallbackCharts() }
    }

    private fun fallbackCharts(): List<PlatformBrowseCard> = FALLBACK_CHARTS.map { fallback ->
        PlatformBrowseCard(
            id = fallback.id,
            title = fallback.title,
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

    private suspend fun chartTracks(topId: String): List<PlatformSearchResult> {
        val body = linkedMapOf<String, Any?>(
            "toplist" to linkedMapOf<String, Any?>(
                "module" to "musicToplist.ToplistInfoServer",
                "method" to "GetDetail",
                "param" to linkedMapOf<String, Any?>(
                    "topid" to (topId.toIntOrNull() ?: 0),
                    "num" to 300,
                    "period" to ""
                )
            ),
            "comm" to linkedMapOf<String, Any?>(
                "uin" to 0,
                "format" to "json",
                "ct" to 20,
                "cv" to 1859
            )
        )
        val text = http.postJson(MUSIC_U_URL, MiniJson.encode(body), QQ_HEADERS) ?: return emptyList()
        val root = MintJson.parseObject(text) ?: return emptyList()
        val list = MintJson.obj(MintJson.obj(root, "toplist"), "data")
            ?.let { MintJson.array(it, "songInfoList") }
            ?: return emptyList()
        return parseSongs(list)
    }

    // ── 歌单详情 ────────────────────────────────────────────────

    private suspend fun playlistTracks(dissId: String): List<PlatformSearchResult> {
        val url = "$CD_INFO_URL?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=$dissId" +
            "&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0" +
            "&platform=yqq.json&needNewCode=0"
        val root = MintJson.parseObject(http.get(url, cdInfoHeaders(dissId))) ?: return emptyList()
        val cdList = MintJson.array(root, "cdlist") ?: return emptyList()
        val cd = cdList.optJSONObject(0) ?: return emptyList()
        val songList = cd.optJSONArray("songlist") ?: return emptyList()
        return parseSongs(songList)
    }

    // ── 曲目解析 ────────────────────────────────────────────────

    private fun parseSongs(list: JSONArray): List<PlatformSearchResult> = buildList {
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val mid = MintJson.stringOf(item, "mid", "strMediaMid") ?: continue
            val title = MintJson.stringOf(item, "name", "title") ?: continue
            val album = item.optJSONObject("album")
            val albumMid = album?.opt("mid")?.toString().orEmpty()
            val singerMid = item.optJSONArray("singer")
                ?.optJSONObject(0)
                ?.opt("mid")
                ?.toString()
                .orEmpty()
            val cover = when {
                albumMid.isNotEmpty() -> "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
                singerMid.isNotEmpty() -> "https://y.gtimg.cn/music/photo_new/T001R300x300M000$singerMid.jpg"
                else -> null
            }
            add(
                PlatformSearchResult(
                    type = PlatformSearchType.SONG,
                    id = mid,
                    name = decodeHtmlEntities(title),
                    subtitle = joinSingers(item.optJSONArray("singer")),
                    album = album?.optString("name", "")?.trim().orEmpty(),
                    durationMs = MintJson.long(item, "interval") * 1000L,
                    coverUrl = cover,
                    extraCount = 0L,
                    webUrl = null,
                    extras = mapOf("strMediaMid" to mid),
                    provider = cardProvider
                )
            )
        }
    }

    private fun joinSingers(singers: JSONArray?): String {
        if (singers == null) return ""
        val names = ArrayList<String>()
        for (index in 0 until singers.length()) {
            val name = singers.optJSONObject(index)?.optString("name", "")?.trim().orEmpty()
            if (name.isNotEmpty()) names.add(name)
        }
        return names.joinToString("、")
    }

    /** 歌曲数既可能是数字，也可能是 id 数组或逗号分隔的字符串。 */
    private fun parseSongCount(value: Any?): Int = when (value) {
        null -> 0
        is Number -> value.toInt()
        is JSONArray -> value.length()
        else -> {
            val text = value.toString().trim()
            if (text.isEmpty()) {
                0
            } else if (text.contains(',')) {
                text.split(',').count { it.isNotBlank() }
            } else {
                text.toIntOrNull() ?: 0
            }
        }
    }

    // ── 分类标签 ────────────────────────────────────────────────

    private suspend fun loadCategories() {
        val data = linkedMapOf<String, Any?>(
            "tags" to linkedMapOf<String, Any?>(
                "method" to "get_all_categories",
                "param" to linkedMapOf<String, Any?>("qq" to ""),
                "module" to "playlist.PlaylistAllCategoriesServer"
            )
        )
        val root = musicuGet(data) ?: return
        val groups = MintJson.obj(MintJson.obj(root, "tags"), "data")
            ?.let { MintJson.array(it, "v_group") }
            ?: return
        for (groupIndex in 0 until groups.length()) {
            val group = groups.optJSONObject(groupIndex) ?: continue
            val groupName = group.optString("group_name", "").trim()
            val items = group.optJSONArray("v_item") ?: continue
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val tagId = item.opt("id")?.toString()?.trim().orEmpty()
                val name = item.optString("name", "").trim()
                if (tagId.isEmpty() || name.isEmpty()) continue
                val categoryKey = MintBrowseCategory.encode(
                    providerCode,
                    MintBrowseKind.TAG,
                    groupName,
                    tagId
                )
                tagLabels[categoryKey] = name
            }
        }
    }

    /** 热门标签来自一个 HTML 页面，需要用正则抓。 */
    private suspend fun loadHotTags(): List<MintTag> {
        val html = http.get(HOT_TAG_PAGE_URL, QQ_HEADERS) ?: return emptyList()
        val result = ArrayList<MintTag>()
        for (match in HOT_TAG_REGEX.findAll(html)) {
            val id = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val name = stripHtmlTags(match.groupValues.getOrNull(2).orEmpty())
            if (id.isEmpty() || name.isEmpty()) continue
            result.add(MintTag(id = id, name = name))
        }
        return result
    }

    private suspend fun hotTags(): List<MintTag> {
        if (hotTagsCache.isNotEmpty()) return hotTagsCache
        hotTagsCache = loadHotTags()
        return hotTagsCache
    }

    // ── 请求 ────────────────────────────────────────────────────

    private suspend fun musicuGet(data: Map<String, Any?>): JSONObject? {
        val encoded = URLEncoder.encode(MiniJson.encode(data), "UTF-8")
        val url = "$MUSIC_U_URL?loginUin=0&hostUin=0&format=json&inCharset=utf-8" +
            "&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data=$encoded"
        val root = MintJson.parseObject(http.get(url, QQ_HEADERS)) ?: return null
        if (MintJson.int(root, "code", 0) != 0) return null
        return root
    }

    private fun stripHtmlTags(text: String): String =
        decodeHtmlEntities(text.replace(HTML_TAG_REGEX, "")).trim()

    /** 歌单详情接口要带 Referer，否则会返回空。 */
    private fun cdInfoHeaders(dissId: String): Map<String, String> = mapOf(
        "Origin" to "https://y.qq.com",
        "Referer" to "https://y.qq.com/n/yqq/playsquare/$dissId.html",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    private companion object {

        const val MUSIC_U_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val TOPLIST_URL =
            "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg?g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0"
        const val CD_INFO_URL =
            "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg"
        const val HOT_TAG_PAGE_URL = "https://c.y.qq.com/node/pc/wk_v15/category_playlist.html"

        /** 标签 id 非法时用的兜底分类。 */
        const val DEFAULT_CATEGORY_ID = 10000000

        /** get_playlist_by_tag 的排序码：2=最新 5=热门。 */
        const val ORDER_HOT = 5

        const val HOT_LIMIT = 36
        const val HOT_RECOMMEND_LIMIT = 20
        const val PAGE_COUNT = 3
        const val HOT_TAG_FALLBACK_COUNT = 3

        val QQ_HEADERS = mapOf(
            "Referer" to "https://y.qq.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        val HOT_TAG_REGEX = Regex("class=\"c_bg_link js_tag_item\" data-id=\"(\\w+)\">(.+?)</a>")
        val HTML_TAG_REGEX = Regex("<[^>]*>")

        /** 排行榜接口挂掉时的兜底榜单。 */
        val FALLBACK_CHARTS = listOf(
            FallbackChart(id = "62", title = "飙升榜"),
            FallbackChart(id = "26", title = "热歌榜"),
            FallbackChart(id = "27", title = "新歌榜"),
            FallbackChart(id = "4", title = "流行指数榜"),
            FallbackChart(id = "57", title = "电音榜"),
            FallbackChart(id = "58", title = "说唱榜"),
            FallbackChart(id = "28", title = "网络歌曲榜"),
            FallbackChart(id = "5", title = "内地榜"),
            FallbackChart(id = "6", title = "港台榜"),
            FallbackChart(id = "3", title = "欧美榜"),
            FallbackChart(id = "16", title = "韩国榜"),
            FallbackChart(id = "17", title = "日本榜")
        )
    }

    /** 兜底榜单。 */
    private data class FallbackChart(val id: String, val title: String)
}
