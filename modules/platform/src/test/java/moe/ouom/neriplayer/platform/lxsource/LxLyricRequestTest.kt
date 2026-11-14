package moe.ouom.neriplayer.platform.lxsource

import moe.ouom.neriplayer.data.model.SongItem
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LX 歌词请求的映射规则。
 *
 * 背景：宿主侧以前**从来没有**调用过脚本的 `lyric` action，
 * 于是「用 LX 音源播放汽水 / 咪咕这类没有内置歌词接口的平台」时永远是「暂无歌词」。
 * 这里锁住「歌曲 -> LX 歌词请求参数」的映射，避免以后再退回去。
 */
class LxLyricRequestTest {

    private fun repository(): LxSourceRepository = LxSourceRepository(
        http = LxHttpClient(OkHttpClient()),
        preloadJs = "",
        store = InMemoryLxSourceStore()
    )

    private fun song(
        id: Long = 1L,
        channelId: String? = "qishui",
        audioId: String? = null,
        name: String = "枪火",
        artist: String = "陈一发",
        durationMs: Long = 180_000L
    ) = SongItem(
        id = id,
        name = name,
        artist = artist,
        album = "专辑",
        albumId = 0L,
        durationMs = durationMs,
        coverUrl = null,
        channelId = channelId,
        audioId = audioId
    )

    @Test
    fun `qishui song asks the qs source for its native track id`() {
        val info = repository().buildLyricMusicInfo(
            song(channelId = "qishui", audioId = "7675915828206094352"),
            LxPlatform.QS
        )
        assertNotNull(info)
        assertEquals("qs", info!!.source)
        assertEquals("7675915828206094352", info.songmid)
        assertEquals("枪火", info.name)
        assertEquals("陈一发", info.singer)
        assertEquals(180_000L, info.durationMs)
    }

    @Test
    fun `kugou song carries the file hash as the kg hash extra`() {
        val info = repository().buildLyricMusicInfo(
            song(channelId = "kugou", audioId = "8C7F1E2A0B4D5E6F708192A3B4C5D6E7"),
            LxPlatform.KG
        )
        assertNotNull(info)
        assertEquals("kg", info!!.source)
        assertEquals("8C7F1E2A0B4D5E6F708192A3B4C5D6E7", info.songmid)
        // 社区脚本按 extras.hash 取歌，不给就换不出歌词
        assertEquals("8C7F1E2A0B4D5E6F708192A3B4C5D6E7", info.extras["hash"])
    }

    @Test
    fun `netease song keeps the numeric song id in both songmid and songId extra`() {
        val info = repository().buildLyricMusicInfo(
            song(channelId = "netease", audioId = "186016"),
            LxPlatform.WY
        )
        assertNotNull(info)
        assertEquals("wy", info!!.source)
        assertEquals("186016", info.songmid)
        assertEquals("186016", info.extras["songId"])
    }

    @Test
    fun `song without a usable platform id is rejected`() {
        assertNull(repository().buildLyricMusicInfo(song(id = 0L, audioId = null), LxPlatform.QS))
        assertNull(repository().buildLyricMusicInfo(song(id = 0L, audioId = "  "), LxPlatform.QS))
        assertNull(repository().buildLyricMusicInfo(song(id = 0L, audioId = "0"), LxPlatform.QS))
    }

    @Test
    fun `song id is used when the platform audio id is missing`() {
        val info = repository().buildLyricMusicInfo(
            song(id = 4242L, channelId = "qq", audioId = null),
            LxPlatform.TX
        )
        assertNotNull(info)
        assertEquals("4242", info!!.songmid)
    }

    @Test
    fun `lyric unsupported errors are recognized so the script is not retried forever`() {
        assertTrue(
            LxResult.Err("script_error", "action not support: lyric")
                .isLyricUnsupported()
        )
        assertTrue(
            LxResult.Err("script_error", "Action Not Supported")
                .isLyricUnsupported()
        )
        // 单曲没歌词 / 超时都不代表脚本没有歌词能力，不能误判
        assertFalse(LxResult.Err("no_result", "脚本没有返回歌词").isLyricUnsupported())
        assertFalse(LxResult.Err("timeout", "lyric timeout or empty").isLyricUnsupported())
        assertFalse(LxResult.Err("script_error", "缺少musicInfo").isLyricUnsupported())
    }
}
