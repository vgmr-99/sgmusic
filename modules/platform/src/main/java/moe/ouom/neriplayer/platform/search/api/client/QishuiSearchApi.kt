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
 * File: moe.ouom.neriplayer.platform.search.api.client/QishuiSearchApi
 * Created: 2026/11/12
 */

import java.util.UUID
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
 * 汽水音乐搜索。
 *
 * 走公开的 luna 搜索接口（`/luna/search/track` 等）：请求头模拟官方 App 的公共参数，
 * 游客态即可拿到结果，不需要登录也不需要签名（签名头只影响播放与部分 PC 接口）。
 * 曲目详情可用 `beta-luna.douyin.com/luna/h5/seo_track` 兜底，但搜索本身够用了。
 */
class QishuiSearchApi(
    private val client: OkHttpClient
) : PlatformSearchApi {

    override val provider: PlatformSearchProvider = PlatformSearchProvider.QISHUI

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
        val path = when (type) {
            PlatformSearchType.SONG -> "search/track"
            PlatformSearchType.PLAYLIST -> "search/playlist"
            PlatformSearchType.ARTIST -> "search/artist"
        }
        val url = "https://api.qishui.com/luna/$path".toHttpUrl().newBuilder()
            .apply { commonParams().forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("q", keyword)
            .addQueryParameter("cursor", ((page.coerceAtLeast(1) - 1) * PAGE_SIZE).toString())
            .addQueryParameter("count", PAGE_SIZE.toString())
            .build()
        val root = requestJson(url.toString()) ?: return@withContext emptyList()
        val group = root.optJSONArray("result_groups")?.optJSONObject(0) ?: return@withContext emptyList()
        val data = group.optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until data.length()) {
                parse(data.optJSONObject(index))?.let { add(it) }
            }
        }
    }

    private fun parse(item: JSONObject?): PlatformSearchResult? {
        val entity = item?.optJSONObject("entity") ?: return null
        val track = entity.optJSONObject("track")
        if (track != null) {
            val name = track.optString("name").trim()
            if (name.isEmpty()) return null
            val artists = track.optJSONArray("artists")
            val artistNames = buildList {
                for (index in 0 until (artists?.length() ?: 0)) {
                    artists?.optJSONObject(index)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { add(it) }
                }
            }
            return PlatformSearchResult(
                type = PlatformSearchType.SONG,
                id = track.optString("id"),
                name = name,
                subtitle = artistNames.joinToString("/"),
                album = track.optJSONObject("album")?.optString("name").orEmpty(),
                durationMs = track.optLong("duration"),
                coverUrl = coverUrl(track.optJSONObject("album")?.optJSONObject("url_cover")),
                webUrl = "https://www.qishui.com/track/${track.optString("id")}",
                provider = provider
            )
        }
        val playlist = entity.optJSONObject("playlist")
        if (playlist != null) {
            val title = playlist.optString("title").trim()
            if (title.isEmpty()) return null
            return PlatformSearchResult(
                type = PlatformSearchType.PLAYLIST,
                id = playlist.optString("id"),
                name = title,
                subtitle = playlist.optJSONObject("owner")?.optString("nickname").orEmpty(),
                coverUrl = coverUrl(playlist.optJSONObject("url_cover")),
                extraCount = playlist.optLong("count_tracks"),
                webUrl = "https://www.qishui.com/playlist/${playlist.optString("id")}",
                provider = provider
            )
        }
        val artist = entity.optJSONObject("artist")
        if (artist != null) {
            val name = artist.optString("name").trim()
            if (name.isEmpty()) return null
            return PlatformSearchResult(
                type = PlatformSearchType.ARTIST,
                id = artist.optString("id"),
                name = name,
                coverUrl = coverUrl(artist.optJSONObject("url_avatar")),
                extraCount = artist.optLong("count_tracks"),
                webUrl = "https://www.qishui.com/artist/${artist.optString("id")}",
                provider = provider
            )
        }
        return null
    }

    /** 抖音系图片模板：{urls[0]}{uri}~{template_prefix}-image.image */
    private fun coverUrl(cover: JSONObject?): String? {
        if (cover == null) return null
        val uri = cover.optString("uri").trim()
        val prefix = cover.optString("template_prefix").trim()
        val host = cover.optJSONArray("urls")?.optString(0)?.trim().orEmpty()
        if (uri.isEmpty() || host.isEmpty()) return null
        val template = if (prefix.isEmpty()) "" else "~$prefix"
        return "$host$uri$template-image.image"
    }

    private fun commonParams(): Map<String, String> {
        // 设备标识只需自洽即可，游客态不依赖登录态
        val deviceId = UUID.randomUUID().toString().replace("-", "").take(16)
        return mapOf(
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "cdid" to "46556f98-1720-4248-83da-62b74b60b46a",
            "channel" to "xiaomi_8478_64",
            "aid" to "386088",
            "app_name" to "luna",
            "version_code" to "100198030",
            "version_name" to "19.8.0",
            "manifest_version_code" to "100198030",
            "update_version_code" to "100198030",
            "resolution" to "1080*1920",
            "dpi" to "480",
            "device_type" to "ABR-AL80",
            "device_brand" to "HUAWEI",
            "language" to "zh",
            "os_api" to "35",
            "os_version" to "15",
            "ac" to "wifi",
            "device_model" to "ABR-AL80",
            "tz_name" to "Asia/Shanghai",
            "tz_offset" to "28800",
            "package" to "com.luna.music",
            "sim_region" to "cn",
            "iid" to deviceId,
            "device_id" to deviceId,
            "_rticket" to System.currentTimeMillis().toString()
        )
    }

    private fun requestJson(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string().takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val USER_AGENT =
            "com.luna.music/100198030 (Linux; U; Android 15; zh_CN_#Hans; ABR-AL80; " +
                "Build/V417IR;tt-ok/3.12.13.19)"
    }
}
