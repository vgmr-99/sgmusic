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
 */

/**
 * LX Music 自定义源的最小契约（按公开协议文档独立实现，GPL-3.0）
 * 协议参考：lxmusic.toside.cn/desktop|mobile/custom-source
 * - globalThis.lx 提供 EVENT_NAMES/inited/request/updateAlert、on/send/request、utils、env、currentScriptInfo
 * - inited 声明 sources/actions/qualitys；request 回调返回 Promise
 * - lx.request 由宿主代发
 *
 * 阶段 1 只做 musicUrl 解析（播放地址），不做搜索/榜单/歌单。
 */
enum class LxPlatform(val code: String) {
    KW("kw"),
    KG("kg"),
    TX("tx"),
    WY("wy"),
    MG("mg"),
    QS("qs"),
    LOCAL("local"),
}

enum class LxQuality(val code: String) {
    Q128("128k"),
    Q320("320k"),
    FLAC("flac"),
    FLAC24("flac24bit");

    companion object {
        /** 界面展示顺序固定：128k -> 320k -> 无损 -> 母带 */
        val STANDARD_ORDER: List<LxQuality> = listOf(Q128, Q320, FLAC, FLAC24)
    }
}

data class CurrentScriptInfo(
    val name: String? = null,
    val author: String? = null,
    val version: String? = null,
    val description: String? = null,
    val srcUrl: String? = null,
)

data class LxSourceInfo(
    val status: Boolean = true,
    val env: String = "mobile",
    val sources: Map<String, List<String>> = emptyMap(),
    val actions: List<String> = emptyList(),
    val alert: String? = null,
) {
    fun supports(platform: LxPlatform): Boolean = sources.containsKey(platform.code)
    fun qualities(platform: LxPlatform): List<String> = sources[platform.code].orEmpty()
    fun supportsQuality(platform: LxPlatform, quality: LxQuality): Boolean =
        qualities(platform).contains(quality.code)
}

enum class LxSourceState { INIT, OK, FAIL, DISABLED }

data class LxSourceItem(
    val id: String,
    val name: String,
    val scriptText: String,
    val state: LxSourceState = LxSourceState.INIT,
    val info: LxSourceInfo? = null,
    val error: String? = null,
    val enabled: Boolean = true,
)

sealed interface LxResult<out T> {
    data class Ok<T>(val value: T) : LxResult<T>
    data class Err(val code: String, val message: String) : LxResult<Nothing>
}
