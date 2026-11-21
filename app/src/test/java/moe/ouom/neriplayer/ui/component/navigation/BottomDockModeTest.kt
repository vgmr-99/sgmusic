package moe.ouom.neriplayer.ui.component.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 底部两栏形态判定的单测。
 *
 * 形态改成手势驱动后：上滑一次收紧、下滑一次恢复，跟列表滚到哪无关。
 * 这里覆盖手势方向判定、阈值边界、回顶注册与点击语义。
 */
class BottomDockModeTest {

    @Test
    fun `上滑收紧下滑恢复`() {
        assertEquals(
            DockSwipeDirection.COLLAPSE,
            resolveDockSwipeDirection(dragY = -120f, thresholdPx = 40f)
        )
        assertEquals(
            DockSwipeDirection.EXPAND,
            resolveDockSwipeDirection(dragY = 120f, thresholdPx = 40f)
        )
    }

    @Test
    fun `没到阈值不触发任何切换`() {
        assertNull(resolveDockSwipeDirection(dragY = -39f, thresholdPx = 40f))
        assertNull(resolveDockSwipeDirection(dragY = 0f, thresholdPx = 40f))
        assertNull(resolveDockSwipeDirection(dragY = 39f, thresholdPx = 40f))
    }

    @Test
    fun `阈值边界包含等号`() {
        assertEquals(
            DockSwipeDirection.COLLAPSE,
            resolveDockSwipeDirection(dragY = -40f, thresholdPx = 40f)
        )
        assertEquals(
            DockSwipeDirection.EXPAND,
            resolveDockSwipeDirection(dragY = 40f, thresholdPx = 40f)
        )
    }

    @Test
    fun `无效阈值始终不触发`() {
        assertNull(resolveDockSwipeDirection(dragY = -500f, thresholdPx = 0f))
        assertNull(resolveDockSwipeDirection(dragY = 500f, thresholdPx = -1f))
    }

    @Test
    fun `收紧态与展开态互斥`() {
        assertEquals(false, BottomDockMode.EXPANDED.isCompact)
        assertEquals(true, BottomDockMode.COMPACT.isCompact)
    }

    @Test
    fun `导航栏收成单钮后点击走回顶而不是切标签`() {
        // 还没开始收紧：点标签就是切标签
        assertEquals(false, shouldScrollToTopOnDockTap(0f))
        assertEquals(false, shouldScrollToTopOnDockTap(0.2f))
        // 非当前标签淡出之后：导航栏实际只剩一颗圆钮，点它回顶
        assertEquals(true, shouldScrollToTopOnDockTap(CompactDockItemsFadeSpan))
        assertEquals(true, shouldScrollToTopOnDockTap(0.5f))
        assertEquals(true, shouldScrollToTopOnDockTap(1f))
    }

    @Test
    fun `单钮阈值早于导航栏收完`() {
        assertEquals(true, CompactDockItemsFadeSpan < 1f)
        assertEquals(true, CompactDockItemsFadeSpan > 0f)
    }

    @Test
    fun `回顶注册与调用`() {
        val reporter = BottomDockScrollReporter()
        assertNull(reporter.scrollToTop)
        // 没有注册动作时调用不应崩溃
        reporter.requestScrollToTop()

        var calls = 0
        reporter.scrollToTop = { calls++ }
        reporter.requestScrollToTop()
        reporter.requestScrollToTop()
        assertEquals(2, calls)
    }

    @Test
    fun `点收紧圆钮同时恢复展开态`() {
        val reporter = BottomDockScrollReporter()
        var expanded = false
        var scrolledToTop = false
        reporter.onDockTap = { expanded = true }
        reporter.scrollToTop = { scrolledToTop = true }

        reporter.requestScrollToTop()

        // 手势驱动后滚动不再改形态，所以点圆钮必须显式恢复展开
        assertEquals(true, expanded)
        assertEquals(true, scrolledToTop)
    }

    @Test
    fun `页面滚动上报不再改形态`() {
        val reporter = BottomDockScrollReporter()
        var scrollToTopCalls = 0
        reporter.scrollToTop = { scrollToTopCalls++ }
        // 各页面仍会调用 report（保留接口兼容），但它只回顶、不影响形态
        reporter.report(Any(), -9999)
        reporter.report(Any(), 0)
        assertEquals(0, scrollToTopCalls)
    }
}
