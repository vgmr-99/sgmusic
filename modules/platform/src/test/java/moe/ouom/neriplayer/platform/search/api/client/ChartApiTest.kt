package moe.ouom.neriplayer.platform.search.api.client

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.search.api.ChartProvider
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartApiTest {
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
    fun `netease hot search parses the search word and the score`() = runTest {
        responseBody = """
            {"code":200,"data":[
              {"score":77222,"iconType":4,"searchWord":"趁早","iconUrl":"https://img/1.jpg"},
              {"score":55000,"searchWord":"孤勇者","iconUrl":""}
            ]}
        """.trimIndent()

        val entries = CloudMusicChartApi(client).hotSearch()

        assertEquals(2, entries.size)
        assertEquals("趁早", entries[0].title)
        assertEquals(77222L, entries[0].score)
        assertEquals("https://img/1.jpg", entries[0].coverUrl)
        assertEquals(ChartProvider.NETEASE, entries[0].source)
        assertEquals("孤勇者", entries[1].title)
        assertEquals(null, entries[1].coverUrl)
        assertEquals("/api/hotsearchlist/get", requests.single().url.encodedPath)
    }

    @Test
    fun `netease hot search honours the limit`() = runTest {
        responseBody = """
            {"code":200,"data":[
              {"score":3,"searchWord":"a"},{"score":2,"searchWord":"b"},{"score":1,"searchWord":"c"}
            ]}
        """.trimIndent()

        val entries = CloudMusicChartApi(client).hotSearch(limit = 2)

        assertEquals(2, entries.size)
        assertEquals(listOf("a", "b"), entries.map { it.title })
    }

    @Test
    fun `netease hot search tolerates an empty payload`() = runTest {
        responseBody = """{"code":200}"""

        assertTrue(CloudMusicChartApi(client).hotSearch().isEmpty())
    }

    @Test
    fun `qq hot search picks the hot song board`() = runTest {
        responseBody = """
            {"code":0,"data":{"topList":[
              {"id":4,"listenCount":7953220,"picUrl":"http://pic/1.jpg",
               "songList":[{"singername":"A","songname":"X"}],"topTitle":"巅峰榜·流行指数"},
              {"id":26,"listenCount":19400000,"picUrl":"http://pic/2.jpg",
               "songList":[{"singername":"B","songname":"Y"},{"singername":"C","songname":"Z"}],
               "topTitle":"巅峰榜·热歌"}
            ]}}
        """.trimIndent()

        val entries = QQMusicChartApi(client).hotSearch()

        assertEquals(2, entries.size)
        assertEquals("Y", entries[0].title)
        assertEquals("B", entries[0].subtitle)
        assertEquals(19400000L, entries[0].score)
        assertEquals("http://pic/2.jpg", entries[0].coverUrl)
        assertEquals(ChartProvider.QQ, entries[0].source)
        assertEquals("Z", entries[1].title)
        assertTrue(requests.single().url.encodedPath.contains("fcg_myqq_toplist"))
    }

    @Test
    fun `qq hot search falls back to the first board when there is no hot song board`() = runTest {
        responseBody = """
            {"code":0,"data":{"topList":[
              {"id":4,"listenCount":100,"picUrl":"http://pic/1.jpg",
               "songList":[{"singername":"A","songname":"X"}],"topTitle":"巅峰榜·流行指数"}
            ]}}
        """.trimIndent()

        val entries = QQMusicChartApi(client).hotSearch()

        assertEquals(1, entries.size)
        assertEquals("X", entries[0].title)
    }
}
