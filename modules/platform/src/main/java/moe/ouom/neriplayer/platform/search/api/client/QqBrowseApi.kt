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
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * QQ音乐浏览页：巅峰榜等榜单网格。
 *
 * 榜单接口（fcg_myqq_toplist.fcg）是明文的，游客可用。
 * 榜单里的歌曲只给了歌名和歌手，没有 songmid，
 * 所以展示曲目时再用公开搜索接口按"歌名 歌手"补一次 songmid。
 */
class QqBrowseApi(
    private val client: OkHttpClient,
    /** 按"歌名 歌手"补 songmid：榜单接口只给了名字 */
    private val resolveSongMid: suspend (keyword: String) -> String? = { null }
) : PlatformBrowseApi {

    override val provider: PlatformSearchProvider = PlatformSearchProvider.QQ

    override val categories: List<String> = emptyList()

    override suspend fun browse(category: String): List<PlatformBrowseCard> =
        withContext(Dispatchers.IO) {
            fetchBoards().map { board ->
                PlatformBrowseCard(
                    id = board.optString("id", "").trim(),
                    title = board.optString("topTitle", "").trim(),
                    subtitle = board.optString("updateTime", "").trim(),
                    coverUrl = board.optString("picUrl", "").trim()
                        .replaceFirst("http://", "https://")
                        .takeIf { it.isNotBlank() },
                    playCount = board.optLong("listenCount", 0L),
                    provider = provider
                )
            }.filter { it.id.isNotEmpty() && it.title.isNotEmpty() }
        }

    override suspend fun tracks(card: PlatformBrowseCard): List<PlatformSearchResult> =
        withContext(Dispatchers.IO) {
            val board = fetchBoards().firstOrNull { it.optString("id", "").trim() == card.id }
                ?: return@withContext emptyList()
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

            // 榜单只给歌名歌手，用公开搜索补 songmid；各条并发查，避免逐条等待
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
                                provider = provider
                            )
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

    /** 榜单原始数据 */
    private fun fetchBoards(): List<JSONObject> {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .build()
        val body = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string().takeIf { it.isNotBlank() }
            }
        }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val list = root.optJSONObject("data")?.optJSONArray("topList") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                list.optJSONObject(index)?.let { add(it) }
            }
        }
    }

    private companion object {
        const val MAX_TRACKS = 20
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
