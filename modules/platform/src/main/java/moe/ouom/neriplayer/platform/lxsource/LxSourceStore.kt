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

/**
 * 脚本文本持久化窄接口。实现放在 :local（DataStore / filesDir JSON），:platform 只消费。
 * 这样 :platform 不反向依赖 :local 的存储实现，符合模块边界。
 */
interface LxSourceStore {
    suspend fun loadAll(): List<LxSourceItem>
    suspend fun save(item: LxSourceItem)
    suspend fun saveAll(items: List<LxSourceItem>)
    suspend fun remove(id: String)
}

class InMemoryLxSourceStore : LxSourceStore {
    private var items: List<LxSourceItem> = emptyList()
    override suspend fun loadAll(): List<LxSourceItem> = items
    override suspend fun save(item: LxSourceItem) {
        items = items.filterNot { it.id == item.id } + item
    }
    override suspend fun saveAll(items: List<LxSourceItem>) { this.items = items }
    override suspend fun remove(id: String) { items = items.filterNot { it.id == id } }
}
