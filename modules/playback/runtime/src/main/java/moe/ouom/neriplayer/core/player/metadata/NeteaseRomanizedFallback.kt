package moe.ouom.neriplayer.core.player.metadata

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.platform.lyrics.repository.EditableLyricsMatcher
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient

internal suspend fun loadNeteaseRomanizedFallback(
    song: SongItem,
    preferWordTimedLyrics: Boolean,
    matcher: EditableLyricsMatcher,
    neteaseClient: NeteaseClient,
    neteaseLyricsCache: LruCache<Long, NeteaseLyricsCacheEntry>,
    matchedNeteaseSongId: Long? = null
): List<LyricEntry> {
    if (song.lyricSyncEdited == true && song.matchedRomanizedLyric != null) return emptyList()
    (matchedNeteaseSongId ?: resolveKnownNeteaseLyricSongId(song))?.let { songId ->
        return PlayerLyricsProvider.getNeteaseRomanizedLyrics(songId, neteaseClient, neteaseLyricsCache)
    }

    val request = EditableLyricMatchRequest(
        keyword = listOf(song.name, song.artist).filter(String::isNotBlank).joinToString(" "),
        trackName = song.name,
        artistName = song.artist,
        albumName = song.album,
        durationMs = song.durationMs,
        preferWordTimed = preferWordTimedLyrics,
        sources = setOf(EditableLyricMatchSource.CLOUD_MUSIC)
    )
    val matches = try {
        matcher.matchHighConfidenceLyricsForSource(
            request,
            EditableLyricMatchSource.CLOUD_MUSIC
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        NPLogger.d("NERI-PlayerManager", "网易云音译回退匹配失败: ${error.message}")
        return emptyList()
    }
    for (match in matches) {
        val candidate = match.candidate
        if (candidate.source != EditableLyricMatchSource.CLOUD_MUSIC) continue
        val songId = candidate.id.toLongOrNull()?.takeIf { it > 0L } ?: continue
        val reliable = PlayerLyricsProvider.selectFirstUsableAutomaticExternalLyrics(
            expectedDurationMs = song.durationMs,
            expectedTitle = song.name,
            expectedArtist = song.artist,
            matches = listOf(match)
        )
        if (reliable == null) continue
        val romanized = PlayerLyricsProvider.getNeteaseRomanizedLyrics(songId, neteaseClient, neteaseLyricsCache)
        if (romanized.isNotEmpty()) return romanized
    }
    return emptyList()
}

fun resolveKnownNeteaseLyricSongId(song: SongItem): Long? {
    // 只有网易云来源的匹配 ID 才能直接用于取词，其他平台的数字 ID 需要重新匹配
    if (song.matchedLyricSource == MusicPlatform.CLOUD_MUSIC) {
        song.matchedSongId?.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
    }
    return song.id.takeIf { it > 0L && song.isNeteaseOriginSong() }
}

/**
 * 这首歌本身是不是网易云来源。
 *
 * 只有网易云来源的歌，song.id 才是网易云的 songId，可以直接拿去查网易云歌词。
 * 汽水 / 咪咕 / 酷我 / QQ 的 song.id 是平台内部 id，拿去查网易云只会拿到
 * 「[00:00.00]暂无歌词」占位语，运气差还会命中另一首歌的歌词。
 */
internal fun SongItem.isNeteaseOriginSong(): Boolean {
    if (channelId.equals("netease", ignoreCase = true)) return true
    if (album.startsWith(SongSourceTags.NETEASE, ignoreCase = true)) return true
    return mediaUri?.startsWith("https://music.163.com/") == true
}
