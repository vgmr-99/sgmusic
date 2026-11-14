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
 * File: moe.ouom.neriplayer.platform.search.api.client.mint/MintKugouBrowseApi
 * Created: 2026/11/13
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseKind
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseSupport
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 酷狗音乐的发现页数据源。
 *
 * 酷狗的分类标签不是接口给的，而是「风格分类 id」这套固定约定：
 * `getSpecial` 的 `c` 参数只认数字 id，传中文会返回同一批默认歌单。
 * 所以这里的标签是写死的，接口只负责按 id 取歌单。
 *
 * 端点：
 * - 歌单广场 `GET http://www2.kugou.kugou.com/yueku/v9/special/getSpecial`
 * - 排行榜   `GET http://mobilecdnbj.kugou.com/api/v5/rank/list`
 * - 榜单详情 `GET http://mobilecdnbj.kugou.com/api/v3/rank/song`
 * - 歌单详情 `GET http://mobileservice.kugou.com/api/v5/special/song`
 */
class MintKugouBrowseApi(
    http: MintHttp = MintHttp()
) : MintBrowseApiBase(http) {

    override val providerCode: String = "kg"

    private val hotCategory: String =
        MintBrowseCategory.encode(providerCode, MintBrowseKind.HOT, "", "")

    private val chartCategory: String =
        MintBrowseCategory.encode(providerCode, MintBrowseKind.CHART, "", "")

    override val categories: List<String> = buildList {
        add(hotCategory)
        add(chartCategory)
        TAG_GROUPS.forEach { group ->
            group.tags.forEach { tag ->
                add(MintBrowseCategory.encode(providerCode, MintBrowseKind.TAG, group.name, tag.id))
            }
        }
    }

    override fun categoryLabel(category: String): String = when (category) {
        hotCategory -> "热门歌单"
        chartCategory -> "排行榜"
        else -> {
            val ref = MintBrowseCategory.decode(category)
            val tagId = ref?.tagId.orEmpty()
            TAG_GROUPS.asSequence()
                .flatMap { it.tags.asSequence() }
                .firstOrNull { it.id == tagId }
                ?.name
                ?: tagId
        }
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
        fetchSpecial(sortId = SORT_RECOMMEND, tagId = "", page = 1)
            .take(HOT_RECOMMEND_LIMIT)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        fetchSpecial(sortId = SORT_HOT, tagId = "", page = 1)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        for (tag in HOT_TAGS.take(HOT_TAG_FALLBACK_COUNT)) {
            val tagged = fetchSpecial(sortId = SORT_HOT, tagId = tag.id, page = 1)
            if (tagged.isNotEmpty()) return tagged
        }
        return emptyList()
    }

    // ── 分类歌单 ────────────────────────────────────────────────

    private suspend fun categoryPlaylists(tagId: String): List<PlatformBrowseCard> {
        val merged = collectPages(PAGE_COUNT) { page ->
            fetchSpecial(sortId = SORT_HOT, tagId = tagId, page = page)
        }
        if (merged.isNotEmpty()) return merged
        if (tagId.isNotEmpty()) return emptyList()

        hotPlaylists().takeIf { it.isNotEmpty() }?.let { return it.take(HOT_LIMIT) }

        for (tag in HOT_TAGS.take(HOT_TAG_FALLBACK_COUNT)) {
            val tagged = fetchSpecial(sortId = SORT_HOT, tagId = tag.id, page = 1)
            if (tagged.isNotEmpty()) return tagged
        }
        return emptyList()
    }

    private suspend fun fetchSpecial(
        sortId: String,
        tagId: String,
        page: Int
    ): List<PlatformBrowseCard> {
        val url = StringBuilder(SPECIAL_URL)
            .append("?is_ajax=1&cdn=cdn&t=").append(sortId)
            .append("&c=").append(tagId)
            .append("&p=").append(page)
            .toString()
        val root = MintJson.parseObject(http.get(url)) ?: return emptyList()
        val list = MintJson.array(root, "special_db") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val id = MintJson.string(item, "specialid") ?: continue
                val title = MintJson.string(item, "specialname") ?: continue
                add(
                    PlatformBrowseCard(
                        id = id,
                        title = decodeHtmlEntities(title),
                        subtitle = decodeHtmlEntities(MintJson.string(item, "intro").orEmpty()),
                        coverUrl = normalizeImage(MintJson.stringOf(item, "img", "imgurl"), COVER_SIZE),
                        trackCount = MintJson.int(item, "songcount", MintJson.int(item, "song_count")),
                        playCount = PlatformBrowseSupport.parsePlayCount(rawPlayCount(item)),
                        webUrl = null,
                        kind = PlatformBrowseKind.PLAYLIST,
                        provider = cardProvider
                    )
                )
            }
        }
    }

    private fun rawPlayCount(item: JSONObject): String {
        val value = item.opt("total_play_count")
            ?: item.opt("playcount")
            ?: item.opt("play_count")
        return when (value) {
            null -> ""
            is Number -> formatPlayCount(value.toLong())
            else -> {
                val text = value.toString().trim()
                when {
                    text.isEmpty() -> ""
                    text.contains("亿") || text.contains("万") -> text
                    else -> text.toLongOrNull()?.let { formatPlayCount(it) } ?: text
                }
            }
        }
    }

    // ── 排行榜 ──────────────────────────────────────────────────

    private suspend fun leaderboards(): List<PlatformBrowseCard> {
        val root = MintJson.parseObject(http.get(RANK_LIST_URL))
        val list = MintJson.array(root, "info")
        val cards = if (list == null) {
            emptyList()
        } else {
            buildList {
                for (index in 0 until list.length()) {
                    val item = list.optJSONObject(index) ?: continue
                    // isvol=1 才是真正的榜单，vol 是MV榜
                    if (item.opt("isvol")?.toString() != "1") continue
                    val id = MintJson.stringOf(item, "rankid", "id") ?: continue
                    val title = MintJson.string(item, "rankname") ?: continue
                    add(
                        PlatformBrowseCard(
                            id = id,
                            title = decodeHtmlEntities(title),
                            subtitle = MintJson.string(item, "update_frequency").orEmpty(),
                            coverUrl = normalizeImage(
                                MintJson.stringOf(item, "banner_9", "table_plaque", "album_img_9"),
                                COVER_SIZE
                            ),
                            playCount = PlatformBrowseSupport.parsePlayCount(
                                formatPlayCount(MintJson.long(item, "play_times"))
                            ),
                            webUrl = null,
                            kind = PlatformBrowseKind.CHART,
                            provider = cardProvider
                        )
                    )
                }
            }
        }
        return cards.ifEmpty {
            FALLBACK_CHARTS.map { fallback ->
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
        }
    }

    // ── 榜单详情 ────────────────────────────────────────────────

    private suspend fun chartTracks(rankId: String): List<PlatformSearchResult> {
        val url = StringBuilder(RANK_SONG_URL)
            .append("?version=9108&ranktype=1&plat=0&pagesize=100&area_code=1&page=1")
            .append("&rankid=").append(rankId)
            .append("&with_res_tag=0&show_portrait_mv=1")
            .toString()
        val root = MintJson.parseObject(http.get(url)) ?: return emptyList()
        val list = MintJson.array(root, "info") ?: MintJson.array(root, "list") ?: return emptyList()
        return parseSongs(list)
    }

    // ── 歌单详情 ────────────────────────────────────────────────

    /**
     * 歌单曲目。
     *
     * 原来的 `m.kugou.com/plist/list/{id}?json=true` 已经下线，
     * 现在返回的是一个错误模板页（`__Tpl=outsidetpl.htm`），
     * 取不到 `list.info.members`，所以所有歌单都读不出曲目。
     *
     * 改用 App 自己在用的这个接口：游客态可读，一次一页。
     */
    private suspend fun playlistTracks(specialId: String): List<PlatformSearchResult> {
        val pages = coroutineScope {
            (1..TRACK_PAGE_COUNT).map { page ->
                async(Dispatchers.IO) { specialSongPage(specialId, page) }
            }.awaitAll()
        }
        return pages.flatten().distinctBy { it.id }
    }

    /** 歌单曲目的一页。 */
    private suspend fun specialSongPage(specialId: String, page: Int): List<PlatformSearchResult> {
        val url = StringBuilder(SPECIAL_SONG_URL)
            .append("?specialid=").append(specialId)
            .append("&page=").append(page)
            .append("&pagesize=").append(TRACK_PAGE_SIZE)
            .toString()
        val root = MintJson.parseObject(http.get(url)) ?: return emptyList()
        val list = MintJson.array(root, "info") ?: return emptyList()
        return parseSongs(list)
    }

    // ── 曲目解析 ────────────────────────────────────────────────

    private fun parseSongs(list: JSONArray): List<PlatformSearchResult> = buildList {
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val audioInfo = item.optJSONObject("audio_info")
            val albumInfo = item.optJSONObject("album_info")
            val hash = MintJson.stringOf(item, "FileHash", "hash", "audio_id")
                ?: audioInfo?.let { MintJson.string(it, "hash") }
                ?: continue
            if (hash.isEmpty()) continue
            // 歌单接口只给 `filename`（"歌手 - 歌名"），没有独立的歌名字段，
            // 所以先把明确的歌名字段取完，取不到再用 filename 拆。
            val filename = MintJson.string(item, "filename").orEmpty()
            val split = splitFilename(filename)
            val title = MintJson.stringOf(item, "SongName", "songname", "songname_original")
                ?: split?.song
                ?: filename
            if (title.isEmpty()) continue
            val artist = MintJson.stringOf(
                item,
                "SingerName",
                "singername",
                "AuthorName",
                "author_name"
            ) ?: split?.artist.orEmpty()
            val album = MintJson.stringOf(item, "AlbumName", "album_name")
                ?: albumInfo?.opt("album_name")?.toString()
                ?: ""
            add(
                PlatformSearchResult(
                    type = PlatformSearchType.SONG,
                    id = hash,
                    name = decodeHtmlEntities(title),
                    subtitle = decodeHtmlEntities(artist),
                    album = decodeHtmlEntities(album),
                    durationMs = parseDurationMillis(item),
                    coverUrl = buildSongCover(item, albumInfo, hash),
                    extraCount = 0L,
                    webUrl = null,
                    extras = mapOf("hash" to hash),
                    provider = cardProvider
                )
            )
        }
    }

    /** 酷狗的 `filename` 形如「孙燕姿 - 我不难过」。 */
    private data class FilenameParts(val artist: String, val song: String)

    /**
     * 拆 `filename` 里的歌手与歌名。
     *
     * 只按第一个 ` - ` 拆：歌名本身带 ` - ` 的情况极少，
     * 一刀切按最后一个拆反而容易把歌手名切坏。
     */
    private fun splitFilename(filename: String): FilenameParts? {
        if (filename.isEmpty()) return null
        val index = filename.indexOf(SEPARATOR)
        if (index <= 0 || index + SEPARATOR.length >= filename.length) return null
        return FilenameParts(
            artist = filename.substring(0, index).trim(),
            song = filename.substring(index + SEPARATOR.length).trim()
        )
    }

    /**
     * 酷狗的时长字段有时是秒、有时是毫秒，超过 10000 就按毫秒算。
     */
    private fun parseDurationMillis(item: JSONObject): Long {
        val audioInfo = item.optJSONObject("audio_info")
        val raw = item.opt("Duration")
            ?: item.opt("duration")
            ?: item.opt("timeLength")
            ?: item.opt("timelength")
            ?: audioInfo?.opt("timelength")
        val value = raw?.toString()?.toDoubleOrNull() ?: 0.0
        if (value <= 0) return 0L
        return if (value > 10000) (value / 1000.0).toLong() else (value.toLong() * 1000L)
    }

    /**
     * 曲目的封面按固定顺序试，最后用 hash 拼一个兜底地址。
     */
    private fun buildSongCover(
        item: JSONObject,
        albumInfo: JSONObject?,
        hash: String
    ): String? {
        // 歌单接口的封面藏在 trans_param.union_cover 里，带 {size} 占位符
        val transParam = item.optJSONObject("trans_param")
        val candidates = listOf(
            item.opt("sizable_cover")?.toString(),
            item.opt("album_sizable_cover")?.toString(),
            albumInfo?.opt("sizable_cover")?.toString(),
            albumInfo?.opt("album_img")?.toString(),
            albumInfo?.opt("img")?.toString(),
            transParam?.opt("union_cover")?.toString(),
            item.opt("img")?.toString(),
            item.opt("pic")?.toString(),
            item.opt("Image")?.toString(),
            item.opt("album_img")?.toString()
        )
        for (candidate in candidates) {
            val image = normalizeImage(candidate, SONG_COVER_SIZE)
            if (!image.isNullOrEmpty()) return image
        }
        return "https://imge.kugou.com/$SONG_COVER_SIZE/$hash.jpg"
    }

    /** 酷狗图片地址归一化：替换尺寸占位符，补全协议并强制 https。 */
    private fun normalizeImage(raw: String?, size: String): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        var result = text.replace("{size}", size)
        if (result.startsWith("//")) result = "https:$result"
        result = result.replace("http://", "https://")
        return result.ifEmpty { null }
    }

    private companion object {

        const val SPECIAL_URL = "http://www2.kugou.kugou.com/yueku/v9/special/getSpecial"
        const val RANK_LIST_URL =
            "http://mobilecdnbj.kugou.com/api/v5/rank/list?version=9108&plat=0&showtype=2&parentid=0&apiver=6&area_code=1&withsong=1"
        const val RANK_SONG_URL = "http://mobilecdnbj.kugou.com/api/v3/rank/song"

        /** 歌单曲目：原来的 m.kugou.com/plist/list 已经下线，换成这个 App 自己用的接口。 */
        const val SPECIAL_SONG_URL = "http://mobileservice.kugou.com/api/v5/special/song"

        /** 歌名与歌手之间的分隔符。 */
        const val SEPARATOR = " - "

        /** 歌单曲目一页拉多少首。 */
        const val TRACK_PAGE_SIZE = 50

        /** 歌单曲目并发拉几页（最多 150 首）。 */
        const val TRACK_PAGE_COUNT = 3

        /** getSpecial 的排序码：5=推荐 6=热门 7=最新。 */
        const val SORT_RECOMMEND = "5"
        const val SORT_HOT = "6"

        const val COVER_SIZE = "400"
        const val SONG_COVER_SIZE = "800"

        const val HOT_LIMIT = 30
        const val HOT_RECOMMEND_LIMIT = 20
        const val PAGE_COUNT = 3
        const val HOT_TAG_FALLBACK_COUNT = 3

        /**
         * 酷狗的风格分类 id。
         *
         * `getSpecial` 的 `c` 参数只认数字 id，传中文会返回同一批默认歌单。
         */
        val TAG_GROUPS = listOf(
            TagGroup(
                name = "语种",
                tags = listOf(
                    MintTag(id = "", name = "全部"),
                    MintTag(id = "11", name = "华语"),
                    MintTag(id = "15", name = "欧美")
                )
            ),
            TagGroup(
                name = "风格",
                tags = listOf(
                    MintTag(id = "30", name = "流行"),
                    MintTag(id = "850", name = "电子"),
                    MintTag(id = "10", name = "DJ")
                )
            )
        )

        /** 兜底时试前几个热门标签。 */
        val HOT_TAGS = listOf(
            MintTag(id = "11", name = "华语"),
            MintTag(id = "30", name = "流行"),
            MintTag(id = "850", name = "电子")
        )

        /** 榜单接口挂掉时的兜底榜单。 */
        val FALLBACK_CHARTS = listOf(
            FallbackChart(id = "8888", title = "TOP500"),
            FallbackChart(id = "6666", title = "飙升榜"),
            FallbackChart(id = "59703", title = "蜂鸟流行音乐榜"),
            FallbackChart(id = "52144", title = "抖音热歌榜"),
            FallbackChart(id = "24971", title = "DJ热歌榜"),
            FallbackChart(id = "23784", title = "网络红歌榜"),
            FallbackChart(id = "31308", title = "内地榜"),
            FallbackChart(id = "31310", title = "欧美榜"),
            FallbackChart(id = "33160", title = "电影榜")
        )
    }

    /** 分类分组。 */
    private data class TagGroup(val name: String, val tags: List<MintTag>)

    /** 兜底榜单。 */
    private data class FallbackChart(val id: String, val title: String)
}
