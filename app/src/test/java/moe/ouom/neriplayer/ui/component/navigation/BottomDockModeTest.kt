package moe.ouom.neriplayer.ui.component.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 底部两栏形态判定的单测。
 *
 * 现在用的是连续进度（0 = 展开，1 = 完全收紧），
 * 导航栏在前半段收成圆钮，迷你条在后半段收窄。
 */
class BottomDockModeTest {

    @Test
    fun `滚动越过阈值后进度随之上升`() {
        var progress = 0f
        val owner = Any()
        val reporter = BottomDockScrollReporter(thresholdPx = 96) { progress = it }
        reporter.report(owner, 0)
        assertEquals(0f, progress, 0.001f)
        reporter.report(owner, -200)
        // 阈值 96px，走满两个阈值距离才完全收紧：200 / 192 → 封顶到 1
        assertEquals(1f, progress, 0.01f)
        // 往回滚一点，进度随之下降（这里 -10 已经离开阈值区间，进度很小）
        reporter.report(owner, -10)
        assertEquals(0.052f, progress, 0.005f)
    }

    @Test
    fun `进度没有变化时不重复回调`() {
        var calls = 0
        val owner = Any()
        val reporter = BottomDockScrollReporter(thresholdPx = 96) { calls++ }
        // 同样的偏移重复上报，不会重复回调
        reporter.report(owner, -10)
        reporter.report(owner, -10)
        reporter.report(owner, -10)
        assertEquals(1, calls)
        // 偏移变了，进度变了，才再回调一次
        reporter.report(owner, -200)
        assertEquals(2, calls)
    }

    @Test
    fun `新页面接管时用自己的位置刷新进度`() {
        var progress = 0f
        val reporter = BottomDockScrollReporter(thresholdPx = 96) { progress = it }
        val settingsOwner = Any()
        val aboutOwner = Any()
        // 设置页已滚到底 -> 完全收紧
        reporter.report(settingsOwner, -300)
        assertEquals(1f, progress, 0.01f)
        // 关于页接管（偏移为 0）-> 立刻恢复展开
        reporter.report(aboutOwner, 0)
        assertEquals(0f, progress, 0.01f)
        // 关于页滚动 -> 再次收紧
        reporter.report(aboutOwner, -300)
        assertEquals(1f, progress, 0.01f)
    }

    @Test
    fun `未滚动时进度为零`() {
        assertEquals(0f, resolveBottomDockProgress(firstItemTopPx = 0, threshold = 96), 0.001f)
    }

    @Test
    fun `滚动一个阈值时进度过半`() {
        // 阈值距离走完，导航栏那一段就收完了
        assertEquals(0.5f, resolveBottomDockProgress(firstItemTopPx = -96, threshold = 96), 0.001f)
    }

    @Test
    fun `超过一个阈值后进度过半`() {
        val progress = resolveBottomDockProgress(firstItemTopPx = -97, threshold = 96)
        assertEquals(true, progress > 0.5f)
    }

    @Test
    fun `走满两个阈值距离后完全收紧`() {
        assertEquals(1f, resolveBottomDockProgress(firstItemTopPx = -192, threshold = 96), 0.001f)
        assertEquals(1f, resolveBottomDockProgress(firstItemTopPx = -1200, threshold = 96), 0.001f)
    }

    @Test
    fun `阈值为零时始终展开`() {
        assertEquals(0f, resolveBottomDockProgress(firstItemTopPx = -500, threshold = 0), 0.001f)
    }

    @Test
    fun `正值不触发收紧`() {
        // 位置为正说明内容在视口下方（还没滚过去）
        assertEquals(0f, resolveBottomDockProgress(firstItemTopPx = 200, threshold = 96), 0.001f)
    }

    @Test
    fun `导航栏那一段在前半程完成`() {
        assertEquals(0f, navCompactProgress(0f), 0.001f)
        assertEquals(1f, navCompactProgress(0.5f), 0.001f)
        assertEquals(1f, navCompactProgress(1f), 0.001f)
    }

    @Test
    fun `迷你条那一段在后半程进行`() {
        assertEquals(0f, miniCompactProgress(0f), 0.001f)
        assertEquals(0f, miniCompactProgress(0.5f), 0.001f)
        assertEquals(1f, miniCompactProgress(1f), 0.001f)
    }

    @Test
    fun `紧凑态与展开态互斥`() {
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
        // 收窄过程中非当前标签还在（只是变成右侧的隐形细条），
        // 这时候点下去不能按位置反查标签，否则会跳到别的标签上。
        assertEquals(true, CompactDockItemsFadeSpan < 1f)
        assertEquals(true, CompactDockItemsFadeSpan > 0f)
    }
}
