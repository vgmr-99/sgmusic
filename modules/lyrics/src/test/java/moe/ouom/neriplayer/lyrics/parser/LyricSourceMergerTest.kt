package moe.ouom.neriplayer.lyrics.parser

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricSourceMergerTest {

    private fun line(
        text: String,
        startMs: Long,
        endMs: Long = startMs + 1000,
        translation: String? = null,
        words: List<WordTiming>? = null
    ) = LyricEntry(
        text = text,
        startTimeMs = startMs,
        endTimeMs = endMs,
        words = words,
        translation = translation
    )

    @Test
    fun `merge prefers word timed lines over plain ones`() {
        val wordTimed = listOf(
            line("逐词行", 1000, 2000, words = listOf(WordTiming(1000, 2000, 3)))
        )
        val plain = listOf(
            line("普通行", 1000, 2000),
            line("只有普通源才有", 3000, 4000)
        )

        val merged = LyricSourceMerger.merge(listOf(wordTimed, plain))

        assertEquals(2, merged.size)
        assertEquals("逐词行", merged[0].text)
        assertEquals("只有普通源才有", merged[1].text)
        assertTrue(merged[0].words?.isNotEmpty() == true)
    }

    @Test
    fun `merge keeps the higher priority source on the same slot`() {
        val first = listOf(line("优先级高", 1000))
        val second = listOf(line("优先级低", 1050))

        val merged = LyricSourceMerger.merge(listOf(first, second))

        assertEquals(1, merged.size)
        assertEquals("优先级高", merged.single().text)
    }

    @Test
    fun `merge returns the only source unchanged`() {
        val only = listOf(line("唯一", 1000))

        val merged = LyricSourceMerger.merge(listOf(only, emptyList()))

        assertEquals(only, merged)
    }

    @Test
    fun `merge returns empty when every source is empty`() {
        assertTrue(LyricSourceMerger.merge(listOf(emptyList(), emptyList())).isEmpty())
    }

    @Test
    fun `merge sorts lines by start time`() {
        val source = listOf(
            line("第三句", 5000),
            line("第一句", 1000),
            line("第二句", 3000)
        )

        val merged = LyricSourceMerger.merge(listOf(source))

        assertEquals(listOf("第一句", "第二句", "第三句"), merged.map { it.text })
    }

    @Test
    fun `merge translations fills missing translations only`() {
        val original = listOf(
            line("第一句", 1000, translation = "已有翻译"),
            line("第二句", 2000)
        )
        val translated = listOf(
            line("第一句", 1000, translation = "别的源的翻译"),
            line("第二句", 2000, translation = "补上的翻译")
        )

        val merged = LyricSourceMerger.mergeTranslations(original, translated)

        assertEquals("已有翻译", merged[0].translation)
        assertEquals("补上的翻译", merged[1].translation)
    }

    @Test
    fun `align romanized matches lines by start time`() {
        val original = listOf(line("第一句", 1000), line("第二句", 2000))
        val romanized = listOf(line("di yi ju", 1000), line("di er ju", 2000))

        val aligned = LyricSourceMerger.alignRomanized(original, romanized)

        assertEquals(2, aligned.size)
        assertEquals("di yi ju", aligned[0]?.text)
        assertEquals("di er ju", aligned[1]?.text)
    }

    @Test
    fun `align romanized returns nulls when nothing matches`() {
        val original = listOf(line("第一句", 1000))
        val romanized = listOf(line("bu dui", 5000))

        val aligned = LyricSourceMerger.alignRomanized(original, romanized)

        assertNull(aligned.single())
    }
}
