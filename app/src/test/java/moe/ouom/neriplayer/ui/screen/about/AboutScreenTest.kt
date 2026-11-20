package moe.ouom.neriplayer.ui.screen.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关于页折叠头进度的单测。
 */
class AboutScreenTest {

    @Test
    fun `未滚动时完全展开`() {
        assertEquals(0f, resolveAboutHeaderCollapseProgress(0, 180f), 0.001f)
    }

    @Test
    fun `滚动到折叠距离时完全折叠`() {
        assertEquals(1f, resolveAboutHeaderCollapseProgress(180, 180f), 0.001f)
    }

    @Test
    fun `超过折叠距离后不再增加`() {
        assertEquals(1f, resolveAboutHeaderCollapseProgress(9999, 180f), 0.001f)
    }

    @Test
    fun `折叠距离为零时始终展开`() {
        assertEquals(0f, resolveAboutHeaderCollapseProgress(500, 0f), 0.001f)
    }

    @Test
    fun `标志随进度缩小`() {
        val expanded = resolveAboutLogoScale(0f)
        val collapsed = resolveAboutLogoScale(1f)
        assertTrue("折叠后应比展开时小", collapsed < expanded)
        assertEquals(1f, expanded, 0.001f)
    }

    @Test
    fun `标志在折叠过程中淡出`() {
        assertEquals(1f, resolveAboutLogoAlpha(0f), 0.001f)
        assertEquals(0f, resolveAboutLogoAlpha(1f), 0.001f)
        assertTrue("中途应比展开时更淡", resolveAboutLogoAlpha(0.5f) < 1f)
    }

    @Test
    fun `标题浮现进度跟随折叠进度`() {
        assertEquals(0f, resolveAboutTitleRevealProgress(0f), 0.001f)
        assertEquals(0.5f, resolveAboutTitleRevealProgress(0.5f), 0.001f)
        assertEquals(1f, resolveAboutTitleRevealProgress(1f), 0.001f)
    }

    @Test
    fun `标志上移行程为折叠距离的一部分`() {
        assertEquals(0f, resolveAboutHeroTranslationY(0f, 1000f), 0.001f)
        assertTrue("应向上移动", resolveAboutHeroTranslationY(1f, 1000f) < 0f)
        assertEquals(-550f, resolveAboutHeroTranslationY(1f, 1000f), 0.001f)
    }
}
