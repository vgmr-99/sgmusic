package moe.ouom.neriplayer.core.player.resolver.lxsource

import moe.ouom.neriplayer.platform.lxsource.LxQuality
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试：播放页切换 LX 音质时，全部 8 档（含 hires/atmos/master）都必须能正确映射，
 * 不能像过去那样被错误地打成 320k；未知值才回退 320k。
 */
class LxQualitySwitchTest {

    @Test
    fun `all eight quality codes map to their enum`() {
        val all = listOf(
            "128k" to LxQuality.Q128,
            "192k" to LxQuality.Q192,
            "320k" to LxQuality.Q320,
            "flac" to LxQuality.FLAC,
            "flac24bit" to LxQuality.FLAC24,
            "hires" to LxQuality.HIRES,
            "atmos" to LxQuality.ATMOS,
            "master" to LxQuality.MASTER,
        )
        all.forEach { (code, expected) ->
            assertEquals(code, expected, lxQualityFromCode(code))
        }
        assertEquals(LxQuality.entries.size, all.size)
    }

    @Test
    fun `mapping is case and whitespace insensitive`() {
        assertEquals(LxQuality.HIRES, lxQualityFromCode("  HIRES  "))
        assertEquals(LxQuality.FLAC, lxQualityFromCode("Flac"))
        assertEquals(LxQuality.MASTER, lxQualityFromCode("\tmaster\n"))
    }

    @Test
    fun `unknown code falls back to 320k`() {
        assertEquals(LxQuality.Q320, lxQualityFromCode(""))
        assertEquals(LxQuality.Q320, lxQualityFromCode("unknown"))
        assertEquals(LxQuality.Q320, lxQualityFromCode("无损"))
    }
}
