package moe.ouom.neriplayer.ui.screen.tab.settings.component.playback

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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component.playback/LxScriptImporter
 * 拾光音乐：LX 脚本导入公共入口（设置页文件选择 + 外部“用拾光音乐打开”共用）
 */

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import moe.ouom.neriplayer.platform.lxsource.CurrentScriptInfo
import moe.ouom.neriplayer.platform.lxsource.LxHttpClient
import moe.ouom.neriplayer.platform.lxsource.LxScriptConverter
import moe.ouom.neriplayer.platform.lxsource.LxSourceItem
import moe.ouom.neriplayer.platform.lxsource.LxSourceRepository
import moe.ouom.neriplayer.platform.lxsource.LxSourceState

/**
 * LX 脚本导入结果。
 * @param item 导入后的脚本条目（含 state / error）
 * @param replaced 是否覆盖了同名旧脚本
 */
data class LxScriptImportResult(
    val item: LxSourceItem,
    val replaced: Boolean,
) {
    val succeeded: Boolean get() = item.state == LxSourceState.OK
    val errorMessage: String? get() = item.error?.takeIf { it.isNotBlank() }
}

/**
 * 读取脚本 URI 并导入 LX 仓库。
 *
 * 抽出来是为了让「设置页从文件导入」和「文件管理器里用拾光音乐打开 .js」走同一套逻辑：
 * 大小校验、文件名提取、同名覆盖策略只有一份实现。
 */
internal object LxScriptImporter {

    /** 2MB，防大文件卡死（与设置页原有上限一致）。 */
    const val MAX_SCRIPT_TEXT = 2 * 1024 * 1024

    private val JS_MIME_TYPES = setOf(
        "application/javascript",
        "text/javascript",
        "application/x-javascript",
        "text/x-javascript",
        "application/ecmascript",
        "text/ecmascript",
    )

    /**
     * 判断 Intent 里的 URI 是否可能是 LX 脚本。
     *
     * Android 没有 .js 的标准 MIME，多数文件管理器会给 text/plain 或
     * application/octet-stream，所以除了 MIME 还要看文件名后缀。
     */
    fun looksLikeScript(context: Context, uri: Uri, mimeType: String?): Boolean {
        if (mimeType?.lowercase() in JS_MIME_TYPES) return true
        val name = queryDisplayName(context, uri)
        return name?.lowercase()?.endsWith(".js") == true
    }

    /** 读取脚本文本与文件名；失败抛异常，由调用方决定怎么提示。 */
    fun readScript(context: Context, uri: Uri): Pair<String, String> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取文件")
        if (bytes.isEmpty()) error("文件为空")
        if (bytes.size > MAX_SCRIPT_TEXT) error("脚本过大")
        val text = bytes.decodeToString().removePrefix("﻿")
        if (text.isBlank()) error("文件为空")
        val name = queryDisplayName(context, uri) ?: "lx-source.js"
        return name to text
    }

    /**
     * 导入脚本。已有同名脚本时覆盖（复用其 id，保留启用状态）。
     */
    suspend fun import(
        repository: LxSourceRepository,
        name: String,
        scriptText: String,
    ): LxScriptImportResult {
        val replaced = repository.hasScriptNamed(name)
        // desktop/CeruMusic 风格脚本经 converter 包装后再存：补 CommonJS 环境、桥接
        // module.exports.musicUrl。原稿由 converter 保留：
        //  - 原生导出 musicUrl 的脚本直接返回原文，原文即原稿
        //  - 需要包装时原稿写进 adapter 的 __lx_original_source，随 scriptText 一起持久化
        // 这样不必改动 FileLxSourceStore 的 DTO，也不必给 import 增加 Context 参数。
        val wrapped = LxScriptConverter.convert(scriptText, CurrentScriptInfo(name = name))
        val item = repository.importScriptReplacingSameName(name = name, scriptText = wrapped)
        return LxScriptImportResult(item = item, replaced = replaced)
    }

    /**
     * 从在线地址拉取脚本再导入。
     *
     * 社区音源大多托管在 GitHub/jsDelivr 这类地址，粘贴链接就能接上，
     * 比手动找文件再导入顺手得多。
     */
    suspend fun importFromUrl(
        repository: LxSourceRepository,
        url: String,
        http: LxHttpClient,
    ): LxScriptImportResult {
        val normalized = url.trim()
        if (normalized.isEmpty()) error("链接不能为空")
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            error("链接必须以 http:// 或 https:// 开头")
        }
        val response = http.request(
            method = "GET",
            url = normalized,
            headers = mapOf("Accept" to "text/javascript, application/javascript, */*")
        )
        if (response.statusCode !in 200..299) {
            error("下载失败（HTTP ${response.statusCode}）")
        }
        val bytes = response.body?.toByteArray(Charsets.UTF_8) ?: error("下载内容为空")
        if (bytes.isEmpty()) error("下载内容为空")
        if (bytes.size > MAX_SCRIPT_TEXT) error("脚本过大")
        val text = bytes.decodeToString().removePrefix("﻿")
        if (text.isBlank()) error("下载内容为空")
        return import(repository, name = scriptNameFromUrl(normalized), scriptText = text)
    }

    /** 从链接里猜脚本名：优先路径末段的 .js 文件名，退回域名 */
    private fun scriptNameFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
            .substringAfterLast('/')
            .ifBlank { return "lx-source.js" }
        return if (path.endsWith(".js", ignoreCase = true) && path.length <= 80) {
            path
        } else {
            "lx-source.js"
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
}
