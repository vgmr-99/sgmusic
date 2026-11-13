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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 宿主代发的 HTTP：
 * - 脚本里调 lx.request，实际走共享 OkHttpClient，绕过 Web CORS
 * - 对齐 lx-music-mobile 的默认 UA / Content-Type / JSON 规则（见 walkman-tv ScriptHttpClient 思路）
 * - 生产应复用 AppContainer.sharedOkHttpClient（经 PlayerRepositoryDependencies.sharedOkHttpClient 注入）
 */
class LxHttpClient(private val client: OkHttpClient) {

    data class Result(
        val statusCode: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val error: String? = null,
    )

    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: Any? = null,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 14) NeriPlayer/LxSource")
            val text = when (body) {
                null -> null
                is JSONObject, is JSONArray -> body.toString()
                else -> body.toString()
            }
            when (method.uppercase()) {
                "POST" -> {
                    val reqBody = text?.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                    builder.post(reqBody ?: "".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                }
                else -> builder.get()
            }
            client.newCall(builder.build()).execute().use { resp ->
                Result(
                    statusCode = resp.code,
                    headers = resp.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() },
                    body = resp.body.string(),
                )
            }
        } catch (e: Exception) {
            Result(statusCode = 0, error = e.message ?: "network_error")
        }
    }
}
