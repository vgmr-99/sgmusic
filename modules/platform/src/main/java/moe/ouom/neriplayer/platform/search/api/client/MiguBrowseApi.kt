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
 * File: moe.ouom.neriplayer.platform.search.api.client/MiguBrowseApi
 * Created: 2026/11/13
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseApi
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseKind
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseSupport
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 咪咕音乐浏览页：歌单广场 + 排行榜。
 *
 * 咪咕没有公开的标签接口（分类等同于热门），榜单是固定的一组栏目 id。
 * - 歌单广场 app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0
 * - 榜单详情 app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do
 */
class MiguBrowseApi(
    private val client: OkHttpClient
) : PlatformBrowseApi {

    override val provider: PlatformSearchProvider = PlatformSearchProvider.MIGU

    override val categories: List<String> = listOf(CATEGORY_HOT, CATEGORY_CHARTS)

    override suspend fun browse(category: String): List<PlatformBrowseCard> =
        withContext(Dispatchers.IO) {
            when (category) {
                CATEGORY_CHARTS -> CHARTS.map { it.toCard() }
                else -> hotPlaylists()
            }
        }

    override suspend fun tracks(card: PlatformBrowseCard): List<PlatformSearchResult> =
        withContext(Dispatchers.IO) {
            val rawId = PlatformBrowseSupport.stripProviderPrefix(card.id)
            val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do".toQueryUrl(
                "columnId" to rawId,
                "needAll" to "0"
            )
            val root = requestJson(url) ?: return@withContext emptyList()
            if (root.optString("code") != "000000") return@withContext emptyList()
            val columnInfo = root.optJSONObject("columnInfo") ?: return@withContext emptyList()
            val contents = columnInfo.optJSONArray("contents") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until contents.length()) {
                    val info = contents.optJSONObject(index)?.optJSONObject("objectInfo") ?: continue
                    val song = info.toPlatformSearchResult() ?: continue
                    add(song)
                }
            }
        }

    /** 歌单广场 */
    private fun hotPlaylists(): List<PlatformBrowseCard> {
        val url = "https://app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0".toQueryUrl(
            "templateVersion" to "2",
            "pageNo" to "1"
        )
        val root = requestJson(url) ?: return emptyList()
        if (root.optString("code") != "000000") return emptyList()
        val result = mutableListOf<PlatformBrowseCard>()
        val seen = mutableSetOf<String>()
        walkPlaylists(root.optJSONObject("data")?.optJSONArray("contents"), result, seen)
        return result
    }

    /**
     * 歌单广场返回的是一棵嵌套树，真正的歌单节点用 resType=2021 标记，
     * 这里递归找出来并按出现顺序去重。
     */
    private fun walkPlaylists(
        node: JSONArray?,
        out: MutableList<PlatformBrowseCard>,
        seen: MutableSet<String>
    ) {
        if (node == null) return
        for (index in 0 until node.length()) {
            val item = node.optJSONObject(index) ?: continue
            val logEvent = item.optJSONObject("logEvent")
            val contentType = logEvent?.optString("contentType", "")
            val resType = item.optString("resType", "")
            val rawId = item.optString("resId", "")
                .ifBlank { logEvent?.optString("contentId", "").orEmpty() }
                .ifBlank { item.optString("contentId", "") }
            if ((resType == "2021" || contentType == "2021") && rawId.isNotBlank() && seen.add(rawId)) {
                val title = item.optString("txt", "")
                    .ifBlank { logEvent?.optString("contentName", "").orEmpty() }
                if (title.isNotBlank()) {
                    out += PlatformBrowseCard(
                        id = PlatformBrowseSupport.qualifyId(provider, rawId),
                        title = title,
                        subtitle = item.optString("txt2", "").trim(),
                        coverUrl = item.optString("img", "").trim().takeIf { it.isNotBlank() },
                        provider = provider
                    )
                }
            }
            walkPlaylists(item.optJSONArray("contents"), out, seen)
            walkPlaylists(item.optJSONArray("content"), out, seen)
            walkPlaylists(item.optJSONArray("itemList"), out, seen)
        }
    }

    /** 咪咕榜单曲目：objectInfo 结构 */
    private fun JSONObject.toPlatformSearchResult(): PlatformSearchResult? {
        val id = optString("copyrightId", "")
            .ifBlank { optString("songId", "") }
            .ifBlank { optString("id", "") }
            .ifBlank { optString("resourceId", "") }
            .trim()
        val title = optString("songName", "")
            .ifBlank { optString("name", "") }
            .ifBlank { optString("title", "") }
            .trim()
        if (id.isEmpty() || title.isEmpty()) return null
        val singerList = optJSONArray("singerList")
        val artist = optString("singerName", "")
            .ifBlank { optString("singer", "") }
            .ifBlank {
                buildList {
                    for (index in 0 until (singerList?.length() ?: 0)) {
                        singerList?.optJSONObject(index)?.optString("name", "")?.trim()
                            ?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }.joinToString("、")
            }
        var cover = optString("img3", "")
            .ifBlank { optString("img2", "") }
            .ifBlank { optString("img1", "") }
            .ifBlank { optString("img", "") }
            .ifBlank { optString("albumImg", "") }
            .trim()
        if (cover.isNotEmpty() && !cover.startsWith("http")) {
            cover = "http://d.musicapp.migu.cn$cover"
        }
        return PlatformSearchResult(
            type = PlatformSearchType.SONG,
            id = id,
            name = title,
            subtitle = artist.trim(),
            album = optString("albumName", "").ifBlank { optString("album", "") }.trim(),
            durationMs = parseDuration(optString("duration", "").ifBlank { optString("length", "") }),
            coverUrl = cover.takeIf { it.isNotBlank() },
            provider = provider,
            extras = mapOf("copyrightId" to id)
        )
    }

    private fun parseDuration(raw: String): Long {
        if (raw.isBlank()) return 0L
        if (raw.contains(':')) {
            return raw.split(':').fold(0L) { acc, part ->
                acc * 60 + (part.trim().toLongOrNull() ?: 0L)
            } * 1000L
        }
        return (raw.toLongOrNull() ?: 0L) * 1000L
    }

    private fun requestJson(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://m.music.migu.cn/")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body.bytes()
                if (bytes.isEmpty()) return@use null
                JSONObject(String(bytes, Charsets.UTF_8))
            }
        }.getOrNull()
    }

    private data class MiguChart(val id: String, val name: String)

    private fun MiguChart.toCard() = PlatformBrowseCard(
        id = PlatformBrowseSupport.qualifyId(provider, id),
        title = name,
        kind = PlatformBrowseKind.CHART,
        provider = provider
    )

    private companion object {
        const val CATEGORY_HOT = "热门歌单"
        const val CATEGORY_CHARTS = "排行榜"

        val CHARTS = listOf(
            MiguChart("27553319", "新歌榜"),
            MiguChart("27186466", "热歌榜"),
            MiguChart("27553408", "原创榜"),
            MiguChart("75959118", "音乐风向榜"),
            MiguChart("76557036", "彩铃分贝榜"),
            MiguChart("76557745", "会员挚爱榜"),
            MiguChart("23189800", "港台榜"),
            MiguChart("23189399", "内地榜"),
            MiguChart("19190036", "欧美榜"),
            MiguChart("83176390", "国风金曲榜")
        )

        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1"
    }
}

/** 给 URL 追加查询参数（值做 URL 编码） */
internal fun String.toQueryUrl(vararg pairs: Pair<String, String>): String {
    val builder = StringBuilder(this)
    pairs.forEach { (key, value) ->
        builder.append(if (builder.contains("?")) "&" else "?")
        builder.append(key).append("=").append(encodeQueryValue(value))
    }
    return builder.toString()
}

private fun encodeQueryValue(value: String): String {
    val encoded = java.net.URLEncoder.encode(value, "UTF-8")
    return encoded.replace("+", "%20")
}
