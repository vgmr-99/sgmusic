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
 * File: moe.ouom.neriplayer.platform.lxsource/LxMusicInfoLocator
 * Created: 2026/11/12
 */

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 按平台定位一首真实存在的歌曲，产出洛雪协议需要的 musicInfo。
 *
 * 洛雪自定义源只负责"拿 musicInfo 换播放地址"，不负责搜索；
 * 要让脚本能取流，就得先给它一个该平台真实存在的 musicInfo。
 *
 * 这里用各平台公开的搜索接口按"歌名 歌手"定位同一首歌，
 * 这样各平台的检测结果指向同一首曲子，结果才有可比性。
 * 定位不到的平台（例如咪咕没有可用的匿名搜索）会返回 null，由调用方标记为不可测。
 */
class LxMusicInfoLocator(
    private val client: OkHttpClient,
    /** 网易云匿名搜索（eapi）：由宿主注入，避免这里耦合 NeteaseClient */
    private val neteaseSearch: suspend (keyword: String) -> String? = { null }
) {

    suspend fun locate(
        platform: LxPlatform,
        keyword: String,
        types: List<LxQuality> = defaultTypes(platform),
        quality: LxQuality? = null
    ): LxMusicInfo? = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext null
        when (platform) {
            LxPlatform.WY -> locateNetease(keyword, types)
            LxPlatform.TX -> locateQq(keyword, types)
            LxPlatform.KG -> locateKugou(keyword, types, quality)
            LxPlatform.KW -> locateKuwo(keyword, types)
            LxPlatform.QS -> locateQishui(keyword, types)
            LxPlatform.MG, LxPlatform.LOCAL -> null
        }
    }

    /** 网易云：eapi 匿名搜索，返回歌曲 id */
    private suspend fun locateNetease(keyword: String, types: List<LxQuality>): LxMusicInfo? {
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
        val album = song.optJSONObject("al")
        return LxMusicInfo(
            source = LxPlatform.WY.code,
            songmid = id.toString(),
            name = song.optString("name", ""),
            singer = artistNames.joinToString("/"),
            albumName = album?.optString("name", "").orEmpty(),
            durationMs = song.optLong("dt", 0L),
            types = types,
            coverUrl = album?.optString("picUrl", "")?.takeIf { it.isNotBlank() }
        )
    }

    /** QQ音乐：client_search_cp，返回 songmid */
    private suspend fun locateQq(keyword: String, types: List<LxQuality>): LxMusicInfo? {
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
        val albumMid = song.optString("albummid", "").trim()
        return LxMusicInfo(
            source = LxPlatform.TX.code,
            songmid = mid,
            name = song.optString("songname", ""),
            singer = artistNames.joinToString("/"),
            albumName = song.optString("albumname", ""),
            durationMs = song.optInt("interval") * 1000L,
            types = types,
            coverUrl = song.optString("albumid", "").takeIf { it.isNotBlank() }
                ?.let { "https://y.qq.com/music/photo_new/T002R300x300M000$it.jpg" },
            extras = buildMap {
                // QQ 源脚本认 strMediaMid（歌曲 mid）与 albumMid（专辑 mid）
                put("strMediaMid", mid)
                if (albumMid.isNotBlank()) put("albumMid", albumMid)
            }
        )
    }

    /** 酷狗：song_search_v2，取各音质的文件 hash */
    private suspend fun locateKugou(
        keyword: String,
        types: List<LxQuality>,
        quality: LxQuality?
    ): LxMusicInfo? {
        val url = "https://songsearch.kugou.com/song_search_v2".toQueryUrl(
            "keyword" to keyword,
            "page" to "1",
            "pagesize" to "10",
            "platform" to "WebFilter"
        )
        val root = requestJson(url, referer = "https://www.kugou.com/") ?: return null
        val list = root.optJSONObject("data")?.optJSONArray("lists") ?: return null
        val song = list.optJSONObject(0) ?: return null
        // 酷狗按音质取流要对应的 hash：128k=FileHash、320k=SQFileHash、无损=HQFileHash、母带=SuperFileHash
        val hashes = mapOf(
            LxQuality.Q128 to song.optString("FileHash", "").trim(),
            LxQuality.Q320 to song.optString("SQFileHash", "").trim(),
            LxQuality.FLAC to song.optString("HQFileHash", "").trim(),
            LxQuality.FLAC24 to song.optString("SuperFileHash", "").trim()
        ).filterValues { it.isNotBlank() }
        // 请求哪档音质就给哪档的 hash，否则脚本会拿 128k 的 hash 去要高音质
        val primary = (quality?.let { hashes[it] } ?: hashes[LxQuality.Q128])
            ?: song.optString("FileHash", "").trim()
        if (primary.isEmpty()) return null
        val albumId = song.optString("AlbumID", "").trim()
        val audioId = song.optString("Audioid", "").trim()
        return LxMusicInfo(
            source = LxPlatform.KG.code,
            songmid = primary,
            name = song.optString("SongName", ""),
            singer = song.optString("SingerName", ""),
            albumName = song.optString("AlbumName", ""),
            durationMs = song.optInt("Duration") * 1000L,
            types = types,
            qualityHashes = hashes,
            coverUrl = song.optString("Image", "").takeIf { it.isNotBlank() },
            extras = buildMap {
                put("hash", primary)
                if (albumId.isNotBlank()) put("albumId", albumId)
                if (audioId.isNotBlank()) put("Audioid", audioId)
                hashes[LxQuality.Q128]?.let { put("FileHash", it) }
                hashes[LxQuality.Q320]?.let { put("SQFileHash", it) }
                hashes[LxQuality.FLAC]?.let { put("HQFileHash", it) }
                hashes[LxQuality.FLAC24]?.let { put("SuperFileHash", it) }
            }
        )
    }

    /** 酷我：search.kuwo.cn，取 DC_TARGETID（即 MUSICRID） */
    private suspend fun locateKuwo(keyword: String, types: List<LxQuality>): LxMusicInfo? {
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
        val durationSec = Regex("'DURATION':'([^']*)'").find(raw)?.groupValues?.get(1)
            ?.trim()?.toLongOrNull() ?: 0L
        return LxMusicInfo(
            source = LxPlatform.KW.code,
            songmid = targetId,
            name = name,
            singer = artist,
            durationMs = durationSec * 1000L,
            types = types,
            extras = mapOf("rid" to targetId)
        )
    }

    /** 汽水音乐：luna/search/track，取 track id */
    private suspend fun locateQishui(keyword: String, types: List<LxQuality>): LxMusicInfo? {
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
        val album = track.optJSONObject("album")
        return LxMusicInfo(
            source = LxPlatform.QS.code,
            songmid = id,
            name = track.optString("name", ""),
            singer = artistNames.joinToString("/"),
            albumName = album?.optString("name", "").orEmpty(),
            durationMs = track.optLong("duration", 0L),
            types = types
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

    companion object {
        /** 各平台常见的可用音质（脚本声明的为准，这里只是兜底） */
        fun defaultTypes(platform: LxPlatform): List<LxQuality> = when (platform) {
            LxPlatform.TX, LxPlatform.WY -> listOf(LxQuality.Q128, LxQuality.Q320, LxQuality.FLAC)
            LxPlatform.MG -> listOf(LxQuality.Q128, LxQuality.Q320)
            else -> LxQuality.STANDARD_ORDER
        }

        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val LUNA_USER_AGENT =
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
