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
 * File: moe.ouom.neriplayer.platform.search.api.client/QQPlatformSearchApi
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
 * QQ 音乐搜索（歌曲）。
 *
 * QQ 的歌单/歌手搜索端点已下线或要求登录态，匿名不可用，
 * 所以这里只开放歌曲分类，界面上也就不会出现其它分类。
 */
class QQPlatformSearchApi(
    private val client: OkHttpClient
) : PlatformSearchApi {

    override val provider: PlatformSearchProvider = PlatformSearchProvider.QQ

    override val supportedTypes: Set<PlatformSearchType> = setOf(PlatformSearchType.SONG)

    override suspend fun search(
        keyword: String,
        type: PlatformSearchType,
        page: Int
    ): List<PlatformSearchResult> = withContext(Dispatchers.IO) {
        if (keyword.isBlank() || type != PlatformSearchType.SONG) return@withContext emptyList()
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("n", PAGE_SIZE.toString())
            .addQueryParameter("p", page.coerceAtLeast(1).toString())
            .addQueryParameter("w", keyword)
            .addQueryParameter("cr", "1")
            .addQueryParameter("g_tk", "5381")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .build()
        val root = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body.string().takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        } ?: return@withContext emptyList()
        val list = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            ?: return@withContext emptyList()
        buildList {
            for (index in 0 until list.length()) {
                val song = list.optJSONObject(index) ?: continue
                val name = song.optString("songname").trim()
                val mid = song.optString("songmid").trim()
                if (name.isEmpty() || mid.isEmpty()) continue
                val singers = song.optJSONArray("singer")
                val artistNames = buildList {
                    for (index in 0 until (singers?.length() ?: 0)) {
                        singers?.optJSONObject(index)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
                            ?.let { add(it) }
                    }
                }
                val albumMid = song.optString("albummid").trim()
                add(
                    PlatformSearchResult(
                        type = PlatformSearchType.SONG,
                        id = mid,
                        name = name,
                        subtitle = artistNames.joinToString("/"),
                        album = song.optString("albumname").trim(),
                        durationMs = song.optInt("interval") * 1000L,
                        coverUrl = albumMid.takeIf { it.isNotEmpty() }
                            ?.let { "https://y.qq.com/music/photo_new/T002R800x800M000$it.jpg" },
                        webUrl = "https://y.qq.com/n/ryqq/songDetail/$mid",
                        provider = provider
                    )
                )
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
