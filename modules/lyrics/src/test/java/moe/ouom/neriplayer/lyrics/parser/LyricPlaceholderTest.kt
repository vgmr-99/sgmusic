package moe.ouom.neriplayer.lyrics.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这首歌没有收录歌词」的占位歌词识别。
 *
 * 真机实测网易云会返回 `{"uncollected":true,"lrc":{"lyric":"[00:00.00]暂无歌词"}}`，
 * 这不是真歌词：当成歌词会把「暂无歌词」画到播放页，
 * 还会因为「平台歌词非空」而挡住所有兜底链路。
 */
class LyricPlaceholderTest {

    @Test
    fun `netease placeholder lyric text is recognized`() {
        assertTrue(isPlaceholderLyricLine("[00:00.00]暂无歌词"))
        assertTrue(isPlaceholderLyricLine("暂无歌词"))
        assertTrue(isPlaceholderLyricLine(" [00:00.00] 暂无歌词 "))
        assertTrue(isPlaceholderLyricLine("[00:00.00]此歌曲为没有填词的音乐"))
        assertTrue(isPlaceholderLyricLine("[00:00.00]纯音乐，请欣赏"))
    }

    @Test
    fun `real lyric text is not treated as placeholder`() {
        assertFalse(isPlaceholderLyricLine("[00:15.00]相对 那就针锋相对"))
        assertFalse(isPlaceholderLyricLine("暂无歌词这两个字也是歌词"))
        assertFalse(isPlaceholderLyricLine(""))
        assertFalse(isPlaceholderLyricLine(null))
    }

    @Test
    fun `placeholder only content is recognized`() {
        assertTrue(isPlaceholderLyricContent("[00:00.00]暂无歌词"))
        assertTrue(isPlaceholderLyricContent("[00:00.00]暂无歌词\n[00:00.01]暂无歌词"))
        assertFalse(isPlaceholderLyricContent("[00:00.00]暂无歌词\n[00:15.00]相对 那就针锋相对"))
        assertFalse(isPlaceholderLyricContent(""))
        assertFalse(isPlaceholderLyricContent(null))
    }

    @Test
    fun `parser drops placeholder line but keeps real lyrics`() {
        val lrc = """
            [00:00.00]暂无歌词
            [00:15.00]相对 那就针锋相对
            [00:17.82]荃湾商厦电梯相会
        """.trimIndent()

        val texts = parseNeteaseLyricsAuto(lrc).map { it.text }

        assertEquals(listOf("相对 那就针锋相对", "荃湾商厦电梯相会"), texts)
    }

    @Test
    fun `parser returns empty for placeholder only content`() {
        assertEquals(emptyList<Any>(), parseNeteaseLyricsAuto("[00:00.00]暂无歌词"))
    }
}
