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
 * File: moe.ouom.neriplayer.platform.search.api.client/CloudMusicChartApi
 * Created: 2026/11/12
 */

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.network.http.awaitResponse
import moe.ouom.neriplayer.platform.search.api.ChartApi
import moe.ouom.neriplayer.platform.search.api.ChartProvider
import moe.ouom.neriplayer.platform.search.api.HotSearchEntry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 网易云热搜榜。
 *
 * 走明文接口，只要匿名上下文 Cookie，不需要登录账号。
 */
class CloudMusicChartApi(
    private val client: OkHttpClient
) : ChartApi {

    companion object {
        private const val HOT_SEARCH_URL = "https://music.163.com/api/hotsearchlist/get"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override suspend fun hotSearch(limit: Int): List<HotSearchEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(HOT_SEARCH_URL)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", "os=pc; appver=8.10.35")
            .build()

        val payload = client.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }

        val entries = JSONObject(payload).optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val title = item.optString("searchWord").trim()
                if (title.isEmpty()) continue
                add(
                    HotSearchEntry(
                        title = title,
                        score = item.optLong("score"),
                        coverUrl = item.optString("iconUrl").takeIf { it.isNotBlank() },
                        source = ChartProvider.NETEASE
                    )
                )
                if (size >= limit) break
            }
        }
    }
}
