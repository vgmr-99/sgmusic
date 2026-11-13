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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component.playback/SettingsLxSourceTestDialog
 * Created: 2026/11/12
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.lxsource.LxPlatform
import moe.ouom.neriplayer.platform.lxsource.LxQuality
import moe.ouom.neriplayer.platform.lxsource.LxQualityProbe
import moe.ouom.neriplayer.platform.lxsource.LxSourceTestReport
import moe.ouom.neriplayer.platform.lxsource.LxTestMode
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.util.ClipboardCopyResult
import moe.ouom.neriplayer.ui.util.copyPlainTextSafely

/** 检测阶段：选模式 -> 进行中 -> 出结果 */
internal enum class LxTestPhase {
    CHOOSE,
    RUNNING,
    RESULT
}

/**
 * 音源音质检测对话框。
 *
 * 检测的是"脚本能不能为某个平台的某档音质换出可用的播放地址"，
 * 所以界面用 平台 × 音质 的矩阵呈现，和常见音源工具的检测表一致。
 */
@Composable
internal fun LxSourceTestDialog(
    sourceName: String,
    phase: LxTestPhase,
    probes: List<LxQualityProbe>,
    report: LxSourceTestReport?,
    onStart: (LxTestMode) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    MiuixSettingsDialog(
        onDismissRequest = { if (phase != LxTestPhase.RUNNING) onDismiss() },
        title = { Text(stringResource(CoreCommonR.string.settings_lx_test_title)) },
        text = {
            when (phase) {
                LxTestPhase.CHOOSE -> LxTestModeChooser(
                    sourceName = sourceName,
                    onStart = onStart
                )
                LxTestPhase.RUNNING -> LxTestRunningContent(
                    sourceName = sourceName,
                    probes = probes,
                    onCancel = onCancel
                )
                LxTestPhase.RESULT -> LxTestResultContent(
                    report = report,
                    probes = probes
                )
            }
        },
        confirmButton = {
            when (phase) {
                LxTestPhase.CHOOSE -> {
                    MiuixSettingsTextButton(onClick = onDismiss) {
                        Text(stringResource(CoreCommonR.string.action_cancel))
                    }
                }
                LxTestPhase.RUNNING -> {
                    MiuixSettingsTextButton(onClick = onCancel) {
                        Text(stringResource(CoreCommonR.string.settings_lx_test_cancel))
                    }
                }
                LxTestPhase.RESULT -> {
                    MiuixSettingsTextButton(onClick = onDismiss) {
                        Text(stringResource(CoreCommonR.string.action_confirm))
                    }
                }
            }
        }
    )
}

@Composable
private fun LxTestModeChooser(
    sourceName: String,
    onStart: (LxTestMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = sourceName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(CoreCommonR.string.settings_lx_test_no_interruption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LxTestModeRow(
            icon = Icons.Filled.Refresh,
            title = stringResource(CoreCommonR.string.settings_lx_test_quick_title),
            subtitle = stringResource(CoreCommonR.string.settings_lx_test_quick_desc),
            onClick = { onStart(LxTestMode.QUICK) }
        )
        LxTestModeRow(
            icon = Icons.Outlined.LibraryMusic,
            title = stringResource(CoreCommonR.string.settings_lx_test_full_title),
            subtitle = stringResource(CoreCommonR.string.settings_lx_test_full_desc),
            onClick = { onStart(LxTestMode.FULL) }
        )
        Text(
            text = stringResource(CoreCommonR.string.settings_lx_test_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LxTestModeRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LxTestRunningContent(
    sourceName: String,
    probes: List<LxQualityProbe>,
    onCancel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = sourceName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(CoreCommonR.string.settings_lx_test_running),
            style = MaterialTheme.typography.bodyMedium
        )
        if (probes.isNotEmpty()) {
            Text(
                text = stringResource(
                    CoreCommonR.string.settings_lx_test_progress,
                    probes.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LxTestResultContent(
    report: LxSourceTestReport?,
    probes: List<LxQualityProbe>
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDetails by remember { mutableStateOf(false) }
    val effective = report ?: return
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!effective.initialized) {
            Text(
                text = stringResource(
                    CoreCommonR.string.settings_lx_self_test_init_failed,
                    effective.initError.orEmpty()
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            return@Column
        }
        Text(
            text = stringResource(
                CoreCommonR.string.settings_lx_test_summary,
                effective.passedCount,
                effective.checkedCount
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        if (effective.cancelled) {
            Text(
                text = stringResource(CoreCommonR.string.settings_lx_test_cancelled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LxTestMatrix(probes = probes, showDetails = showDetails)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MiuixSettingsTextButton(onClick = { showDetails = !showDetails }) {
                Text(stringResource(CoreCommonR.string.settings_lx_test_details))
            }
            MiuixSettingsTextButton(
                onClick = {
                    scope.launch {
                        val result = clipboard.copyPlainTextSafely(
                            label = "lx-source-test",
                            text = buildLxTestReport(effective, probes)
                        )
                        AppFeedback.showToast(
                            context,
                            context.getString(
                                when (result) {
                                    is ClipboardCopyResult.Copied -> CoreCommonR.string.toast_copied
                                    ClipboardCopyResult.TransactionTooLarge ->
                                        CoreCommonR.string.toast_copy_failed
                                }
                            )
                        )
                    }
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(CoreCommonR.string.settings_lx_test_copy_report),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

/** 平台 × 音质 矩阵 */
@Composable
private fun LxTestMatrix(
    probes: List<LxQualityProbe>,
    showDetails: Boolean
) {
    val platforms = probes.map { it.platform }.distinct()
    if (platforms.isEmpty()) return
    val qualities = LxQuality.STANDARD_ORDER
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(CoreCommonR.string.settings_lx_test_column_platform),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1.4f)
            )
            qualities.forEach { quality ->
                Text(
                    text = lxQualityLabel(quality),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        platforms.forEach { platform ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lxPlatformLabel(platform),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1.4f)
                )
                qualities.forEach { quality ->
                    val probe = probes.firstOrNull {
                        it.platform == platform && it.quality == quality
                    }
                    Text(
                        text = lxProbeCellLabel(probe),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = when {
                            probe == null || probe.skipped -> MaterialTheme.colorScheme.onSurfaceVariant
                            probe.ok -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (showDetails) {
                qualities.forEach { quality ->
                    val probe = probes.firstOrNull {
                        it.platform == platform && it.quality == quality
                    } ?: return@forEach
                    if (probe.skipped && probe.detail.isBlank()) return@forEach
                    Text(
                        text = "${lxPlatformLabel(platform)} · ${lxQualityLabel(quality)} · " +
                            "${lxProbeCellLabel(probe)} · ${probe.elapsedMs}ms" +
                            if (probe.detail.isBlank()) "" else " · ${probe.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (probe.ok) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun lxPlatformLabel(platform: LxPlatform): String = stringResource(
    when (platform) {
        LxPlatform.KW -> CoreCommonR.string.settings_lx_platform_kw
        LxPlatform.KG -> CoreCommonR.string.settings_lx_platform_kg
        LxPlatform.TX -> CoreCommonR.string.settings_lx_platform_tx
        LxPlatform.WY -> CoreCommonR.string.settings_lx_platform_wy
        LxPlatform.MG -> CoreCommonR.string.settings_lx_platform_mg
        LxPlatform.QS -> CoreCommonR.string.settings_lx_platform_qs
        LxPlatform.LOCAL -> CoreCommonR.string.settings_lx_platform_local
    }
)

@Composable
private fun lxQualityLabel(quality: LxQuality): String = stringResource(
    when (quality) {
        LxQuality.Q128 -> CoreCommonR.string.settings_lx_quality_128
        LxQuality.Q320 -> CoreCommonR.string.settings_lx_quality_320
        LxQuality.FLAC -> CoreCommonR.string.settings_lx_quality_flac
        LxQuality.FLAC24 -> CoreCommonR.string.settings_lx_quality_flac24
    }
)

@Composable
private fun lxProbeCellLabel(probe: LxQualityProbe?): String {
    if (probe == null || probe.skipped) {
        return stringResource(CoreCommonR.string.settings_lx_test_cell_skip)
    }
    return stringResource(
        if (probe.ok) {
            CoreCommonR.string.settings_lx_test_cell_pass
        } else {
            CoreCommonR.string.settings_lx_test_cell_fail
        }
    )
}

/**
 * 在线导入脚本对话框：粘贴脚本链接即可接上音源。
 */
@Composable
internal fun LxScriptUrlImportDialog(
    loading: Boolean,
    errorMessage: String?,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    MiuixSettingsDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(CoreCommonR.string.settings_lx_import_url_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_import_url_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MiuixSettingsTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(CoreCommonR.string.settings_lx_import_url_label)) },
                    placeholder = { Text("https://.../source.js") },
                    singleLine = true,
                    enabled = !loading
                )
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(
                onClick = { onImport(url) },
                enabled = !loading && url.isNotBlank()
            ) {
                Text(stringResource(CoreCommonR.string.settings_lx_import_url_confirm))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDismiss, enabled = !loading) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

/**
 * 脱敏报告：只保留平台、音质、结果与耗时，不带测试歌曲 id 与地址。
 */
private fun buildLxTestReport(
    report: LxSourceTestReport,
    probes: List<LxQualityProbe>
): String = buildString {
    appendLine("音源音质检测报告")
    appendLine("音源：${report.sourceName}")
    appendLine("结果：${report.passedCount}/${report.checkedCount} 项音质解析通过")
    if (report.cancelled) appendLine("状态：已取消")
    if (report.timedOut) appendLine("状态：脚本超时中止")
    appendLine("----")
    probes.forEach { probe ->
        appendLine(
            "${probe.platform.code} / ${probe.quality.code} / " +
                if (probe.skipped) "跳过" else if (probe.ok) "通过" else "失败" +
                    " / ${probe.elapsedMs}ms" +
                    if (probe.detail.isBlank()) "" else " / ${probe.detail}"
        )
    }
}
