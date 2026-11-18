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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseApi
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseSupport
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import moe.ouom.neriplayer.platform.search.api.client.mint.decodeHtmlEntities
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 汽水音乐浏览页：场景歌单。
 *
 * 汽水没有公开的榜单接口，也没有歌单广场，
 * 但按关键词搜歌单是通的（游客态即可）。
 * 这里用一组热门场景词当作分类，搜出来的就是汽水真实存在的歌单。
 *
 * 歌单详情接口（`luna/playlist/detail`）本身就把曲目列表带在 `media_resources` 里，
 * 所以点开歌单能直接列出曲目并播放，不用跳官方网页。
 */
class QishuiBrowseApi(
    private val client: OkHttpClient
) : PlatformBrowseApi {

    override val provider: PlatformSearchProvider = PlatformSearchProvider.QISHUI

    override val categories: List<String> = SCENES.keys.toList()

    /**
     * 汽水的搜索歌单和浏览歌单都是同一个 id 空间（都是 `luna` 的 playlist id），
     * 所以搜索出来的歌单也能直接读曲目。
     */
    override val supportsSearchPlaylistTracks: Boolean = true

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
                            // 官方页面留作兜底：曲目接口读不到时还能让用户去汽水看
                            webUrl = "https://www.qishui.com/playlist/$id",
                            provider = provider
                        )
                    )
                }
            }
        }

    /**
     * 汽水歌单曲目。
     *
     * `luna/playlist/detail` 除了歌单元信息，还把曲目列表直接放在 `media_resources` 里，
     * 游客态就能取到；`cursor` 是偏移量。
     *
     * 页数按歌单曲目总数推算后**并发**拉取——串行的话大歌单要等好几秒。
     */
    override suspend fun tracks(card: PlatformBrowseCard): List<PlatformSearchResult> {
        val playlistId = PlatformBrowseSupport.stripProviderPrefix(card.id).trim()
        if (playlistId.isEmpty()) return emptyList()
        val pages = if (card.trackCount > 0) {
            ((card.trackCount + TRACK_PAGE_SIZE - 1) / TRACK_PAGE_SIZE).coerceIn(1, MAX_TRACK_PAGES)
        } else {
            1
        }
        val details = coroutineScope {
            (0 until pages).map { page ->
                async(Dispatchers.IO) {
                    playlistDetail(playlistId, page * TRACK_PAGE_SIZE, TRACK_PAGE_SIZE)
                }
            }.awaitAll()
        }
        return details
            .filterNotNull()
            .flatMap { parseTracks(it) }
            .distinctBy { it.id }
    }

    /** 歌单详情里的曲目列表 */
    private fun parseTracks(root: JSONObject): List<PlatformSearchResult> {
        val resources = root.optJSONArray("media_resources") ?: return emptyList()
        val tracks = ArrayList<PlatformSearchResult>(resources.length())
        for (index in 0 until resources.length()) {
            val track = resources.optJSONObject(index)
                ?.optJSONObject("entity")
                ?.optJSONObject("track_wrapper")
                ?.optJSONObject("track") ?: continue
            parseTrack(track)?.let { tracks.add(it) }
        }
        return tracks
    }

    /** `media_resources` 里的单个曲目 -> 统一搜索结果 */
    private fun parseTrack(track: JSONObject): PlatformSearchResult? {
        val id = track.optString("id", "").trim()
        val name = decodeHtmlEntities(track.optString("name", "").trim())
        if (id.isEmpty() || name.isEmpty()) return null
        val artists = track.optJSONArray("artists")
        val artistNames = buildList {
            for (index in 0 until (artists?.length() ?: 0)) {
                artists?.optJSONObject(index)?.optString("name", "").orEmpty().trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { add(it) }
            }
        }
        val album = track.optJSONObject("album")
        return PlatformSearchResult(
            type = PlatformSearchType.SONG,
            id = id,
            name = name,
            subtitle = artistNames.joinToString("/"),
            album = decodeHtmlEntities(album?.optString("name", "").orEmpty().trim()),
            durationMs = track.optLong("duration", 0L),
            coverUrl = playlistCoverUrl(album),
            extraCount = 0L,
            webUrl = null,
            extras = emptyMap(),
            provider = provider
        )
    }

    /** 歌单详情（含曲目列表） */
    private fun playlistDetail(playlistId: String, cursor: Int, count: Int): JSONObject? {
        // 老接口 GET api.qishui.com/luna/playlist/detail 已被服务端降级为返回 1000005。
        // 现行 App 协议是 POST beta-luna.douyin.com/luna/playlist/detail（JSON body），
        // 免签名、游客态可用，media_resources 里直接带曲目列表。
        val body = JSONObject()
            .put("playlist_id", playlistId)
            .put("cursor", cursor.toString())
            .put("count", count)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(LUNA_HOST + "/luna/playlist/detail")
            .header("User-Agent", LUNA_APP_UA)
            .header("Content-Type", "application/json; charset=utf-8")
            .post(body)
            .build()
        return requestJson(request)
    }

    private fun requestJson(request: Request): JSONObject? {
        // 不用 runCatching：它会连 CancellationException 一起吞掉，切 tab 取消后还会继续跑
        val text = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body.string().takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return text?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun searchPlaylists(keyword: String): JSONObject? {
        // 老接口 GET api.qishui.com/luna/search/playlist 已失效：
        // 服务端对 app_name=luna 的移动端搜索统一返回 {"extra":{"empty_search":1}}，
        // 与出口地区/设备号无关（国内国外都一样），所以国内也无法恢复。
        // 现行可用的是 PC 搜索 GET /luna/pc/search/playlist + LunaPC UA，
        // 返回结构与老接口一致（result_groups[0].data[].entity.playlist）。
        val url = "https://api.qishui.com/luna/pc/search/playlist".toHttpUrl().newBuilder()
            .apply { pcSearchParams().forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("q", keyword)
            .addQueryParameter("search_method", "input")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", LUNA_PC_UA)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        return requestJson(request)
    }

    /** 抖音系图片模板：{urls[0]}{uri}~{template_prefix}-image.image */
    private fun playlistCoverUrl(playlist: JSONObject?): String? {
        if (playlist == null) return null
        val cover = playlist.optJSONObject("url_cover") ?: return null
        val uri = cover.optString("uri", "").trim()
        val prefix = cover.optString("template_prefix", "").trim()
        val host = cover.optJSONArray("urls")?.optString(0)?.trim().orEmpty()
        if (uri.isEmpty() || host.isEmpty()) return null
        val template = if (prefix.isEmpty()) "" else "~$prefix"
        return "$host$uri$template-image.image"
    }

    /** PC 搜索的公共参数。实测搜索/歌单读取不需要 x-helios/x-medusa 签名头。 */
    private fun pcSearchParams(): Map<String, String> = mapOf(
        "aid" to "386088",
        "device_platform" to "web",
        "channel" to "pc_web",
        "cursor" to "0",
        "count" to "20"
    )

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

        /** 歌单曲目分页大小 */
        const val TRACK_PAGE_SIZE = 50

        /** 歌单曲目最多并发拉几页，避免超大歌单把界面撑爆 */
        const val MAX_TRACK_PAGES = 5

        /** 免签名的 luna H5/App 网关：discover、playlist/detail、seo_track 都走这里 */
        const val LUNA_HOST = "https://beta-luna.douyin.com"

        /** postLuna 的 UA，参考 qishui-api：Luna/19.1.0 Android */
        const val LUNA_APP_UA = "Luna/19.1.0 Android"

        /** PC 搜索/详情的 UA，服务端据此放行匿名请求 */
        const val LUNA_PC_UA = "LunaPC/3.0.0(290101097)"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
