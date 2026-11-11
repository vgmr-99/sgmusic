package moe.ouom.neriplayer.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uPlaylistCodecTest {

    @Test
    fun `decode reads extended entries with title and duration`() {
        val text = """
            #EXTM3U
            #EXTINF:210,Artist A - Song A
            /storage/emulated/0/Music/song-a.mp3
            #EXTINF:185,Artist B - Song B
            /storage/emulated/0/Music/song-b.mp3
        """.trimIndent()

        val entries = M3uPlaylistCodec.decode(text)

        assertEquals(2, entries.size)
        assertEquals("/storage/emulated/0/Music/song-a.mp3", entries[0].location)
        assertEquals("Artist A - Song A", entries[0].title)
        assertEquals(210_000L, entries[0].durationMs)
        assertEquals("/storage/emulated/0/Music/song-b.mp3", entries[1].location)
        assertEquals(185_000L, entries[1].durationMs)
    }

    @Test
    fun `decode ignores comments and keeps bare path lines`() {
        val text = """
            #EXTM3U
            # just a comment
            /music/plain.mp3
            #PLAYLIST:My List
            /music/second.mp3
        """.trimIndent()

        val entries = M3uPlaylistCodec.decode(text)

        assertEquals(2, entries.size)
        assertEquals("/music/plain.mp3", entries[0].location)
        assertNull(entries[0].title)
        assertEquals("/music/second.mp3", entries[1].location)
    }

    @Test
    fun `split artist and title handles plain titles`() {
        val withArtist = M3uEntry(location = "/a.mp3", title = "Artist - Song")
        assertEquals("Artist" to "Song", withArtist.splitArtistAndTitle())

        val plain = M3uEntry(location = "/music/just-name.mp3", title = null)
        assertNull(plain.splitArtistAndTitle().first)
        assertEquals("just-name", plain.splitArtistAndTitle().second)
    }

    @Test
    fun `looks like m3u accepts extended header and plain path lists`() {
        assertTrue(M3uPlaylistCodec.looksLikeM3u("#EXTM3U\n/music/a.mp3"))
        assertTrue(M3uPlaylistCodec.looksLikeM3u("/music/a.mp3"))
        assertTrue(M3uPlaylistCodec.looksLikeM3u("https://example.com/a.mp3"))
        assertFalse(M3uPlaylistCodec.looksLikeM3u(""))
        assertFalse(M3uPlaylistCodec.looksLikeM3u("just some plain text"))
    }
}
