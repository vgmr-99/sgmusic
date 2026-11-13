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
 * File: moe.ouom.neriplayer.platform.search.api.client.mint/MintNeteaseBrowseApi
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
 * 网易云音乐的发现页数据源。
 *
 * 与 Mint 的差别只在调用方式：Mint 是一条条「直连 → 代理」的顺序链，
 * 这里保留同样的顺序（直连失败才走代理），但把结果缓存下来，
 * 并把「热门歌单」「分类歌单」两处兜底链原样搬过来。
 *
 * 端点：
 * - 分类目录 `POST /weapi/playlist/catalogue`
 * - 热门标签 `POST /weapi/playlist/hottags`
 * - 歌单列表 `POST /weapi/playlist/list`
 * - 排行榜   `POST /weapi/toplist`
 * - 歌单详情 `POST /weapi/v3/playlist/detail` 与 linuxapi 转发接口
 */
class MintNeteaseBrowseApi(
    http: MintHttp = MintHttp()
) : MintBrowseApiBase(http), MintRefreshableCategories {

    override val providerCode: String = MintProviderLookup.MINT_PROVIDER_NETEASE

    /** 分类 key -> 中文标签名。 */
    private val tagLabels = LinkedHashMap<String, String>()

    /** 热门标签，供第三层兜底使用。 */
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
        loadCatalogue()
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
        val detail = playlistDetail(id, preferLinuxApi = card.kind != PlatformBrowseKind.CHART)
            ?: return emptyList()
        return parseSongs(detail)
    }

    // ── 热门歌单 ────────────────────────────────────────────────

    /**
     * 热门歌单的三层兜底。
     *
     * ① 直接拉热门歌单；② 退到「热门 + 空标签」的分类歌单；
     * ③ 再退到前 3 个热门标签逐个试。
     */
    private suspend fun hotPlaylists(): List<PlatformBrowseCard> {
        // ① 热门歌单（接口与分类歌单相同，只是条数不同）
        fetchPlaylistList(cat = ALL_CATEGORY, order = ORDER_HOT, limit = HOT_RECOMMEND_LIMIT, offset = 0)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        // ② 退到「热门 + 空标签」的分类歌单
        fetchPlaylistList(cat = ALL_CATEGORY, order = ORDER_HOT, limit = HOT_LIMIT, offset = 0)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        for (tag in hotTags().take(HOT_TAG_FALLBACK_COUNT)) {
            val tagged = fetchPlaylistList(cat = tag.id, order = ORDER_HOT, limit = HOT_LIMIT, offset = 0)
            if (tagged.isNotEmpty()) return tagged
        }
        return emptyList()
    }

    // ── 分类歌单 ────────────────────────────────────────────────

    private suspend fun categoryPlaylists(tagId: String): List<PlatformBrowseCard> {
        val cat = tagId.ifEmpty { ALL_CATEGORY }
        val merged = collectPages(PAGE_COUNT) { page ->
            fetchPlaylistList(
                cat = cat,
                order = ORDER_HOT,
                limit = HOT_LIMIT,
                offset = HOT_LIMIT * (page - 1)
            )
        }
        if (merged.isNotEmpty()) return merged

        // 只有「无标签 + 首页 + 热门」这一种情况才有后两层兜底。
        if (tagId.isNotEmpty()) return emptyList()

        hotPlaylists().takeIf { it.isNotEmpty() }?.let { return it.take(HOT_LIMIT) }

        for (tag in hotTags().take(HOT_TAG_FALLBACK_COUNT)) {
            val tagged = fetchPlaylistList(cat = tag.id, order = ORDER_HOT, limit = HOT_LIMIT, offset = 0)
            if (tagged.isNotEmpty()) return tagged
        }
        return emptyList()
    }

    private suspend fun fetchPlaylistList(
        cat: String,
        order: String,
        limit: Int,
        offset: Int
    ): List<PlatformBrowseCard> {
        val body = linkedMapOf<String, Any?>(
            "cat" to cat,
            "order" to order,
            "limit" to limit,
            "offset" to offset,
            "total" to true
        )
        val root = weapiRequest(PLAYLIST_LIST_PATH, body) ?: return emptyList()
        val list = MintJson.array(root, "playlists") ?: MintJson.array(root, "playlist")
        if (list == null) return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val id = MintJson.string(item, "id") ?: continue
                val title = MintJson.string(item, "name") ?: continue
                val author = MintJson.obj(item, "creator")
                    ?.optString("nickname", "")?.trim().orEmpty()
                val description = MintJson.string(item, "description").orEmpty()
                add(
                    PlatformBrowseCard(
                        id = id,
                        title = title,
                        subtitle = description.ifBlank { author },
                        coverUrl = normalizeCoverUrl(MintJson.string(item, "coverImgUrl")),
                        trackCount = MintJson.int(item, "trackCount"),
                        playCount = PlatformBrowseSupport.parsePlayCount(
                            formatPlayCount(MintJson.long(item, "playCount"))
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
        val root = weapiRequest(TOPLIST_PATH, emptyMap()) ?: return emptyList()
        val list = MintJson.array(root, "list") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val id = MintJson.string(item, "id") ?: continue
                val title = MintJson.string(item, "name") ?: continue
                add(
                    PlatformBrowseCard(
                        id = id,
                        title = title,
                        subtitle = MintJson.string(item, "updateFrequency").orEmpty(),
                        coverUrl = normalizeCoverUrl(MintJson.string(item, "coverImgUrl")),
                        playCount = PlatformBrowseSupport.parsePlayCount(
                            formatPlayCount(MintJson.long(item, "playCount"))
                        ),
                        webUrl = null,
                        kind = PlatformBrowseKind.CHART,
                        provider = cardProvider
                    )
                )
            }
        }
    }

    // ── 歌单详情 ────────────────────────────────────────────────

    /**
     * 歌单详情。
     *
     * Mint 对普通歌单用 linuxapi 转发、对榜单用 weapi，
     * 这里两条都试，最后再退到明文代理，保证同一个卡片总能拿到曲目。
     */
    private suspend fun playlistDetail(id: String, preferLinuxApi: Boolean): JSONObject? {
        if (preferLinuxApi) {
            linuxApiDetail(id)?.let { return it }
        }
        weapiDetail(id)?.let { return it }
        if (!preferLinuxApi) {
            linuxApiDetail(id)?.let { return it }
        }
        proxyPlaylistDetail(id)?.let { return it }
        return null
    }

    private suspend fun weapiDetail(id: String): JSONObject? {
        val body = linkedMapOf<String, Any?>(
            "id" to id,
            "n" to DETAIL_TRACK_LIMIT,
            "p" to 1
        )
        val root = weapiRequest(PLAYLIST_DETAIL_PATH, body) ?: return null
        return MintJson.obj(root, "playlist")
    }

    private suspend fun linuxApiDetail(id: String): JSONObject? {
        val body = linkedMapOf<String, Any?>(
            "method" to "POST",
            "url" to LINUX_API_TARGET,
            "params" to linkedMapOf<String, Any?>(
                "id" to id,
                "n" to DETAIL_TRACK_LIMIT,
                "s" to 8
            )
        )
        val form = MintNeteaseCrypto.linuxapi(body)
        val text = http.postForm(LINUX_API_URL, form, NETEASE_HEADERS) ?: return null
        val root = MintJson.parseObject(text) ?: return null
        if (MintJson.int(root, "code", 0) != 200) return null
        MintJson.obj(root, "playlist")?.let { return it }
        // 转发接口偶尔会把真正的响应塞在 result 字符串里。
        val nested = MintJson.parseObject(root.optString("result", ""))
        return MintJson.obj(nested, "playlist")
    }

    private suspend fun proxyPlaylistDetail(id: String): JSONObject? {
        val root = proxyRequest(
            "/playlist/detail",
            mapOf("id" to id, "n" to DETAIL_TRACK_LIMIT.toString(), "s" to "8")
        ) ?: return null
        return MintJson.obj(root, "playlist")
    }

    private fun parseSongs(playlist: JSONObject): List<PlatformSearchResult> {
        val tracks = playlist.optJSONArray("tracks") ?: return emptyList()
        return buildList {
            for (index in 0 until tracks.length()) {
                val track = tracks.optJSONObject(index) ?: continue
                val songId = MintJson.string(track, "id") ?: continue
                if (songId.toLongOrNull() == null) continue
                val name = MintJson.string(track, "name").orEmpty()
                if (name.isEmpty()) continue
                val album = track.optJSONObject("al")?.optString("name", "")?.trim()
                    ?: track.optJSONObject("album")?.optString("name", "")?.trim()
                    ?: ""
                val cover = normalizeCoverUrl(track.optJSONObject("al")?.optString("picUrl"))
                    ?: normalizeCoverUrl(track.optJSONObject("album")?.optString("picUrl"))
                    ?: normalizeCoverUrl(MintJson.string(track, "picUrl"))
                add(
                    PlatformSearchResult(
                        type = PlatformSearchType.SONG,
                        id = songId,
                        name = name,
                        subtitle = joinArtists(track.optJSONArray("ar") ?: track.optJSONArray("artists")),
                        album = album,
                        durationMs = MintJson.long(track, "dt", MintJson.long(track, "duration", 0L)),
                        coverUrl = cover,
                        extraCount = 0L,
                        webUrl = null,
                        extras = emptyMap(),
                        provider = cardProvider
                    )
                )
            }
        }
    }

    private fun joinArtists(artists: JSONArray?): String {
        if (artists == null) return ""
        val names = ArrayList<String>()
        for (index in 0 until artists.length()) {
            val name = artists.optJSONObject(index)?.optString("name", "")?.trim().orEmpty()
            if (name.isNotEmpty()) names.add(name)
        }
        return names.joinToString("、")
    }

    // ── 分类标签 ────────────────────────────────────────────────

    /** 拉取平台真实的分类目录（分组 + 组内标签）。 */
    private suspend fun loadCatalogue() {
        val root = weapiRequest(CATALOGUE_PATH, emptyMap()) ?: return
        val sub = MintJson.array(root, "sub") ?: return
        val categories = MintJson.obj(root, "categories") ?: return

        val groupNames = LinkedHashMap<String, String>()
        categories.keys().forEach { key ->
            groupNames[key] = categories.optString(key, "").trim()
        }
        val orderedKeys = groupNames.keys.sortedWith(
            compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it })
        )

        for (key in orderedKeys) {
            val groupName = groupNames[key].orEmpty()
            for (index in 0 until sub.length()) {
                val item = sub.optJSONObject(index) ?: continue
                val category = item.opt("category")?.toString()?.trim().orEmpty()
                if (category != key) continue
                val name = item.optString("name", "").trim()
                if (name.isEmpty()) continue
                val categoryKey = MintBrowseCategory.encode(providerCode, MintBrowseKind.TAG, groupName, name)
                tagLabels[categoryKey] = name
            }
        }
    }

    /** 拉取热门标签，用于第三层兜底。 */
    private suspend fun loadHotTags(): List<MintTag> {
        val root = weapiRequest(HOT_TAGS_PATH, emptyMap()) ?: return emptyList()
        val tags = MintJson.array(root, "tags") ?: return emptyList()
        val result = ArrayList<MintTag>()
        for (index in 0 until tags.length()) {
            val item = tags.optJSONObject(index) ?: continue
            val nested = item.optJSONObject("playlistTag")?.optString("name", "")?.trim().orEmpty()
            val name = nested.ifEmpty { item.optString("name", "").trim() }
            if (name.isEmpty()) continue
            result.add(MintTag(id = name, name = name))
        }
        return result
    }

    private suspend fun hotTags(): List<MintTag> {
        if (hotTagsCache.isNotEmpty()) return hotTagsCache
        hotTagsCache = loadHotTags()
        return hotTagsCache
    }

    // ── 请求 ────────────────────────────────────────────────────

    /** 直连优先，失败退到明文代理。 */
    private suspend fun weapiRequest(path: String, body: Map<String, Any?>): JSONObject? {
        val form = MintNeteaseCrypto.weapi(body)
        val text = http.postForm(NETEASE_HOST + path, form, NETEASE_HEADERS)
        val direct = MintJson.parseObject(text)
        if (direct != null && MintJson.int(direct, "code", 0) == 200) return direct
        return proxyRequest(proxyPathOf(path), proxyParamsOf(path, body))
    }

    /** 明文代理：先主代理，再备用代理。 */
    private suspend fun proxyRequest(path: String, params: Map<String, String>): JSONObject? {
        for (base in PROXY_BASES) {
            val url = StringBuilder(base).append(path).toString() + queryString(params)
            val json = MintJson.parseObject(http.get(url)) ?: continue
            if (MintJson.int(json, "code", 0) == 200) return json
        }
        return null
    }

    private fun queryString(params: Map<String, String>): String {
        if (params.isEmpty()) return ""
        return params.entries.joinToString("&", prefix = "?") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
    }

    /** weapi 路径对应的代理路径。 */
    private fun proxyPathOf(path: String): String = when (path) {
        CATALOGUE_PATH -> "/playlist/catlist"
        HOT_TAGS_PATH -> "/playlist/hot"
        PLAYLIST_LIST_PATH -> "/top/playlist"
        TOPLIST_PATH -> "/toplist"
        PLAYLIST_DETAIL_PATH -> "/playlist/detail"
        else -> path
    }

    /** weapi 请求体对应的代理查询参数。 */
    private fun proxyParamsOf(path: String, body: Map<String, Any?>): Map<String, String> =
        when (path) {
            PLAYLIST_LIST_PATH -> mapOf(
                "cat" to (body["cat"]?.toString() ?: ALL_CATEGORY),
                "order" to (body["order"]?.toString() ?: ORDER_HOT),
                "limit" to (body["limit"]?.toString() ?: HOT_LIMIT.toString()),
                "offset" to (body["offset"]?.toString() ?: "0")
            )

            PLAYLIST_DETAIL_PATH -> mapOf(
                "id" to (body["id"]?.toString().orEmpty()),
                "n" to DETAIL_TRACK_LIMIT.toString(),
                "s" to "8"
            )

            else -> emptyMap()
        }

    /** 网易云封面归一化。 */
    private fun normalizeCoverUrl(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        var normalized = when {
            text.startsWith("//") -> "https:$text"
            text.startsWith("http://") -> "https://${text.substring(7)}"
            else -> text
        }
        if (normalized.contains("music.126.net")) {
            val queryIndex = normalized.indexOf('?')
            if (queryIndex >= 0) normalized = normalized.substring(0, queryIndex)
            normalized = "$normalized?param=400y400"
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return null
        return normalized
    }

    private companion object {

        const val NETEASE_HOST = "https://music.163.com"
        const val LINUX_API_URL = "https://music.163.com/api/local/forward"
        const val LINUX_API_TARGET = "https://music.163.com/api/v3/playlist/detail"

        const val CATALOGUE_PATH = "/weapi/playlist/catalogue"
        const val HOT_TAGS_PATH = "/weapi/playlist/hottags"
        const val PLAYLIST_LIST_PATH = "/weapi/playlist/list"
        const val TOPLIST_PATH = "/weapi/toplist"
        const val PLAYLIST_DETAIL_PATH = "/weapi/v3/playlist/detail"

        /** 明文代理：主代理与备用代理。 */
        val PROXY_BASES = listOf(
            "https://netease-cloud-music-api.fe-mm.com",
            "https://ncmapi.btwoa.com"
        )

        val NETEASE_HEADERS = mapOf(
            "Referer" to "https://music.163.com/",
            "Origin" to "https://music.163.com"
        )

        const val ALL_CATEGORY = "全部"
        const val ORDER_HOT = "hot"

        /** 单页拉多少条歌单。 */
        const val HOT_LIMIT = 30

        /** 热门歌单接口单页的条数。 */
        const val HOT_RECOMMEND_LIMIT = 20

        /** 浏览页一次合并前几页。 */
        const val PAGE_COUNT = 3

        /** 兜底时试前几个热门标签。 */
        const val HOT_TAG_FALLBACK_COUNT = 3

        /** 歌单详情一次要拿到的曲目条数。 */
        const val DETAIL_TRACK_LIMIT = 100000
    }
}
