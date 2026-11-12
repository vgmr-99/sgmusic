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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

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
    var testPhase by remember { mutableStateOf<LxTestPhase?>(null) }
    var testTarget by remember { mutableStateOf<LxSourceItem?>(null) }
    var testProbes by remember { mutableStateOf<List<LxQualityProbe>>(emptyList()) }
    var testReport by remember { mutableStateOf<LxSourceTestReport?>(null) }
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

        // 两个导入入口并排，避免长列表里被拉开距离
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiuixSettingsTextButton(
                onClick = { scriptPicker.launch(arrayOf("text/javascript", "text/plain", "*/*")) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_import_file),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MiuixSettingsTextButton(
                onClick = {
                    if (importing) return@MiuixSettingsTextButton
                    importing = true
                    AppContainer.launchBackgroundIo {
                        val result = runCatching {
                            val text = context.assets.open("script/example-source.js").bufferedReader().use { it.readText() }
                            LxScriptImporter.import(lxRepository, "example-source.js", text)
                        }
                        importing = false
                        errorMessage = result.exceptionOrNull()?.message
                            ?: result.getOrNull()?.errorMessage
                        if (result.isSuccess) {
                            AppFeedback.showToast(context, context.getString(CoreCommonR.string.settings_lx_imported))
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(CoreCommonR.string.settings_lx_import_example),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                ListItem(
                    headlineContent = { Text(item.name) },
                    supportingContent = {
                        val sources = item.info?.sources?.keys?.joinToString(",").orEmpty()
                        val status = when (item.state) {
                            LxSourceState.OK -> stringResource(CoreCommonR.string.settings_lx_state_ok)
                            LxSourceState.FAIL -> stringResource(CoreCommonR.string.settings_lx_state_fail)
                            LxSourceState.DISABLED -> stringResource(CoreCommonR.string.settings_lx_state_disabled)
                            LxSourceState.INIT -> stringResource(CoreCommonR.string.settings_lx_state_init)
                        }
                        Text(if (sources.isEmpty()) status else "$status · $sources")
                        // 失败原因外露：下次再碰 init 失败，不必抓 logcat 才知道
                        if (!item.error.isNullOrBlank()) {
                            Text(
                                text = item.error!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    testTarget = item
                                    testProbes = emptyList()
                                    testReport = null
                                    testPhase = LxTestPhase.CHOOSE
                                },
                                enabled = testPhase == null
                            ) {
                                Icon(
                                    Icons.Outlined.Science,
                                    contentDescription = stringResource(CoreCommonR.string.settings_lx_test_action)
                                )
                            }
                            MiuixSettingsSwitch(
                                checked = item.enabled,
                                onCheckedChange = { next ->
                                    AppContainer.launchBackgroundIo { lxRepository.setEnabled(item.id, next) }
                                }
                            )
                            IconButton(onClick = { AppContainer.launchBackgroundIo { lxRepository.remove(item.id) } }) {
                                Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(CoreCommonR.string.settings_lx_delete))
                            }
                        }
                    }
                )
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
    }
}
