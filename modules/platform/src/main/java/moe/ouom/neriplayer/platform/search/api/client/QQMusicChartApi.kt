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
 * File: moe.ouom.neriplayer.platform.search.api.client/QQMusicChartApi
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
 * QQ 音乐巅峰榜（取「热歌」分组）。
 *
 * 榜单接口是明文的，不需要登录，也不带 g_tk 签名。
 */
class QQMusicChartApi(
    private val client: OkHttpClient
) : ChartApi {

    companion object {
        private const val TOP_LIST_URL =
            "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg?format=json"
        private const val HOT_SONG_BOARD = "热歌"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override suspend fun hotSearch(limit: Int): List<HotSearchEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(TOP_LIST_URL)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .build()

        val payload = client.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }

        val boards = JSONObject(payload).optJSONObject("data")?.optJSONArray("topList")
            ?: return@withContext emptyList()

        // 接口会一次返回多个榜单，优先取「热歌」分组，没有再退回第一个
        var board: JSONObject? = null
        for (index in 0 until boards.length()) {
            val candidate = boards.optJSONObject(index) ?: continue
            val title = candidate.optString("topTitle")
            if (title.contains(HOT_SONG_BOARD)) {
                board = candidate
                break
            }
            if (board == null) board = candidate
        }
        val songs = board?.optJSONArray("songList") ?: return@withContext emptyList()
        val score = board.optLong("listenCount")
        val coverUrl = board.optString("picUrl").takeIf { it.isNotBlank() }

        buildList {
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val title = song.optString("songname").trim()
                if (title.isEmpty()) continue
                add(
                    HotSearchEntry(
                        title = title,
                        subtitle = song.optString("singername").trim(),
                        score = score,
                        coverUrl = coverUrl,
                        source = ChartProvider.QQ
                    )
                )
                if (size >= limit) break
            }
        }
    }
}
