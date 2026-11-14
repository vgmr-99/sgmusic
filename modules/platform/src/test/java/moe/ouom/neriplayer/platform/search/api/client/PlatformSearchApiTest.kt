package moe.ouom.neriplayer.platform.search.api.client

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.search.api.PlatformSearchType
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
 * 免登录搜索与浏览的解析测试。
 */
class PlatformSearchApiTest {
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
    fun `qq song search maps the id, artist and cover`() = runTest {
        responseBody = """
            {"code":0,"data":{"song":{"list":[
              {"songmid":"003OUlho2HcRHC","songname":"晴天","interval":269,
               "albumname":"叶惠美","albummid":"002rODniR2x1ez",
               "singer":[{"name":"周杰伦"},{"name":"费玉清"}]}
            ]}}}
        """.trimIndent()

        val results = QQPlatformSearchApi(client).search("晴天", PlatformSearchType.SONG, 1)

        assertEquals(1, results.size)
        val song = results.single()
        assertEquals(PlatformSearchType.SONG, song.type)
        assertEquals("003OUlho2HcRHC", song.id)
        assertEquals("晴天", song.name)
        assertEquals("周杰伦/费玉清", song.subtitle)
        assertEquals("叶惠美", song.album)
        assertEquals(269000L, song.durationMs)
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R800x800M000002rODniR2x1ez.jpg",
            song.coverUrl
        )
        assertEquals("https://y.qq.com/n/ryqq/songDetail/003OUlho2HcRHC", song.webUrl)
        assertEquals("/soso/fcgi-bin/client_search_cp", requests.single().url.encodedPath)
    }

    @Test
    fun `qq only exposes the song category`() {
        assertEquals(
            setOf(PlatformSearchType.SONG),
            QQPlatformSearchApi(client).supportedTypes
        )
    }

    @Test
    fun `kugou song search reads the audio id and duration`() = runTest {
        responseBody = """
            {"status":1,"data":{"lists":[
              {"Audioid":"1B56126A8A03924F1DD066259C095CBC","SongName":"晴天",
               "SingerName":"周杰伦","AlbumName":"叶惠美","Duration":269,"Image":"http://img/1.jpg"}
            ]}}
        """.trimIndent()

        val results = KugouSearchApi(client).search("晴天", PlatformSearchType.SONG, 1)

        assertEquals(1, results.size)
        val song = results.single()
        assertEquals("1B56126A8A03924F1DD066259C095CBC", song.id)
        assertEquals("晴天", song.name)
        assertEquals("周杰伦", song.subtitle)
        assertEquals(269000L, song.durationMs)
        assertEquals("http://img/1.jpg", song.coverUrl)
        assertEquals("/song_search_v2", requests.single().url.encodedPath)
    }

    @Test
    fun `kugou playlist category maps albums`() = runTest {
        responseBody = """
            {"status":1,"data":{"lists":[
              {"albumid":"966846","albumname":"叶惠美","singer":"周杰伦",
               "img":"http://img/2.jpg","songcount":11}
            ]}}
        """.trimIndent()

        val results = KugouSearchApi(client).search("晴天", PlatformSearchType.PLAYLIST, 1)

        assertEquals(1, results.size)
        val album = results.single()
        assertEquals(PlatformSearchType.PLAYLIST, album.type)
        assertEquals("966846", album.id)
        assertEquals("叶惠美", album.name)
        assertEquals("周杰伦", album.subtitle)
        assertEquals(11L, album.extraCount)
        assertEquals("https://www.kugou.com/yy/album/966846/", album.webUrl)
        assertEquals("/album_search", requests.single().url.encodedPath)
    }

    @Test
    fun `kugou artist category maps singers`() = runTest {
        responseBody = """{"status":1,"data":[{"singername":"周杰伦","singerid":3520}]}"""

        val results = KugouSearchApi(client).search("周杰伦", PlatformSearchType.ARTIST, 1)

        assertEquals(1, results.size)
        val artist = results.single()
        assertEquals(PlatformSearchType.ARTIST, artist.type)
        assertEquals("3520", artist.id)
        assertEquals("周杰伦", artist.name)
        assertEquals("https://www.kugou.com/yy/singer/3520/", artist.webUrl)
        assertEquals("/api/v3/search/singer", requests.single().url.encodedPath)
    }

    @Test
    fun `qishui track search joins the artists and builds the cover url`() = runTest {
        responseBody = """
            {"status_info":{"log_id":"x"},"result_groups":[{"id":"tracks","next_cursor":"20","has_more":true,
             "data":[{"meta":{"item_type":"track"},"entity":{"track":{
               "id":"7676337516358617115","name":"晴天","duration":237336,
               "album":{"id":"1","name":"叶惠美","url_cover":{
                 "uri":"tos-cn-v-2774c002/oIAIAITAzvMIVFCeSAWEEgfGIeCGaCLtGqbBpj",
                 "urls":["https://p3-luna.douyinpic.com/img/"],
                 "template_prefix":"tplv-b829550vbb"}},
               "artists":[{"name":"晚风纯音"}]
             }}}]}]}
        """.trimIndent()

        val results = QishuiSearchApi(client).search("晴天", PlatformSearchType.SONG, 1)

        assertEquals(1, results.size)
        val track = results.single()
        assertEquals("7676337516358617115", track.id)
        assertEquals("晴天", track.name)
        assertEquals("晚风纯音", track.subtitle)
        assertEquals("叶惠美", track.album)
        assertEquals(237336L, track.durationMs)
        assertEquals(
            "https://p3-luna.douyinpic.com/img/tos-cn-v-2774c002/oIAIAITAzvMIVFCeSAWEEgfGIeCGaCLtGqbBpj" +
                "~tplv-b829550vbb-image.image",
            track.coverUrl
        )
        assertEquals("/luna/search/track", requests.single().url.encodedPath)
    }

    @Test
    fun `qishui playlist and artist results carry their own page url`() = runTest {
        responseBody = """
            {"result_groups":[{"id":"playlists","data":[{"meta":{"item_type":"playlist"},
             "entity":{"playlist":{"id":"7624726859628183552","title":"晴天歌单",
               "owner":{"nickname":"某用户"},"count_tracks":30,
               "url_cover":{"uri":"u1","urls":["https://p3-luna.douyinpic.com/img/"],"template_prefix":"tplv-x"}}}}]}]}
        """.trimIndent()

        val playlists = QishuiSearchApi(client).search("晴天", PlatformSearchType.PLAYLIST, 1)

        assertEquals(1, playlists.size)
        assertEquals("7624726859628183552", playlists.single().id)
        assertEquals("某用户", playlists.single().subtitle)
        assertEquals(30L, playlists.single().extraCount)
        assertEquals(
            "https://www.qishui.com/playlist/7624726859628183552",
            playlists.single().webUrl
        )
    }

    @Test
    fun `platform search tolerates an empty payload`() = runTest {
        responseBody = """{"result_groups":[]}"""

        assertTrue(QishuiSearchApi(client).search("晴天", PlatformSearchType.SONG, 1).isEmpty())
    }

    @Test
    fun `kugou chart maps the rank entries`() = runTest {
        responseBody = """
            {"data":{"info":[
              {"songname":"慌啥子嘛","authors":[{"name":"村口吴老二"}],"rank_count":17,
               "hash":"D0F928C394C175BC0F4CAD8B9C3B5E9E"}
            ]}}
        """.trimIndent()

        val entries = KugouChartApi(client).hotSearch()

        assertEquals(1, entries.size)
        assertEquals("慌啥子嘛", entries.single().title)
        assertEquals("村口吴老二", entries.single().subtitle)
        assertEquals(17L, entries.single().score)
        assertEquals(moe.ouom.neriplayer.platform.search.api.ChartProvider.KUGOU, entries.single().source)
        assertEquals("/api/v5/rank/song", requests.single().url.encodedPath)
    }

    @Test
    fun `kugou chart tolerates a payload without rank info`() = runTest {
        responseBody = """{"data":{}}"""

        assertTrue(KugouChartApi(client).hotSearch().isEmpty())
        assertEquals("8888", requests.single().url.queryParameter("rankid"))
    }
}
