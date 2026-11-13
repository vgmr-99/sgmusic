package moe.ouom.neriplayer.platform.search.api.client

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷我搜索的解析测试。
 *
 * 酷我的 r.s 返回的是单引号 JS 字面量，不是标准 JSON，这里确认能正确还原。
 */
class KuwoSearchApiTest {
    private val requests = CopyOnWriteArrayList<Request>()
    private var responseBody = "{}"
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("fixture")
            .body(responseBody.toResponseBody())
            .build()
    }.build()

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `songs are parsed from the single quote literal`() = runTest {
        responseBody = "{'abslist':[{'DC_TARGETID':'51685512','NAME':'晴天'," +
            "'ARTIST':'周杰伦','ALBUM':'叶惠美','DURATION':'269','MINFO':'x'}]}"

        val results = KuwoSearchApi(client).search(
            keyword = "晴天 周杰伦",
            type = moe.ouom.neriplayer.platform.search.api.PlatformSearchType.SONG,
            page = 1
        )

        assertEquals(1, results.size)
        val song = results.first()
        assertEquals("51685512", song.id)
        assertEquals("晴天", song.name)
        assertEquals("周杰伦", song.subtitle)
        assertEquals("叶惠美", song.album)
        assertEquals(269_000L, song.durationMs)
        assertEquals("51685512", song.extras["rid"])
        assertTrue(requests.single().url.host.contains("kuwo.cn"))
    }

    @Test
    fun `a blank keyword never hits the network`() = runTest {
        val results = KuwoSearchApi(client).search(
            keyword = "   ",
            type = moe.ouom.neriplayer.platform.search.api.PlatformSearchType.SONG,
            page = 1
        )

        assertTrue(results.isEmpty())
        assertEquals(0, requests.size)
    }
}
