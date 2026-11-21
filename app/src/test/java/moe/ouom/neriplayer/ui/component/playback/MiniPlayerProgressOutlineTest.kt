package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 收紧态播放栏边框进度的计算。
 *
 * 进度线要严格贴着胶囊周长走，段长和无时长兜底都必须可验证，
 * 否则真机上很容易出现「进度乱跳」或「直播画假进度」。
 */
class MiniPlayerProgressOutlineTest {

    @Test
    fun `播放进度按位置除以时长并夹在零到一`() {
        assertEquals(0f, resolvePlaybackProgress(0L, 200L), 0.0001f)
        assertEquals(0.5f, resolvePlaybackProgress(100L, 200L), 0.0001f)
        assertEquals(1f, resolvePlaybackProgress(300L, 200L), 0.0001f)
    }

    @Test
    fun `未知时长不画假进度`() {
        assertEquals(0f, resolvePlaybackProgress(10_000L, 0L), 0.0001f)
        assertEquals(0f, resolvePlaybackProgress(10_000L, -1L), 0.0001f)
    }

    @Test
    fun `负位置被当成零`() {
        assertEquals(0f, resolvePlaybackProgress(-500L, 200L), 0.0001f)
    }

    @Test
    fun `胶囊周长等于两条直线加整圆`() {
        // 宽 300、高 60、圆角半径 30 → 两段直线各 240 + 一个整圆周长
        val perimeter = resolveRoundedRectPerimeter(Size(300f, 60f), 30f)
        val expected = 240f * 2f + 2f * Math.PI.toFloat() * 30f
        assertEquals(expected, perimeter, 0.01f)
    }

    @Test
    fun `圆角半径不会超过短边一半`() {
        val perimeter = resolveRoundedRectPerimeter(Size(300f, 60f), 999f)
        val expected = 240f * 2f + 2f * Math.PI.toFloat() * 30f
        assertEquals(expected, perimeter, 0.01f)
    }

    @Test
    fun `进度段长度等于周长乘进度`() {
        val intervals = resolveProgressDashIntervals(1000f, 0.25f)
        assertEquals(true, intervals != null)
        assertEquals(250f, intervals!![0], 0.001f)
        assertEquals(750f, intervals[1], 0.001f)
    }

    @Test
    fun `零进度不画避免 dash 异常`() {
        assertNull(resolveProgressDashIntervals(1000f, 0f))
        assertNull(resolveProgressDashIntervals(1000f, 0.0005f))
        assertNull(resolveProgressDashIntervals(0f, 0.5f))
    }

    @Test
    fun `满进度时 off 至少留一像素`() {
        val intervals = resolveProgressDashIntervals(1000f, 1f)
        assertEquals(true, intervals != null)
        assertTrue(intervals!![1] >= 1f)
        // dash 的 on + off 必须覆盖整圈，跑马灯相位才不会露出断口
        assertTrue(abs(intervals[0] + intervals[1] - 1000f) < 2f)
    }
}
