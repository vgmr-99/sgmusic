package moe.ouom.neriplayer.platform.search.api.client.mint

import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mint 来源代号与平台枚举的映射。
 *
 * 这层映射一旦出错，界面就会把某个平台的卡片当成另一个平台渲染，
 * 症状是「点开歌单报网络错」这种很难定位的问题，
 * 所以用测试把五个真实来源代号钉死。
 */
class MintProviderLookupTest {

    @Test
    fun `mint provider codes map to their own platform`() {
        assertEquals(PlatformSearchProvider.NETEASE, MintProviderLookup.providerOf("wy"))
        assertEquals(PlatformSearchProvider.KUGOU, MintProviderLookup.providerOf("kg"))
        assertEquals(PlatformSearchProvider.KUWO, MintProviderLookup.providerOf("kw"))
        assertEquals(PlatformSearchProvider.QQ, MintProviderLookup.providerOf("tx"))
        assertEquals(PlatformSearchProvider.MIGU, MintProviderLookup.providerOf("mg"))
    }

    @Test
    fun `provider code also matches the enum code`() {
        assertEquals(PlatformSearchProvider.NETEASE, MintProviderLookup.providerOf("netease"))
        assertEquals(PlatformSearchProvider.KUGOU, MintProviderLookup.providerOf("kugou"))
        assertEquals(PlatformSearchProvider.QQ, MintProviderLookup.providerOf("qq"))
        // 酷我 / 咪咕的 code 就是两字母缩写（kw / mg），不是全拼
        assertEquals(PlatformSearchProvider.KUWO, MintProviderLookup.providerOf("kw"))
        assertEquals(PlatformSearchProvider.MIGU, MintProviderLookup.providerOf("mg"))
    }

    @Test
    fun `unknown provider code is rejected instead of silently becoming another platform`() {
        // 以前这里会兜底成汽水，汽水被移除后这个兜底就是个定时炸弹：
        // 来源代号写错时不会报错，而是把卡片当成汽水的歌单渲染。
        assertNull(MintProviderLookup.providerOfOrNull("unknown"))
        val error = runCatching { MintProviderLookup.providerOf("unknown") }.exceptionOrNull()
        assertEquals(true, error is IllegalArgumentException)
    }

    @Test
    fun `netease provider is registered in the enum`() {
        assertEquals(true, MintProviderLookup.hasNeteaseProvider())
    }
}
