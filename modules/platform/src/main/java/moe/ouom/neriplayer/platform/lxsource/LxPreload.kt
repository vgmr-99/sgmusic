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

/**
 * 从 assets/script/user-api-preload.js 读取LX预加载契约。
 * 该文件按公开协议文档独立编写（见 assets 注释），不要直接复制 lx-music-mobile 原文。
 */
object LxPreload {
    private const val ASSET_PATH = "script/user-api-preload.js"

    fun load(context: Context): String {
        return context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
    }
}
