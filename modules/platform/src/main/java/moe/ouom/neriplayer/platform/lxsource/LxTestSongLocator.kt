package moe.ouom.neriplayer.platform.lxsource

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
 * File: moe.ouom.neriplayer.platform.lxsource/LxTestSongLocator
 * Created: 2026/11/12
 */

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 音质检测用的测试歌曲引用。
 *
 * @param songmid 平台内的歌曲 id（洛雪协议里 musicUrl 的 info.songmid）
 * @param hash 平台内的文件 hash（酷狗取流认这个字段）
 */
data class LxTestSongRef(
    val songmid: String,
    val hash: String = "",
    val title: String = "",
    val artist: String = ""
)

/**
 * 为音质检测定位一首测试歌曲。
 *
 * 洛雪自定义源只负责"拿 songmid 换播放地址"，不负责搜索；
 * 想验证某个平台的某档音质能不能出流，必须先有一个该平台真实存在的 songmid。
 *
 * 这里用各平台公开的搜索接口按"歌名 歌手"定位同一首歌，
 * 这样各平台的检测结果指向同一首曲子，结果才有可比性。
 * 定位不到的平台（例如咪咕没有可用的匿名搜索）会返回 null，由调用方标记为不可测。
 */
class LxTestSongLocator(
    private val client: OkHttpClient,
    /** 网易云匿名搜索（eapi）：由宿主注入，避免这里耦合 NeteaseClient */
    private val neteaseSearch: suspend (keyword: String) -> String? = { null }
) {

    suspend fun locate(platform: LxPlatform, keyword: String): LxTestSongRef? =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext null
            when (platform) {
                LxPlatform.WY -> locateNetease(keyword)
                LxPlatform.TX -> locateQq(keyword)
                LxPlatform.KG -> locateKugou(keyword)
                LxPlatform.KW -> locateKuwo(keyword)
                LxPlatform.QS -> locateQishui(keyword)
                LxPlatform.MG, LxPlatform.LOCAL -> null
            }
        }

    /** 网易云：eapi 匿名搜索，返回歌曲 id */
    private suspend fun locateNetease(keyword: String): LxTestSongRef? {
        val raw = runCatching { neteaseSearch(keyword) }.getOrNull() ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 200) return null
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
        val song = songs.optJSONObject(0) ?: return null
        val id = song.optLong("id", 0L)
        if (id <= 0L) return null
        val artists = song.optJSONArray("ar")
        val artistNames = buildList {
            for (index in 0 until (artists?.length() ?: 0)) {
                artists?.optJSONObject(index)?.optString("name")?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        return LxTestSongRef(
            songmid = id.toString(),
            title = song.optString("name", ""),
            artist = artistNames.joinToString("/")
        )
    }

    /** QQ音乐：client_search_cp，返回 songmid */
    private suspend fun locateQq(keyword: String): LxTestSongRef? {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toQueryUrl(
            "format" to "json",
            "n" to "10",
            "p" to "1",
            "w" to keyword,
            "cr" to "1",
            "g_tk" to "5381"
        )
        val root = requestJson(url, referer = "https://y.qq.com/") ?: return null
        val list = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            ?: return null
        val song = list.optJSONObject(0) ?: return null
        val mid = song.optString("songmid", "").trim()
        if (mid.isEmpty()) return null
        val singers = song.optJSONArray("singer")
        val artistNames = buildList {
            for (index in 0 until (singers?.length() ?: 0)) {
                singers?.optJSONObject(index)?.optString("name")?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        return LxTestSongRef(
            songmid = mid,
            title = song.optString("songname", ""),
            artist = artistNames.joinToString("/")
        )
    }

    /** 酷狗：song_search_v2，取 FileHash（洛雪协议里酷狗认 hash） */
    private suspend fun locateKugou(keyword: String): LxTestSongRef? {
        val url = "https://songsearch.kugou.com/song_search_v2".toQueryUrl(
            "keyword" to keyword,
            "page" to "1",
            "pagesize" to "10",
            "platform" to "WebFilter"
        )
        val root = requestJson(url, referer = "https://www.kugou.com/") ?: return null
        val list = root.optJSONObject("data")?.optJSONArray("lists") ?: return null
        val song = list.optJSONObject(0) ?: return null
        val hash = song.optString("FileHash", "").trim()
        val audioId = song.optString("Audioid", "").trim()
        val songmid = hash.ifEmpty { audioId }
        if (songmid.isEmpty()) return null
        return LxTestSongRef(
            songmid = songmid,
            hash = hash,
            title = song.optString("SongName", ""),
            artist = song.optString("SingerName", "")
        )
    }

    /** 酷我：search.kuwo.cn，取 DC_TARGETID（即 MUSICRID） */
    private suspend fun locateKuwo(keyword: String): LxTestSongRef? {
        val url = "https://search.kuwo.cn/r.s".toQueryUrl(
            "all" to keyword,
            "ft" to "music",
            "itemset" to "web_2013",
            "client" to "kt",
            "pn" to "0",
            "rn" to "20",
            "rformat" to "json",
            "encoding" to "utf8"
        )
        val raw = requestText(url, referer = "https://www.kuwo.cn/") ?: return null
        // 这个接口返回的是单引号 JS 字面量，不是标准 JSON
        val targetId = Regex("'DC_TARGETID':'([^']*)'").find(raw)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val name = Regex("'NAME':'([^']*)'").find(raw)?.groupValues?.get(1)
            ?.replace("&nbsp;", " ")?.trim().orEmpty()
        val artist = Regex("'ARTIST':'([^']*)'").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        return LxTestSongRef(
            songmid = targetId,
            title = name,
            artist = artist
        )
    }

    /** 汽水音乐：luna/search/track，取 track id */
    private suspend fun locateQishui(keyword: String): LxTestSongRef? {
        val url = "https://api.qishui.com/luna/search/track".toQueryUrl(
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
            "resolution" to "1080*1920",
            "dpi" to "480",
            "device_model" to "ABR-AL80",
            "device_brand" to "HUAWEI",
            "device_type" to "ABR-AL80",
            "tz_name" to "Asia/Shanghai",
            "tz_offset" to "28800",
            "ac" to "wifi",
            "channel" to "xiaomi_8478_64",
            "ssmix" to "a",
            "cdid" to "46556f98-1720-4248-83da-62b74b60b46a",
            "iid" to randomDeviceId(),
            "device_id" to randomDeviceId(),
            "_rticket" to System.currentTimeMillis().toString(),
            "q" to keyword,
            "cursor" to "0",
            "count" to "10"
        )
        val root = requestJson(url, userAgent = LUNA_USER_AGENT) ?: return null
        val group = root.optJSONArray("result_groups")?.optJSONObject(0) ?: return null
        val track = group.optJSONArray("data")?.optJSONObject(0)
            ?.optJSONObject("entity")?.optJSONObject("track") ?: return null
        val id = track.optString("id", "").trim()
        if (id.isEmpty()) return null
        val artists = track.optJSONArray("artists")
        val artistNames = buildList {
            for (index in 0 until (artists?.length() ?: 0)) {
                artists?.optJSONObject(index)?.optString("name")?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        return LxTestSongRef(
            songmid = id,
            title = track.optString("name", ""),
            artist = artistNames.joinToString("/")
        )
    }

    private fun requestJson(
        url: String,
        referer: String? = null,
        userAgent: String = WEB_USER_AGENT
    ): JSONObject? {
        val text = requestText(url, referer, userAgent) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun requestText(
        url: String,
        referer: String? = null,
        userAgent: String = WEB_USER_AGENT
    ): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/plain, */*")
            .apply { referer?.let { header("Referer", it) } }
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string().takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun randomDeviceId(): String =
        UUID.randomUUID().toString().replace("-", "").take(16)

    private companion object {
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val LUNA_USER_AGENT =
            "com.luna.music/100198030 (Linux; U; Android 15; zh_CN_#Hans; ABR-AL80; " +
                "Build/V417IR;tt-ok/3.12.13.19)"
    }
}

/** 给 URL 追加查询参数（值做 URL 编码） */
private fun String.toQueryUrl(vararg pairs: Pair<String, String>): String {
    val builder = StringBuilder(this)
    pairs.forEach { (key, value) ->
        builder.append(if (builder.contains("?")) "&" else "?")
        builder.append(key).append("=").append(encodeQueryValue(value))
    }
    return builder.toString()
}

private fun encodeQueryValue(value: String): String {
    val encoded = java.net.URLEncoder.encode(value, "UTF-8")
    // URLEncoder 把空格编成 +，查询串里按 %20 更通用
    return encoded.replace("+", "%20")
}
