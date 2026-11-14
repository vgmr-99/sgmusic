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

import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 宿主代发的 HTTP（LX 音源脚本的 `lx.request` / `cerumusic.request` 走这里）。
 *
 * 契约对齐 Mint-Music 的 `js_engine_service.dart`（GPL-3.0），要点：
 *
 * 1. **任意 HTTP method**：洛雪脚本常用 POST/PUT/DELETE/PATCH，
 *    只支持 GET/POST 会让一半脚本取不到地址。
 * 2. **超时真实生效**：脚本传的 `timeout` 必须落到 OkHttp 的 callTimeout，
 *    否则脚本里的 `timeout: 3000` 形同虚设。
 * 3. **二进制安全**：QQ QRC 图片、音源自定义加密数据都是二进制，
 *    只给字符串会把内容写坏；这里同时给出 `bodyBase64` 与原始字节。
 * 4. **忽略 TLS 校验**：Mint 全局 `badCertificateCallback = true`，
 *    部分音源接口用的是自签/过期证书，不放宽就整条链路取不到流。
 * 5. **失败不抛异常**：网络异常返回 statusCode=0 + error，
 *    由 JS 侧转成「500 响应对象」，脚本的 `.then` 分支仍能走到。
 */
class LxHttpClient(
    private val client: OkHttpClient,
    /** 是否放行自签/过期证书（对齐 Mint 的 badCertificateCallback）。 */
    private val allowInsecureTls: Boolean = true,
) {

    data class Result(
        val statusCode: Int,
        val headers: Map<String, String> = emptyMap(),
        /** 响应正文（UTF-8 文本） */
        val body: String? = null,
        /** 响应正文的 base64（二进制安全） */
        val bodyBase64: String? = null,
        /** 响应正文的原始字节数 */
        val bodySize: Int = 0,
        /** 错误信息；非空表示请求没拿到正常响应 */
        val error: String? = null,
        val isTimeout: Boolean = false,
    )

    /** 实际使用的 client：需要忽略 TLS 时在 base 上派生一份（共享连接池）。 */
    private val effectiveClient: OkHttpClient = if (allowInsecureTls) {
        buildLenientClient(client)
    } else {
        client
    }

    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: Any? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        /** urlencoded 表单；与 body 同时存在时 form 优先 */
        form: Map<String, String>? = null,
        followRedirects: Boolean = true,
        maxRedirects: Int = 5,
    ): Result = withContext(Dispatchers.IO) {
        val verb = method.trim().uppercase().ifEmpty { "GET" }
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (!hasHeader(headers, "User-Agent")) {
            builder.header("User-Agent", DEFAULT_USER_AGENT)
        }

        val requestBody = buildBody(verb, headers, body, form)
        when (verb) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> builder.post(requestBody ?: emptyJsonBody())
            "PUT" -> builder.put(requestBody ?: emptyJsonBody())
            "PATCH" -> builder.patch(requestBody ?: emptyJsonBody())
            "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            "OPTIONS" -> builder.method("OPTIONS", requestBody)
            else -> builder.method(verb, requestBody ?: emptyJsonBody())
        }

        val callClient = try {
            effectiveClient.newBuilder()
                .callTimeout(timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS), TimeUnit.MILLISECONDS)
                .followRedirects(followRedirects)
                .followSslRedirects(followRedirects)
                .build()
        } catch (_: Exception) {
            effectiveClient
        }

        try {
            callClient.newCall(builder.build()).execute().use { resp ->
                val bytes = resp.body.bytes()
                Result(
                    statusCode = resp.code,
                    headers = resp.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() },
                    body = String(bytes, UTF_8),
                    bodyBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                    bodySize = bytes.size,
                )
            }
        } catch (e: IOException) {
            val timeout = e is java.io.InterruptedIOException ||
                e.message?.contains("timeout", ignoreCase = true) == true
            Result(
                statusCode = 0,
                error = e.message ?: "network_error",
                isTimeout = timeout,
            )
        } catch (e: Exception) {
            Result(statusCode = 0, error = e.message ?: "network_error")
        }
    }

    private fun buildBody(
        verb: String,
        headers: Map<String, String>,
        body: Any?,
        form: Map<String, String>?,
    ): okhttp3.RequestBody? {
        if (verb == "GET" || verb == "HEAD") return null
        if (form != null) {
            val formBody = FormBody.Builder().apply {
                form.forEach { (k, v) -> add(k, v) }
            }.build()
            return formBody
        }
        if (body == null) return null
        val contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
        val mediaType = (contentType ?: "application/json; charset=utf-8").toMediaTypeOrNull()
            ?: "application/json; charset=utf-8".toMediaTypeOrNull()
        val text = when (body) {
            is JSONObject, is JSONArray -> body.toString()
            is ByteArray -> return body.toRequestBody(mediaType)
            else -> body.toString()
        }
        return text.toRequestBody(mediaType)
    }

    private fun emptyJsonBody() =
        "{}".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

    private fun hasHeader(headers: Map<String, String>, name: String): Boolean =
        headers.keys.any { it.equals(name, ignoreCase = true) }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val MIN_TIMEOUT_MS = 1_000L
        private const val MAX_TIMEOUT_MS = 120_000L
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) NeriPlayer/LxSource"

        /**
         * 派生一份不校验证书的 client。
         *
         * 与 Mint-Music 的 `HttpClient()..badCertificateCallback = (_,_,_) => true` 等价：
         * 音源脚本会打到自签/过期证书的接口，不放宽就整条取流链路直接失败。
         * 连接池与调度器沿用 base client，不会额外占用连接。
         */
        private fun buildLenientClient(base: OkHttpClient): OkHttpClient = try {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            base.newBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier(HostnameVerifier { _, _ -> true })
                .build()
        } catch (_: Exception) {
            base
        }
    }
}
