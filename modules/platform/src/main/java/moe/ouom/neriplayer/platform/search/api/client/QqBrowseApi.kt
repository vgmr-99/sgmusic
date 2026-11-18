package moe.ouom.neriplayer.platform.search.api.client

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
 * File: moe.ouom.neriplayer.platform.search.api.client/QqBrowseApi
 * Created: 2026/11/12
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseApi
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseKind
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseSupport
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * QQ音乐浏览页：歌单广场 + 分类歌单 + 巅峰榜。
 *
 * - 歌单广场 musicu.fcg → playlist.get_playlist_by_tag（结果在 v_playlist）
 * - 分类歌单 musicu.fcg → playlist.get_category_content（结果在 content.v_item）
 * - 分类标签 c.y.qq.com/node/pc/wk_v15/category_playlist.html（HTML，正则取 data-id）
 * - 巅峰榜   c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg
 * - 歌单详情 c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg
 *
 * 榜单接口只给了歌名和歌手，没有 songmid，展示曲目时用公开搜索补齐。
 */
class QqBrowseApi(
    private val client: OkHttpClient,
    /** 按"歌名 歌手"补 songmid：榜单接口只给了名字 */
    private val resolveSongMid: suspend (keyword: String) -> String? = { null }
) : PlatformBrowseApi {

    override val provider: PlatformSearchProvider = PlatformSearchProvider.QQ

    override val categories: List<String> =
        listOf(CATEGORY_HOT, CATEGORY_CHARTS) + TAG_CATEGORIES.map { it.label }

    override suspend fun browse(category: String): List<PlatformBrowseCard> =
        withContext(Dispatchers.IO) {
            when (category) {
                CATEGORY_CHARTS -> leaderboards()
                CATEGORY_HOT -> plazaPlaylists()
                else -> PlatformBrowseSupport.browseWithFallback(
                    category = category,
                    categories = TAG_CATEGORIES,
                    primary = { label -> categoryPlaylists(tagIdFor(label)) },
                    fallback = { plazaPlaylists() },
                    hotTags = { TAG_CATEGORIES }
                )
            }
        }

    override suspend fun tracks(card: PlatformBrowseCard): List<PlatformSearchResult> =
        withContext(Dispatchers.IO) {
            val rawId = PlatformBrowseSupport.stripProviderPrefix(card.id)
            when (card.kind) {
                PlatformBrowseKind.CHART -> leaderboardTracks(rawId)
                PlatformBrowseKind.PLAYLIST -> playlistTracks(rawId)
            }
        }

    /** 歌单广场 */
    private fun plazaPlaylists(): List<PlatformBrowseCard> {
        val data = JSONObject()
            .put("comm", JSONObject().put("cv", 1602).put("ct", 20))
            .put(
                "playlist",
                JSONObject()
                    .put("method", "get_playlist_by_tag")
                    .put(
                        "param",
                        JSONObject()
                            .put("id", PLAZA_CATEGORY_ID)
                            .put("sin", 0)
                            .put("size", 30)
                            .put("order", ORDER_HOT)
                            .put("cur_page", 1)
                    )
                    .put("module", "playlist.PlayListPlazaServer")
            )
        val root = musicu(data) ?: return emptyList()
        val list = root.optJSONObject("playlist")?.optJSONObject("data")
            ?.optJSONArray("v_playlist") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val tid = item.optString("tid", "").trim()
                val title = item.optString("title", "").trim()
                if (tid.isEmpty() || title.isEmpty()) continue
                add(
                    PlatformBrowseCard(
                        id = PlatformBrowseSupport.qualifyId(provider, tid),
                        title = title,
                        subtitle = item.optString("desc", "").trim(),
                        coverUrl = item.optString("cover_url_medium", "")
                            .trim()
                            .ifBlank { item.optString("cover_url_big", "").trim() }
                            .takeIf { it.isNotBlank() },
                        trackCount = item.optJSONArray("song_ids")?.length() ?: 0,
                        playCount = PlatformBrowseSupport.parsePlayCount(
                            item.optString("access_num", "")
                        ),
                        provider = provider
                    )
                )
            }
        }
    }

    /** 分类名 -> 接口用的 titleid */
    private fun tagIdFor(category: String): String =
        TAG_CATEGORIES.firstOrNull { it.label == category }?.id.orEmpty()

    /** 分类歌单：tagId 为空时等价于歌单广场 */
    private fun categoryPlaylists(tagId: String): List<PlatformBrowseCard> {
        val titleId = tagId.toIntOrNull() ?: PLAZA_CATEGORY_ID
        val data = JSONObject()
            .put("comm", JSONObject().put("cv", 1602).put("ct", 20))
            .put(
                "playlist",
                JSONObject()
                    .put("method", "get_category_content")
                    .put(
                        "param",
                        JSONObject()
                            .put("titleid", titleId)
                            .put("caller", "0")
                            .put("category_id", titleId)
                            .put("size", 30)
                            .put("page", 0)
                            .put("use_page", 1)
                    )
                    .put("module", "playlist.PlayListCategoryServer")
            )
        val root = musicu(data) ?: return emptyList()
        val list = root.optJSONObject("playlist")?.optJSONObject("data")
            ?.optJSONObject("content")?.optJSONArray("v_item") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val basic = list.optJSONObject(index)?.optJSONObject("basic") ?: continue
                val tid = basic.optString("tid", "").trim()
                val title = basic.optString("title", "").trim()
                if (tid.isEmpty() || title.isEmpty()) continue
                add(
                    PlatformBrowseCard(
                        id = PlatformBrowseSupport.qualifyId(provider, tid),
                        title = title,
                        subtitle = basic.optString("desc", "").trim().replace("<br>", "\n"),
                        coverUrl = basic.optJSONObject("cover")?.optString("medium_url", "")?.trim()
                            ?.ifBlank { basic.optJSONObject("cover")?.optString("default_url", "")?.trim().orEmpty() }
                            ?.takeIf { it.isNotBlank() },
                        trackCount = basic.optInt("song_cnt", 0),
                        playCount = PlatformBrowseSupport.parsePlayCount(
                            basic.optString("play_cnt", "")
                        ),
                        provider = provider
                    )
                )
            }
        }
    }

    /** 歌单详情 */
    private fun playlistTracks(disstId: String): List<PlatformSearchResult> {
        val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg".toHttpUrl().newBuilder()
            .addQueryParameter("type", "1")
            .addQueryParameter("json", "1")
            .addQueryParameter("utf8", "1")
            .addQueryParameter("onlysong", "0")
            .addQueryParameter("new_format", "1")
            .addQueryParameter("disstid", disstId)
            .addQueryParameter("loginUin", "0")
            .addQueryParameter("hostUin", "0")
            .addQueryParameter("format", "json")
            .addQueryParameter("inCharset", "utf8")
            .addQueryParameter("outCharset", "utf-8")
            .addQueryParameter("notice", "0")
            .addQueryParameter("platform", "yqq.json")
            .addQueryParameter("needNewCode", "0")
            .build()
        val root = requestJson(url.toString()) ?: return emptyList()
        if (root.optInt("code", -1) != 0) return emptyList()
        val cdlist = root.optJSONArray("cdlist") ?: return emptyList()
        val songList = cdlist.optJSONObject(0)?.optJSONArray("songlist") ?: return emptyList()
        return buildList {
            for (index in 0 until songList.length()) {
                val song = songList.optJSONObject(index) ?: continue
                val mid = song.optString("songmid", "").trim()
                val name = song.optString("songname", "").trim()
                if (mid.isEmpty() || name.isEmpty()) continue
                val singers = song.optJSONArray("singer")
                val artistNames = buildList {
                    for (singerIndex in 0 until (singers?.length() ?: 0)) {
                        singers?.optJSONObject(singerIndex)?.optString("name", "")?.trim()
                            ?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
                add(
                    PlatformSearchResult(
                        type = PlatformSearchType.SONG,
                        id = mid,
                        name = name,
                        subtitle = artistNames.joinToString("、"),
                        album = song.optString("albumname", "").trim(),
                        durationMs = song.optInt("interval", 0) * 1000L,
                        coverUrl = song.optString("albummid", "").trim().takeIf { it.isNotBlank() }
                            ?.let { "https://y.qq.com/music/photo_new/T002R300x300M000$it.jpg" },
                        provider = provider,
                        extras = mapOf("strMediaMid" to mid)
                    )
                )
            }
        }
    }

    /** 排行榜 */
    private fun leaderboards(): List<PlatformBrowseCard> {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .build()
        val root = requestJson(url.toString()) ?: return emptyList()
        val list = root.optJSONObject("data")?.optJSONArray("topList") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val board = list.optJSONObject(index) ?: continue
                val id = board.optString("id", "").trim()
                val title = board.optString("topTitle", "").trim()
                if (id.isEmpty() || title.isEmpty()) continue
                add(
                    PlatformBrowseCard(
                        id = PlatformBrowseSupport.qualifyId(provider, id),
                        title = title,
                        subtitle = board.optString("updateTime", "").trim(),
                        coverUrl = board.optString("picUrl", "").trim()
                            .replaceFirst("http://", "https://")
                            .takeIf { it.isNotBlank() },
                        playCount = board.optLong("listenCount", 0L),
                        kind = PlatformBrowseKind.CHART,
                        provider = provider
                    )
                )
            }
        }
    }

    /** 榜单曲目：榜单只给歌名歌手，songmid 靠搜索补 */
    private suspend fun leaderboardTracks(boardId: String): List<PlatformSearchResult> =
        withContext(Dispatchers.IO) {
            val url = "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json")
                .build()
            val root = requestJson(url.toString()) ?: return@withContext emptyList()
            val board = root.optJSONObject("data")?.optJSONArray("topList")
                ?.let { list ->
                    (0 until list.length()).firstNotNullOfOrNull { index ->
                        list.optJSONObject(index)?.takeIf { it.optString("id", "").trim() == boardId }
                    }
                } ?: return@withContext emptyList()
            val songs = board.optJSONArray("songList") ?: return@withContext emptyList()
            val entries = buildList {
                for (index in 0 until songs.length()) {
                    val song = songs.optJSONObject(index) ?: continue
                    val name = song.optString("songname", "").trim()
                    if (name.isEmpty()) continue
                    add(name to song.optString("singername", "").trim())
                }
            }.take(MAX_TRACKS)
            if (entries.isEmpty()) return@withContext emptyList()
            coroutineScope {
                entries.map { (name, singer) ->
                    async {
                        val keyword = listOf(name, singer).filter { it.isNotBlank() }.joinToString(" ")
                        val songmid = resolveSongMid(keyword)
                        if (songmid.isNullOrBlank()) {
                            null
                        } else {
                            PlatformSearchResult(
                                type = PlatformSearchType.SONG,
                                id = songmid,
                                name = name,
                                subtitle = singer,
                                provider = provider,
                                extras = mapOf("strMediaMid" to songmid)
                            )
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

    private fun musicu(data: JSONObject): JSONObject? {
        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg".toHttpUrl().newBuilder()
            .addQueryParameter("loginUin", "0")
            .addQueryParameter("hostUin", "0")
            .addQueryParameter("format", "json")
            .addQueryParameter("inCharset", "utf-8")
            .addQueryParameter("outCharset", "utf-8")
            .addQueryParameter("notice", "0")
            .addQueryParameter("platform", "wk_v15.json")
            .addQueryParameter("needNewCode", "0")
            .addQueryParameter("data", data.toString())
            .build()
        return requestJson(url.toString())
    }

    private fun requestJson(url: String): JSONObject? {
        val text = requestText(url) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun requestText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://y.qq.com/")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string().takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private companion object {
        const val CATEGORY_HOT = "热门歌单"
        const val CATEGORY_CHARTS = "排行榜"
        const val PLAZA_CATEGORY_ID = 10000000

        /**
         * 分类标签：显示名 -> 接口用的 titleid。
         *
         * 取自 c.y.qq.com 的分类歌单页（HTML 里 data-id 属性），线上实测可用。
         */
        val TAG_CATEGORIES = listOf(
            PlatformBrowseSupport.BrowseCategory("门店音乐", "3270"),
            PlatformBrowseSupport.BrowseCategory("节奏控", "3255"),
            PlatformBrowseSupport.BrowseCategory("运动", "132"),
            PlatformBrowseSupport.BrowseCategory("官方歌单", "3317"),
            PlatformBrowseSupport.BrowseCategory("网络歌曲", "3056"),
            PlatformBrowseSupport.BrowseCategory("短视频热歌", "3384"),
            PlatformBrowseSupport.BrowseCategory("KTV金曲", "64"),
            PlatformBrowseSupport.BrowseCategory("DJ神曲", "3357"),
            PlatformBrowseSupport.BrowseCategory("经典老歌", "59"),
            PlatformBrowseSupport.BrowseCategory("睡前", "27"),
            PlatformBrowseSupport.BrowseCategory("轻音乐", "49"),
            PlatformBrowseSupport.BrowseCategory("古风", "61"),
            PlatformBrowseSupport.BrowseCategory("伤感", "74"),
            PlatformBrowseSupport.BrowseCategory("情歌", "71"),
            PlatformBrowseSupport.BrowseCategory("广场舞", "3388")
        )
        const val ORDER_HOT = 5
        const val MAX_TRACKS = 20

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
