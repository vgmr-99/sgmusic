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
 * File: moe.ouom.neriplayer.platform.search.api.client/QishuiBrowseApi
 * Created: 2026/11/12
 */

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseApi
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseSupport
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.client.mint.decodeHtmlEntities
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 汽水音乐浏览页：场景歌单。
 *
 * 汽水没有公开的榜单接口，也没有歌单广场，
 * 但按关键词搜歌单是通的（游客态即可）。
 * 这里用一组热门场景词当作分类，搜出来的就是汽水真实存在的歌单；
 * 汽水歌单没有公开的曲目接口，点击后跳官方歌单页。
 */
class QishuiBrowseApi(
    private val client: OkHttpClient
) : PlatformBrowseApi {

    override val provider: PlatformSearchProvider = PlatformSearchProvider.QISHUI

    override val categories: List<String> = SCENES.keys.toList()

    override suspend fun browse(category: String): List<PlatformBrowseCard> =
        withContext(Dispatchers.IO) {
            val keyword = SCENES[category] ?: SCENES.values.first()
            val root = searchPlaylists(keyword) ?: return@withContext emptyList()
            val group = root.optJSONArray("result_groups")?.optJSONObject(0) ?: return@withContext emptyList()
            val data = group.optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until data.length()) {
                    val playlist = data.optJSONObject(index)
                        ?.optJSONObject("entity")
                        ?.optJSONObject("playlist") ?: continue
                    val id = playlist.optString("id", "").trim()
                    val title = playlist.optString("title", "").trim()
                    if (id.isEmpty() || title.isEmpty()) continue
                    add(
                        PlatformBrowseCard(
                            id = PlatformBrowseSupport.qualifyId(provider, id),
                            title = decodeHtmlEntities(title),
                            subtitle = decodeHtmlEntities(
                                playlist.optJSONObject("owner")?.optString("nickname", "").orEmpty()
                            ),
                            coverUrl = playlistCoverUrl(playlist),
                            trackCount = playlist.optLong("count_tracks", 0L).toInt(),
                            webUrl = "https://www.qishui.com/playlist/$id",
                            provider = provider
                        )
                    )
                }
            }
        }

    /** 汽水歌单没有公开的曲目接口，这里只回卡片本身，不假装能列曲目 */
    override suspend fun tracks(card: PlatformBrowseCard): List<PlatformSearchResult> = emptyList()

    private fun searchPlaylists(keyword: String): JSONObject? {
        val url = "https://api.qishui.com/luna/search/playlist".toHttpUrl().newBuilder()
            .apply { commonParams().forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("q", keyword)
            .addQueryParameter("cursor", "0")
            .addQueryParameter("count", "20")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", LUNA_USER_AGENT)
            .header("Accept", "*/*")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string().takeIf { it.isNotBlank() }
            }
        }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    /** 抖音系图片模板：{urls[0]}{uri}~{template_prefix}-image.image */
    private fun playlistCoverUrl(playlist: JSONObject): String? {
        val cover = playlist.optJSONObject("url_cover") ?: return null
        val uri = cover.optString("uri", "").trim()
        val prefix = cover.optString("template_prefix", "").trim()
        val host = cover.optJSONArray("urls")?.optString(0)?.trim().orEmpty()
        if (uri.isEmpty() || host.isEmpty()) return null
        val template = if (prefix.isEmpty()) "" else "~$prefix"
        return "$host$uri$template-image.image"
    }

    private fun commonParams(): Map<String, String> {
        val deviceId = UUID.randomUUID().toString().replace("-", "").take(16)
        return mapOf(
            "device_platform" to "android",
            "os" to "android",
            "aid" to "386088",
            "app_name" to "luna",
            "version_code" to "100198030",
            "version_name" to "19.8.0",
            "package" to "com.luna.music",
            "language" to "zh",
            "os_api" to "35",
            "os_version" to "15",
            "channel" to "xiaomi_8478_64",
            "ssmix" to "a",
            "cdid" to "46556f98-1720-4248-83da-62b74b60b46a",
            "iid" to deviceId,
            "device_id" to deviceId,
            "_rticket" to System.currentTimeMillis().toString()
        )
    }

    private companion object {
        /** 场景分类 -> 搜索关键词 */
        val SCENES = linkedMapOf(
            "热歌" to "热歌",
            "伤感" to "伤感",
            "轻音乐" to "轻音乐",
            "古风" to "古风",
            "DJ" to "DJ",
            "粤语" to "粤语"
        )

        const val LUNA_USER_AGENT =
            "com.luna.music/100198030 (Linux; U; Android 15; zh_CN_#Hans; ABR-AL80; " +
                "Build/V417IR;tt-ok/3.12.13.19)"
    }
}
