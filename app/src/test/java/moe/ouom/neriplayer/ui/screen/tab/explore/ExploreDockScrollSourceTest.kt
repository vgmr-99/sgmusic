package moe.ouom.neriplayer.ui.screen.tab.explore

import moe.ouom.neriplayer.ui.viewmodel.tab.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 探索页底部两栏的滚动源选择。
 *
 * 探索页是 HorizontalPager，多个页面各有自己的滚动容器。
 * 只上报当前页的容器：既修掉「上报了一个没绑任何列表的 state」，
 * 也避免滚过的旧页面把底栏一直顶在收紧态。
 */
class ExploreDockScrollSourceTest {

    @Test
    fun `搜索态优先用搜索结果列表`() {
        // 即使当前 pager 页是网易云，只要有搜索词，滚的就是结果列表
        assertEquals(
            ExploreDockScrollSource.SEARCH,
            resolveExploreDockScrollSource(true, SearchSource.NETEASE)
        )
        assertEquals(
            ExploreDockScrollSource.SEARCH,
            resolveExploreDockScrollSource(true, SearchSource.YOUTUBE_MUSIC)
        )
    }

    @Test
    fun `各平台浏览页用宿主传入的网格`() {
        val browseSources = listOf(
            SearchSource.NETEASE,
            SearchSource.QQ_MUSIC,
            SearchSource.KUGOU,
            SearchSource.KUWO,
            SearchSource.MIGU
        )
        browseSources.forEach { source ->
            assertEquals(
                "source=$source",
                ExploreDockScrollSource.BROWSE,
                resolveExploreDockScrollSource(false, source)
            )
        }
    }

    @Test
    fun `YouTube 页用自己的网格`() {
        assertEquals(
            ExploreDockScrollSource.YOUTUBE,
            resolveExploreDockScrollSource(false, SearchSource.YOUTUBE_MUSIC)
        )
    }

    @Test
    fun `无可滚动内容的页按展开态处理`() {
        assertEquals(
            ExploreDockScrollSource.NONE,
            resolveExploreDockScrollSource(false, SearchSource.BILIBILI)
        )
        assertEquals(
            ExploreDockScrollSource.NONE,
            resolveExploreDockScrollSource(false, SearchSource.LINK_RECOGNITION)
        )
        assertEquals(
            ExploreDockScrollSource.NONE,
            resolveExploreDockScrollSource(false, null)
        )
    }
}
