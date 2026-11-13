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

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.common.logging.NPLogger
import java.io.File

/** 文件持久化 DTO：落 id/name/scriptText/enabled + 的运行期 state（供排查；info 不持久，init 时重算）。 */
@Serializable
private data class LxSourceDocument(
    val version: Int = 1,
    val scripts: List<LxScriptDocument> = emptyList(),
)

@Serializable
private data class LxScriptDocument(
    val id: String,
    val name: String,
    val scriptText: String,
    val enabled: Boolean = true,
    val state: String = "INIT",
    val error: String? = null,
)

/**
 * LxSourceStore 的文件实现：filesDir/lx_sources.json。
 * - 读失败/损坏时回退为空（同 DataStore 的腐蚀回退策略），不阻塞启动
 * - 写入先写临时文件再 rename，避免杀进程截断
 */
class FileLxSourceStore(
    context: Context,
    private val file: File = File(context.applicationContext.filesDir, FILE_NAME),
) : LxSourceStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    override suspend fun loadAll(): List<LxSourceItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readLocked().map {
                LxSourceItem(
                    id = it.id,
                    name = it.name,
                    scriptText = it.scriptText,
                    enabled = it.enabled,
                    state = runCatching { LxSourceState.valueOf(it.state) }.getOrDefault(LxSourceState.INIT),
                    error = it.error,
                )
            }
        }
    }

    override suspend fun save(item: LxSourceItem) {
        val doc = LxScriptDocument(item.id, item.name, item.scriptText, item.enabled, item.state.name, item.error)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                writeLocked(readLocked().filterNot { it.id == item.id } + doc)
            }
        }
    }

    override suspend fun saveAll(items: List<LxSourceItem>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                writeLocked(items.map { LxScriptDocument(it.id, it.name, it.scriptText, it.enabled, it.state.name, it.error) })
            }
        }
    }

    override suspend fun remove(id: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                writeLocked(readLocked().filterNot { it.id == id })
            }
        }
    }

    /** 调用方需已持 mutex。剥 BOM：外部写入的 JSON 可能带 UTF-8 BOM，不能因它整库回退为空。 */
    private fun readLocked(): List<LxScriptDocument> {
        if (!file.exists()) return emptyList()
        return try {
            val raw = file.readText().removePrefix("﻿")
            val doc = json.decodeFromString(LxSourceDocument.serializer(), raw)
            doc.scripts
        } catch (e: Exception) {
            NPLogger.e("FileLxSourceStore", "lx_sources.json 损坏，已回退为空", e)
            emptyList()
        }
    }

    private fun writeLocked(doc: List<LxScriptDocument>) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(LxSourceDocument.serializer(), LxSourceDocument(scripts = doc)))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            NPLogger.e("FileLxSourceStore", "lx_sources.json 写入失败", e)
        }
    }

    companion object {
        const val FILE_NAME = "lx_sources.json"
    }
}
