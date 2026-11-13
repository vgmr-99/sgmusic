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
 * File: moe.ouom.neriplayer.platform.search.api.client/KugouChartApi
 * Created: 2026/11/12
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.search.api.ChartApi
import moe.ouom.neriplayer.platform.search.api.ChartProvider
import moe.ouom.neriplayer.platform.search.api.HotSearchEntry
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 酷狗音乐榜单（TOP500）。
 *
 * 明文接口，不需要登录也不需要签名。
 */
class KugouChartApi(
    private val client: OkHttpClient
) : ChartApi {

    override suspend fun hotSearch(limit: Int): List<HotSearchEntry> = withContext(Dispatchers.IO) {
        val url = "https://mobileservice.kugou.com/api/v5/rank/song".toHttpUrl().newBuilder()
            .addQueryParameter("rankid", RANK_ID)
            .addQueryParameter("page", "1")
            .addQueryParameter("pagesize", limit.coerceIn(1, 50).toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.kugou.com/")
            .build()
        val root = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body.string().takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        } ?: return@withContext emptyList()
        val info = root.optJSONObject("data")?.optJSONArray("info") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until info.length()) {
                val song = info.optJSONObject(index) ?: continue
                val name = song.optString("songname").trim()
                if (name.isEmpty()) continue
                val authors = song.optJSONArray("authors")
                val artistNames = buildList {
                    for (authorIndex in 0 until (authors?.length() ?: 0)) {
                        authors?.optJSONObject(authorIndex)?.optString("name")?.trim()
                            ?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
                add(
                    HotSearchEntry(
                        title = name,
                        subtitle = artistNames.joinToString("/"),
                        score = song.optLong("rank_count"),
                        coverUrl = song.optString("image").takeIf { it.isNotBlank() },
                        source = ChartProvider.KUGOU
                    )
                )
                if (size >= limit) break
            }
        }
    }

    private companion object {
        /** 酷狗 TOP500 榜单 id */
        const val RANK_ID = "8888"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
