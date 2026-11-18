package moe.ouom.neriplayer.data.download

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import moe.ouom.neriplayer.data.stats.toPlaybackStatsSongItem
import moe.ouom.neriplayer.data.model.stats.TrackStat

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.download/SmartDownloadPlanner
 * Created: 2026/11/11
 */

/**
 * 智能下载的候选挑选策略。
 *
 * 依据播放统计挑出"常听但还没下载"的歌曲，交给下载管理器排队。
 * 纯函数部分单独抽出，便于在没有 Android 依赖的环境下做单元测试。
 */
object SmartDownloadPlanner {

    val statisticsPeriod: PlaybackStatsPeriod = PlaybackStatsPeriod.MONTH

    /**
     * 从候选里挑出值得自动下载的歌曲。
     *
     * @param candidates 按播放次数降序排列的候选曲目
     * @param playCountThreshold 至少播放这么多次才算"常听"
     * @param maxCount 本轮最多自动下载几首
     * @param alreadyDownloaded 已下载过的歌曲标识集合
     */
    fun select(
        candidates: List<SongItem>,
        playCountThreshold: Int,
        maxCount: Int,
        alreadyDownloaded: Set<String>
    ): List<SongItem> {
        if (maxCount <= 0 || playCountThreshold <= 0) return emptyList()
        val seen = mutableSetOf<String>()
        val selected = mutableListOf<SongItem>()
        for (song in candidates) {
            if (selected.size >= maxCount) break
            val key = song.stableKey()
            if (key in alreadyDownloaded) continue
            // 同一首歌可能因多来源重复出现，只取一次
            if (!seen.add(key)) continue
            selected += song
        }
        return selected
    }

    /**
     * 统计候选曲目的播放次数。
     *
     * 播放统计里存的是曲目标识，这里按标识聚合出播放次数。
     */
    fun buildPlayCountIndex(tracks: List<TrackStat>): Map<String, Int> {
        val index = HashMap<String, Int>()
        tracks.forEach { stat ->
            index[stat.identityKey] = (index[stat.identityKey] ?: 0) + 1
        }
        return index
    }
}

/**
 * 智能下载调度器。
 *
 * 定时从播放统计里挑出常听歌曲，交给下载管理器自动下载，让离线播放更常用得上。
 */
class SmartDownloadScheduler(
    private val context: Context,
    private val statsRepository: PlaybackStatsRepository,
    private val downloader: (SongItem) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val runLock = Mutex()
    private val tag = "SmartDownloadScheduler"

    /**
     * 执行一轮挑选与下载。
     *
     * @return 本轮实际排队下载的歌曲数量
     */
    suspend fun runOnce(
        playCountThreshold: Int,
        maxCount: Int,
        alreadyDownloaded: () -> Set<String>
    ): Int = runLock.withLock {
        try {
            val query = PlaybackStatsQuery(
                period = SmartDownloadPlanner.statisticsPeriod,
                sort = PlaybackStatsSort.PLAY_COUNT,
                nowMillis = System.currentTimeMillis()
            )
            val page = statsRepository.readPage(query, null, SMART_CANDIDATE_LIMIT)
            if (page.tracks.isEmpty()) return@withLock 0

            // 播放次数不足的曲目直接排除，避免把偶尔听一次的歌也下载
            val eligible = page.tracks.filter { it.playCount >= playCountThreshold }
            if (eligible.isEmpty()) return@withLock 0

            val selected = SmartDownloadPlanner.select(
                candidates = eligible.map { it.toPlaybackStatsSongItem() },
                playCountThreshold = playCountThreshold,
                maxCount = maxCount,
                alreadyDownloaded = alreadyDownloaded()
            )
            selected.forEach { song ->
                try {
                    downloader(song)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    NPLogger.w(tag, "Smart download failed for ${song.name}", error)
                }
            }
            if (selected.isNotEmpty()) {
                NPLogger.d(tag, "Smart download queued ${selected.size} songs")
            }
            selected.size
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NPLogger.e(tag, "Smart download round failed", error)
            0
        }
    }

    /** 在后台触发一轮智能下载。 */
    fun schedule(playCountThreshold: Int, maxCount: Int, alreadyDownloaded: () -> Set<String>) {
        scope.launch {
            runOnce(playCountThreshold, maxCount, alreadyDownloaded)
        }
    }

    private companion object {
        /** 统计查询上限，够挑选常听歌曲又不至于把整个统计表读进内存。 */
        const val SMART_CANDIDATE_LIMIT = 50
    }
}