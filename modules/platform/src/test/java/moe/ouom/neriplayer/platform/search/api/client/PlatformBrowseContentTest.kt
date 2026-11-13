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
 * 酷狗 / QQ / 汽水 / 咪咕 浏览页的解析测试。
 */
class PlatformBrowseContentTest {
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
    fun `kugou special playlists are parsed`() = runTest {
        responseBody = """
            {"status":1,"special_db":[
              {"specialid":"3339907","specialname":"乡村之旅","intro":"安静惬意",
               "img":"http://c1.kgimg.com/custom/240/x.jpg","song_count":30,
               "total_play_count":"1720万","nickname":"董若"}
            ]}
        """.trimIndent()

        val cards = KugouBrowseApi(client).browse("热门歌单")

        assertEquals(1, cards.size)
        val card = cards.first()
        assertEquals("kg_3339907", card.id)
        assertEquals("乡村之旅", card.title)
        assertEquals(30, card.trackCount)
        assertEquals(17_200_000L, card.playCount)
        assertTrue(requests.single().url.encodedPath.contains("getSpecial"))
    }

    @Test
    fun `kugou chart tracks carry the hash extra`() = runTest {
        responseBody = """
            {"status":1,"data":{"info":[
              {"songname":"慌啥子嘛","authors":[{"name":"村口吴老二"}],"album_id":"209518658",
               "album_name":"单曲","duration":186,"hash":"D0F928C394C175BC0F4CAD8B9C3B5E9E",
               "album_sizable_cover":"http://cover.jpg"}
            ]}}
        """.trimIndent()

        val api = KugouBrowseApi(client)
        // 榜单卡片来自榜单列表接口，这里直接构造
        val card = moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard(
            id = "kg_8888",
            title = "TOP500",
            kind = moe.ouom.neriplayer.platform.search.api.PlatformBrowseKind.CHART,
            provider = moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider.KUGOU
        )
        val tracks = api.tracks(card)

        assertEquals(1, tracks.size)
        assertEquals("D0F928C394C175BC0F4CAD8B9C3B5E9E", tracks[0].id)
        assertEquals("村口吴老二", tracks[0].subtitle)
        assertEquals("D0F928C394C175BC0F4CAD8B9C3B5E9E", tracks[0].extras["hash"])
        assertEquals("209518658", tracks[0].extras["albumId"])
    }

    @Test
    fun `qq plaza playlists are parsed from v playlist`() = runTest {
        responseBody = """
            {"code":0,"playlist":{"code":0,"data":{"v_playlist":[
              {"tid":"7707261125","title":"甜度爆表","desc":"说唱",
               "cover_url_medium":"http://cover.jpg","song_ids":["1","2","3"],
               "access_num":"2333"}
            ]}}}
        """.trimIndent()

        val cards = QqBrowseApi(client).browse("热门歌单")

        assertEquals(1, cards.size)
        val card = cards.first()
        assertEquals("tx_7707261125", card.id)
        assertEquals("甜度爆表", card.title)
        assertEquals(3, card.trackCount)
        assertEquals(2333L, card.playCount)
    }

    @Test
    fun `qq category playlists are parsed from v item`() = runTest {
        responseBody = """
            {"code":0,"playlist":{"code":0,"data":{"content":{"v_item":[
              {"basic":{"tid":"3821289344","title":"短视频BGM","desc":"持续更新",
                "cover":{"medium_url":"http://cover.jpg"},"song_cnt":1981,"play_cnt":"1979788267",
                "creator":{"nick":"一番队队长"}}}
            ]}}}}
        """.trimIndent()

        val cards = QqBrowseApi(client).browse("短视频热歌")

        assertEquals(1, cards.size)
        assertEquals("tx_3821289344", cards.first().id)
        assertEquals(1981, cards.first().trackCount)
        assertEquals(1_979_788_267L, cards.first().playCount)
    }

    @Test
    fun `qq playlist tracks carry the str media mid extra`() = runTest {
        responseBody = """
            {"code":0,"cdlist":[{"songlist":[
              {"songmid":"003OUlho2HcRHC","songname":"晴天","singer":[{"name":"周杰伦"}],
               "albumname":"叶惠美","interval":269,"albummid":"002rODniR2x1ez"}
            ]}]}
        """.trimIndent()

        val api = QqBrowseApi(client)
        val tracks = api.tracks(
            moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard(
                id = "tx_123",
                title = "歌单",
                kind = moe.ouom.neriplayer.platform.search.api.PlatformBrowseKind.PLAYLIST,
                provider = moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider.QQ
            )
        )

        assertEquals(1, tracks.size)
        assertEquals("003OUlho2HcRHC", tracks[0].id)
        assertEquals("003OUlho2HcRHC", tracks[0].extras["strMediaMid"])
    }

    @Test
    fun `qishui playlists are parsed from the luna response`() = runTest {
        responseBody = """
            {"result_groups":[{"id":"playlists","data":[{"entity":{"playlist":{
              "id":"7429294603956600867","title":"伤感emo歌曲合集","count_tracks":75,
              "owner":{"nickname":"艾君子EK"},
              "url_cover":{"uri":"tos-cn-i-b829550vbb/9dafb8",
                "urls":["https://p3-luna.douyinpic.com/img/"],"template_prefix":"tplv-b829550vbb"}
            }}}]}]}
        """.trimIndent()

        val cards = QishuiBrowseApi(client).browse("伤感")

        assertEquals(1, cards.size)
        val card = cards.first()
        assertEquals("qs_7429294603956600867", card.id)
        assertEquals("伤感emo歌曲合集", card.title)
        assertEquals(75, card.trackCount)
        assertEquals(
            "https://www.qishui.com/playlist/7429294603956600867",
            card.webUrl
        )
    }

    @Test
    fun `migu playlist square marks the playlist nodes`() = runTest {
        responseBody = """
            {"code":"000000","data":{"contents":[
              {"columnId":"49937966","title":"场景歌单","view":"playlist_square_scene_zone",
               "contents":[{"resType":"2021","resId":"234168581","txt":"循环一整天",
                 "txt2":"抖音爆款","img":"https://d.musicapp.migu.cn/a.jpeg"}]}
            ]}}
        """.trimIndent()

        val cards = MiguBrowseApi(client).browse("热门歌单")

        assertEquals(1, cards.size)
        assertEquals("mg_234168581", cards.first().id)
        assertEquals("循环一整天", cards.first().title)
        assertEquals("抖音爆款", cards.first().subtitle)
    }

    @Test
    fun `migu chart tracks carry the copyright id extra`() = runTest {
        responseBody = """
            {"code":"000000","columnInfo":{"columnTitle":"热歌榜","contents":[
              {"objectInfo":{"copyrightId":"60054701923","songName":"晴天","singerName":"周杰伦",
                "albumName":"叶惠美","duration":269,"img":"http://cover.jpg"}}
            ]}}
        """.trimIndent()

        val api = MiguBrowseApi(client)
        val tracks = api.tracks(
            moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard(
                id = "mg_27186466",
                title = "热歌榜",
                kind = moe.ouom.neriplayer.platform.search.api.PlatformBrowseKind.CHART,
                provider = moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider.MIGU
            )
        )

        assertEquals(1, tracks.size)
        assertEquals("60054701923", tracks[0].id)
        assertEquals("晴天", tracks[0].name)
        assertEquals("60054701923", tracks[0].extras["copyrightId"])
    }
}
