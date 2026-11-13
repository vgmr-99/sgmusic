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
 * File: moe.ouom.neriplayer.platform.search.api.client.mint/MintKuwoBrowseApi
 * Created: 2026/11/13
 */

import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseKind
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseSupport
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 酷我音乐的发现页数据源。
 *
 * 酷我的歌单 id 是 `kw_digest-{digest}__{id}` 这种组合串，
 * 标签 id 是 `{id}-{digest}`，都不能直接当分类 key 显示，所以分类 key 与
 * 中文标签名分开维护。
 *
 * 端点：
 * - 歌单广场 `GET http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList`
 * - 分类标签 `GET http://wapi.kuwo.cn/api/pc/classify/playlist/getTagList`
 * - 热门标签 `GET http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmTagList`
 * - 分类歌单 `GET http://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList`
 * - 排行榜   `GET http://qukudata.kuwo.cn/q.k`
 * - 榜单详情 `GET https://wbd.kuwo.cn/api/bd/bang/bang_info`（AES-ECB + MD5 签名）
 * - 歌单详情 `GET http://nplserver.kuwo.cn/pl.svc`
 */
class MintKuwoBrowseApi(
    http: MintHttp = MintHttp()
) : MintBrowseApiBase(http), MintRefreshableCategories {

    override val providerCode: String = "kw"

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
        loadTags()
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
        val id = card.id
        if (id.isEmpty()) return emptyList()
        return when (card.kind) {
            PlatformBrowseKind.CHART -> chartTracks(id)
            PlatformBrowseKind.PLAYLIST -> playlistTracks(id)
        }
    }

    // ── 热门歌单 ────────────────────────────────────────────────

    /** 热门歌单的三层兜底：热门推荐 → 热门分类 → 前 3 个热门标签。 */
    private suspend fun hotPlaylists(): List<PlatformBrowseCard> {
        // ① 热门歌单接口的页码是 1 起
        fetchRecommendPlaylists(pn = 1, limit = HOT_RECOMMEND_LIMIT)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        // ② 退到「热门 + 空标签」的分类歌单
        fetchRecommendPlaylists(pn = 0, limit = HOT_LIMIT)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        for (tag in hotTags().take(HOT_TAG_FALLBACK_COUNT)) {
            val tagged = fetchTagPlaylists(tagId = tag.id, page = 1, limit = HOT_LIMIT)
            if (tagged.isNotEmpty()) return tagged
        }
        return emptyList()
    }

    // ── 分类歌单 ────────────────────────────────────────────────

    private suspend fun categoryPlaylists(tagId: String): List<PlatformBrowseCard> {
        val merged = collectPages(PAGE_COUNT) { page ->
            if (tagId.isEmpty()) {
                fetchRecommendPlaylists(pn = page - 1, limit = HOT_LIMIT)
            } else {
                fetchTagPlaylists(tagId = tagId, page = page, limit = HOT_LIMIT)
            }
        }
        if (merged.isNotEmpty()) return merged
        if (tagId.isNotEmpty()) return emptyList()

        hotPlaylists().takeIf { it.isNotEmpty() }?.let { return it.take(HOT_LIMIT) }

        for (tag in hotTags().take(HOT_TAG_FALLBACK_COUNT)) {
            val tagged = fetchTagPlaylists(tagId = tag.id, page = 1, limit = HOT_LIMIT)
            if (tagged.isNotEmpty()) return tagged
        }
        return emptyList()
    }

    private suspend fun fetchRecommendPlaylists(pn: Int, limit: Int): List<PlatformBrowseCard> {
        val url = "$RECOMMEND_URL?loginUid=0&loginSid=0&appUid=$APP_UID" +
            "&pn=$pn&rn=$limit&order=hot"
        val root = parseJsonObject(http.get(url)) ?: return emptyList()
        val list = MintJson.array(root, "data") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val id = MintJson.string(item, "id") ?: continue
                val title = MintJson.string(item, "name") ?: continue
                val digest = MintJson.string(item, "digest").orEmpty()
                add(
                    PlatformBrowseCard(
                        id = playlistId(digest, id),
                        title = decodeHtmlEntities(title),
                        subtitle = decodeHtmlEntities(MintJson.string(item, "desc").orEmpty()),
                        coverUrl = normalizeCoverUrl(MintJson.string(item, "img")),
                        trackCount = MintJson.int(item, "total"),
                        playCount = PlatformBrowseSupport.parsePlayCount(
                            formatPlayCount(MintJson.long(item, "listencnt"), strictGreater = true)
                        ),
                        webUrl = null,
                        kind = PlatformBrowseKind.PLAYLIST,
                        provider = cardProvider
                    )
                )
            }
        }
    }

    private suspend fun fetchTagPlaylists(
        tagId: String,
        page: Int,
        limit: Int
    ): List<PlatformBrowseCard> {
        val id = tagId.substringBefore('-')
        val url = "$TAG_PLAYLIST_URL?loginUid=0&loginSid=0&appUid=$APP_UID" +
            "&pn=${page - 1}&id=$id&rn=$limit"
        val root = parseJsonObject(http.get(url)) ?: return emptyList()
        val list = MintJson.array(root, "data") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val rawId = MintJson.string(item, "id") ?: continue
                val title = MintJson.string(item, "name") ?: continue
                val digest = MintJson.string(item, "digest").orEmpty()
                add(
                    PlatformBrowseCard(
                        id = playlistId(digest, rawId),
                        title = decodeHtmlEntities(title),
                        subtitle = decodeHtmlEntities(MintJson.string(item, "desc").orEmpty()),
                        coverUrl = normalizeCoverUrl(MintJson.string(item, "img")),
                        trackCount = MintJson.int(item, "total"),
                        playCount = PlatformBrowseSupport.parsePlayCount(
                            formatPlayCount(MintJson.long(item, "listencnt"), strictGreater = true)
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
        val root = parseJsonObject(http.get(RANK_TREE_URL))
        val list = MintJson.array(root, "child")
        val cards = if (list == null) {
            emptyList()
        } else {
            val entries = ArrayList<Pair<Long, PlatformBrowseCard>>()
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val id = MintJson.string(item, "sourceid") ?: continue
                val title = MintJson.string(item, "name") ?: continue
                val listen = MintJson.long(item, "listen")
                entries.add(
                    listen to PlatformBrowseCard(
                        id = id,
                        title = decodeHtmlEntities(title),
                        subtitle = MintJson.string(item, "info").orEmpty(),
                        coverUrl = normalizeCoverUrl(MintJson.string(item, "pic")),
                        playCount = PlatformBrowseSupport.parsePlayCount(
                            formatPlayCount(listen, strictGreater = true)
                        ),
                        webUrl = null,
                        kind = PlatformBrowseKind.CHART,
                        provider = cardProvider
                    )
                )
            }
            entries.sortedByDescending { it.first }.map { it.second }
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

    private suspend fun chartTracks(rankId: String): List<PlatformSearchResult> {
        wbdChartTracks(rankId).takeIf { it.isNotEmpty() }?.let { return it }
        plainChartTracks(rankId).takeIf { it.isNotEmpty() }?.let { return it }
        return playlistTracks(rankId)
    }

    /** 加密版榜单详情：AES-128-ECB + MD5 签名。 */
    private suspend fun wbdChartTracks(rankId: String): List<PlatformSearchResult> {
        val body = linkedMapOf<String, Any?>(
            "uid" to "",
            "devId" to "",
            "sFrom" to "kuwo_sdk",
            "user_type" to "AP",
            "carSource" to "kwplayercar_ar_6.0.1.0_apk_keluze.apk",
            "id" to (rankId.toIntOrNull() ?: rankId),
            "pn" to 0,
            "rn" to 100
        )
        val encodedData = MintCrypto.aesEcbEncryptBase64(MiniJson.encode(body), WBD_KEY)
        val time = System.currentTimeMillis()
        val sign = MintCrypto.md5Hex("$WBD_APP_ID$encodedData$time".toByteArray(Charsets.UTF_8)).uppercase()
        val url = "$WBD_URL?data=${URLEncoder.encode(encodedData, "UTF-8")}" +
            "&time=$time&appId=$WBD_APP_ID&sign=$sign"
        val text = http.get(url) ?: return emptyList()
        val decoded = decodeWbdPayload(text) ?: return emptyList()
        val list = MintJson.array(decoded, "musiclist") ?: return emptyList()
        return parseSongs(list)
    }

    /** 明文版榜单详情，作为加密接口失败时的兜底。 */
    private suspend fun plainChartTracks(rankId: String): List<PlatformSearchResult> {
        val url = "$KBANG_URL?from=pc&fmt=json&pn=0&rn=100&type=bang&data=content" +
            "&id=$rankId&show_copyright_off=0&pcmp4=1&isbang=1"
        val root = parseJsonObject(http.get(url)) ?: return emptyList()
        val list = MintJson.array(root, "musiclist")
            ?: MintJson.array(MintJson.obj(root, "bang"), "musiclist")
            ?: return emptyList()
        return parseSongs(list)
    }

    private fun decodeWbdPayload(text: String): JSONObject? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val normalized = runCatching {
            URLDecoder.decode(trimmed.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(trimmed)
        val plain = MintCrypto.aesEcbDecryptBase64(normalized, WBD_KEY) ?: return null
        return parseJsonObject(plain)
    }

    // ── 歌单详情 ────────────────────────────────────────────────

    private suspend fun playlistTracks(playlistId: String): List<PlatformSearchResult> {
        val realId = playlistRealId(playlistId)
        val url = "$PLAYLIST_DETAIL_URL?op=getlistinfo&pid=$realId&pn=0&rn=1000" +
            "&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&plat=web&from=web" +
            "&vipver=MUSIC_9.0.5.0_W1&newver=1"
        val root = parseJsonObject(http.get(url)) ?: return emptyList()
        val list = MintJson.array(root, "musiclist") ?: return emptyList()
        return parseSongs(list)
    }

    private fun parseSongs(list: JSONArray): List<PlatformSearchResult> = buildList {
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val rid = MintJson.stringOf(item, "id", "rid", "mid") ?: continue
            val title = MintJson.string(item, "name") ?: continue
            add(
                PlatformSearchResult(
                    type = PlatformSearchType.SONG,
                    id = rid,
                    name = decodeHtmlEntities(title),
                    subtitle = decodeHtmlEntities(
                        MintJson.stringOf(item, "artist", "singer").orEmpty()
                    ),
                    album = decodeHtmlEntities(MintJson.string(item, "album").orEmpty()),
                    durationMs = MintJson.long(item, "duration") * 1000L,
                    coverUrl = normalizeCoverUrl(
                        MintJson.stringOf(item, "pic", "albumpic", "musicPic")
                    ),
                    extraCount = 0L,
                    webUrl = null,
                    extras = mapOf("rid" to rid),
                    provider = cardProvider
                )
            )
        }
    }

    // ── 分类标签 ────────────────────────────────────────────────

    private suspend fun loadTags() {
        val url = "$TAG_LIST_URL?cmd=rcm_keyword_playlist&user=0&prod=kwplayer_pc_9.0.5.0" +
            "&vipver=9.0.5.0&source=kwplayer_pc_9.0.5.0&loginUin=0&loginSid=0&appUid=$APP_UID"
        val root = parseJsonObject(http.get(url)) ?: return
        val groups = MintJson.array(root, "data") ?: return
        for (groupIndex in 0 until groups.length()) {
            val group = groups.optJSONObject(groupIndex) ?: continue
            val groupName = group.optString("name", "").trim()
            val tags = group.optJSONArray("data") ?: continue
            for (index in 0 until tags.length()) {
                val tag = tags.optJSONObject(index) ?: continue
                val name = tag.optString("name", "").trim()
                if (name.isEmpty()) continue
                val tagId = "${tag.opt("id")}-${tag.opt("digest")}"
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

    private suspend fun loadHotTags(): List<MintTag> {
        val url = "$HOT_TAG_URL?loginUin=0&loginSid=0&appUid=$APP_UID"
        val root = parseJsonObject(http.get(url)) ?: return emptyList()
        val groups = MintJson.array(root, "data") ?: return emptyList()
        val first = groups.optJSONObject(0) ?: return emptyList()
        val tags = first.optJSONArray("data") ?: return emptyList()
        val result = ArrayList<MintTag>()
        for (index in 0 until tags.length()) {
            val tag = tags.optJSONObject(index) ?: continue
            val name = tag.optString("name", "").trim()
            if (name.isEmpty()) continue
            result.add(MintTag(id = "${tag.opt("id")}-${tag.opt("digest")}", name = name))
        }
        return result
    }

    private suspend fun hotTags(): List<MintTag> {
        if (hotTagsCache.isNotEmpty()) return hotTagsCache
        hotTagsCache = loadHotTags()
        return hotTagsCache
    }

    // ── 工具 ────────────────────────────────────────────────────

    /** 歌单 id：酷我的歌单需要带上 digest 才能定位到正确的一单。 */
    private fun playlistId(digest: String, id: String): String = "kw_digest-${digest}__$id"

    /** 从歌单 id 里解析出真正要传给接口的数字 id。 */
    private fun playlistRealId(raw: String): String {
        var id = raw
        if (id.startsWith("kw_")) id = id.substring(3)
        if (id.startsWith("digest-")) {
            val parts = id.split("__")
            if (parts.size == 2) id = parts[1]
        }
        return id
    }

    /**
     * 酷我的封面归一化。
     *
     * 协议统一降到 http（酷我图片站 https 证书不全），
     * 只给路径的按 `albumcover` 目录拼完整地址。
     */
    private fun normalizeCoverUrl(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.startsWith("//")) return "http:$text"
        if (text.startsWith("https://")) return "http://${text.substring(8)}"
        if (text.startsWith("http://")) return text
        val path = text.trimStart('/')
        return "http://img1.kuwo.cn/star/albumcover/$path"
    }

    /** 酷我偶尔会返回单引号的 JSON，先按标准 JSON 解析，失败再换引号重试。 */
    private fun parseJsonObject(text: String?): JSONObject? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        MintJson.parseObject(trimmed)?.let { return it }
        if (!trimmed.contains('\'')) return null
        return MintJson.parseObject(trimmed.replace('\'', '"'))
    }

    private companion object {

        const val RECOMMEND_URL =
            "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList"
        const val TAG_PLAYLIST_URL =
            "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList"
        const val TAG_LIST_URL =
            "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagList"
        const val HOT_TAG_URL =
            "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmTagList"
        const val RANK_TREE_URL =
            "http://qukudata.kuwo.cn/q.k?op=query&cont=tree&node=2&pn=0&rn=1000&fmt=json&level=3"
        const val WBD_URL = "https://wbd.kuwo.cn/api/bd/bang/bang_info"
        const val KBANG_URL = "http://kbangserver.kuwo.cn/ksong.s"
        const val PLAYLIST_DETAIL_URL = "http://nplserver.kuwo.cn/pl.svc"

        const val APP_UID = "76039576"
        const val WBD_APP_ID = "y67sprxhhpws"

        /** 酷我签名接口用的 AES 密钥。 */
        val WBD_KEY = byteArrayOf(
            112, 87, 39, 61, 199.toByte(), 250.toByte(), 41.toByte(), 191.toByte(),
            57, 68, 45, 114, 221.toByte(), 94, 140.toByte(), 228.toByte()
        )

        const val HOT_LIMIT = 30
        const val HOT_RECOMMEND_LIMIT = 20
        const val PAGE_COUNT = 3
        const val HOT_TAG_FALLBACK_COUNT = 3

        /** 排行榜接口挂掉时的兜底榜单。 */
        val FALLBACK_CHARTS = listOf(
            FallbackChart(id = "93", title = "飙升榜"),
            FallbackChart(id = "16", title = "热歌榜"),
            FallbackChart(id = "17", title = "新歌榜"),
            FallbackChart(id = "158", title = "流行趋势榜"),
            FallbackChart(id = "145", title = "抖音歌曲榜"),
            FallbackChart(id = "187", title = "快手歌曲榜"),
            FallbackChart(id = "141", title = "网络神曲榜"),
            FallbackChart(id = "184", title = "综艺热歌榜"),
            FallbackChart(id = "242", title = "华语榜"),
            FallbackChart(id = "243", title = "欧美榜"),
            FallbackChart(id = "244", title = "日韩榜")
        )
    }

    /** 兜底榜单。 */
    private data class FallbackChart(val id: String, val title: String)
}
