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
 */

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.platform.lxsource.CurrentScriptInfo
import moe.ouom.neriplayer.platform.lxsource.LxQualityProbe
import moe.ouom.neriplayer.platform.lxsource.LxSourceItem
import moe.ouom.neriplayer.platform.lxsource.LxSourceState
import moe.ouom.neriplayer.platform.lxsource.LxSourceTestReport
import moe.ouom.neriplayer.platform.lxsource.LxTestMode
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch

/**
 * LX 自定义源兜底 页：脚本列表（导入、启停、删除、检测）。
 * 脚本状态三态：OK（已 init）/ FAIL（脚本报错，error 展示）/ DISABLED（停用）。
 */
@Composable
internal fun SettingsLxSourceSection(
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 导入/init/启停必须走应用级 scope：用 rememberCoroutineScope 时，离开设置页会把脚本
    // 初始化打断（ForgottenCoroutineScopeException），且后续不自动重试，状态将长期卡死在失败。
    // 因此本文件直接用 AppContainer.launchBackgroundIo（进程级协程），装到脚本完成为止。
    val lxRepository = remember { AppContainer.lxSourceRepository }
    val enabled by settingsRepository.lxSourceEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val scripts by lxRepository.items.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var urlImportError by remember { mutableStateOf<String?>(null) }
    var testPhase by remember { mutableStateOf<LxTestPhase?>(null) }
    var testTarget by remember { mutableStateOf<LxSourceItem?>(null) }
    var remarkTarget by remember { mutableStateOf<LxSourceItem?>(null) }
    var remarkDraft by remember { mutableStateOf("") }
    var testProbes by remember { mutableStateOf<List<LxQualityProbe>>(emptyList()) }
    var testReport by remember { mutableStateOf<LxSourceTestReport?>(null) }
    var showUrlImport by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    val testCancelFlag = remember { AtomicBoolean(false) }

    val scriptPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || importing) return@rememberLauncherForActivityResult
        importing = true
        AppContainer.launchBackgroundIo {
            val result = runCatching {
                val (name, text) = LxScriptImporter.readScript(context, uri)
                LxScriptImporter.import(lxRepository, name, text)
            }
            importing = false
            errorMessage = result.exceptionOrNull()?.message
                ?: result.getOrNull()?.errorMessage
            if (result.isSuccess) {
                AppFeedback.showToast(context, context.getString(CoreCommonR.string.settings_lx_imported))
            }
        }
    }

    /**
     * 音质检测：在独立实例里跑脚本，按"平台 × 音质"逐项验证能否换出可用的播放地址。
     */
    fun startTest(item: LxSourceItem, mode: LxTestMode) {
        if (testPhase == LxTestPhase.RUNNING) return
        testTarget = item
        testProbes = emptyList()
        testReport = null
        testCancelFlag.set(false)
        testPhase = LxTestPhase.RUNNING
        AppContainer.launchBackgroundIo {
            val report = runCatching {
                AppContainer.lxSourceTester.test(
                    scriptText = item.scriptText,
                    scriptInfo = CurrentScriptInfo(name = item.name),
                    mode = mode,
                    onProgress = { probe -> testProbes = testProbes + probe },
                    isCancelled = { testCancelFlag.get() }
                )
            }.getOrElse { error ->
                LxSourceTestReport(
                    sourceName = item.name,
                    initialized = false,
                    initError = error.message ?: error.toString()
                )
            }
            testProbes = report.probes
            testReport = report
            testPhase = LxTestPhase.RESULT
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(CoreCommonR.string.settings_lx_source_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // 两个导入入口并排，做成实心按钮，比原来的纯文字入口更清晰、更好点
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { scriptPicker.launch(arrayOf("text/javascript", "text/plain", "*/*")) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Outlined.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_import_file),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalButton(
                onClick = { showUrlImport = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Outlined.Link,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_import_url),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 免责声明式按钮：集中放「支持项 / 使用方法 / 风险提示」的教程正文
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            FilledTonalButton(onClick = { showGuide = true }) {
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_guide_button),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        errorMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        if (scripts.isEmpty()) {
            Text(
                text = stringResource(CoreCommonR.string.settings_lx_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            scripts.forEach { item ->
                val sources = item.info?.sources?.keys?.joinToString(",").orEmpty()
                val status = when (item.state) {
                    LxSourceState.OK -> stringResource(CoreCommonR.string.settings_lx_state_ok)
                    LxSourceState.FAIL -> stringResource(CoreCommonR.string.settings_lx_state_fail)
                    LxSourceState.DISABLED -> stringResource(CoreCommonR.string.settings_lx_state_disabled)
                    LxSourceState.INIT -> stringResource(CoreCommonR.string.settings_lx_state_init)
                }
                // 质感边框卡片：背景与描边均取主题色，明暗主题切换时自动适配，
                // 不会在黑/白背景下消失（surfaceContainerLow + outlineVariant 为主题感知色）。
                val cardShape = RoundedCornerShape(16.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, cardShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    // 第一排：名称 + 单行文件名（信息区占满整行宽度）。
                    // 状态只在第二排「状态 · 声明平台」显示一次，避免重复。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.remark.takeIf { it.isNotBlank() } ?: item.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(CoreCommonR.string.settings_lx_remark_file, item.name),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // 失败原因外露
                    if (!item.error.isNullOrBlank()) {
                        Text(
                            text = item.error!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    // 第二排：左侧状态/声明平台，右侧四个缩小按钮（顺序不变：备注/检测/开关/删除）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (sources.isEmpty()) status else "$status · $sources",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // 备注：铅笔图标
                        IconButton(
                            onClick = {
                                remarkTarget = item
                                remarkDraft = item.remark
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(CoreCommonR.string.settings_lx_remark_action),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                        // 检测：单文字「测」代替图标
                        IconButton(
                            onClick = {
                                testTarget = item
                                testProbes = emptyList()
                                testReport = null
                                testPhase = LxTestPhase.CHOOSE
                            },
                            enabled = testPhase == null,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Text(
                                text = stringResource(CoreCommonR.string.settings_lx_test_short),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                        MiuixSettingsSwitch(
                            checked = item.enabled,
                            onCheckedChange = { next ->
                                AppContainer.launchBackgroundIo { lxRepository.setEnabled(item.id, next) }
                            }
                        )
                        Spacer(Modifier.width(2.dp))
                        IconButton(
                            onClick = { AppContainer.launchBackgroundIo { lxRepository.remove(item.id) } },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Outlined.DeleteForever,
                                contentDescription = stringResource(CoreCommonR.string.settings_lx_delete),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        testPhase?.let { phase ->
            val target = testTarget
            if (target != null) {
                LxSourceTestDialog(
                    sourceName = target.name,
                    phase = phase,
                    probes = testProbes,
                    report = testReport,
                    onStart = { mode -> startTest(item = target, mode = mode) },
                    onCancel = {
                        if (phase == LxTestPhase.RUNNING) {
                            testCancelFlag.set(true)
                        } else {
                            testPhase = null
                        }
                    },
                    onDismiss = { testPhase = null }
                )
            }
        }

        if (showUrlImport) {
            LxScriptUrlImportDialog(
                loading = importing,
                errorMessage = urlImportError,
                onImport = { url ->
                    if (importing) return@LxScriptUrlImportDialog
                    importing = true
                    urlImportError = null
                    AppContainer.launchBackgroundIo {
                        val result = runCatching {
                            LxScriptImporter.importFromUrl(
                                repository = lxRepository,
                                url = url,
                                http = AppContainer.lxScriptHttpClient
                            )
                        }
                        importing = false
                        result.exceptionOrNull()?.let { error ->
                            urlImportError = error.message ?: error.toString()
                            return@launchBackgroundIo
                        }
                        val imported = result.getOrNull() ?: return@launchBackgroundIo
                        if (imported.succeeded) {
                            showUrlImport = false
                            AppFeedback.showToast(
                                context,
                                context.getString(CoreCommonR.string.settings_lx_imported)
                            )
                        } else {
                            urlImportError = imported.errorMessage
                        }
                    }
                },
                onDismiss = {
                    showUrlImport = false
                    urlImportError = null
                }
            )
        }

        if (showGuide) {
            LxSourceGuideDialog(onDismiss = { showGuide = false })
        }

        remarkTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { remarkTarget = null },
                title = { Text(stringResource(CoreCommonR.string.settings_lx_remark_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = remarkDraft,
                            onValueChange = { remarkDraft = it.take(40) },
                            singleLine = true,
                            label = { Text(stringResource(CoreCommonR.string.settings_lx_remark_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(CoreCommonR.string.settings_lx_remark_file, target.name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val value = remarkDraft.trim()
                        AppContainer.launchBackgroundIo { lxRepository.setRemark(target.id, value) }
                        remarkTarget = null
                    }) {
                        Text(stringResource(CoreCommonR.string.settings_lx_remark_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { remarkTarget = null }) {
                        Text(stringResource(CoreCommonR.string.action_cancel))
                    }
                }
            )
        }
    }
}

/**
 * 自定义音乐源说明弹窗：支持项 / 使用方法 / 风险免责。
 * 样式与「关于」页的免责声明一致（可滚动正文 + 确定按钮）。
 */
@Composable
private fun LxSourceGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(CoreCommonR.string.settings_lx_guide_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_guide_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_guide_support_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_guide_support_body),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_guide_usage_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_guide_usage_body),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_guide_risk_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_guide_risk_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        }
    )
}
