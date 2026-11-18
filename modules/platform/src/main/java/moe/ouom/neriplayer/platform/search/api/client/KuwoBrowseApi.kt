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
 * File: moe.ouom.neriplayer.platform.search.api.client/KuwoBrowseApi
 * Created: 2026/11/13
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseApi
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseKind
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseSupport
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import moe.ouom.neriplayer.platform.search.api.PlatformSearchResult
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 酷我浏览页：热门歌单 + 歌单标签 + 排行榜。
 *
 * 端点都是明文的，游客可用：
 * - 热门歌单 wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList
 * - 标签分组 wapi.kuwo.cn/api/pc/classify/playlist/getTagList
 * - 热门标签 wapi.kuwo.cn/api/pc/classify/playlist/getRcmTagList
 * - 分类歌单 wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList
 * - 排行榜   qukudata.kuwo.cn/q.k?cont=tree&node=2
 * - 榜单详情 kbangserver.kuwo.cn/ksong.s
 */
class KuwoBrowseApi(
    private val client: OkHttpClient
) : PlatformBrowseApi {

    override val provider: PlatformSearchProvider = PlatformSearchProvider.KUWO

    override val categories: List<String> =
        listOf(CATEGORY_HOT, CATEGORY_CHARTS) + TAG_CATEGORIES.map { it.label }

    override suspend fun browse(category: String): List<PlatformBrowseCard> =
        withContext(Dispatchers.IO) {
            when (category) {
                CATEGORY_CHARTS -> leaderboards()
                CATEGORY_HOT -> hotPlaylists()
                else -> PlatformBrowseSupport.browseWithFallback(
                    category = category,
                    categories = TAG_CATEGORIES,
                    primary = { label -> categoryPlaylists(tagIdFor(label)) },
                    fallback = { hotPlaylists() },
                    hotTags = { TAG_CATEGORIES }
                )
            }
        }

    override suspend fun tracks(card: PlatformBrowseCard): List<PlatformSearchResult> =
        withContext(Dispatchers.IO) {
            val rawId = PlatformBrowseSupport.stripProviderPrefix(card.id)
            when (card.kind) {
                PlatformBrowseKind.CHART -> leaderboardTracks(rawId)
                // 酷我歌单详情要 digest 形式，匿名接口拿不到曲目
                PlatformBrowseKind.PLAYLIST -> emptyList()
            }
        }

    /** 热门歌单 */
    private fun hotPlaylists(): List<PlatformBrowseCard> {
        val url = "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList".toHttpUrl().newBuilder()
            .addQueryParameter("loginUid", "0")
            .addQueryParameter("loginSid", "0")
            .addQueryParameter("appUid", "76039576")
            .addQueryParameter("pn", "0")
            .addQueryParameter("rn", "30")
            .addQueryParameter("order", "hot")
            .build()
        return parsePlaylistList(requestJson(url.toString()))
    }

    /** 分类名 -> 接口用的 tag id */
    private fun tagIdFor(category: String): String =
        TAG_CATEGORIES.firstOrNull { it.label == category }?.id.orEmpty()

    /** 分类歌单：tagId 为空时等价于热门歌单 */
    private fun categoryPlaylists(tagId: String): List<PlatformBrowseCard> {
        val builder = "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList".toHttpUrl().newBuilder()
            .addQueryParameter("loginUid", "0")
            .addQueryParameter("loginSid", "0")
            .addQueryParameter("appUid", "76039576")
            .addQueryParameter("pn", "0")
            .addQueryParameter("rn", "30")
        if (tagId.isNotBlank()) {
            // tagId 形如 "12-abc123"，接口只认前半段的数字 id
            builder.addQueryParameter("id", tagId.substringBefore('-'))
        }
        return parsePlaylistList(requestJson(builder.build().toString()))
    }

    /** 排行榜 */
    private fun leaderboards(): List<PlatformBrowseCard> {
        val url = "http://qukudata.kuwo.cn/q.k".toHttpUrl().newBuilder()
            .addQueryParameter("op", "query")
            .addQueryParameter("cont", "tree")
            .addQueryParameter("node", "2")
            .addQueryParameter("pn", "0")
            .addQueryParameter("rn", "1000")
            .addQueryParameter("fmt", "json")
            .addQueryParameter("level", "3")
            .build()
        val root = requestJson(url.toString()) ?: return FALLBACK_CHARTS.map { it.toCard() }
        val children = root.optJSONArray("child") ?: return FALLBACK_CHARTS.map { it.toCard() }
        val cards = buildList {
            for (index in 0 until children.length()) {
                val item = children.optJSONObject(index) ?: continue
                val sourceId = item.optString("sourceid", "").trim()
                val name = item.optString("name", "").trim()
                if (sourceId.isEmpty() || name.isEmpty()) continue
                add(
                    PlatformBrowseCard(
                        id = PlatformBrowseSupport.qualifyId(provider, sourceId),
                        title = name,
                        subtitle = item.optString("info", "").trim(),
                        coverUrl = item.optString("pic", "").trim().takeIf { it.isNotBlank() },
                        playCount = PlatformBrowseSupport.parsePlayCount(item.optString("listen", "")),
                        kind = PlatformBrowseKind.CHART,
                        provider = provider
                    )
                )
            }
        }
        if (cards.isEmpty()) return FALLBACK_CHARTS.map { it.toCard() }
        return cards.sortedByDescending { it.playCount }
    }

    /** 榜单曲目 */
    private fun leaderboardTracks(boardId: String): List<PlatformSearchResult> {
        val url = "http://kbangserver.kuwo.cn/ksong.s".toHttpUrl().newBuilder()
            .addQueryParameter("from", "pc")
            .addQueryParameter("fmt", "json")
            .addQueryParameter("pn", "0")
            .addQueryParameter("rn", "100")
            .addQueryParameter("type", "bang")
            .addQueryParameter("data", "content")
            .addQueryParameter("id", boardId)
            .addQueryParameter("show_copyright_off", "0")
            .addQueryParameter("pcmp4", "1")
            .addQueryParameter("isbang", "1")
            .build()
        val root = requestJson(url.toString()) ?: return emptyList()
        val list = root.optJSONArray("musiclist")
            ?: root.optJSONObject("bang")?.optJSONArray("musiclist")
            ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val rid = item.optString("id", "").ifBlank { item.optString("mid", "") }.trim()
                val name = item.optString("name", "").trim()
                if (rid.isEmpty() || name.isEmpty()) continue
                add(
                    PlatformSearchResult(
                        type = PlatformSearchType.SONG,
                        id = rid,
                        name = name,
                        subtitle = item.optString("artist", "").ifBlank { item.optString("singer", "") }.trim(),
                        album = item.optString("album", "").trim(),
                        durationMs = item.optString("duration", "0").toLongOrNull()?.times(1000L) ?: 0L,
                        coverUrl = item.optString("pic", "").ifBlank { item.optString("albumpic", "") }
                            .trim().takeIf { it.isNotBlank() },
                        provider = provider,
                        extras = mapOf("rid" to rid)
                    )
                )
            }
        }
    }

    /** 酷我歌单列表体：data.data[] */
    private fun parsePlaylistList(root: JSONObject?): List<PlatformBrowseCard> {
        val list = root?.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val digest = item.optString("digest", "").trim()
                val rawId = item.optString("id", "").trim()
                if (rawId.isEmpty()) continue
                val title = item.optString("name", "").trim()
                if (title.isEmpty()) continue
                add(
                    PlatformBrowseCard(
                        id = PlatformBrowseSupport.qualifyId(provider, "digest-${digest}__${rawId}"),
                        title = title,
                        subtitle = item.optString("desc", "").trim(),
                        coverUrl = item.optString("img", "").trim().takeIf { it.isNotBlank() },
                        trackCount = item.optInt("total", 0),
                        playCount = PlatformBrowseSupport.parsePlayCount(item.optString("listencnt", "")),
                        provider = provider
                    )
                )
            }
        }
    }

    private fun requestJson(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://www.kuwo.cn/")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string().takeIf { it.isNotBlank() }
            }
        }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private data class FallbackChart(val id: String, val name: String)

    private fun FallbackChart.toCard() = PlatformBrowseCard(
        id = PlatformBrowseSupport.qualifyId(provider, id),
        title = name,
        provider = provider
    )

    private companion object {
        const val CATEGORY_HOT = "热门歌单"
        const val CATEGORY_CHARTS = "排行榜"

        /**
         * 分类标签：显示名 -> 接口用的数字 tag id。
         *
         * 这些 id 取自 wapi.kuwo.cn 的 getRcmTagList / getTagList，
         * 线上实测可用；接口只认数字 id，传中文会返回 code=-1。
         */
        val TAG_CATEGORIES = listOf(
            PlatformBrowseSupport.BrowseCategory("短视频", "2189"),
            PlatformBrowseSupport.BrowseCategory("经典", "1265"),
            PlatformBrowseSupport.BrowseCategory("情歌", "2200"),
            PlatformBrowseSupport.BrowseCategory("伤感", "146"),
            PlatformBrowseSupport.BrowseCategory("怀旧", "155"),
            PlatformBrowseSupport.BrowseCategory("古风", "127"),
            PlatformBrowseSupport.BrowseCategory("网络", "621"),
            PlatformBrowseSupport.BrowseCategory("轻音乐", "173"),
            PlatformBrowseSupport.BrowseCategory("开车", "376"),
            PlatformBrowseSupport.BrowseCategory("流行", "393")
        )

        val FALLBACK_CHARTS = listOf(
            FallbackChart("93", "飙升榜"),
            FallbackChart("16", "热歌榜"),
            FallbackChart("17", "新歌榜"),
            FallbackChart("158", "流行趋势榜"),
            FallbackChart("145", "抖音歌曲榜"),
            FallbackChart("187", "快手歌曲榜"),
            FallbackChart("141", "网络神曲榜"),
            FallbackChart("184", "综艺热歌榜"),
            FallbackChart("242", "华语榜"),
            FallbackChart("243", "欧美榜"),
            FallbackChart("244", "日韩榜")
        )

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
