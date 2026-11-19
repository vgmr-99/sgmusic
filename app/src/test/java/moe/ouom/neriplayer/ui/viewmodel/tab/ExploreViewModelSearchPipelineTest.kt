package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExploreViewModel 搜索流水线纯函数回归测试。
 *
 * 这些函数决定搜索结果的排序、分页合并与"是否加载下一页"，属于高频调用且
 * 边界条件易错的逻辑；此前只有映射/门控测试覆盖，这里补齐流水线本身。
 */
class ExploreViewModelSearchPipelineTest {

    private fun song(id: Long, name: String, artist: String = "歌手"): SongItem = SongItem(
        id = id,
        name = name,
        artist = artist,
        album = "专辑",
        albumId = id,
        durationMs = 180_000L,
        coverUrl = null,
        channelId = "netease"
    )

    private fun result(id: Long, name: String = "歌$id") =
        ExploreSearchResult.Song(song(id, name))

    // ---------- mergeExploreSearchResults ----------

    @Test
    fun `merge keeps existing order and appends only new stable keys`() {
        val existing = listOf(result(1), result(2))
        val incoming = listOf(result(2), result(3), result(1), result(4))

        val merged = mergeExploreSearchResults(existing, incoming)

        assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { (it as ExploreSearchResult.Song).song.id })
    }

    @Test
    fun `merge with empty side returns the other side deduplicated`() {
        val items = listOf(result(1), result(1), result(2))

        assertEquals(2, mergeExploreSearchResults(emptyList(), items).size)
        assertSame(items, mergeExploreSearchResults(items, emptyList()))
    }

    @Test
    fun `different channels with same numeric id are distinct songs`() {
        val netease = result(1)
        val qq = ExploreSearchResult.Song(song(1, "同一首").copy(channelId = "qq"))

        assertEquals(2, mergeExploreSearchResults(listOf(netease), listOf(qq)).size)
    }

    // ---------- hasMoreExploreSearchResults ----------

    @Test
    fun `hasMore uses total count when the server reports it`() {
        assertTrue(hasMoreExploreSearchResults(totalCount = 100, loadedCount = 20, pageItemCount = 20, pageSize = 20))
        assertFalse(hasMoreExploreSearchResults(totalCount = 20, loadedCount = 20, pageItemCount = 20, pageSize = 20))
        // totalCount 为 0 时即使本页满也不应继续
        assertFalse(hasMoreExploreSearchResults(totalCount = 0, loadedCount = 0, pageItemCount = 20, pageSize = 20))
    }

    @Test
    fun `hasMore falls back to a full page when total is unknown`() {
        assertTrue(hasMoreExploreSearchResults(totalCount = null, loadedCount = 20, pageItemCount = 20, pageSize = 20))
        assertFalse(hasMoreExploreSearchResults(totalCount = null, loadedCount = 15, pageItemCount = 15, pageSize = 20))
    }

    // ---------- shouldLoadExploreSearchMore ----------

    @Test
    fun `loadMore triggers only within prefetch distance`() {
        assertTrue(shouldLoadExploreSearchMore(resultCount = 20, lastVisibleItemIndex = 15, hasMore = true, searching = false, loadingMore = false))
        assertFalse(shouldLoadExploreSearchMore(resultCount = 20, lastVisibleItemIndex = 10, hasMore = true, searching = false, loadingMore = false))
    }

    @Test
    fun `loadMore is blocked while searching loading or failed`() {
        val args = { hasMore: Boolean, searching: Boolean, loading: Boolean, failed: Boolean ->
            shouldLoadExploreSearchMore(
                resultCount = 20, lastVisibleItemIndex = 19, hasMore = hasMore,
                searching = searching, loadingMore = loading, loadMoreFailed = failed
            )
        }
        assertFalse(args(false, false, false, false))
        assertFalse(args(true, true, false, false))
        assertFalse(args(true, false, true, false))
        assertFalse(args(true, false, false, true))
        assertTrue(args(true, false, false, false))
    }

    @Test
    fun `loadMore needs a known visible index and a non empty list`() {
        assertFalse(shouldLoadExploreSearchMore(20, null, true, false, false))
        assertFalse(shouldLoadExploreSearchMore(0, 0, true, false, false))
    }

    // ---------- rankExploreSongSearchResults ----------

    @Test
    fun `ranking passes through blank query and single song untouched`() {
        val songs = listOf(song(1, "晴天"), song(2, "发如雪"))
        assertSame(songs, rankExploreSongSearchResults("  ", songs))
        val single = listOf(song(1, "晴天"))
        assertSame(single, rankExploreSongSearchResults("晴天", single))
    }

    @Test
    fun `ranking puts exact name match first and keeps unmatched at the tail`() {
        val songs = listOf(
            song(1, "周杰伦 其他作品"),
            song(2, "晴天", artist = "周杰伦"),
            song(3, "晴天Live")
        )

        val ranked = rankExploreSongSearchResults("晴天", songs).map { it.id }

        assertEquals(2L, ranked.first())
        assertEquals(3, ranked.size)
        assertTrue(ranked.containsAll(listOf(1L, 2L, 3L)))
    }

    @Test
    fun `ranking is stable for equal scores`() {
        val songs = listOf(song(1, "晴天 A"), song(2, "晴天 B"), song(3, "晴天 C"))

        val ranked = rankExploreSongSearchResults("晴天", songs).map { it.id }

        assertEquals(listOf(1L, 2L, 3L), ranked)
    }
}
