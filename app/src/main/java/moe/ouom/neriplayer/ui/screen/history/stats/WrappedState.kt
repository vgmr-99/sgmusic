package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary

/**
 * 听歌总结页的数据状态。
 *
 * [tracks] 已是按播放次数降序的前若干首，[artists] 是聚合后的艺人排行。
 */
internal data class WrappedState(
    val summary: PlaybackStatsSummary = PlaybackStatsSummary(),
    val tracks: List<TrackStat> = emptyList(),
    val artists: List<WrappedArtist> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false
)

internal data class WrappedArtist(val name: String, val playCount: Long)

/**
 * 读取指定周期的听歌总结。
 *
 * 与播放统计页共用 [PlaybackStatsRepository]，跟随其 revisionFlow 自动刷新。
 */
@Composable
internal fun rememberWrappedState(
    period: PlaybackStatsPeriod,
    repository: PlaybackStatsRepository = AppContainer.playbackStatsRepo
): State<WrappedState> = produceState(
    initialValue = WrappedState(),
    key1 = period,
    key2 = repository,
    producer = loadWrappedState(period, repository)
)

private fun loadWrappedState(
    period: PlaybackStatsPeriod,
    repository: PlaybackStatsRepository
): suspend ProduceStateScope<WrappedState>.() -> Unit = {
    repository.revisionFlow.catch { error ->
        if (error is CancellationException) throw error
        value = value.copy(loading = false, failed = true)
    }.collectLatest {
        value = value.copy(loading = true, failed = false)
        value = try {
            val query = PlaybackStatsQuery(
                period = period,
                sort = PlaybackStatsSort.PLAY_COUNT,
                nowMillis = System.currentTimeMillis()
            )
            val summary = repository.readSummary(query)
            val page = repository.readPage(query, null, WrappedTrackLimit)
            val tracks = page.tracks
            WrappedState(
                summary = summary,
                tracks = tracks,
                artists = aggregateWrappedArtists(tracks),
                loading = false,
                failed = false
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            value.copy(loading = false, failed = true)
        }
    }
}

/**
 * 按艺人聚合播放次数。
 *
 * 只统计播放次数为正的曲目，空艺人名归入未知艺人。
 */
internal fun aggregateWrappedArtists(tracks: List<TrackStat>): List<WrappedArtist> {
    val counts = LinkedHashMap<String, Long>()
    tracks.forEach { track ->
        if (track.playCount <= 0) return@forEach
        val key = track.artist.trim().ifEmpty { UnknownArtistName }
        counts[key] = (counts[key] ?: 0L) + track.playCount
    }
    return counts.entries
        .sortedByDescending { it.value }
        .map { WrappedArtist(it.key, it.value) }
}

internal const val UnknownArtistName = "未知艺人"
internal const val WrappedTrackLimit = 50
