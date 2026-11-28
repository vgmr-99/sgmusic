package moe.ouom.neriplayer.ui.screen.nowplaying

import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingProgressStyle
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingStyle
import moe.ouom.neriplayer.ui.component.playback.NowPlayingProgressShape
import moe.ouom.neriplayer.ui.component.playback.resolveProgressShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页模板化契约测试。
 *
 * 锁定三件事：
 * 1. 三套模板的骨架类型确实不同（真模板，而非只换背景色）
 * 2. 三套模板的歌词字号确实不同（视觉主导权不同）
 * 3. 进度条样式独立于模板，可自由映射到六种形状
 */
class NowPlayingTemplateLayoutTest {

    @Test
    fun `three templates produce distinct layout skeletons`() {
        val classic = resolveNowPlayingCoverPagePresentation(false, false, NowPlayingStyle.Classic, 900f)
        val expressive = resolveNowPlayingCoverPagePresentation(false, false, NowPlayingStyle.Expressive, 900f)
        val apple = resolveNowPlayingCoverPagePresentation(false, false, NowPlayingStyle.AppleMusic, 900f)

        // Classic 保持原有行为：封面尺寸自适应（null）、标题展开、经典竖排
        assertNull(classic.preferredCoverSize)
        assertEquals(NowPlayingTrackIdentityLayout.Expanded, classic.trackIdentityLayout)
        assertEquals(NowPlayingTemplateLayout.CLASSIC_STACK, classic.templateLayout)
        assertTrue(!classic.sideBySideHeader)

        // Expressive：大封面 + 大标题 + 封面主导骨架
        assertEquals(NowPlayingTrackIdentityLayout.LargeCover, expressive.trackIdentityLayout)
        assertNotEquals(null, expressive.preferredCoverSize)
        assertEquals(NowPlayingTemplateLayout.ARTWORK_FIRST, expressive.templateLayout)

        // AppleMusic：小封面 + 标题并排 + 歌词主导骨架
        assertEquals(NowPlayingTrackIdentityLayout.Compact, apple.trackIdentityLayout)
        assertNotEquals(null, apple.preferredCoverSize)
        assertEquals(NowPlayingTemplateLayout.LYRICS_FIRST, apple.templateLayout)
        assertTrue(apple.sideBySideHeader)

        // 三套骨架必须两两不同，否则不是真模板
        assertNotEquals(classic.templateLayout, expressive.templateLayout)
        assertNotEquals(expressive.templateLayout, apple.templateLayout)
        assertNotEquals(classic.preferredCoverSize, expressive.preferredCoverSize)
        assertNotEquals(expressive.preferredCoverSize, apple.preferredCoverSize)
    }

    @Test
    fun `cover size is budgeted on short screens`() {
        val shortExpressive = resolveNowPlayingCoverPagePresentation(false, false, NowPlayingStyle.Expressive, 400f)
        val tallExpressive = resolveNowPlayingCoverPagePresentation(false, false, NowPlayingStyle.Expressive, 1000f)
        // 小屏封面应小于大屏，避免控件被挤出屏幕
        assert(shortExpressive.preferredCoverSize!! < tallExpressive.preferredCoverSize!!)
    }

    @Test
    fun `immersive mode still overrides template`() {
        // 沉浸模式优先级高于模板（保持原有行为）
        listOf(NowPlayingStyle.Classic, NowPlayingStyle.Expressive, NowPlayingStyle.AppleMusic).forEach { style ->
            val immersive = resolveNowPlayingCoverPagePresentation(true, false, style, 900f)
            assertEquals(NowPlayingTrackIdentityLayout.LargeCover, immersive.trackIdentityLayout)
        }
    }

    @Test
    fun `three templates use different lyric sizes`() {
        val sizes = NowPlayingStyle.entries.map(::resolveNowPlayingLyricBaseFontSize)
        assertEquals(3, sizes.toSet().size)
        // 封面主导的模板歌词更小，歌词主导的模板歌词更大
        assertTrue(
            resolveNowPlayingLyricBaseFontSize(NowPlayingStyle.Expressive) <
                resolveNowPlayingLyricBaseFontSize(NowPlayingStyle.AppleMusic)
        )
    }

    @Test
    fun `progress style maps independently to shape`() {
        assertEquals(NowPlayingProgressShape.WAVE, resolveProgressShape(NowPlayingProgressStyle.WAVE))
        assertEquals(NowPlayingProgressShape.SEGMENTED, resolveProgressShape(NowPlayingProgressStyle.SEGMENTED))
        assertEquals(NowPlayingProgressShape.CAPSULE, resolveProgressShape(NowPlayingProgressStyle.CAPSULE))
    }
}
