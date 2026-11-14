package moe.ouom.neriplayer.platform.lyrics.matching

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌名归一：括号里的别名 / 副标题不能让同一首歌对不上。
 *
 * 真机场景：汽水的《枪火（相对那就针锋相对）》在网易云叫《枪火》
 * （别名「相对那就针锋相对」）。两边歌名不一致时，正确的歌词会被判成
 * 「身份不一致」丢掉，用户看到的就是「用 LX 音源读不到歌词」。
 */
class EditableLyricMatchCoreTitleTest {

    @Test
    fun `alias inside parentheses still matches the plain title`() {
        assertTrue(
            isReliableLyricMatchIdentity(
                expectedTitle = "枪火（相对那就针锋相对）",
                expectedArtist = "宝石Gem",
                candidateTitle = "枪火",
                candidateArtist = "宝石Gem"
            )
        )
        assertTrue(
            isReliableLyricMatchIdentity(
                expectedTitle = "枪火",
                expectedArtist = "宝石Gem",
                candidateTitle = "枪火 (相对那就针锋相对)",
                candidateArtist = "宝石Gem"
            )
        )
    }

    @Test
    fun `release markers inside parentheses are never stripped`() {
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "夜曲 (Live)",
                expectedArtist = "周杰伦",
                candidateTitle = "夜曲",
                candidateArtist = "周杰伦"
            )
        )
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "夜曲 (伴奏)",
                expectedArtist = "周杰伦",
                candidateTitle = "夜曲",
                candidateArtist = "周杰伦"
            )
        )
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "夜曲 (DJ版)",
                expectedArtist = "周杰伦",
                candidateTitle = "夜曲",
                candidateArtist = "周杰伦"
            )
        )
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "夜曲 (Remix)",
                expectedArtist = "周杰伦",
                candidateTitle = "夜曲",
                candidateArtist = "周杰伦"
            )
        )
    }

    @Test
    fun `different songs are still rejected`() {
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "枪火（相对那就针锋相对）",
                expectedArtist = "宝石Gem",
                candidateTitle = "完全不同的歌",
                candidateArtist = "另一个歌手"
            )
        )
    }
}
