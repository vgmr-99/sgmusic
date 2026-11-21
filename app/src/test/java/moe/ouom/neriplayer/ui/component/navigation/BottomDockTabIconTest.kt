package moe.ouom.neriplayer.ui.component.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import moe.ouom.neriplayer.navigation.Destinations
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * 收紧态圆钮的图标判定。
 *
 * 媒体库有不少顶层子页面（播放统计、最近播放等），它们不在 library 的
 * NavGraph 下面，destination hierarchy 里根本没有 library route，
 * 只靠 hierarchy 判定会得到 -1 → 圆钮空白。
 * 现在 App 层传入权威的 selectedMainTabRoute，子页面也能正确显示当前 Tab 图标。
 */
class BottomDockTabIconTest {

    private val items = listOf(
        Destinations.Home to Icons.Filled.Home,
        Destinations.Explore to Icons.Filled.Search,
        Destinations.Library to Icons.Filled.LibraryMusic,
        Destinations.Settings to Icons.Filled.Settings
    )

    @Test
    fun `顶层子页面没有 tab 祖先时靠权威 route 判定`() {
        // 播放统计是顶层 route，parent 是不带 tab route 的 root graph
        val root = graph(route = null, parent = null)
        val playbackStats = destination(route = "playback_stats", parent = root)

        // 没有权威 route 时找不到（这正是原来圆钮空白的场景）
        assertEquals(-1, resolveSelectedBottomBarIndex(items, playbackStats, null))

        // 传入媒体库的权威 route 后，正确解析为媒体库图标
        assertEquals(
            2,
            resolveSelectedBottomBarIndex(items, playbackStats, Destinations.Library.route)
        )
    }

    @Test
    fun `权威 route 优先于 hierarchy 匹配`() {
        val library = graph(route = "library", parent = graph(route = null, parent = null))
        val detail = destination(route = "playlist_detail", parent = library)

        // hierarchy 能匹配到媒体库，但权威 route 说是设置，以权威为准
        assertEquals(
            3,
            resolveSelectedBottomBarIndex(items, detail, Destinations.Settings.route)
        )
    }

    @Test
    fun `权威 route 无匹配时退回 hierarchy`() {
        val explore = graph(route = "explore", parent = graph(route = null, parent = null))
        val detail = destination(route = "search_results", parent = explore)

        assertEquals(
            1,
            resolveSelectedBottomBarIndex(items, detail, "unknown_route")
        )
    }

    @Test
    fun `紧凑态可见项用权威 route 解析`() {
        val root = graph(route = null, parent = null)
        val playbackStats = destination(route = "playback_stats", parent = root)

        assertEquals(
            listOf(2),
            resolveVisibleBottomBarItems(
                items = items,
                currentDestination = playbackStats,
                compact = true,
                selectedTabRoute = Destinations.Library.route
            )
        )
    }

    @Test
    fun `展开态保留全部标签`() {
        val root = graph(route = null, parent = null)
        val stats = destination(route = "playback_stats", parent = root)
        assertEquals(
            items.indices.toList(),
            resolveVisibleBottomBarItems(items, stats, false, Destinations.Library.route)
        )
    }

    private fun destination(route: String?, parent: NavGraph? = null): NavDestination =
        mock(NavDestination::class.java).also { destination ->
            `when`(destination.route).thenReturn(route)
            `when`(destination.parent).thenReturn(parent)
        }

    private fun graph(route: String?, parent: NavGraph?): NavGraph =
        mock(NavGraph::class.java).also { graph ->
            `when`(graph.route).thenReturn(route)
            `when`(graph.parent).thenReturn(parent)
        }
}
