package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import moe.ouom.neriplayer.ui.component.playback.NowPlayingActionBarShape
import moe.ouom.neriplayer.ui.component.playback.requiresSurface
import moe.ouom.neriplayer.ui.component.playback.usesGlassSurface
import moe.ouom.neriplayer.ui.component.playback.usesLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底部功能键形态决策测试。
 *
 * 关键约束：用户关掉「工具栏底座」设置后，悬浮底座形态必须降级为纯图标，
 * 既有的底座开关不能因为新增样式而失效。
 */
class NowPlayingActionBarShapeTest {

    @Test
    fun `dock degrades to plain when the dock setting is off`() {
        assertEquals(
            NowPlayingActionBarShape.PLAIN,
            resolveNowPlayingActionBarShape(NowPlayingActionBarShape.DOCK, dockEnabled = false)
        )
    }

    @Test
    fun `label style is never affected by the dock setting`() {
        assertEquals(
            NowPlayingActionBarShape.LABEL,
            resolveNowPlayingActionBarShape(NowPlayingActionBarShape.LABEL, dockEnabled = false)
        )
        assertEquals(
            NowPlayingActionBarShape.LABEL,
            resolveNowPlayingActionBarShape(NowPlayingActionBarShape.LABEL, dockEnabled = true)
        )
    }

    @Test
    fun `surface and label flags match the style contract`() {
        assertTrue(NowPlayingActionBarShape.DOCK.requiresSurface())
        assertTrue(NowPlayingActionBarShape.GLASS.requiresSurface())
        assertFalse(NowPlayingActionBarShape.LABEL.requiresSurface())
        assertFalse(NowPlayingActionBarShape.PLAIN.requiresSurface())

        assertTrue(NowPlayingActionBarShape.LABEL.usesLabels())
        assertFalse(NowPlayingActionBarShape.DOCK.usesLabels())
        assertFalse(NowPlayingActionBarShape.PLAIN.usesLabels())
    }

    @Test
    fun `glass is the only style with a translucent surface`() {
        assertTrue(NowPlayingActionBarShape.GLASS.usesGlassSurface())
        assertFalse(NowPlayingActionBarShape.DOCK.usesGlassSurface())
        assertFalse(NowPlayingActionBarShape.LABEL.usesGlassSurface())
    }
}
