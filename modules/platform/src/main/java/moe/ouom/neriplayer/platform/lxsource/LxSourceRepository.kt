package moe.ouom.neriplayer.platform.lxsource

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
 */

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 多脚本管理：启停 / 状态三态 / 并行 runtime
 * 合入后升为主仓的 LxSourceRepository，持久化经 LxSourceStore 窄接口注入。
 */
class LxSourceRepository(
    private val http: LxHttpClient,
    private val preloadJs: String,
    private val store: LxSourceStore,
) {
    private val runtimes = ConcurrentHashMap<String, LxRuntime>()
    private val _items = MutableStateFlow<List<LxSourceItem>>(emptyList())
    val items: StateFlow<List<LxSourceItem>> = _items

    suspend fun init() {
        val loaded = store.loadAll()
        NPLogger.d("LxSource", "init start: scripts=${loaded.size}")
        _items.value = loaded
        loaded.filter { it.enabled }.forEach { item ->
            val runtime = LxRuntime(http, preloadJs)
            runtimes[item.id] = runtime
            NPLogger.d("LxSource", "init script=${item.name} id=${item.id}")
            when (val r = runtime.init(item.scriptText, CurrentScriptInfo(name = item.name))) {
                is LxResult.Ok -> {
                    NPLogger.d("LxSource", "init OK script=${item.name} sources=${r.value.sources.keys}")
                    val ok = item.copy(state = LxSourceState.OK, info = r.value, error = null)
                    replace(ok)
                }
                is LxResult.Err -> {
                    NPLogger.w("LxSource", "init FAIL script=${item.name} code=${r.code} message=${r.message}")
                    val fail = item.copy(state = LxSourceState.FAIL, error = "${r.code}:${r.message}")
                    replace(fail)
                }
            }
        }
    }

    suspend fun importScript(name: String, scriptText: String): LxSourceItem {
        val id = UUID.randomUUID().toString()
        val item = LxSourceItem(id = id, name = name, scriptText = scriptText)
        replace(item)
        store.save(item)
        val runtime = LxRuntime(http, preloadJs)
        runtimes[id] = runtime
        return when (val r = runtime.init(scriptText, CurrentScriptInfo(name = name))) {
            is LxResult.Ok -> {
                val okItem = item.copy(state = LxSourceState.OK, info = r.value, error = null)
                replace(okItem)
                store.save(okItem)
                okItem
            }
            is LxResult.Err -> {
                val fail = item.copy(state = LxSourceState.FAIL, error = "${r.code}:${r.message}")
                replace(fail)
                store.save(fail)
                fail
            }
        }
    }

    suspend fun resolveMusicUrl(
        songMid: String,
        platform: LxPlatform,
        quality: LxQuality,
    ): LxResult<String> {
        for ((id, runtime) in runtimes) {
            val item = _items.value.firstOrNull { it.id == id } ?: continue
            if (!item.enabled || item.state != LxSourceState.OK) continue
            if (item.info?.supports(platform) != true) continue
            when (val r = runtime.requestMusicUrl(songMid, platform, quality)) {
                is LxResult.Ok -> return r
                is LxResult.Err -> continue
            }
        }
        return LxResult.Err("no_source", "no enabled LX source matched")
    }

    /**
     * 按 Neri 的 SongItem 精确解析：先 search 候选 → LxIdMatcher 选最佳 songmid → musicUrl。
     * search 失败/无匹配时回退到 guessSongmid（见 LxSourceResolver）。
     */
    suspend fun resolveMusicUrlForSong(
        songName: String,
        artist: String,
        durationMs: Long,
        platform: LxPlatform,
        quality: LxQuality,
    ): LxResult<String> {
        for ((id, runtime) in runtimes) {
            val item = _items.value.firstOrNull { it.id == id } ?: continue
            if (!item.enabled || item.state != LxSourceState.OK) continue
            if (item.info?.supports(platform) != true) continue
            val keyword = songName.trim()
            val songmid = if (keyword.isNotBlank()) {
                val candidates = (runtime.search(platform, keyword) as? LxResult.Ok)?.value.orEmpty()
                LxIdMatcher.best(songName, artist, durationMs, candidates)?.songmid
            } else {
                null
            }
            val attempt = songmid ?: "$songName|$artist".replace(" ", "").lowercase()
            when (val r = runtime.requestMusicUrl(attempt, platform, quality)) {
                is LxResult.Ok -> return r
                is LxResult.Err -> continue
            }
        }
        return LxResult.Err("no_source", "no enabled LX source matched")
    }

    /**
     * 探索页搜索用：经首个可用脚本取候选并映射为 SongItem。
     * id 由 songmid 稳定派生（播放实际走 LX 解析，不以 id 为播放 key）；
     * audioId 带 songmid，保证探索页 stableKey 唯一。
     */
    suspend fun searchSongsForUi(keyword: String, platform: LxPlatform = LxPlatform.WY): List<SongItem> {
        for ((id, runtime) in runtimes) {
            val item = _items.value.firstOrNull { it.id == id } ?: continue
            if (!item.enabled || item.state != LxSourceState.OK) continue
            if (item.info?.supports(platform) != true) continue
            val candidates = (runtime.search(platform, keyword) as? LxResult.Ok)?.value.orEmpty()
            if (candidates.isEmpty()) continue
            return candidates.take(50).map { c ->
                SongItem(
                    id = kotlin.math.abs(c.songmid.hashCode().toLong()) + 1_000_000_000L,
                    name = c.title.ifBlank { keyword },
                    artist = c.artist,
                    album = c.album,
                    albumId = 0L,
                    durationMs = c.durationMs,
                    coverUrl = c.picUrl,
                    audioId = c.songmid,
                )
            }
        }
        return emptyList()
    }

    suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        val item = _items.value.firstOrNull { it.id == sourceId } ?: return
        val updated = if (!enabled) {
            item.copy(enabled = false, state = LxSourceState.DISABLED)
        } else {
            item.copy(enabled = true)
        }
        replace(updated)
        store.save(updated)
        if (enabled) {
            val runtime = runtimes[sourceId] ?: LxRuntime(http, preloadJs).also { runtimes[sourceId] = it }
            if (updated.state != LxSourceState.OK) {
                when (val r = runtime.init(updated.scriptText, CurrentScriptInfo(name = updated.name))) {
                    is LxResult.Ok -> replace(updated.copy(state = LxSourceState.OK, info = r.value, error = null))
                    is LxResult.Err -> replace(updated.copy(state = LxSourceState.FAIL, error = "${r.code}:${r.message}"))
                }
            }
        }
    }

    suspend fun remove(sourceId: String) {
        runtimes.remove(sourceId)?.close()
        _items.value = _items.value.filterNot { it.id == sourceId }
        store.remove(sourceId)
    }

    private fun replace(item: LxSourceItem) {
        _items.value = _items.value.filterNot { it.id == item.id } + item
    }
}
