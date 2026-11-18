package moe.ouom.neriplayer.data.model.settings.download

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
 * File: moe.ouom.neriplayer.data.model.settings.download/SmartDownloadDefaults
 * Created: 2026/11/11
 */

/** 播放次数达到该阈值才纳入自动下载候选。 */
const val DEFAULT_SMART_DOWNLOAD_PLAY_COUNT_THRESHOLD = 3

/** 每轮自动下载的最大曲目数。 */
const val DEFAULT_SMART_DOWNLOAD_MAX_COUNT = 5

/** 智能下载的最大可选曲目数上限，避免一次拉太多流量。 */
const val MAX_SMART_DOWNLOAD_MAX_COUNT = 20

/** 播放次数阈值的可选范围。 */
val SMART_DOWNLOAD_PLAY_COUNT_THRESHOLD_RANGE = 1..20
