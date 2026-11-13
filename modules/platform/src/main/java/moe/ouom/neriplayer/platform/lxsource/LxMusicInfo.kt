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
 * File: moe.ouom.neriplayer.platform.lxsource/LxMusicInfo
 * Created: 2026/11/12
 */

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 脚本返回的播放地址。
 *
 * @param headers 脚本要求带的请求头（部分音源的地址需要 Referer 才能拉流）
 */
data class LxMusicUrl(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

/**
 * 洛雪自定义源协议里的 musicInfo 对象。
 *
 * 洛雪脚本收到的是 `{ source, action, info }`：
 * - action = `musicUrl` 时 `info = { type: <音质>, musicInfo: <音乐信息对象> }`
 * - action = `lyric` / `pic` 时 `info = { musicInfo: <音乐信息对象> }`
 *
 * musicInfo 里必须带歌曲的 id、名字、歌手，脚本才能拼出自己的取链请求；
 * 只传 songmid 的话，脚本会直接报「缺少musicInfo」。
 *
 * 平台特有字段（酷狗 hash、咪咕 copyrightId、QQ strMediaMid/albumMid 等）
 * 没有值时必须**整个 key 省略**：社区脚本普遍用 `hash ?? songmid` 判空，
 * 给空字符串会把有效值遮住。
 */
data class LxMusicInfo(
    /** 平台代码（kw/kg/tx/wy/mg/qs） */
    val source: String,
    /** 平台内的歌曲 id */
    val songmid: String,
    val name: String = "",
    val singer: String = "",
    val albumName: String = "",
    val durationMs: Long = 0L,
    /** 该歌曲可用的音质列表 */
    val types: List<LxQuality> = emptyList(),
    /** 各音质对应的文件 hash（酷狗按音质取流时需要） */
    val qualityHashes: Map<LxQuality, String> = emptyMap(),
    /** 平台特有字段，如 kg.hash / mg.copyrightId / tx.strMediaMid */
    val extras: Map<String, String> = emptyMap(),
    val coverUrl: String? = null
) {
    val id: String get() = "${source}_$songmid"

    /**
     * 换成另一档音质。
     *
     * 酷狗这类平台每个音质对应不同的文件 hash，
     * 请求 320k 却给 128k 的 hash，脚本就换不出对应音质的地址。
     */
    fun withQuality(quality: LxQuality): LxMusicInfo {
        val hash = qualityHashes[quality] ?: return this
        return copy(
            songmid = hash,
            extras = extras.toMutableMap().apply { put("hash", hash) }
        )
    }

    /** 洛雪协议里的时长格式：mm:ss（超过一小时为 hh:mm:ss） */
    val interval: String get() = formatInterval(durationMs)

    /**
     * 输出洛雪协议里 musicInfo 的完整字段集。
     *
     * 字段清单（洛雪插件协议约定的 21 个）：
     * songmid, songId, songid, hash, singer, name, albumName, albumId, albumMid,
     * strMediaMid, copyrightId, source, interval, img, lrc, lrcUrl, mrcUrl, trcUrl,
     * types, _types, typeUrl, id
     *
     * 关键坑：**extras 里的键有值才写进顶层**。
     * 社区脚本普遍用 `hash ?? songmid` 判空，给一个空字符串会把真正有效的值遮住，
     * 所以平台特有字段（hash / rid / strMediaMid / copyrightId / songId ...）
     * 拿不到值时整个 key 省略，而不是写成 ""。
     */
    fun toJson(): JSONObject {
        val qualityTypes = JSONArray()
        val qualityMap = JSONObject()
        types.forEach { quality ->
            val hash = qualityHashes[quality]?.takeIf { it.isNotBlank() }
            val entry = JSONObject()
                .put("type", quality.code)
                .put("size", JSONObject.NULL)
            val mapEntry = JSONObject().put("size", JSONObject.NULL)
            if (hash != null) {
                entry.put("hash", hash)
                mapEntry.put("hash", hash)
            }
            qualityTypes.put(entry)
            qualityMap.put(quality.code, mapEntry)
        }

        // songId / songid：社区脚本普遍用 songId ?? songmid 判空，优先平台原始 id
        val songIdValue = extras["songId"]?.takeIf { it.isNotBlank() }
            ?: extras["copyrightId"]?.takeIf { it.isNotBlank() }
            ?: songmid.takeIf { it.isNotBlank() }
        val cover = coverUrl?.takeIf { it.isNotBlank() }

        val meta = JSONObject()
            .put("songId", songIdValue ?: JSONObject.NULL)
            .put("albumName", albumName)
            .put("picUrl", cover ?: JSONObject.NULL)
            .put("interval", interval)
            .put("types", qualityTypes)
            .put("_types", qualityMap)
            .put("qualitys", qualityTypes)
            .put("_qualitys", qualityMap)

        val json = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("singer", singer)
            .put("source", source)
            .put("songmid", songmid)
            .put("albumName", albumName)
            .put("interval", interval)
            .put("types", qualityTypes)
            .put("_types", qualityMap)
            // 部分社区脚本读的是 qualitys/_qualitys，两个拼写都给上
            .put("qualitys", qualityTypes)
            .put("_qualitys", qualityMap)
            .put("typeUrl", JSONObject())
            .put("meta", meta)
            // 本宿主没有歌词来源的字段：置 null（脚本读到 null 不会当成空串去判空）
            .put("lrc", JSONObject.NULL)
            .put("lrcUrl", JSONObject.NULL)
            .put("mrcUrl", JSONObject.NULL)
            .put("trcUrl", JSONObject.NULL)

        // desktop/CeruMusic 脚本读 songId/songid/img，有值才写
        if (songIdValue != null) {
            json.put("songId", songIdValue)
            json.put("songid", songIdValue)
            meta.put("songId", songIdValue)
            meta.put("songid", songIdValue)
        }
        if (cover != null) {
            json.put("img", cover)
            meta.put("img", cover)
        }
        // hash / albumId / albumMid / strMediaMid / copyrightId / rid 等平台特有字段
        // 全部来自 extras，且「有值才写」：空字符串会遮住社区脚本
        // `hash ?? songmid` 里真正有效的值（见 LxMusicInfoTest 的断言）。
        extras.forEach { (key, value) ->
            if (value.isNotBlank()) {
                json.put(key, value)
                meta.put(key, value)
            }
        }
        return json
    }

    companion object {
        fun formatInterval(durationMs: Long): String {
            val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }
}
