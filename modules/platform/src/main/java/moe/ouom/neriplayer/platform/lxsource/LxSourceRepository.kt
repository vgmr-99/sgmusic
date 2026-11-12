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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** 用我们自己的平台搜索为脚本定位 musicInfo（脚本常常不实现 search） */
    private val locator: LxMusicInfoLocator? = null,
) {
    private val runtimes = ConcurrentHashMap<String, LxRuntime>()
    private val _items = MutableStateFlow<List<LxSourceItem>>(emptyList())
    val items: StateFlow<List<LxSourceItem>> = _items
    /**
     * init 与 import 共用同一把锁：冷启动时 Application 里的 init() 往往与外部 Intent
     * 触发的 importScript() 并发，两者都往 _items 与 lx_sources.json 写，
     * 不串行会出现重复初始化、状态互相覆盖、落盘丢脚本。
     */
    private val mutationLock = Mutex()

    suspend fun init() {
        mutationLock.withLock {
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
    }

    suspend fun importScript(name: String, scriptText: String): LxSourceItem =
        importScriptInternal(name = name, scriptText = scriptText, replaceExisting = false)

    /**
     * 同名覆盖导入：已存在同名脚本时复用其 id 替换内容（保留启用状态），
     * 避免反复打开同一个 .js 时列表里堆出多个同名条目。
     */
    suspend fun importScriptReplacingSameName(name: String, scriptText: String): LxSourceItem =
        importScriptInternal(name = name, scriptText = scriptText, replaceExisting = true)

    private suspend fun importScriptInternal(
        name: String,
        scriptText: String,
        replaceExisting: Boolean,
    ): LxSourceItem = mutationLock.withLock {
        val existing = if (replaceExisting) {
            _items.value.firstOrNull { it.name == name }
        } else {
            null
        }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val item = LxSourceItem(
            id = id,
            name = name,
            scriptText = scriptText,
            enabled = existing?.enabled ?: true
        )
        replace(item)
        store.save(item)
        existing?.let { old ->
            // 复用 id 时先关掉旧 runtime，避免同一脚本两个 QuickJS 实例并存
            runtimes.remove(old.id)?.close()
        }
        val runtime = LxRuntime(http, preloadJs)
        runtimes[id] = runtime
        when (val r = runtime.init(scriptText, CurrentScriptInfo(name = name))) {
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
        musicInfo: LxMusicInfo,
        platform: LxPlatform,
        quality: LxQuality,
    ): LxResult<LxMusicUrl> {
        for ((id, runtime) in runtimes) {
            val item = _items.value.firstOrNull { it.id == id } ?: continue
            if (!item.enabled || item.state != LxSourceState.OK) continue
            if (item.info?.supports(platform) != true) continue
            when (val r = runtime.requestMusicUrl(musicInfo, platform, quality)) {
                is LxResult.Ok -> return r
                is LxResult.Err -> continue
            }
        }
        return LxResult.Err("no_source", "no enabled LX source matched")
    }

    /**
     * 按 Neri 的 SongItem 解析播放地址。
     *
     * 洛雪脚本只认自己平台的 songmid，所以这里先定位出该平台真实存在的 musicInfo：
     * 1) 网易云歌曲直接用自己的 id（脚本的 wy 源认的就是它）
     * 2) 脚本自带 search 时用它的候选（最准）
     * 3) 都不行就用我们自己的平台搜索定位（社区脚本常常不实现 search）
     */
    suspend fun resolveMusicUrlForSong(
        song: SongItem,
        platform: LxPlatform,
        quality: LxQuality,
    ): LxResult<LxMusicUrl> {
        for ((id, runtime) in runtimes) {
            val item = _items.value.firstOrNull { it.id == id } ?: continue
            if (!item.enabled || item.state != LxSourceState.OK) continue
            if (item.info?.supports(platform) != true) continue
            val musicInfo = resolveMusicInfo(runtime, song, platform, quality) ?: continue
            when (val r = runtime.requestMusicUrl(musicInfo, platform, quality)) {
                is LxResult.Ok -> return r
                is LxResult.Err -> continue
            }
        }
        return LxResult.Err("no_source", "no enabled LX source matched")
    }

    private suspend fun resolveMusicInfo(
        runtime: LxRuntime,
        song: SongItem,
        platform: LxPlatform,
        quality: LxQuality
    ): LxMusicInfo? {
        // 网易云歌曲本身就是 wy 源的 songmid，不用再搜
        if (platform == LxPlatform.WY && song.channelId.equals("netease", ignoreCase = true)) {
            val songmid = song.audioId?.takeIf { it.isNotBlank() } ?: song.id.toString()
            if (songmid.isNotBlank() && songmid != "0") {
                return LxMusicInfo(
                    source = platform.code,
                    songmid = songmid,
                    name = song.name,
                    singer = song.artist,
                    albumName = song.album,
                    durationMs = song.durationMs,
                    types = listOf(quality),
                    coverUrl = song.coverUrl
                )
            }
        }
        val keyword = song.name.trim()
        if (keyword.isBlank()) return null
        // 脚本自带 search 时优先用它的候选
        val candidates = (runtime.search(platform, keyword) as? LxResult.Ok)?.value.orEmpty()
        LxIdMatcher.best(song.name, song.artist, song.durationMs, candidates)?.let { best ->
            return LxMusicInfo(
                source = platform.code,
                songmid = best.songmid,
                name = best.title.ifBlank { song.name },
                singer = best.artist.ifBlank { song.artist },
                albumName = best.album,
                durationMs = best.durationMs.takeIf { it > 0L } ?: song.durationMs,
                types = listOf(quality),
                coverUrl = best.picUrl
            )
        }
        // 脚本不实现 search 时，用我们自己的平台搜索定位同一首歌
        return locator?.locate(
            platform = platform,
            keyword = "$keyword ${song.artist}".trim(),
            types = listOf(quality),
            quality = quality
        )
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
        mutationLock.withLock {
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
    }

    suspend fun remove(sourceId: String) {
        mutationLock.withLock {
            runtimes.remove(sourceId)?.close()
            _items.value = _items.value.filterNot { it.id == sourceId }
            store.remove(sourceId)
        }
    }

    /** 供外部（Intent 导入）判断是否已有同名脚本，用于「覆盖」提示文案。 */
    fun hasScriptNamed(name: String): Boolean = _items.value.any { it.name == name }

    private fun replace(item: LxSourceItem) {
        _items.value = _items.value.filterNot { it.id == item.id } + item
    }
}
