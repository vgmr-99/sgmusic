package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.lyrics.parser.LyricSourceMerger
import moe.ouom.neriplayer.lyrics.parser.hasWordTimedEntries
import moe.ouom.neriplayer.lyrics.parser.parseEmbeddedPhoneticLyrics
import moe.ouom.neriplayer.platform.lyrics.repository.AmllLyricsRepository
import moe.ouom.neriplayer.platform.lyrics.repository.AmllLyricsResolver

/**
 * AMLL 逐词歌词的缓存与加载，以及平台歌词和逐词歌词之间的取舍。
 *
 * 从 PlayerLyricsProvider 拆出，主文件不再继续膨胀。
 */
internal object PlayerAmllLyricsSource {

    fun buildCacheKey(song: SongItem, requireDurationMatch: Boolean): String {
        return buildString {
            append(song.stableKey())
            append('|')
            append(song.name)
            append('|')
            append(song.artist)
            append('|')
            append(song.durationMs)
            append("|durationMatch=")
            append(requireDurationMatch)
        }
    }

    suspend fun loadWithCache(
        cache: androidx.collection.LruCache<String, PreferredLyricSourceResult>,
        song: SongItem,
        amllTtmlClient: AmllLyricsRepository,
        requireDurationMatch: Boolean
    ): List<LyricEntry> {
        val cacheKey = buildCacheKey(song, requireDurationMatch)
        cache.get(cacheKey)?.let { cached ->
            NPLogger.d("NERI-PlayerManager", "Using cached AMLL lyrics for '${song.name}'")
            return cached.lyrics
        }
        val resolved = AmllLyricsResolver.loadRawByMetadata(
            trackName = song.name,
            artistName = song.artist,
            durationMs = song.durationMs,
            amllTtmlClient = amllTtmlClient,
            requireDurationMatch = requireDurationMatch
        )
        val result = PreferredLyricSourceResult(
            lyrics = resolved?.entries.orEmpty(),
            romanizedLyrics = parseEmbeddedPhoneticLyrics(resolved?.rawLyrics.orEmpty()),
            source = LyricSourcePreference.AmllTtml
        )
        cache.put(cacheKey, result)
        return result.lyrics
    }

    /** 平台歌词优先的几种情况：B 站字幕已加载、不要逐词、平台本身带逐词或逐词功能关闭 */
    fun preferPlatformLyrics(
        biliCcLyricsLoaded: Boolean,
        preferWordTimedLyrics: Boolean,
        platformLyrics: List<LyricEntry>,
        amllLyricsEnabled: Boolean
    ): Boolean = biliCcLyricsLoaded ||
        !preferWordTimedLyrics ||
        platformLyrics.hasWordTimedEntries() ||
        !amllLyricsEnabled

    /** 跨源合并：逐字歌词优先，缺失的行用平台歌词补齐 */
    fun mergeAcrossSources(
        amllLyrics: List<LyricEntry>,
        platformLyrics: List<LyricEntry>
    ): List<LyricEntry> {
        val merged = LyricSourceMerger.merge(listOf(amllLyrics, platformLyrics))
        return if (merged.isEmpty()) platformLyrics else merged
    }
}
