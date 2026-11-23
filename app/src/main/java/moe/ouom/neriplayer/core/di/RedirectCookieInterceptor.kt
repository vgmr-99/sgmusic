package moe.ouom.neriplayer.core.di

import moe.ouom.neriplayer.common.logging.NPLogger
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale

/**
 * 处理 YouTube 搜索/引导请求的「跟随重定向 + 手动 Cookie」问题。
 *
 * 根因：共享客户端的认证拦截器在最外层只执行一次，注入的 Cookie 在整条重定向链里
 * 保持不变；重定向中服务器返回的 Set-Cookie 要等链结束才被合并，导致需要设置 Cookie
 * 才能继续的重定向无法满足 → 同一 URL 反复 302 → OkHttp 抛
 * `Too many follow-up requests: 21`。
 *
 * 方案：
 * - [Init] 作为应用拦截器（每 Call 一次）：把本次调用的独立状态塞进 request.tag，
 *   清空计数；这样每次 Call 互不干扰。
 * - [Hop] 作为网络拦截器（每跳一次）：把累积的 Set-Cookie 与请求自带 Cookie 合并写回
 *   Cookie 头（自带优先），收集本跳 Set-Cookie，记录 Location 链，超过上限则抛清晰异常。
 *
 * 只作用于 YouTube host（由 [isYouTubeHost] 判定），其它平台完全不受影响。
 */
internal class RedirectCookieInterceptor {

    internal class State {
        val cookies = LinkedHashMap<String, String>()
        var redirects = 0
        val chain = mutableListOf<String>()
    }

    /** 应用拦截器：为每次 Call 初始化独立状态。 */
    inner class Init : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!isYouTubeHost(request.url.host.lowercase(Locale.ROOT))) {
                return chain.proceed(request)
            }
            val state = State()
            return chain.proceed(request.newBuilder().tag(State::class.java, state).build())
        }
    }

    /** 网络拦截器：每一跳重定向都会执行。 */
    inner class Hop(
        private val maxRedirects: Int = 8
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val host = request.url.host.lowercase(Locale.ROOT)
            if (!isYouTubeHost(host)) return chain.proceed(request)

            val state = request.tag(State::class.java) ?: return chain.proceed(request)

            // 请求前：仅当之前某跳收集到 Set-Cookie 时才改写 Cookie 头。
            // 首跳（state.cookies 为空）完全不碰请求头——认证拦截器注入的 Cookie 是精确
            // 构造的（含 CONSENT/SAPISID 等），重序列化可能破坏其值，进而影响取流/音质。
            // 这样首个 POST 原样透传，只在重定向的后续跳合并新 Cookie。
            val requestWithCookies = if (state.cookies.isNotEmpty()) {
                val merged = LinkedHashMap(state.cookies)
                parseCookieHeader(request.header("Cookie")).forEach { merged[it.key] = it.value }
                val mergedHeader = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
                if (mergedHeader.isNotBlank()) {
                    request.newBuilder().header("Cookie", mergedHeader).build()
                } else {
                    request
                }
            } else {
                request
            }

            val response = chain.proceed(requestWithCookies)

            // 响应后：收集本跳 Set-Cookie，供下一跳使用
            response.headers.values("Set-Cookie").forEach { raw ->
                parseCookieHeader(raw).forEach { (name, value) ->
                    if (value.isEmpty()) state.cookies.remove(name) else state.cookies[name] = value
                }
            }

            // 重定向诊断与上限
            val location = response.header("Location")
            if (location != null && response.code in 300..399) {
                state.redirects++
                state.chain.add("${response.code} ${request.url} -> $location")
                if (state.redirects <= 3) {
                    NPLogger.w("RedirectCookie", "redirect #${state.redirects}: ${request.url} -> $location")
                }
                if (state.redirects > maxRedirects) {
                    val trace = state.chain.joinToString("\n")
                    response.close()
                    NPLogger.e("RedirectCookie", "redirect loop terminated after $maxRedirects hops:\n$trace")
                    throw IOException("YouTube redirect loop after $maxRedirects hops (last: $location)")
                }
            }

            return response
        }
    }

    private fun parseCookieHeader(header: String?): Map<String, String> =
        parseRedirectCookieHeader(header)
}

/** 解析 Cookie / Set-Cookie 头中的 name=value，跳过属性段。 */
internal fun parseRedirectCookieHeader(header: String?): Map<String, String> {
    if (header.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    header.split(';').forEach { part ->
        val trimmed = part.trim()
        if (trimmed.isEmpty()) return@forEach
        val idx = trimmed.indexOf('=')
        if (idx <= 0) return@forEach
        val name = trimmed.substring(0, idx).trim()
        if (name.equals("Expires", true) || name.equals("Path", true) ||
            name.equals("Domain", true) || name.equals("Max-Age", true) ||
            name.equals("Secure", true) || name.equals("HttpOnly", true) ||
            name.equals("SameSite", true) || name.equals("Priority", true)
        ) return@forEach
        var value = trimmed.substring(idx + 1).trim()
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))
        ) {
            value = value.substring(1, value.length - 1)
        }
        out[name] = value
    }
    return out
}

/** 复用 app 内既有的 YouTube host 判定（在 AppContainer / RequestSupport 中使用）。 */
private fun isYouTubeHost(host: String): Boolean =
    host == "music.youtube.com" || host == "www.youtube.com" ||
        host == "youtube.com" || host == "m.youtube.com" ||
        host == "accounts.google.com" || host.endsWith(".youtube.com") ||
        host == "www.youtube-nocookie.com"
