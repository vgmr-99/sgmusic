package moe.ouom.neriplayer.ui.screen.nowplaying

import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingActionBarStyle
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingButtonStyle
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingProgressStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 三类播放页样式枚举的持久化兼容性测试。
 *
 * 这些值直接写进 DataStore，任何解析错误都会让用户的偏好丢失，
 * 因此锁定：旧值仍然有效、未知值回退到默认、默认值本身可往返。
 */
class NowPlayingControlStyleStorageTest {

    @Test
    fun `progress style keeps every stored value readable`() {
        NowPlayingProgressStyle.entries.forEach { style ->
            assertEquals(style, NowPlayingProgressStyle.fromStorage(style.storageValue))
        }
        assertEquals(
            NowPlayingProgressStyle.WAVE,
            NowPlayingProgressStyle.fromStorage(NowPlayingProgressStyle.DEFAULT_STORAGE_VALUE)
        )
    }

    @Test
    fun `progress style falls back to wave for unknown values`() {
        assertEquals(NowPlayingProgressStyle.WAVE, NowPlayingProgressStyle.fromStorage(null))
        assertEquals(NowPlayingProgressStyle.WAVE, NowPlayingProgressStyle.fromStorage(""))
        assertEquals(NowPlayingProgressStyle.WAVE, NowPlayingProgressStyle.fromStorage("unknown"))
    }

    @Test
    fun `button style keeps every stored value readable`() {
        NowPlayingButtonStyle.entries.forEach { style ->
            assertEquals(style, NowPlayingButtonStyle.fromStorage(style.storageValue))
        }
        assertEquals(
            NowPlayingButtonStyle.CLASSIC,
            NowPlayingButtonStyle.fromStorage(NowPlayingButtonStyle.DEFAULT_STORAGE_VALUE)
        )
        assertEquals(NowPlayingButtonStyle.CLASSIC, NowPlayingButtonStyle.fromStorage("garbage"))
    }

    @Test
    fun `action bar style keeps every stored value readable`() {
        NowPlayingActionBarStyle.entries.forEach { style ->
            assertEquals(style, NowPlayingActionBarStyle.fromStorage(style.storageValue))
        }
        assertEquals(
            NowPlayingActionBarStyle.DOCK,
            NowPlayingActionBarStyle.fromStorage(NowPlayingActionBarStyle.DEFAULT_STORAGE_VALUE)
        )
        assertEquals(NowPlayingActionBarStyle.DOCK, NowPlayingActionBarStyle.fromStorage(null))
        assertEquals(3, NowPlayingActionBarStyle.entries.size)
    }

    @Test
    fun `stored values never collide inside each family`() {
        // 三类控件各写各的 DataStore key，跨族重名无害，但族内必须唯一
        val progress = NowPlayingProgressStyle.entries.map { it.storageValue }
        val buttons = NowPlayingButtonStyle.entries.map { it.storageValue }
        val actionBars = NowPlayingActionBarStyle.entries.map { it.storageValue }
        assertEquals(progress.size, progress.toSet().size)
        assertEquals(buttons.size, buttons.toSet().size)
        assertEquals(actionBars.size, actionBars.toSet().size)
    }
}
