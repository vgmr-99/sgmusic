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
 * 音质检测的测试歌曲定位测试。
 *
 * 检测要覆盖"平台 × 音质"，前提是能给每个平台找到一个真实存在的 songmid，
 * 这里用固定响应验证各平台响应字段的解析是否正确。
 */
class LxTestSongLocatorTest {
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
    fun `qq locator reads the song mid`() = runTest {
        responseBody = """
            {"code":0,"data":{"song":{"list":[
              {"songmid":"003OUlho2HcRHC","songname":"晴天","singer":[{"name":"周杰伦"}]}
            ]}}}
        """.trimIndent()

        val ref = LxTestSongLocator(client).locate(LxPlatform.TX, "晴天 周杰伦")

        assertEquals("003OUlho2HcRHC", ref?.songmid)
        assertEquals("晴天", ref?.title)
        assertEquals("周杰伦", ref?.artist)
    }

    @Test
    fun `kugou locator prefers the file hash`() = runTest {
        responseBody = """
            {"status":1,"data":{"lists":[
              {"SongName":"晴天","SingerName":"周杰伦","Audioid":"20505418",
               "FileHash":"B3A52A7A958BF0AED0EBFBA2E9A818B7"}
            ]}}
        """.trimIndent()

        val ref = LxTestSongLocator(client).locate(LxPlatform.KG, "晴天 周杰伦")

        assertEquals("B3A52A7A958BF0AED0EBFBA2E9A818B7", ref?.songmid)
        assertEquals("B3A52A7A958BF0AED0EBFBA2E9A818B7", ref?.hash)
        assertEquals("周杰伦", ref?.artist)
    }

    @Test
    fun `kuwo locator reads the target id from the js literal`() = runTest {
        responseBody = "{'abslist':[{'DC_TARGETID':'51685512','NAME':'晴天&nbsp;(KTV版伴奏)'," +
            "'ARTIST':'周杰伦','DURATION':'269'}]}"

        val ref = LxTestSongLocator(client).locate(LxPlatform.KW, "晴天 周杰伦")

        assertEquals("51685512", ref?.songmid)
        assertEquals("晴天 (KTV版伴奏)", ref?.title)
        assertEquals("周杰伦", ref?.artist)
    }

    @Test
    fun `qishui locator reads the track id`() = runTest {
        responseBody = """
            {"result_groups":[{"id":"tracks","data":[{"entity":{"track":{
              "id":"7676337516358617115","name":"晴天","artists":[{"name":"周杰伦"}]
            }}}]}]}
        """.trimIndent()

        val ref = LxTestSongLocator(client).locate(LxPlatform.QS, "晴天 周杰伦")

        assertEquals("7676337516358617115", ref?.songmid)
        assertEquals("周杰伦", ref?.artist)
    }

    @Test
    fun `netease locator uses the injected anonymous search`() = runTest {
        val raw = """
            {"code":200,"result":{"songs":[
              {"id":186016,"name":"晴天","ar":[{"name":"周杰伦"}]}
            ]}}
        """.trimIndent()

        val ref = LxTestSongLocator(client, neteaseSearch = { raw }).locate(LxPlatform.WY, "晴天")

        assertEquals("186016", ref?.songmid)
        assertEquals("晴天", ref?.title)
        assertEquals("周杰伦", ref?.artist)
    }

    @Test
    fun `netease locator returns null when the search fails`() = runTest {
        val ref = LxTestSongLocator(client, neteaseSearch = { null }).locate(LxPlatform.WY, "晴天")

        assertNull(ref)
    }

    @Test
    fun `platforms without an anonymous search are not testable`() = runTest {
        val locator = LxTestSongLocator(client)

        assertNull(locator.locate(LxPlatform.MG, "晴天"))
        assertNull(locator.locate(LxPlatform.LOCAL, "晴天"))
    }

    @Test
    fun `a blank keyword never resolves a song`() = runTest {
        assertNull(LxTestSongLocator(client).locate(LxPlatform.TX, "   "))
    }

    @Test
    fun `report counts passed and checked items`() {
        val report = LxSourceTestReport(
            sourceName = "demo",
            initialized = true,
            probes = listOf(
                LxQualityProbe(LxPlatform.WY, LxQuality.Q128, ok = true),
                LxQualityProbe(LxPlatform.WY, LxQuality.Q320, ok = false),
                LxQualityProbe(LxPlatform.WY, LxQuality.FLAC, ok = false, skipped = true),
                LxQualityProbe(LxPlatform.TX, LxQuality.Q128, ok = true)
            )
        )

        assertEquals(2, report.passedCount)
        assertEquals(3, report.checkedCount)
        assertEquals(listOf(LxPlatform.WY, LxPlatform.TX), report.platforms)
        assertTrue(report.probe(LxPlatform.WY, LxQuality.FLAC)?.skipped == true)
        assertNull(report.probe(LxPlatform.TX, LxQuality.FLAC24))
    }

    @Test
    fun `standard quality order matches the detection matrix`() {
        assertEquals(
            listOf(LxQuality.Q128, LxQuality.Q320, LxQuality.FLAC, LxQuality.FLAC24),
            LxQuality.STANDARD_ORDER
        )
    }
}
