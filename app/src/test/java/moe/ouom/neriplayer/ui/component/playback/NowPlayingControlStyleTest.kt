package moe.ouom.neriplayer.ui.component.playback

import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingActionBarStyle
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingButtonStyle
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingProgressStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页控件样式解析单测。
 *
 * 锁定解耦后的核心契约：
 * 1. 进度条样式 → 形状是一一对应的完整映射
 * 2. 播放键样式 → 图标风格 + 容器形态
 * 3. 底部功能键样式 → 工具栏容器形态
 * 4. 三类控件互相独立，互不影响
 */
class NowPlayingControlStyleTest {

    @Test
    fun `every progress style maps to its own shape`() {
        val shapes = NowPlayingProgressStyle.entries.map(::resolveProgressShape)
        assertEquals(3, shapes.size)
        assertEquals(3, shapes.toSet().size)
        assertEquals(NowPlayingProgressShape.WAVE, resolveProgressShape(NowPlayingProgressStyle.WAVE))
        assertEquals(
            NowPlayingProgressShape.SEGMENTED,
            resolveProgressShape(NowPlayingProgressStyle.SEGMENTED)
        )
        assertEquals(
            NowPlayingProgressShape.CAPSULE,
            resolveProgressShape(NowPlayingProgressStyle.CAPSULE)
        )
    }

    @Test
    fun `classic button style keeps the original outlined icons and filled circle`() {
        val spec = resolveNowPlayingButtonSpec(NowPlayingButtonStyle.CLASSIC)
        assertEquals(NowPlayingIconStyle.OUTLINED, spec.iconStyle)
        assertEquals(NowPlayingButtonShape.CIRCLE, spec.shape)
    }

    @Test
    fun `ring and flat change the play button container`() {
        assertEquals(
            NowPlayingButtonShape.RING,
            resolveNowPlayingButtonSpec(NowPlayingButtonStyle.RING).shape
        )
        assertEquals(
            NowPlayingButtonShape.FLAT,
            resolveNowPlayingButtonSpec(NowPlayingButtonStyle.FLAT).shape
        )
    }

    @Test
    fun `flat style is the only one without a container`() {
        NowPlayingButtonStyle.entries.forEach { style ->
            val shape = resolveNowPlayingButtonSpec(style).shape
            assertEquals(
                style != NowPlayingButtonStyle.FLAT,
                shape.hasContainer()
            )
        }
    }

    @Test
    fun `every action bar style maps to its own container shape`() {
        val shapes = NowPlayingActionBarStyle.entries.map(::resolveNowPlayingActionBarShape)
        assertEquals(3, shapes.size)
        assertEquals(3, shapes.toSet().size)
    }

    @Test
    fun `the three control families stay independent`() {
        // 换一个控件样式不能影响另外两个
        val progressBefore = resolveProgressShape(NowPlayingProgressStyle.WAVE)
        val buttonBefore = resolveNowPlayingButtonSpec(NowPlayingButtonStyle.CLASSIC)
        val actionBefore = resolveNowPlayingActionBarShape(NowPlayingActionBarStyle.DOCK)

        resolveProgressShape(NowPlayingProgressStyle.CAPSULE)
        resolveNowPlayingButtonSpec(NowPlayingButtonStyle.FLAT)
        resolveNowPlayingActionBarShape(NowPlayingActionBarStyle.LABEL)

        assertEquals(progressBefore, resolveProgressShape(NowPlayingProgressStyle.WAVE))
        assertEquals(buttonBefore, resolveNowPlayingButtonSpec(NowPlayingButtonStyle.CLASSIC))
        assertEquals(actionBefore, resolveNowPlayingActionBarShape(NowPlayingActionBarStyle.DOCK))
        assertNotEquals(
            resolveNowPlayingActionBarShape(NowPlayingActionBarStyle.DOCK),
            resolveNowPlayingActionBarShape(NowPlayingActionBarStyle.LABEL)
        )
    }

    @Test
    fun `removed progress styles fall back to wave instead of crashing`() {
        // 旧版本写进 DataStore 的 linear/dots/stripe 必须安全回退
        assertTrue(
            NowPlayingProgressStyle.fromStorage("linear") == NowPlayingProgressStyle.WAVE
        )
        assertTrue(
            NowPlayingProgressStyle.fromStorage("dots") == NowPlayingProgressStyle.WAVE
        )
    }
}
