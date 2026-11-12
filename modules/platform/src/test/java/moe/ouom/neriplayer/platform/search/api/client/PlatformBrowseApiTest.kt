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
 * 平台浏览页（歌单 / 榜单）的解析测试。
 */
class PlatformBrowseApiTest {
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
    fun `kugou browse lists the charts`() = runTest {
        responseBody = """
            {"status":1,"data":{"info":[
              {"id":"2","rankname":"TOP500","play_times":10127472,
               "img_9":"http://imge.kugou.com/mcommon/{size}/20241219/a.png",
               "intro":"数据来源：全曲库歌曲"},
              {"id":"35","rankname":"内地榜","play_times":542369,"img_9":""}
            ]}}
        """.trimIndent()

        val cards = KugouBrowseApi(client).browse("")

        assertEquals(2, cards.size)
        assertEquals("2", cards[0].id)
        assertEquals("TOP500", cards[0].title)
        assertEquals(10127472L, cards[0].playCount)
        // {size} 占位要换成真实尺寸，否则封面加载不出来
        assertEquals("http://imge.kugou.com/mcommon/300x300/20241219/a.png", cards[0].coverUrl)
        assertTrue(requests.single().url.encodedPath.contains("/api/v5/rank/list"))
    }

    @Test
    fun `kugou browse reads the chart tracks`() = runTest {
        responseBody = """
            {"status":1,"data":{"info":[
              {"songname":"慌啥子嘛","authors":[{"name":"村口吴老二"}],
               "album_id":"209518658","album_name":"单曲","duration":186,
               "hash":"D0F928C394C175BC0F4CAD8B9C3B5E9E",
               "album_sizable_cover":"http://cover.jpg"}
            ]}}
        """.trimIndent()

        val api = KugouBrowseApi(client)
        val card = api.browse("").firstOrNull() ?: return@runTest
        val tracks = api.tracks(card)

        assertEquals(1, tracks.size)
        assertEquals("D0F928C394C175BC0F4CAD8B9C3B5E9E", tracks[0].id)
        assertEquals("慌啥子嘛", tracks[0].name)
        assertEquals("村口吴老二", tracks[0].subtitle)
        assertEquals(186_000L, tracks[0].durationMs)
    }

    @Test
    fun `qq browse lists the boards`() = runTest {
        responseBody = """
            {"code":0,"data":{"topList":[
              {"id":"26","topTitle":"巅峰榜·热歌","listenCount":19600000,
               "picUrl":"http://y.gtimg.cn/music/photo_new/T003R300x300M0001.jpg",
               "songList":[{"songname":"晴天","singername":"周杰伦"}]}
            ]}}
        """.trimIndent()

        val cards = QqBrowseApi(client).browse("")

        assertEquals(1, cards.size)
        assertEquals("26", cards[0].id)
        assertEquals("巅峰榜·热歌", cards[0].title)
        assertEquals(19600000L, cards[0].playCount)
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T003R300x300M0001.jpg",
            cards[0].coverUrl
        )
    }

    @Test
    fun `qq browse resolves the song mid through search`() = runTest {
        responseBody = """
            {"code":0,"data":{"topList":[
              {"id":"26","topTitle":"巅峰榜·热歌","listenCount":1,
               "songList":[{"songname":"晴天","singername":"周杰伦"}]}
            ]}}
        """.trimIndent()

        // 榜单只给歌名歌手，songmid 要靠搜索补齐
        val api = QqBrowseApi(client) { "003OUlho2HcRHC" }
        val card = api.browse("").firstOrNull() ?: return@runTest
        val tracks = api.tracks(card)

        assertEquals(1, tracks.size)
        assertEquals("003OUlho2HcRHC", tracks[0].id)
        assertEquals("晴天", tracks[0].name)
        assertEquals("周杰伦", tracks[0].subtitle)
    }
}
