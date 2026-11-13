package moe.ouom.neriplayer.platform.lxsource

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
 * 免登录搜索与浏览的定位测试。
 *
 * 检测要覆盖"平台 × 音质"，前提是能给每个平台找到真实存在的 songmid，
 * 这里用固定响应验证各平台响应字段的解析是否正确。
 */
class LxMusicInfoLocatorTest {
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
    fun `netease locator reads the song id`() = runTest {
        val raw = """
            {"code":200,"result":{"songs":[
              {"id":186016,"name":"晴天","dt":269000,
               "ar":[{"name":"周杰伦"}],"al":{"name":"叶惠美","picUrl":"https://p1/cover.jpg"}}
            ]}}
        """.trimIndent()

        val info = LxMusicInfoLocator(client, neteaseSearch = { raw })
            .locate(LxPlatform.WY, "晴天 周杰伦")

        assertEquals("186016", info?.songmid)
        assertEquals("晴天", info?.name)
        assertEquals("周杰伦", info?.singer)
        assertEquals("叶惠美", info?.albumName)
        assertEquals(269_000L, info?.durationMs)
        assertEquals("wy_186016", info?.id)
    }

    @Test
    fun `qq locator reads the song mid and album mid`() = runTest {
        responseBody = """
            {"code":0,"data":{"song":{"list":[
              {"songmid":"003OUlho2HcRHC","songname":"晴天","interval":269,
               "albummid":"002rODniR2x1ez","albumname":"叶惠美","singer":[{"name":"周杰伦"}]}
            ]}}}
        """.trimIndent()

        val info = LxMusicInfoLocator(client).locate(LxPlatform.TX, "晴天 周杰伦")

        assertEquals("003OUlho2HcRHC", info?.songmid)
        assertEquals("003OUlho2HcRHC", info?.extras?.get("strMediaMid"))
        assertEquals("002rODniR2x1ez", info?.extras?.get("albumMid"))
    }

    @Test
    fun `kugou locator keeps one hash per quality`() = runTest {
        responseBody = """
            {"status":1,"data":{"lists":[
              {"SongName":"晴天","SingerName":"周杰伦","AlbumName":"叶惠美","AlbumID":"966846",
               "Audioid":"20505418","Duration":269,
               "FileHash":"HASH128","SQFileHash":"HASH320","HQFileHash":"HASHFLAC"}
            ]}}
        """.trimIndent()

        val locator = LxMusicInfoLocator(client)
        val info = locator.locate(LxPlatform.KG, "晴天 周杰伦")
        assertTrue("应定位到歌曲", info != null)
        info ?: return@runTest

        assertEquals("HASH128", info.songmid)
        assertEquals("HASH128", info.extras["hash"])
        assertEquals("HASH320", info.qualityHashes[LxQuality.Q320])
        assertEquals("HASHFLAC", info.qualityHashes[LxQuality.FLAC])
        // 换成 320k 时 songmid/hash 都要跟着换，否则脚本会拿 128k 的 hash 去要高音质
        val flac = info.withQuality(LxQuality.FLAC)
        assertEquals("HASHFLAC", flac.songmid)
        assertEquals("HASHFLAC", flac.extras["hash"])
    }

    @Test
    fun `kuwo locator reads the target id from the js literal`() = runTest {
        responseBody = "{'abslist':[{'DC_TARGETID':'51685512','NAME':'晴天&nbsp;(KTV版伴奏)'," +
            "'ARTIST':'周杰伦','DURATION':'269'}]}"

        val info = LxMusicInfoLocator(client).locate(LxPlatform.KW, "晴天 周杰伦")

        assertEquals("51685512", info?.songmid)
        assertEquals("晴天 (KTV版伴奏)", info?.name)
        assertEquals("周杰伦", info?.singer)
        assertEquals("51685512", info?.extras?.get("rid"))
    }

    @Test
    fun `qishui locator reads the track id`() = runTest {
        responseBody = """
            {"result_groups":[{"id":"tracks","data":[{"entity":{"track":{
              "id":"7676337516358617115","name":"晴天","duration":237336,
              "album":{"name":"叶惠美"},"artists":[{"name":"晚风纯音"}]
            }}}]}]}
        """.trimIndent()

        val info = LxMusicInfoLocator(client).locate(LxPlatform.QS, "晴天 周杰伦")

        assertEquals("7676337516358617115", info?.songmid)
        assertEquals("晚风纯音", info?.singer)
    }

    @Test
    fun `platforms without an anonymous search are not testable`() = runTest {
        val locator = LxMusicInfoLocator(client)

        assertNull(locator.locate(LxPlatform.MG, "晴天"))
        assertNull(locator.locate(LxPlatform.LOCAL, "晴天"))
    }

    @Test
    fun `a blank keyword never resolves a song`() = runTest {
        assertNull(LxMusicInfoLocator(client).locate(LxPlatform.TX, "   "))
    }
}
