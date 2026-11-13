package moe.ouom.neriplayer.platform.search.api

import moe.ouom.neriplayer.platform.lxsource.LxPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 浏览页公共工具的测试。
 */
class PlatformBrowseSupportTest {

    @Test
    fun `play count parses plain numbers`() {
        assertEquals(12345L, PlatformBrowseSupport.parsePlayCount("12345"))
        assertEquals(12345L, PlatformBrowseSupport.parsePlayCount(12345))
        assertEquals(12345L, PlatformBrowseSupport.parsePlayCount("12,345"))
    }

    @Test
    fun `play count parses chinese units`() {
        assertEquals(12000L, PlatformBrowseSupport.parsePlayCount("1.2万"))
        assertEquals(350000000L, PlatformBrowseSupport.parsePlayCount("3.5亿"))
        assertEquals(12_340_000L, PlatformBrowseSupport.parsePlayCount("1234万次"))
        assertEquals(916685L, PlatformBrowseSupport.parsePlayCount("916685"))
    }

    @Test
    fun `play count falls back to zero instead of throwing`() {
        assertEquals(0L, PlatformBrowseSupport.parsePlayCount(null))
        assertEquals(0L, PlatformBrowseSupport.parsePlayCount(""))
        assertEquals(0L, PlatformBrowseSupport.parsePlayCount("暂无"))
    }

    @Test
    fun `play count formats compactly`() {
        assertEquals("1.2万", PlatformBrowseSupport.formatPlayCount(12_345L))
        assertEquals("3.5亿", PlatformBrowseSupport.formatPlayCount(350_000_000L))
        assertEquals("999", PlatformBrowseSupport.formatPlayCount(999L))
        assertEquals("", PlatformBrowseSupport.formatPlayCount(0L))
    }

    @Test
    fun `card id keeps the source prefix`() {
        val id = PlatformBrowseSupport.qualifyId(PlatformSearchProvider.KUGOU, "HASH123")
        assertEquals("kg_HASH123", id)
        // 已经有前缀时不重复加
        assertEquals(id, PlatformBrowseSupport.qualifyId(PlatformSearchProvider.KUGOU, id))
    }

    @Test
    fun `source prefix is stripped and detected`() {
        val id = PlatformBrowseSupport.qualifyId(PlatformSearchProvider.KUGOU, "HASH123")
        assertEquals("HASH123", PlatformBrowseSupport.stripProviderPrefix(id))
        assertEquals(PlatformSearchProvider.KUGOU, PlatformBrowseSupport.providerOf(id))
        assertNull(PlatformBrowseSupport.providerOf("no-prefix"))
    }

    @Test
    fun `netease channel id differs from the lx source code`() {
        assertEquals("netease", LxPlatform.WY.channelId())
        assertEquals("kw", LxPlatform.KW.channelId())
        assertEquals("wy", LxPlatform.WY.code)
    }
}
