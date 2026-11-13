package moe.ouom.neriplayer.platform.lxsource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 洛雪平台与应用内 channelId 的对应关系。
 *
 * 两边字符串不一样（wy/netease、kg/kugou、tx/qq、qs/qishui），
 * 对错会导致播放时用错源，或者明明有 id 却还去重新搜一遍。
 */
class LxPlatformChannelTest {

    @Test
    fun `channel id differs from the lx source code`() {
        assertEquals("wy", LxPlatform.WY.code)
        assertEquals("netease", LxPlatform.WY.channelId())
        assertEquals("kg", LxPlatform.KG.code)
        assertEquals("kugou", LxPlatform.KG.channelId())
        assertEquals("tx", LxPlatform.TX.code)
        assertEquals("qq", LxPlatform.TX.channelId())
        assertEquals("qs", LxPlatform.QS.code)
        assertEquals("qishui", LxPlatform.QS.channelId())
        assertEquals("kw", LxPlatform.KW.code)
        assertEquals("kw", LxPlatform.KW.channelId())
        assertEquals("mg", LxPlatform.MG.code)
        assertEquals("mg", LxPlatform.MG.channelId())
    }

    @Test
    fun `platform is resolved back from the app channel id`() {
        assertEquals(LxPlatform.WY, LxPlatform.forChannelId("netease"))
        assertEquals(LxPlatform.KG, LxPlatform.forChannelId("kugou"))
        assertEquals(LxPlatform.TX, LxPlatform.forChannelId("qq"))
        assertEquals(LxPlatform.QS, LxPlatform.forChannelId("qishui"))
        assertEquals(LxPlatform.KW, LxPlatform.forChannelId("kw"))
        assertEquals(LxPlatform.MG, LxPlatform.forChannelId("mg"))
    }

    @Test
    fun `unknown channel ids resolve to nothing`() {
        assertNull(LxPlatform.forChannelId(null))
        assertNull(LxPlatform.forChannelId(""))
        assertNull(LxPlatform.forChannelId("bilibili"))
        // 洛雪 source code 不是应用内的 channelId
        assertNull(LxPlatform.forChannelId("wy"))
        assertNull(LxPlatform.forChannelId("tx"))
    }
}
