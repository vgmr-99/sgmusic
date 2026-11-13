package moe.ouom.neriplayer.platform.lxsource

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 洛雪协议里 musicInfo 的结构测试。
 *
 * 脚本收到 info.musicInfo，只传 songmid 的话脚本会直接报「缺少musicInfo」；
 * 平台特有字段（hash / copyrightId / strMediaMid）没有值时必须整个 key 省略，
 * 否则社区脚本的 `hash ?? songmid` 会被空串遮住。
 */
class LxMusicInfoTest {

    @Test
    fun `music info carries the fields scripts read`() {
        val info = LxMusicInfo(
            source = "wy",
            songmid = "186016",
            name = "晴天",
            singer = "周杰伦",
            albumName = "叶惠美",
            durationMs = 269_000L,
            types = listOf(LxQuality.Q128, LxQuality.Q320),
            coverUrl = "https://p1.music.126.net/cover.jpg"
        ).toJson()

        assertEquals("wy_186016", info.optString("id"))
        assertEquals("晴天", info.optString("name"))
        assertEquals("周杰伦", info.optString("singer"))
        assertEquals("wy", info.optString("source"))
        assertEquals("186016", info.optString("songmid"))
        assertEquals("叶惠美", info.optString("albumName"))
        assertEquals("04:29", info.optString("interval"))
        assertEquals(2, info.getJSONArray("types").length())
        assertEquals("128k", info.getJSONArray("types").getJSONObject(0).optString("type"))
        assertTrue(info.has("_types"))
        assertTrue(info.getJSONObject("meta").has("songId"))
    }

    @Test
    fun `platform extras are only written when they have a value`() {
        val info = LxMusicInfo(
            source = "kg",
            songmid = "B3A52A7A958BF0AED0EBFBA2E9A818B7",
            types = listOf(LxQuality.Q128),
            extras = mapOf(
                "hash" to "B3A52A7A958BF0AED0EBFBA2E9A818B7",
                "albumId" to "",
                "copyrightId" to ""
            )
        ).toJson()

        assertEquals("B3A52A7A958BF0AED0EBFBA2E9A818B7", info.optString("hash"))
        // 空值字段整个 key 省略，脚本的 `hash ?? songmid` 才不会被空串遮住
        assertFalse(info.has("albumId"))
        assertFalse(info.has("copyrightId"))
    }

    @Test
    fun `quality hashes are exposed per quality`() {
        val info = LxMusicInfo(
            source = "kg",
            songmid = "hash128",
            types = listOf(LxQuality.Q128, LxQuality.FLAC),
            qualityHashes = mapOf(
                LxQuality.Q128 to "hash128",
                LxQuality.FLAC to "hashFlac"
            )
        ).toJson()

        val types = info.getJSONArray("types")
        assertEquals("hash128", types.getJSONObject(0).optString("hash"))
        assertEquals("hashFlac", types.getJSONObject(1).optString("hash"))
        assertEquals("hashFlac", info.getJSONObject("_types").getJSONObject("flac").optString("hash"))
    }

    @Test
    fun `interval formats minutes and hours`() {
        assertEquals("00:00", LxMusicInfo.formatInterval(0L))
        assertEquals("03:55", LxMusicInfo.formatInterval(235_000L))
        assertEquals("01:02:03", LxMusicInfo.formatInterval(3_723_000L))
    }

    @Test
    fun `blank cover url is reported as null instead of empty string`() {
        val info = LxMusicInfo(source = "kw", songmid = "1").toJson()
        assertTrue(info.getJSONObject("meta").isNull("picUrl"))
    }

    @Test
    fun `music url keeps the headers the script asked for`() {
        val url = LxMusicUrl(
            url = "https://cdn.example.com/a.mp3",
            headers = mapOf("Referer" to "https://www.kugou.com/")
        )
        assertEquals("https://cdn.example.com/a.mp3", url.url)
        assertEquals("https://www.kugou.com/", url.headers["Referer"])
    }

    @Test
    fun `info json stays a plain object`() {
        val json = LxMusicInfo(source = "tx", songmid = "mid").toJson()
        assertTrue(json is JSONObject)
    }
}
