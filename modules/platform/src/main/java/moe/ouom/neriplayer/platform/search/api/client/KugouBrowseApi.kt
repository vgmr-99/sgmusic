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
 * File: moe.ouom.neriplayer.platform.search.api.client/KugouBrowseApi
 * Created: 2026/11/12
 */

import kotlinx.coroutines.Dispatchers
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
 * 酷狗浏览页：歌单广场 + 排行榜。
 *
 * - 歌单广场 www2.kugou.kugou.com/yueku/v9/special/getSpecial（该域名只有 http 可用，https 证书链不完整）
 * - 排行榜     mobileservice.kugou.com/api/v5/rank/list
 */
class KugouBrowseApi(
    private val client: OkHttpClient
) : PlatformBrowseApi {

    override val provider: PlatformSearchProvider = PlatformSearchProvider.KUGOU

    override val categories: List<String> =
        listOf(CATEGORY_HOT, CATEGORY_CHARTS) + STYLE_TAGS.map { it.label }

    override suspend fun browse(category: String): List<PlatformBrowseCard> =
        withContext(Dispatchers.IO) {
            when (category) {
                CATEGORY_CHARTS -> leaderboards()
                CATEGORY_HOT -> categoryPlaylists("")
                else -> PlatformBrowseSupport.browseWithFallback(
                    category = category,
                    categories = STYLE_TAGS,
                    primary = { label -> categoryPlaylists(tagIdFor(label)) },
                    fallback = { categoryPlaylists("") },
                    hotTags = { STYLE_TAGS }
                )
            }
        }

    override suspend fun tracks(card: PlatformBrowseCard): List<PlatformSearchResult> =
        withContext(Dispatchers.IO) {
            val rawId = PlatformBrowseSupport.stripProviderPrefix(card.id)
            when (card.kind) {
                PlatformBrowseKind.CHART -> chartTracks(rawId)
                PlatformBrowseKind.PLAYLIST -> specialPlaylistTracks(rawId)
            }
        }

    /** 分类名 -> 接口用的风格分类 id */
    private fun tagIdFor(category: String): String =
        STYLE_TAGS.firstOrNull { it.label == category }?.id.orEmpty()

    /** 歌单广场 / 分类歌单 */
    private fun categoryPlaylists(tagId: String): List<PlatformBrowseCard> {
        val url = "http://www2.kugou.kugou.com/yueku/v9/special/getSpecial".toHttpUrl().newBuilder()
            .addQueryParameter("is_ajax", "1")
            .addQueryParameter("cdn", "cdn")
            .addQueryParameter("t", SORT_HOT)
            .addQueryParameter("c", tagId)
            .addQueryParameter("p", "1")
            .build()
        val root = requestJson(url.toString()) ?: return emptyList()
        if (root.optInt("status", 0) != 1) return emptyList()
        val list = root.optJSONArray("special_db") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val id = item.optString("specialid", "").trim()
                val title = item.optString("specialname", "").trim()
                if (id.isEmpty() || title.isEmpty()) continue
                add(
                    PlatformBrowseCard(
                        id = PlatformBrowseSupport.qualifyId(provider, id),
                        title = title,
                        subtitle = item.optString("intro", "").trim(),
                        coverUrl = item.optString("img", "").trim().takeIf { it.isNotBlank() },
                        trackCount = item.optInt("song_count", 0),
                        playCount = PlatformBrowseSupport.parsePlayCount(
                            item.optString("total_play_count", "")
                        ),
                        provider = provider
                    )
                )
            }
        }
    }

    /** 歌单曲目 */
    private fun specialPlaylistTracks(specialId: String): List<PlatformSearchResult> {
        val url = "https://www.kugou.com/yy/playlist/".toHttpUrl().newBuilder()
            .addPathSegment(specialId)
            .addQueryParameter("page", "1")
            .addQueryParameter("pagesize", "50")
            .build()
        val html = requestText(url.toString()) ?: return emptyList()
        // 页面内嵌 global.data，解析里面的 songlist
        val marker = "global.data = "
        val start = html.indexOf(marker)
        if (start < 0) return emptyList()
        val from = start + marker.length
        val jsonStart = html.indexOf('{', from)
        if (jsonStart < 0) return emptyList()
        var depth = 0
        var end = jsonStart
        for (index in jsonStart until html.length) {
            when (html[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { end = index; break }
                }
            }
        }
        if (end <= jsonStart) return emptyList()
        val root = runCatching { JSONObject(html.substring(jsonStart, end + 1)) }.getOrNull() ?: return emptyList()
        val list = root.optJSONArray("songlist") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val song = list.optJSONObject(index) ?: continue
                val hash = song.optString("hash", "").trim()
                val name = song.optString("songname", "").trim()
                if (hash.isEmpty() || name.isEmpty()) continue
                add(
                    PlatformSearchResult(
                        type = PlatformSearchType.SONG,
                        id = hash,
                        name = name,
                        subtitle = song.optString("singername", "").trim(),
                        album = song.optString("album_name", "").trim(),
                        durationMs = song.optInt("duration", 0) * 1000L,
                        coverUrl = song.optString("img", "").trim().takeIf { it.isNotBlank() },
                        provider = provider,
                        extras = buildMap {
                            put("hash", hash)
                            song.optString("album_id", "").trim().takeIf { it.isNotBlank() }
                                ?.let { put("albumId", it) }
                        }
                    )
                )
            }
        }
    }

    /** 排行榜 */
    private fun leaderboards(): List<PlatformBrowseCard> {
        val url = "https://mobileservice.kugou.com/api/v5/rank/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("pagesize", "30")
            .build()
        val root = requestJson(url.toString()) ?: return emptyList()
        val info = root.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return buildList {
            for (index in 0 until info.length()) {
                val rank = info.optJSONObject(index) ?: continue
                val id = rank.optString("id", "").trim()
                val title = rank.optString("rankname", "").trim()
                if (id.isEmpty() || title.isEmpty()) continue
                add(
                    PlatformBrowseCard(
                        id = PlatformBrowseSupport.qualifyId(provider, id),
                        title = title,
                        subtitle = rank.optString("intro", "").trim(),
                        coverUrl = rank.optString("img_9", "")
                            .replace("{size}", "300x300")
                            .trim()
                            .takeIf { it.isNotBlank() },
                        playCount = PlatformBrowseSupport.parsePlayCount(
                            rank.optString("play_times", "")
                        ),
                        kind = PlatformBrowseKind.CHART,
                        provider = provider
                    )
                )
            }
        }
    }

    /** 榜单曲目 */
    private fun chartTracks(rankId: String): List<PlatformSearchResult> {
        val url = "https://mobileservice.kugou.com/api/v5/rank/song".toHttpUrl().newBuilder()
            .addQueryParameter("rankid", rankId)
            .addQueryParameter("page", "1")
            .addQueryParameter("pagesize", "50")
            .build()
        val root = requestJson(url.toString()) ?: return emptyList()
        val info = root.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return buildList {
            for (index in 0 until info.length()) {
                val song = info.optJSONObject(index) ?: continue
                val hash = song.optString("hash", "").trim()
                val name = song.optString("songname", "").trim()
                if (hash.isEmpty() || name.isEmpty()) continue
                val authors = song.optJSONArray("authors")
                val artistNames = buildList {
                    for (authorIndex in 0 until (authors?.length() ?: 0)) {
                        authors?.optJSONObject(authorIndex)?.optString("name", "")?.trim()
                            ?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
                add(
                    PlatformSearchResult(
                        type = PlatformSearchType.SONG,
                        id = hash,
                        name = name,
                        subtitle = artistNames.joinToString("/"),
                        album = song.optString("album_name", "").trim(),
                        durationMs = song.optLong("duration", 0L) * 1000L,
                        coverUrl = song.optString("album_sizable_cover", "").trim()
                            .takeIf { it.isNotBlank() },
                        provider = provider,
                        extras = buildMap {
                            put("hash", hash)
                            song.optString("album_id", "").trim().takeIf { it.isNotBlank() }
                                ?.let { put("albumId", it) }
                        }
                    )
                )
            }
        }
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
            .header("Referer", "https://www.kugou.com/")
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

        /** getSpecial 的排序码：5=推荐 6=热门 7=最新 */
        const val SORT_HOT = "6"

        /**
         * 酷狗的风格分类 id（数字，接口不认中文）。
         *
         * 线上实测：11=中国风、15=欧美、30=流行、850=电音、10=DJ。
         */
        val STYLE_TAGS = listOf(
            PlatformBrowseSupport.BrowseCategory("华语", "11"),
            PlatformBrowseSupport.BrowseCategory("欧美", "15"),
            PlatformBrowseSupport.BrowseCategory("流行", "30"),
            PlatformBrowseSupport.BrowseCategory("电子", "850"),
            PlatformBrowseSupport.BrowseCategory("DJ", "10")
        )

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
