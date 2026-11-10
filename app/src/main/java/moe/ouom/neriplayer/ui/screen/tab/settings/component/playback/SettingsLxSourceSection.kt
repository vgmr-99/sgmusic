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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.platform.lxsource.LxSourceState
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

/**
 * LX 自定义源兜底 页：总开关 + 脚本列表（导入、启停、删除）。
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

    Column(modifier = modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(CoreCommonR.string.settings_lx_source_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // LX 总开关已移除：脚本只要存在（已启用）即可作为自动箤底；其启用状态由脚本行开关决定
        MiuixSettingsTextButton(onClick = { scriptPicker.launch(arrayOf("text/javascript", "text/plain", "*/*")) }) {
            Text(stringResource(CoreCommonR.string.settings_lx_import_file))
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
            }
        ) {
            Text(stringResource(CoreCommonR.string.settings_lx_import_example))
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
    }
}
