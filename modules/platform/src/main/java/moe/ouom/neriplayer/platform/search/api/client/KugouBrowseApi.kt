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
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 酷狗浏览页：排行榜网格 + 榜单曲目。
 *
 * 榜单列表接口（api/v5/rank/list）是明文的，游客可用；
 * 榜单曲目（api/v5/rank/song）带 hash，能直接换成播放地址。
 */
class KugouBrowseApi(
    private val client: OkHttpClient
) : PlatformBrowseApi {

    override val provider: PlatformSearchProvider = PlatformSearchProvider.KUGOU

    /** 酷狗的榜单接口没有分类维度，这里不显示分类标签栏 */
    override val categories: List<String> = emptyList()

    override suspend fun browse(category: String): List<PlatformBrowseCard> =
        withContext(Dispatchers.IO) {
            val url = "https://mobileservice.kugou.com/api/v5/rank/list".toHttpUrl().newBuilder()
                .addQueryParameter("page", "1")
                .addQueryParameter("pagesize", "30")
                .build()
            val root = requestJson(url.toString(), referer = "https://www.kugou.com/") ?: return@withContext emptyList()
            val info = root.optJSONObject("data")?.optJSONArray("info") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until info.length()) {
                    val rank = info.optJSONObject(index) ?: continue
                    val id = rank.optString("id", "").trim()
                    val title = rank.optString("rankname", "").trim()
                    if (id.isEmpty() || title.isEmpty()) continue
                    add(
                        PlatformBrowseCard(
                            id = id,
                            title = title,
                            subtitle = rank.optString("intro", "").trim(),
                            coverUrl = rank.optString("img_9", "")
                                .replace("{size}", "300x300")
                                .takeIf { it.isNotBlank() },
                            playCount = rank.optLong("play_times", 0L),
                            provider = provider
                        )
                    )
                }
            }
        }

    override suspend fun tracks(card: PlatformBrowseCard): List<PlatformSearchResult> =
        withContext(Dispatchers.IO) {
            val url = "https://mobileservice.kugou.com/api/v5/rank/song".toHttpUrl().newBuilder()
                .addQueryParameter("rankid", card.id)
                .addQueryParameter("page", "1")
                .addQueryParameter("pagesize", "50")
                .build()
            val root = requestJson(url.toString(), referer = "https://www.kugou.com/") ?: return@withContext emptyList()
            val info = root.optJSONObject("data")?.optJSONArray("info") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until info.length()) {
                    val song = info.optJSONObject(index) ?: continue
                    val name = song.optString("songname", "").trim()
                    val hash = song.optString("hash", "").trim()
                    if (name.isEmpty() || hash.isEmpty()) continue
                    val authors = song.optJSONArray("authors")
                    val artistNames = buildList {
                        for (authorIndex in 0 until (authors?.length() ?: 0)) {
                            authors?.optJSONObject(authorIndex)?.optString("name")?.trim()
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
                            coverUrl = song.optString("album_sizable_cover", "")
                                .takeIf { it.isNotBlank() },
                            provider = provider
                        )
                    )
                }
            }
        }

    private fun requestJson(url: String, referer: String? = null): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .apply { referer?.let { header("Referer", it) } }
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string().takeIf { it.isNotBlank() }
            }
        }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
