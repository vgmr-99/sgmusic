package moe.ouom.neriplayer.platform.search.api.client

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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.  If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.platform.search.api.client/LunaDeviceId
 * Created: 2026/11/14
 */

import java.util.UUID

/**
 * 汽水（luna / 抖音系）侧的设备标识。
 *
 * **整个进程内固定不变**：之前每次请求都现生成一个随机 id，
 * 接口会把它当成一批互不相干的新设备，偶发返回空歌单列表
 * （表现是探索页浏览汽水分类时一片空白，缓存还会把空结果留住）。
 *
 * 固定之后接口把它当成同一台设备，行为稳定。
 */
internal object LunaDeviceId {
    /** 16 位十六进制设备号，与抖音系 App 的 iid/device_id 形态一致 */
    val value: String = UUID.randomUUID().toString().replace("-", "").take(16)
}
