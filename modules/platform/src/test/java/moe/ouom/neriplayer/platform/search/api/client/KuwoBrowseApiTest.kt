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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷我浏览页的解析测试。
 */
class KuwoBrowseApiTest {
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
    fun `hot playlists parse the recommendation list`() = runTest {
        responseBody = """
            {"code":200,"data":{"total":1754,"data":[
              {"id":"1001","digest":"abc","name":"伤感歌单","desc":"emo","img":"http://img.jpg",
               "total":42,"listencnt":"1720万","uname":"某用户"}
            ]}}
        """.trimIndent()

        val cards = KuwoBrowseApi(client).browse("热门歌单")

        assertEquals(1, cards.size)
        val card = cards.first()
        assertEquals("kw_digest-abc__1001", card.id)
        assertEquals("伤感歌单", card.title)
        assertEquals("emo", card.subtitle)
        assertEquals("http://img.jpg", card.coverUrl)
        assertEquals(42, card.trackCount)
        assertEquals(17_200_000L, card.playCount)
    }

    @Test
    fun `a category is queried with its numeric tag id`() = runTest {
        responseBody = """
            {"code":200,"data":{"total":1,"data":[
              {"id":"2002","digest":"zz","name":"短视频精选","img":"","total":10,"listencnt":"1.2万","uname":"u"}
            ]}}
        """.trimIndent()

        val cards = KuwoBrowseApi(client).browse("短视频")
        assertEquals(1, cards.size)
        assertEquals("kw_digest-zz__2002", cards.first().id)
        assertEquals(12_000L, cards.first().playCount)
        // 接口只认数字 tag id，中文名会被判为参数错误
        assertEquals("2189", requests.single().url.queryParameter("id"))
    }

    @Test
    fun `the hot category does not send a tag id`() = runTest {
        responseBody = """
            {"code":200,"data":{"total":1,"data":[
              {"id":"1001","digest":"abc","name":"伤感歌单","img":"","total":1,"listencnt":"1"}
            ]}}
        """.trimIndent()

        KuwoBrowseApi(client).browse("热门歌单")
        assertNull(requests.single().url.queryParameter("id"))
    }

    @Test
    fun `leaderboards read the chart tree`() = runTest {
        responseBody = """
            {"id":"2","name":"排行榜","child":[
              {"sourceid":"16","name":"热歌榜","pic":"http://pic.jpg","listen":"19600000","info":"每天更新"},
              {"sourceid":"93","name":"飙升榜","pic":"","listen":"10127472","info":""}
            ]}
        """.trimIndent()

        val cards = KuwoBrowseApi(client).browse("排行榜")

        assertEquals(2, cards.size)
        assertEquals("kw_16", cards[0].id)
        assertEquals("热歌榜", cards[0].title)
        assertEquals(19_600_000L, cards[0].playCount)
        assertTrue(requests.single().url.encodedPath.contains("/q.k"))
    }

    @Test
    fun `leaderboard tracks are read from the song list`() = runTest {
        responseBody = """
            {"musiclist":[
              {"id":"51685512","name":"晴天","artist":"周杰伦","album":"叶惠美",
               "duration":269,"pic":"http://cover.jpg"}
            ]}
        """.trimIndent()

        val api = KuwoBrowseApi(client)
        // 榜单卡片来自另一个接口，这里直接构造一张榜单卡片
        val card = moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard(
            id = "kw_16",
            title = "热歌榜",
            kind = moe.ouom.neriplayer.platform.search.api.PlatformBrowseKind.CHART,
            provider = moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider.KUWO
        )
        val tracks = api.tracks(card)

        assertEquals(1, tracks.size)
        assertEquals("51685512", tracks[0].id)
        assertEquals("晴天", tracks[0].name)
        assertEquals("周杰伦", tracks[0].subtitle)
        assertEquals(269_000L, tracks[0].durationMs)
        assertEquals("51685512", tracks[0].extras["rid"])
    }
}
