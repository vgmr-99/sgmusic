package moe.ouom.neriplayer.platform.search.api.client.mint

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
 * File: moe.ouom.neriplayer.platform.search.api.client.mint/MiniJson
 * Created: 2026/11/13
 */

import org.json.JSONArray
import org.json.JSONObject

/**
 * 保序 JSON 编码器。
 *
 * 网易云的 weapi / linuxapi / eapi 都要求请求体是一段「键序固定」的 JSON 字符串：
 * eapi 的签名就是这段字符串的 MD5，键序变了签名就对不上；
 * weapi / linuxapi 虽然只传密文，但服务端解析失败会直接返回业务错误码。
 * Kotlin 自带的序列化库虽然能保序，但没法表达「只输出这几个键、且顺序固定」，
 * 所以这里手写一个最小编码器：入参用 LinkedHashMap 承载，输出即插入顺序。
 *
 * 输出与 Dart 的 `jsonEncode` 对齐：
 * - 字符串只转义 `"` `\` 与控制字符，中文等非 ASCII 字符原样输出；
 * - 整数按整数输出，小数带 `.0`；
 * - `null` / `true` / `false` 与 Dart 一致。
 */
internal object MiniJson {

    /** 编码任意值。 */
    fun encode(value: Any?): String {
        val builder = StringBuilder()
        writeValue(builder, value)
        return builder.toString()
    }

    private fun writeValue(builder: StringBuilder, value: Any?) {
        when (value) {
            null -> builder.append("null")
            is Boolean -> builder.append(if (value) "true" else "false")
            is String -> writeString(builder, value)
            is Number -> writeNumber(builder, value)
            is Map<*, *> -> writeObject(builder, value)
            is Iterable<*> -> writeArray(builder, value)
            is Array<*> -> writeArray(builder, value.asList())
            else -> writeString(builder, value.toString())
        }
    }

    private fun writeObject(builder: StringBuilder, map: Map<*, *>) {
        builder.append('{')
        var first = true
        for (entry in map) {
            if (!first) builder.append(',')
            first = false
            writeString(builder, entry.key.toString())
            builder.append(':')
            writeValue(builder, entry.value)
        }
        builder.append('}')
    }

    private fun writeArray(builder: StringBuilder, values: Iterable<*>) {
        builder.append('[')
        var first = true
        for (value in values) {
            if (!first) builder.append(',')
            first = false
            writeValue(builder, value)
        }
        builder.append(']')
    }

    private fun writeNumber(builder: StringBuilder, value: Number) {
        val double = value.toDouble()
        if (!double.isFinite()) {
            builder.append("null")
            return
        }
        val asLong = double.toLong()
        if (asLong.toDouble() == double && value !is Double) {
            builder.append(asLong.toString())
        } else if (asLong.toDouble() == double) {
            builder.append(asLong.toString()).append(".0")
        } else {
            builder.append(double.toString())
        }
    }

    private fun writeString(builder: StringBuilder, value: String) {
        builder.append('"')
        for (char in value) {
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> {
                    if (char < ' ') {
                        builder.append("\\u")
                        builder.append(HEX[(char.code shr 12) and 0x0f])
                        builder.append(HEX[(char.code shr 8) and 0x0f])
                        builder.append(HEX[(char.code shr 4) and 0x0f])
                        builder.append(HEX[char.code and 0x0f])
                    } else {
                        builder.append(char)
                    }
                }
            }
        }
        builder.append('"')
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * 读 JSON 的小工具。
 *
 * 各家接口的响应结构很随意：同一个字段有时在顶层，有时裹在 `data` 里，
 * 有时又裹在 `result` 里。这里统一「顶层优先、找不到再往下翻一层」的取法，
 * 免得每个平台都写一遍 fallback。
 */
internal object MintJson {

    /** 解析文本为 JSON 对象，失败返回 null。 */
    fun parseObject(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /** 解析文本为 JSON 数组，失败返回 null。 */
    fun parseArray(text: String?): JSONArray? {
        if (text.isNullOrBlank()) return null
        return runCatching { JSONArray(text) }.getOrNull()
    }

    /** 取数组：顶层优先，再看 `data` / `result` 下的同名字段。 */
    fun array(root: JSONObject?, name: String): JSONArray? {
        if (root == null) return null
        root.optJSONArray(name)?.let { return it }
        for (wrapper in WRAPPERS) {
            root.optJSONObject(wrapper)?.optJSONArray(name)?.let { return it }
        }
        return null
    }

    /** 取对象：顶层优先，再看 `data` / `result` 下的同名字段。 */
    fun obj(root: JSONObject?, name: String): JSONObject? {
        if (root == null) return null
        root.optJSONObject(name)?.let { return it }
        for (wrapper in WRAPPERS) {
            root.optJSONObject(wrapper)?.optJSONObject(name)?.let { return it }
        }
        return null
    }

    /** 取字符串：顶层优先；空串视作没取到。 */
    fun string(root: JSONObject?, name: String): String? {
        if (root == null) return null
        val direct = root.optString(name, "").trim()
        if (direct.isNotEmpty()) return direct
        for (wrapper in WRAPPERS) {
            val nested = root.optJSONObject(wrapper)?.optString(name, "")?.trim().orEmpty()
            if (nested.isNotEmpty()) return nested
        }
        return null
    }

    /** 取整数。 */
    fun int(root: JSONObject?, name: String, fallback: Int = 0): Int {
        if (root == null) return fallback
        val direct = root.optInt(name, Int.MIN_VALUE)
        if (direct != Int.MIN_VALUE) return direct
        for (wrapper in WRAPPERS) {
            val nested = root.optJSONObject(wrapper)?.optInt(name, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (nested != Int.MIN_VALUE) return nested
        }
        return fallback
    }

    /** 取长整数。 */
    fun long(root: JSONObject?, name: String, fallback: Long = 0L): Long {
        if (root == null) return fallback
        val direct = root.optLong(name, Long.MIN_VALUE)
        if (direct != Long.MIN_VALUE) return direct
        for (wrapper in WRAPPERS) {
            val nested = root.optJSONObject(wrapper)?.optLong(name, Long.MIN_VALUE) ?: Long.MIN_VALUE
            if (nested != Long.MIN_VALUE) return nested
        }
        return fallback
    }

    /** 依次尝试若干个字段名，返回第一个非空的。 */
    fun stringOf(root: JSONObject?, vararg names: String): String? {
        for (name in names) {
            string(root, name)?.let { return it }
        }
        return null
    }

    private val WRAPPERS = arrayOf("data", "result", "body")
}
