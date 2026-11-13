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
 * File: moe.ouom.neriplayer.platform.search.api.client/KuwoSearchApi
 * Created: 2026/11/13
 */

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
 * 酷我音乐搜索。
 *
 * search.kuwo.cn/r.s 是明文的，游客可用，
 * 返回的是单引号 JS 字面量而不是标准 JSON，所以按字段逐个取。
 */
class KuwoSearchApi(
    private val client: OkHttpClient
) : PlatformSearchApi {

    override val provider: PlatformSearchProvider = PlatformSearchProvider.KUWO

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
        val url = "http://search.kuwo.cn/r.s".toHttpUrl().newBuilder()
            .addQueryParameter("all", keyword)
            .addQueryParameter("ft", "music")
            .addQueryParameter("itemset", "web_2013")
            .addQueryParameter("client", "kt")
            .addQueryParameter("pn", ((page.coerceAtLeast(1) - 1) * PAGE_SIZE).toString())
            .addQueryParameter("rn", PAGE_SIZE.toString())
            .addQueryParameter("rformat", "json")
            .addQueryParameter("encoding", "utf8")
            .build()
        val raw = requestText(url.toString()) ?: return@withContext emptyList()
        parseSongs(raw)
    }

    /** 单引号 JS 字面量，转成标准 JSON 再解析 */
    private fun parseSongs(raw: String): List<PlatformSearchResult> {
        val json = runCatching { JSONObject(toJsonLiteral(raw)) }.getOrNull() ?: return emptyList()
        val list = json.optJSONArray("abslist") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val rid = item.optString("DC_TARGETID", "").trim()
                val name = item.optString("NAME", "").trim()
                if (rid.isEmpty() || name.isEmpty()) continue
                add(
                    PlatformSearchResult(
                        type = PlatformSearchType.SONG,
                        id = rid,
                        name = name,
                        subtitle = item.optString("ARTIST", "").trim(),
                        album = item.optString("ALBUM", "").trim(),
                        durationMs = item.optString("DURATION", "0").toLongOrNull()?.times(1000L) ?: 0L,
                        provider = provider,
                        extras = mapOf("rid" to rid)
                    )
                )
            }
        }
    }

    /**
     * 把单引号 JS 字面量转成标准 JSON。
     *
     * 酷我的 r.s 返回形如 {'key':'value',...}，直接扔给 org.json 会解析失败。
     */
    private fun toJsonLiteral(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return trimmed
        return buildString(trimmed.length) {
            var index = 0
            while (index < trimmed.length) {
                val ch = trimmed[index]
                when (ch) {
                    '\'' -> append('"')
                    '\\' -> {
                        val next = trimmed.getOrNull(index + 1)
                        if (next == '\'') {
                            append('\'')
                            index++
                        } else {
                            append(ch)
                        }
                    }
                    else -> append(ch)
                }
                index++
            }
        }
    }

    private fun requestText(url: String): String? {
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
        }.getOrNull()
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
