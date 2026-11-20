package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.platform.search.api.PlatformBrowseCard
import moe.ouom.neriplayer.platform.search.api.PlatformSearchProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 平台浏览卡片的去重规则。
 *
 * 浏览页的卡片网格用「平台 + 卡片 id」当 LazyVerticalGrid 的 key，
 * 上游返回重复卡片时 Compose 会抛 IllegalArgumentException 直接崩 App，
 * 所以数据进 state 之前必须按同样的口径去重。
 */
class ExplorePlatformBrowseCardDedupeTest {

    private fun card(
        id: String,
        provider: PlatformSearchProvider = PlatformSearchProvider.QISHUI
    ) = PlatformBrowseCard(
        id = id,
        title = "歌单 $id",
        provider = provider
    )

    @Test
    fun `duplicate cards of the same platform are removed`() {
        val cards = listOf(
            card("qs_1"),
            card("qs_1"),
            card("qs_2")
        )

        val deduped = dedupePlatformBrowseCards(cards)

        assertEquals(listOf("qs_1", "qs_2"), deduped.map { it.id })
    }

    @Test
    fun `cards of different platforms with the same id are kept`() {
        val cards = listOf(
            card("1001", PlatformSearchProvider.QISHUI),
            card("1001", PlatformSearchProvider.QQ)
        )

        val deduped = dedupePlatformBrowseCards(cards)

        assertEquals(2, deduped.size)
    }

    @Test
    fun `dedupe keeps the first occurrence and its order`() {
        val cards = listOf(
            card("qs_3"),
            card("qs_1"),
            card("qs_3")
        )

        val deduped = dedupePlatformBrowseCards(cards)

        assertEquals(listOf("qs_3", "qs_1"), deduped.map { it.id })
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(0, dedupePlatformBrowseCards(emptyList()).size)
    }
}
