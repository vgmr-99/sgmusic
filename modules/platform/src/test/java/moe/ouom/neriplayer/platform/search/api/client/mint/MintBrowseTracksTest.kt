package moe.ouom.neriplayer.platform.search.api.client.mint

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
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
 * 酷狗歌单曲目与网易云浏览请求路径的解析测试。
 *
 * 背景：
 * - 酷狗原来的歌单曲目接口 `m.kugou.com/plist/list/{id}?json=true` 已下线，
 *   换成了 `mobileservice.kugou.com/api/v5/special/song`；
 * - 网易云的 weapi 直连在游客态会回「HTTP 200 + 空 body」，
 *   所以浏览接口改走明文 `/api/` 直连。
 */
class MintBrowseTracksTest {

    private val requests = CopyOnWriteArrayList<Request>()
    private val bodies = LinkedHashMap<String, String>()

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val path = request.url.encodedPath
        val body = bodies.entries.firstOrNull { path.contains(it.key) }?.value ?: "{}"
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("fixture")
            .body(body.toResponseBody())
            .build()
    }.build()

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // ── 酷狗 ────────────────────────────────────────────────────

    @Test
    fun `kugou playlist tracks are parsed from the special song api`() = runTest {
        bodies["/api/v5/special/song"] = """
            {"status":1,"errcode":0,"data":{"timestamp":1794636454,"total":2,"info":[
              {"hash":"3FD7E1EC51122C745C637735C8BDE716","duration":320,
               "album_id":965883,"audio_id":284977,
               "filename":"孙燕姿 - 我不难过",
               "trans_param":{"union_cover":"http://imge.kugou.com/stdmusic/{size}/20250624/20250624174203375937.jpg"}},
              {"hash":"1F7E7659B9FFF4FB6F71562A61BBC25F","duration":299,
               "filename":"周杰伦 - 发如雪"}
            ]}}
        """.trimIndent()

        val tracks = MintKugouBrowseApi(MintHttp(client = client))
            .tracks(PlatformBrowseCard(id = "1852429", title = "抖音歌曲最火的歌", provider = PlatformSearchProvider.KUGOU))

        assertEquals(2, tracks.size)
        val first = tracks.first()
        assertEquals("3FD7E1EC51122C745C637735C8BDE716", first.id)
        // filename 是「歌手 - 歌名」，要拆开
        assertEquals("我不难过", first.name)
        assertEquals("孙燕姿", first.subtitle)
        assertEquals(320_000L, first.durationMs)
        assertEquals("https://imge.kugou.com/stdmusic/800/20250624/20250624174203375937.jpg", first.coverUrl)
        assertEquals("发如雪", tracks[1].name)
        assertEquals("周杰伦", tracks[1].subtitle)
        // 没有封面时用 hash 兜底
        assertEquals("https://imge.kugou.com/800/1F7E7659B9FFF4FB6F71562A61BBC25F.jpg", tracks[1].coverUrl)

        val request = requests.first()
        assertTrue(request.url.encodedPath.contains("/api/v5/special/song"))
        assertEquals("1852429", request.url.queryParameter("specialid"))
    }

    @Test
    fun `kugou playlist tracks stay empty when the api returns no info`() = runTest {
        bodies["/api/v5/special/song"] = """{"status":1,"data":{"total":0,"info":[]}}"""

        val tracks = MintKugouBrowseApi(MintHttp(client = client))
            .tracks(PlatformBrowseCard(id = "1", title = "空歌单", provider = PlatformSearchProvider.KUGOU))

        assertEquals(emptyList<Any>(), tracks)
    }

    // ── 网易云 ──────────────────────────────────────────────────

    @Test
    fun `netease playlist list uses the plain api instead of the encrypted weapi`() = runTest {
        bodies["/api/playlist/list"] = """
            {"playlists":[
              {"id":18271967084,"name":"欧美R&B｜顶级旋律","description":"好听",
               "coverImgUrl":"http://p1.music.126.net/xxx.jpg","trackCount":96,
               "playCount":1200000,"creator":{"nickname":"某用户"}}
            ]}
        """.trimIndent()

        val category = MintBrowseCategory.encode("wy", MintBrowseKind.HOT, "", "")
        val cards = MintNeteaseBrowseApi(MintHttp(client = client)).browse(category)

        assertEquals(1, cards.size)
        assertEquals("18271967084", cards.first().id)
        assertEquals("欧美R&B｜顶级旋律", cards.first().title)
        assertEquals(96, cards.first().trackCount)

        val request = requests.first()
        assertTrue(request.url.encodedPath.contains("/api/playlist/list"))
        // 关键：不该再打 weapi，那条路游客态会返回空 body 并触发无意义重试
        assertTrue(requests.none { it.url.encodedPath.contains("/weapi/") })
    }

    @Test
    fun `netease playlist tracks use the plain detail api`() = runTest {
        bodies["/api/v3/playlist/detail"] = """
            {"code":200,"playlist":{"id":3778678,"name":"可踩点 热血歌单","trackCount":1,"tracks":[
              {"id":1860169929,"name":"孤勇者","dt":210000,
               "ar":[{"name":"陈奕迅"}],"al":{"name":"孤勇者","picUrl":"http://p1.music.126.net/y.jpg"}}
            ]}}
        """.trimIndent()

        val tracks = MintNeteaseBrowseApi(MintHttp(client = client))
            .tracks(PlatformBrowseCard(id = "3778678", title = "可踩点 热血歌单", provider = PlatformSearchProvider.NETEASE))

        assertEquals(1, tracks.size)
        assertEquals("1860169929", tracks.first().id)
        assertEquals("孤勇者", tracks.first().name)
        assertEquals("陈奕迅", tracks.first().subtitle)
        assertEquals(210_000L, tracks.first().durationMs)

        val request = requests.first()
        assertTrue(request.url.encodedPath.contains("/api/v3/playlist/detail"))
        assertEquals("3778678", request.url.queryParameter("id"))
    }

    @Test
    fun `netease toplist uses the plain api`() = runTest {
        bodies["/api/toplist"] = """
            {"code":200,"list":[
              {"id":11665,"name":"飙升榜","coverImgUrl":"http://p1.music.126.net/a.jpg","trackCount":200}
            ]}
        """.trimIndent()

        val category = MintBrowseCategory.encode("wy", MintBrowseKind.CHART, "", "")
        val cards = MintNeteaseBrowseApi(MintHttp(client = client)).browse(category)

        assertEquals(1, cards.size)
        assertEquals("11665", cards.first().id)
        assertTrue(requests.first().url.encodedPath.contains("/api/toplist"))
    }

    // ── MintHttp ────────────────────────────────────────────────

    @Test
    fun `empty responses are not retried`() = runTest {
        // 服务端回 HTTP 200 但 body 为空：这是明确拒绝，重试没有意义
        val emptyClient = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("fixture")
                .body("".toResponseBody())
                .build()
        }.build()
        val before = requests.size

        val result = MintHttp(client = emptyClient).get("https://example.com/empty")

        assertEquals(null, result)
        assertEquals(
            "空响应只该请求一次",
            before + 1,
            requests.size
        )
        emptyClient.dispatcher.executorService.shutdown()
        emptyClient.connectionPool.evictAll()
    }
}
