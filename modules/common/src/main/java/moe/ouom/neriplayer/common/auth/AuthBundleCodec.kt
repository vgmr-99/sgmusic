package moe.ouom.neriplayer.common.auth

import org.json.JSONObject

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
 * File: moe.ouom.neriplayer.common.auth/AuthBundleCodec
 * Created: 2026/11/11
 */

/**
 * 账号凭据包的通用 JSON 编解码。
 *
 * 各平台的凭据包结构一致（cookies + savedAt），这里抽出共用逻辑，
 * 平台侧只需要提供构造与归一化逻辑，避免每个平台复制一份编解码实现。
 */
object AuthBundleCodec {

    private const val KEY_COOKIES = "cookies"
    private const val KEY_SAVED_AT = "savedAt"

    /**
     * 把 Cookie 与保存时间编码成 JSON。
     */
    fun encode(cookies: Map<String, String>, savedAt: Long): String {
        return JSONObject().apply {
            put(
                KEY_COOKIES,
                JSONObject().apply {
                    cookies.forEach { (key, value) -> put(key, value) }
                }
            )
            put(KEY_SAVED_AT, savedAt)
        }.toString()
    }

    /**
     * 从 JSON 还原 Cookie 与保存时间。
     *
     * 解析失败时返回空 Cookie 与 0 时间，由调用方决定如何兜底。
     */
    fun decode(json: String): DecodedAuthBundle {
        return runCatching {
            val root = JSONObject(json)
            val cookiesJson = root.optJSONObject(KEY_COOKIES) ?: JSONObject()
            val cookies = linkedMapOf<String, String>()
            val keys = cookiesJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                cookies[key] = cookiesJson.optString(key, "")
            }
            DecodedAuthBundle(cookies = cookies, savedAt = root.optLong(KEY_SAVED_AT, 0L))
        }.getOrDefault(DecodedAuthBundle())
    }
}

/** 解码出来的原始凭据内容。 */
data class DecodedAuthBundle(
    val cookies: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L
)
