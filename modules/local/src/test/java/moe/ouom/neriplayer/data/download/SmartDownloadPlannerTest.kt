package moe.ouom.neriplayer.data.download

import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartDownloadPlannerTest {

    private fun song(id: Long, name: String) = SongItem(
        id = id,
        name = name,
        artist = "Artist $id",
        album = "Album $id",
        albumId = id,
        durationMs = 180_000L,
        coverUrl = null
    )

    @Test
    fun `select respects the max count`() {
        val candidates = (1L..10L).map { song(it, "Song $it") }

        val selected = SmartDownloadPlanner.select(
            candidates = candidates,
            playCountThreshold = 1,
            maxCount = 3,
            alreadyDownloaded = emptySet()
        )

        assertEquals(3, selected.size)
        assertEquals("Song 1", selected.first().name)
    }

    @Test
    fun `select skips already downloaded songs`() {
        val candidates = listOf(song(1L, "One"), song(2L, "Two"), song(3L, "Three"))

        val selected = SmartDownloadPlanner.select(
            candidates = candidates,
            playCountThreshold = 1,
            maxCount = 10,
            alreadyDownloaded = setOf(candidates[1].stableKey())
        )

        assertEquals(2, selected.size)
        assertTrue(selected.none { it.name == "Two" })
    }

    @Test
    fun `select removes duplicated entries of the same song`() {
        val candidates = listOf(song(1L, "Same"), song(1L, "Same"), song(2L, "Other"))

        val selected = SmartDownloadPlanner.select(
            candidates = candidates,
            playCountThreshold = 1,
            maxCount = 10,
            alreadyDownloaded = emptySet()
        )

        assertEquals(2, selected.size)
    }

    @Test
    fun `select returns nothing for invalid thresholds`() {
        val candidates = listOf(song(1L, "One"))

        assertTrue(
            SmartDownloadPlanner.select(candidates, playCountThreshold = 0, maxCount = 5, alreadyDownloaded = emptySet())
                .isEmpty()
        )
        assertTrue(
            SmartDownloadPlanner.select(candidates, playCountThreshold = 1, maxCount = 0, alreadyDownloaded = emptySet())
                .isEmpty()
        )
    }
}
