package moe.ouom.neriplayer.platform.netease.api.client

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未登录时的网易云搜索必须能用，因此 WEAPI 请求的 csrf_token、
 * Cookie 里的 __csrf 和加密体里的 csrf 必须三方一致。
 */
class NeteaseAnonymousSearchTest {

    @Test
    fun `anonymous search aligns csrf token with the session cookie`() = runTest {
        val client = NeteaseClient { error("no comment token in this test") }
        val requests = mutableListOf<Request>()
        val session = intercept(client) { requests += it }

        client.searchSongsCancellable(
            keyword = "song",
            limit = 20,
            offset = 0,
            usePersistedCookies = false
        )

        val request = requests.single()
        val csrfToken = request.url.queryParameter("csrf_token")
        assertNotNull("csrf_token 不应缺失", csrfToken)
        assertTrue("csrf_token 不应为空串", csrfToken!!.isNotBlank())
        assertEquals(
            "csrf_token 必须与 CookieJar 里的 __csrf 一致",
            jarCookie(session, request, "__csrf"),
            csrfToken
        )
        assertEquals("pc", jarCookie(session, request, "os"))
    }

    @Test
    fun `signed in session prefers the server issued csrf`() = runTest {
        val client = NeteaseClient { error("no comment token in this test") }
        client.setPersistedCookies(mapOf("MUSIC_U" to "account-token", "__csrf" to "server-csrf"))
        val requests = mutableListOf<Request>()
        val session = intercept(client) { requests += it }

        client.searchSongsCancellable(
            keyword = "song",
            limit = 20,
            offset = 0,
            usePersistedCookies = false
        )

        val request = requests.single()
        assertEquals("server-csrf", request.url.queryParameter("csrf_token"))
        assertEquals("server-csrf", jarCookie(session, request, "__csrf"))
    }

    @Test
    fun `eapi search endpoint needs no csrf token`() = runTest {
        val client = NeteaseClient { error("no comment token in this test") }
        val requests = mutableListOf<Request>()
        intercept(client) { requests += it }

        client.searchSongsEapiCancellable(keyword = "song", limit = 20, offset = 0)

        val request = requests.single()
        assertEquals("/eapi/cloudsearch/pc", request.url.encodedPath)
        assertEquals("interface3.music.163.com", request.url.host)
        assertNull(request.url.queryParameter("csrf_token"))
    }

    /** 取会话 CookieJar 真正会写进请求头的 Cookie 值 */
    private fun jarCookie(session: NeteaseRequestSession, request: Request, name: String): String? =
        session.cookieJar.loadForRequest(request.url)
            .firstOrNull { it.name == name }
            ?.value

    private fun intercept(client: NeteaseClient, onRequest: (Request) -> Unit): NeteaseRequestSession {
        val store = NeteaseClient::class.java.getDeclaredField("sessionStore").run {
            isAccessible = true
            get(client) as NeteaseRequestSessionStore
        }
        val session = store.currentSession()
        val offlineClient = session.okHttpClient.newBuilder().addInterceptor { chain ->
            onRequest(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"code":200,"result":{"songs":[]}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        NeteaseRequestSession::class.java.getDeclaredField("okHttpClient").apply {
            isAccessible = true
            set(session, offlineClient)
        }
        return session
    }
}
