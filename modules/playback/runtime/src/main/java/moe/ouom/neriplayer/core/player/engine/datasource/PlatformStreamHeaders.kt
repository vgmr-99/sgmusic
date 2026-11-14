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
 * File: moe.ouom.neriplayer.core.player.engine.datasource/PlatformStreamHeaders
 * Created: 2026/11/13
 */

import android.net.Uri

/**
 * 音乐平台拉流请求头规则。
 *
 * 各家平台的音频直链大多要求带 Referer，否则返回 403 或者拿到一段几秒的试听提示音。
 * 这里按 URL 内容（不是按 host 白名单）匹配，理由是同一家平台的 CDN 域名很多，
 * 而且脚本返回的地址可能来自任何域名。
 *
 * 规则与 Mint-Music 的 audio_handler 保持一致，避免同一首歌在两个应用里表现不同。
 */
internal object PlatformStreamHeaders {

    /** 默认 UA，模拟桌面浏览器，避免部分 CDN 拒绝移动端 UA。 */
    const val DEFAULT_USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /**
     * 按 URL 推断 Referer。
     *
     * @return Referer 值；无法判断平台时返回 null，表示不加 Referer。
     */
    fun refererFor(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains("kuwo") || lower.contains("kugou") -> "https://www.kuwo.cn/"
            lower.contains("migu") -> "https://music.migu.cn/"
            lower.contains("qq.com") || lower.contains("qqmusic") -> "https://y.qq.com/"
            lower.contains("163") || lower.contains("netease") -> "https://music.163.com/"
            else -> null
        }
    }

    /**
     * 酷我车机 CDN 例外。
     *
     * 这些地址返回的是 11 秒左右的试听提示音而不是正片，
     * 而且加上任何请求头都会触发风控，必须原样裸请求。
     */
    fun isKuwoCarPreviewHost(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host.startsWith("car-") && host.endsWith(".kuwo.cn")
    }

    /**
     * 给音乐平台的拉流请求补齐必要请求头。
     *
     * @param original 已有请求头（DataSpec 上带的）
     * @param scriptHeaders 音源脚本返回的请求头，优先级最高
     * @return 合并后的请求头
     */
    fun buildMusicStreamHeaders(
        uri: Uri,
        original: Map<String, String>,
        scriptHeaders: Map<String, String>
    ): Map<String, String> {
        if (isKuwoCarPreviewHost(uri)) {
            // 试听提示音：不加任何头，加了反而会被拦
            return original
        }
        val headers = LinkedHashMap<String, String>(original)
        val referer = refererFor(uri.toString())
        if (referer != null && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            headers["Referer"] = referer
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = DEFAULT_USER_AGENT
        }
        if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
            headers["Accept"] = "*/*"
        }
        if (headers.keys.none { it.equals("Accept-Encoding", ignoreCase = true) }) {
            // 明确用 identity，避免代理压缩后 Range 偏移算错
            headers["Accept-Encoding"] = "identity"
        }
        // 脚本返回的请求头优先级最高，覆盖上面的默认值
        scriptHeaders.forEach { (name, value) ->
            val existing = headers.keys.filter { it.equals(name, ignoreCase = true) }
            existing.forEach { headers.remove(it) }
            headers[name] = value
        }
        return headers
    }
}
