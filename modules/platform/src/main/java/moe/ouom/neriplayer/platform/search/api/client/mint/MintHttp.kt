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
 * File: moe.ouom.neriplayer.platform.search.api.client.mint/MintHttp
 * Created: 2026/11/13
 */

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Mint 数据源的 HTTP 参数。
 *
 * 默认值和 Mint 的 dio 客户端保持一致：UA 用移动端 Chrome、连接 15 秒、读取 30 秒、
 * 失败重试两次并按 1s / 2s 退避。
 *
 * @param allowInsecureTls 是否跳过 TLS 证书校验。
 *        默认关闭。开启后所有 HTTPS 请求都不再校验证书链，证书来源无法确认，
 *        中间人可以直接改写响应内容，所以只在明确知道对端是可信自签名源时打开。
 *        打开方式：构造本类时传 `allowInsecureTls = true`。
 */
data class MintHttpOptions(
    val userAgent: String = MintHttpOptions.DEFAULT_USER_AGENT,
    val connectTimeoutSeconds: Long = 15L,
    val readTimeoutSeconds: Long = 30L,
    val retryCount: Int = 2,
    val retryBackoffMillis: List<Long> = listOf(1000L, 2000L),
    val allowInsecureTls: Boolean = false
) {
    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
    }
}

/**
 * Mint 数据源的 HTTP 客户端。
 *
 * 只做三件事：按选项配置超时与 TLS、放上统一 UA、按 Mint 的语义重试。
 * 具体接口的拼装留给各平台实现。
 */
class MintHttp(
    options: MintHttpOptions = MintHttpOptions(),
    private val client: OkHttpClient? = null
) {

    private val options: MintHttpOptions = options

    private val httpClient: OkHttpClient = client ?: buildClient(options)

    /** 发起 GET 请求，失败返回 null。 */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String? =
        request(url, "GET", null, headers)

    /**
     * 发起表单 POST 请求，失败返回 null。
     *
     * 网易云的 weapi / eapi / linuxapi 都走这个形式。
     */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap()
    ): String? = request(url, "POST", encodeForm(form), headers)

    /** 发起 JSON POST 请求，失败返回 null。QQ 音乐的榜单详情走这个形式。 */
    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap()
    ): String? = request(url, "POST", json, headers, jsonBody = true)

    /**
     * 按 Mint 的重试语义执行一次请求。
     *
     * 第一次失败后等 1 秒重试，第二次还失败再等 2 秒重试，之后放弃。
     * HTTP 状态码错误（4xx / 5xx）同样算失败。
     */
    private suspend fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        jsonBody: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        var attempt = 0
        while (attempt <= options.retryCount) {
            // 不用 runCatching：它会连 CancellationException 一起吞掉，
            // 上游超时/切 tab 取消后重试循环还会继续跑。
            val text = try {
                execute(url, method, body, headers, jsonBody)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (text != null) return@withContext text
            if (attempt == options.retryCount) break
            val backoff = options.retryBackoffMillis.getOrElse(attempt) { 1000L * (attempt + 1) }
            kotlinx.coroutines.delay(backoff)
            attempt++
        }
        null
    }

    /** 把表单字段拼成 `application/x-www-form-urlencoded` 的请求体。 */
    private fun encodeForm(form: Map<String, String>): String =
        form.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    private fun execute(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        jsonBody: Boolean
    ): String? {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", options.userAgent)
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        when (method) {
            "POST" -> {
                val content = body.orEmpty()
                val mediaType: MediaType = if (jsonBody) {
                    "application/json; charset=utf-8".toMediaType()
                } else {
                    "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
                }
                val requestBody: RequestBody = content.toRequestBody(mediaType)
                requestBuilder.post(requestBody)
            }

            else -> requestBuilder.get()
        }

        return httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null
            if (bytes.isEmpty()) return@use null
            String(bytes, Charsets.UTF_8)
        }
    }

    private companion object {

        fun buildClient(options: MintHttpOptions): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(options.connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(options.readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(options.readTimeoutSeconds, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)

            if (options.allowInsecureTls) {
                applyInsecureTls(builder)
            }
            return builder.build()
        }

        /**
         * 装上不校验证书的 TLS 配置。
         *
         * 只在 [MintHttpOptions.allowInsecureTls] 打开时才会走到这里，
         * 默认路径不会引入任何信任放宽的行为。
         */
        private fun applyInsecureTls(builder: OkHttpClient.Builder) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
    }
}
