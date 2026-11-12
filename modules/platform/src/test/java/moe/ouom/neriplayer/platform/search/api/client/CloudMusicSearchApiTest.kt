package moe.ouom.neriplayer.platform.search.api.client

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class CloudMusicSearchApiTest {
    private val netease = mock(NeteaseClient::class.java)
    private val requests = CopyOnWriteArrayList<Request>()
    private var lyricStatus = 200
    private var detailStatus = 200
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val detail = request.url.encodedPath.contains("detail")
        val body = if (detail) {
            """{"songs":[{"name":"Signal","artists":[{"name":"Artist"}],"album":{"name":"Album","picUrl":null}}]}"""
        } else {
            """{"lrc":{"lyric":"[00:01.000]line"},"tlyric":{"lyric":"[00:01.000]译文"}}"""
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(if (detail) detailStatus else lyricStatus).message("fixture")
            .body(body.toResponseBody()).build()
    }.build()

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `details use injected transport and read cookies for each request`() = runTest {
        `when`(netease.getCookies()).thenReturn(mapOf("fixture" to "one"), mapOf("fixture" to "two"))
        val result = CloudMusicSearchApi(netease, client, debugLogging = true).getSongInfo("12")
        assertEquals("Signal", result.songName)
        assertEquals("[00:01.000]line", result.lyric)
        assertEquals("[00:01.000]译文", result.translatedLyric)
        assertEquals(listOf("fixture=one", "fixture=two"), requests.map { it.header("Cookie") })
    }

    @Test
    fun `metadata only lookup sends one detail request without fetching lyrics`() = runTest {
        `when`(netease.getCookies()).thenReturn(emptyMap())

        val result = CloudMusicSearchApi(netease, client).getSongMetadataOnly("12")

        assertEquals("Signal", result.songName)
        assertNull(result.lyric)
        assertNull(result.translatedLyric)
        assertEquals(1, requests.size)
        assertEquals("/api/song/detail/", requests.single().url.encodedPath)
    }

    @Test
    fun `lyric failure preserves song details`() = runTest {
        `when`(netease.getCookies()).thenReturn(emptyMap())
        lyricStatus = 503
        val result = CloudMusicSearchApi(netease, client).getSongInfo("12")
        assertEquals("Signal", result.songName)
        assertNull(result.lyric)
        assertNull(result.translatedLyric)
    }

    @Test
    fun `detail failure propagates without lyric request`() {
        `when`(netease.getCookies()).thenReturn(emptyMap())
        detailStatus = 503
        assertThrows(IOException::class.java) {
            runTest { CloudMusicSearchApi(netease, client).getSongInfo("12") }
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `search uses the eapi channel when there is no login`() = runTest {
        val neteaseClient = NeteaseClient { error("no comment token in this test") }
        val neteaseRequests = mutableListOf<Request>()
        interceptNetease(neteaseClient) { neteaseRequests += it }

        val result = CloudMusicSearchApi(neteaseClient, client).search("song", 1)

        assertEquals(1, result.size)
        assertEquals("Signal", result.single().songName)
        assertEquals("Artist", result.single().singer)
        assertEquals("Album", result.single().albumName)
        assertEquals("http://img/cover.jpg", result.single().coverUrl)
        assertTrue(
            "匿名搜索应走 eapi 通道",
            neteaseRequests.any { it.url.encodedPath == "/eapi/cloudsearch/pc" }
        )
    }

    /**
     * 把 NeteaseClient 的真实会话换成离线拦截器：
     * eapi 路径返回搜索结果，其它路径返回 503。
     */
    private fun interceptNetease(client: NeteaseClient, onRequest: (Request) -> Unit) {
        val store = NeteaseClient::class.java.getDeclaredField("sessionStore").run {
            isAccessible = true
            get(client) as moe.ouom.neriplayer.platform.netease.api.client.NeteaseRequestSessionStore
        }
        val session = store.currentSession()
        val offlineClient = session.okHttpClient.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            onRequest(request)
            val eapi = request.url.encodedPath == "/eapi/cloudsearch/pc"
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (eapi) 200 else 503)
                .message(if (eapi) "OK" else "blocked")
                .body(
                    (
                        if (eapi) {
                            """{"result":{"songs":[{"id":7,"name":"Signal","dt":1000,"ar":[{"name":"Artist"}],"al":{"name":"Album","picUrl":"http://img/cover.jpg"}}]}}"""
                        } else {
                            """{"code":503}"""
                        }
                        ).toResponseBody()
                )
                .build()
        }.build()
        moe.ouom.neriplayer.platform.netease.api.client.NeteaseRequestSession::class.java
            .getDeclaredField("okHttpClient").apply {
                isAccessible = true
                set(session, offlineClient)
            }
    }
}
