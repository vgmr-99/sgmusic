package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网易云「没有收录歌词」的占位响应。
 *
 * 真机实测（汽水歌《枪火（相对那就针锋相对）》）：
 * `{"uncollected":true,...,"lrc":{"version":1,"lyric":"[00:00.00]暂无歌词"},"code":200}`
 *
 * 这份占位内容有两个害处：
 * 1. 播放页会把「暂无歌词」当成一行歌词画出来；
 * 2. 平台歌词「非空」会挡住 LX 脚本与多源高置信兜底，
 *    于是「用 LX 音源播放汽水 / 咪咕这类平台的歌」永远读不到歌词。
 */
class NeteasePlaceholderLyricTest {

    @Test
    fun `uncollected response is recognized as no lyrics`() {
        val payload = """{"uncollected":true,"sgc":false,"sfy":false,"qfy":false,"needDesc":true,""" +
            """"lrc":{"version":1,"lyric":"[00:00.00]暂无歌词"},"code":200,"briefDesc":null}"""

        assertTrue(isNeteaseUncollectedLyricResponse(payload))

        val entry = PlayerLyricsProvider.buildNeteaseLyricsCacheEntry(payload)
        assertEquals("", entry.preferredLyricText)
        assertTrue(entry.preferredLyricEntries.isEmpty())
    }

    @Test
    fun `real netease lyric response is not treated as placeholder`() {
        val payload = """{"code":200,"lrc":{"version":1,"lyric":"[00:15.00]相对 那就针锋相对"}}"""

        assertFalse(isNeteaseUncollectedLyricResponse(payload))

        val entry = PlayerLyricsProvider.buildNeteaseLyricsCacheEntry(payload)
        assertEquals("[00:15.00]相对 那就针锋相对", entry.preferredLyricText)
        assertEquals(listOf("相对 那就针锋相对"), entry.preferredLyricEntries.map { it.text })
    }

    @Test
    fun `empty lyric response is not treated as placeholder`() {
        assertFalse(isNeteaseUncollectedLyricResponse("""{"code":200,"lrc":{"lyric":""}}"""))
    }

    @Test
    fun `platform lyric id only comes from netease songs or netease matches`() {
        val qishuiSong = song(id = 89_386_085_287_488_145_86L, channelId = "qishui")
        assertNull(resolvePlatformLyricNeteaseSongId(qishuiSong))

        val neteaseSong = song(id = 3_326_404_841L, channelId = "netease")
        assertEquals(3_326_404_841L, resolvePlatformLyricNeteaseSongId(neteaseSong))

        val matchedSong = qishuiSong.copy(
            matchedSongId = "3415993173",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC
        )
        assertEquals(3_415_993_173L, resolvePlatformLyricNeteaseSongId(matchedSong))

        val qqMatchedSong = qishuiSong.copy(
            matchedSongId = "3415993173",
            matchedLyricSource = MusicPlatform.QQ_MUSIC
        )
        assertNull(resolvePlatformLyricNeteaseSongId(qqMatchedSong))
    }

    private fun song(id: Long, channelId: String): SongItem = SongItem(
        id = id,
        name = "枪火（相对那就针锋相对）",
        artist = "宝石Gem",
        album = "通俗说唱",
        albumId = 1L,
        durationMs = 198_000L,
        coverUrl = null,
        mediaUri = "https://example.com/audio.mp3",
        channelId = channelId,
        audioId = "7675915828206094352"
    )
}
