package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAmllLyricsSourceTest {
    private fun line(
        text: String,
        startMs: Long,
        words: List<WordTiming>? = null
    ) = LyricEntry(
        text = text,
        startTimeMs = startMs,
        endTimeMs = startMs + 1000,
        words = words
    )

    @Test
    fun `platform lyrics win when they already carry word timing`() {
        val platform = listOf(line("逐词行", 1000, listOf(WordTiming(1000, 2000, 1))))
        assertTrue(
            PlayerAmllLyricsSource.preferPlatformLyrics(
                biliCcLyricsLoaded = false,
                preferWordTimedLyrics = true,
                platformLyrics = platform,
                amllLyricsEnabled = true
            )
        )
    }

    @Test
    fun `platform lyrics win when word timing is not preferred`() {
        assertTrue(
            PlayerAmllLyricsSource.preferPlatformLyrics(
                biliCcLyricsLoaded = false,
                preferWordTimedLyrics = false,
                platformLyrics = emptyList(),
                amllLyricsEnabled = true
            )
        )
    }

    @Test
    fun `platform lyrics win when the amll source is disabled`() {
        assertTrue(
            PlayerAmllLyricsSource.preferPlatformLyrics(
                biliCcLyricsLoaded = false,
                preferWordTimedLyrics = true,
                platformLyrics = emptyList(),
                amllLyricsEnabled = false
            )
        )
    }

    @Test
    fun `b站字幕已经加载时直接用字幕歌词`() {
        assertTrue(
            PlayerAmllLyricsSource.preferPlatformLyrics(
                biliCcLyricsLoaded = true,
                preferWordTimedLyrics = true,
                platformLyrics = emptyList(),
                amllLyricsEnabled = true
            )
        )
    }

    @Test
    fun `amll lyrics are preferred when it is the only word timed source`() {
        assertFalse(
            PlayerAmllLyricsSource.preferPlatformLyrics(
                biliCcLyricsLoaded = false,
                preferWordTimedLyrics = true,
                platformLyrics = emptyList(),
                amllLyricsEnabled = true
            )
        )
    }

    @Test
    fun `merge keeps the word timed line and fills the missing ones`() {
        val amll = listOf(line("逐词行", 1000, listOf(WordTiming(1000, 2000, 1))))
        val platform = listOf(line("普通行", 1000), line("补齐行", 3000))

        val merged = PlayerAmllLyricsSource.mergeAcrossSources(amll, platform)

        assertEquals(2, merged.size)
        assertEquals("逐词行", merged[0].text)
        assertEquals("补齐行", merged[1].text)
    }

    @Test
    fun `merge returns an empty list when both sources are empty`() {
        assertTrue(PlayerAmllLyricsSource.mergeAcrossSources(emptyList(), emptyList()).isEmpty())
    }
}
