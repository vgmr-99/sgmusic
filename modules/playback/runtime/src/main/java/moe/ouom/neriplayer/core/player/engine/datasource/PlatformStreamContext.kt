package moe.ouom.neriplayer.core.player.engine.datasource

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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
 * File: moe.ouom.neriplayer.core.player.engine.datasource/PlatformStreamContext
 * Created: 2026/11/13
 */

import android.net.Uri

/**
 * 当前播放流的上下文。
 *
 * LX 音源脚本取流成功后会带回一组请求头（多数音源要求带 Referer 才能拉流），
 * 这些头必须跟着播放地址一起送到 DataSource，否则会出现「有地址却播不出声」。
 *
 * [url] 用来做匹配：只有当 DataSource 要请求的地址与当初解析出的地址一致时，
 * 才应用这组请求头，避免上一首的 Referer 串到下一首。
 */
data class PlatformStreamContext(
    /** 解析出这个上下文时的播放地址。 */
    val url: String = "",
    /** 音源脚本返回的请求头。 */
    val headers: Map<String, String> = emptyMap()
) {
    /**
     * 取这份上下文里适用于 [targetUri] 的请求头。
     *
     * 地址一致（忽略末尾斜杠）时返回全部请求头，否则返回空表。
     */
    fun headersFor(targetUri: Uri): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        val target = targetUri.toString()
        if (target == url) return headers
        // 去掉查询串再比一次：部分 CDN 会在跳转时补参数
        val targetBase = target.substringBefore('?')
        val urlBase = url.substringBefore('?')
        if (targetBase == urlBase) return headers
        return emptyMap()
    }

    companion object {
        /** 空上下文。 */
        val NONE: PlatformStreamContext = PlatformStreamContext()
    }
}
