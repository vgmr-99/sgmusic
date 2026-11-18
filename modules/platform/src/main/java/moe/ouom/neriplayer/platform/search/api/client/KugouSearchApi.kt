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
 * File: moe.ouom.neriplayer.platform.search.api.client/KugouSearchApi
 * Created: 2026/11/12
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.search.api.PlatformSearchApi
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 酷狗音乐搜索。
 *
 * 三个端点都是明文的，不需要登录也不需要签名：
 * - 歌曲：songsearch.kugou.com/song_search_v2
 * - 专辑（作为歌单分类使用）：albumsearch.kugou.com/album_search
 * - 歌手：mobiles.kugou.com/api/v3/search/singer
 */
class KugouSearchApi(
    private val client: OkHttpClient
) : PlatformSearchApi {

    override val provider: PlatformSearchProvider = PlatformSearchProvider.KUGOU

    override val supportedTypes: Set<PlatformSearchType> = setOf(
        PlatformSearchType.SONG,
        PlatformSearchType.PLAYLIST,
        PlatformSearchType.ARTIST
    )

    override suspend fun search(
        keyword: String,
        type: PlatformSearchType,
        page: Int
    ): List<PlatformSearchResult> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val safePage = page.coerceAtLeast(1)
        when (type) {
            PlatformSearchType.SONG -> searchSongs(keyword, safePage)
            PlatformSearchType.PLAYLIST -> searchAlbums(keyword, safePage)
            PlatformSearchType.ARTIST -> searchArtists(keyword, safePage)
        }
    }

    private fun searchSongs(keyword: String, page: Int): List<PlatformSearchResult> {
        val url = "https://songsearch.kugou.com/song_search_v2".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", keyword)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("pagesize", PAGE_SIZE.toString())
            .addQueryParameter("platform", "WebFilter")
            .build()
        val root = requestJson(url.toString()) ?: return emptyList()
        val lists = root.optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()
        return buildList {
            for (index in 0 until lists.length()) {
                val song = lists.optJSONObject(index) ?: continue
                val name = song.optString("SongName").trim()
                if (name.isEmpty()) continue
                add(
                    PlatformSearchResult(
                        type = PlatformSearchType.SONG,
                        id = song.optString("Audioid").ifBlank { song.optString("Hash") }.ifBlank { name },
                        name = name,
                        subtitle = song.optString("SingerName").trim(),
                        album = song.optString("AlbumName").trim(),
                        durationMs = song.optInt("Duration") * 1000L,
                        coverUrl = song.optString("Image").takeIf { it.isNotBlank() },
                        provider = provider
                    )
                )
            }
        }
    }

    private fun searchAlbums(keyword: String, page: Int): List<PlatformSearchResult> {
        val url = "https://albumsearch.kugou.com/album_search".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", keyword)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("pagesize", PAGE_SIZE.toString())
            .addQueryParameter("platform", "WebFilter")
            .build()
        val root = requestJson(url.toString()) ?: return emptyList()
        val lists = root.optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()
        return buildList {
            for (index in 0 until lists.length()) {
                val album = lists.optJSONObject(index) ?: continue
                val name = album.optString("albumname").trim()
                if (name.isEmpty()) continue
                add(
                    PlatformSearchResult(
                        type = PlatformSearchType.PLAYLIST,
                        id = album.optString("albumid").ifBlank { name },
                        name = name,
                        subtitle = album.optString("singer").trim(),
                        durationMs = 0L,
                        coverUrl = album.optString("img").takeIf { it.isNotBlank() },
                        extraCount = album.optInt("songcount").toLong(),
                        webUrl = "https://www.kugou.com/yy/album/${album.optString("albumid")}/",
                        provider = provider
                    )
                )
            }
        }
    }

    private fun searchArtists(keyword: String, page: Int): List<PlatformSearchResult> {
        val url = "https://mobiles.kugou.com/api/v3/search/singer".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", keyword)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("pagesize", PAGE_SIZE.toString())
            .addQueryParameter("format", "json")
            .build()
        val root = requestJson(url.toString()) ?: return emptyList()
        val list = root.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val artist = list.optJSONObject(index) ?: continue
                val name = artist.optString("singername").trim()
                if (name.isEmpty()) continue
                add(
                    PlatformSearchResult(
                        type = PlatformSearchType.ARTIST,
                        id = artist.optString("singerid").ifBlank { name },
                        name = name,
                        webUrl = "https://www.kugou.com/yy/singer/${artist.optString("singerid")}/",
                        provider = provider
                    )
                )
            }
        }
    }

    private fun requestJson(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.kugou.com/")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string().takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
