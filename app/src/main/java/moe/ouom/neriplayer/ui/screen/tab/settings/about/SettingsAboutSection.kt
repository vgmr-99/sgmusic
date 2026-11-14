package moe.ouom.neriplayer.ui.screen.tab.settings.about

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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.about/SettingsAboutSection
 * Updated: 2026/3/23
 */

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.BuildConfig
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.util.format.convertTimestampToDate
import moe.ouom.neriplayer.R as AppR

internal fun LazyListScope.settingsAboutSection(
    devModeEnabled: Boolean,
    onVersionClick: () -> Unit,
    onCopyValue: (String) -> Unit = {},
    onOpenGitHubRepo: () -> Unit,
    onOpenUpstreamGitHubRepo: () -> Unit = onOpenGitHubRepo,
    onOpenForkGitHubRepo: () -> Unit = onOpenGitHubRepo
) {
    item {
        val disclaimerState = remember { mutableStateOf(false) }
        SettingsAboutContent(
            devModeEnabled = devModeEnabled,
            onVersionClick = onVersionClick,
            onCopyValue = onCopyValue,
            onOpenGitHubRepo = onOpenGitHubRepo,
            onOpenUpstreamGitHubRepo = onOpenUpstreamGitHubRepo,
            onOpenForkGitHubRepo = onOpenForkGitHubRepo,
            onOpenDisclaimer = { disclaimerState.value = true }
        )
        if (disclaimerState.value) {
            SettingsDisclaimerDialog(onDismiss = { disclaimerState.value = false })
        }
    }
}

@Composable
internal fun SettingsAboutContent(
    devModeEnabled: Boolean,
    onVersionClick: () -> Unit,
    onCopyValue: (String) -> Unit,
    onOpenGitHubRepo: () -> Unit,
    onOpenUpstreamGitHubRepo: () -> Unit = onOpenGitHubRepo,
    onOpenForkGitHubRepo: () -> Unit = onOpenGitHubRepo,
    onOpenDisclaimer: () -> Unit = {}
) {
    SettingsAboutIntroItem()
    SettingsBuildUuidItem(onCopyValue)
    SettingsVersionItem(
        devModeEnabled = devModeEnabled,
        onVersionClick = onVersionClick,
        onCopyValue = onCopyValue
    )
    SettingsBuildTimeItem(onCopyValue)
    // 拾光音乐：只保留本项目仓库地址，不展示上游仓库入口
    SettingsGitHubItem(
        title = stringResource(CoreCommonR.string.settings_github_fork_url),
        supporting = stringResource(CoreCommonR.string.settings_github_fork_title),
        onOpenGitHubRepo = onOpenForkGitHubRepo
    )
    SettingsDisclaimerButtonItem(onOpenDisclaimer)
}

@Composable
private fun SettingsAboutIntroItem() {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(CoreCommonR.string.settings_about),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = {
            Text(
                text = stringResource(CoreCommonR.string.settings_about),
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = {
            // 拾光音乐：介绍分两行展示，第一行品牌名，第二行说明二次开发来源
            Column {
                Text(
                    text = stringResource(AppR.string.about_app_name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(AppR.string.about_app_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SettingsBuildUuidItem(onCopyValue: (String) -> Unit) {
    val buildUuid = BuildConfig.BUILD_UUID

    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Verified,
                contentDescription = stringResource(CoreCommonR.string.settings_build_uuid),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = {
            Text(
                text = stringResource(CoreCommonR.string.settings_build_uuid),
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = { Text(buildUuid) },
        modifier = androidx.compose.ui.Modifier.settingsItemClickable {
            onCopyValue(buildUuid)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SettingsVersionItem(
    devModeEnabled: Boolean,
    onVersionClick: () -> Unit,
    onCopyValue: (String) -> Unit
) {
    val suffix = if (devModeEnabled) {
        " (${stringResource(CoreCommonR.string.settings_version_debug_suffix)})"
    } else {
        ""
    }
    // 对外版本号走 APP_VERSION（gradle.properties 的 appVersion，如 1.4.0），
    // VERSION_NAME 是构建身份（<git短哈希>.<时间戳>），放在副行保留可追溯性。
    val appVersion = "${BuildConfig.APP_VERSION}$suffix"
    val buildIdentity = BuildConfig.VERSION_NAME

    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Update,
                contentDescription = stringResource(CoreCommonR.string.settings_version),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = {
            Text(
                text = stringResource(CoreCommonR.string.common_version),
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = appVersion,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = buildIdentity,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = androidx.compose.ui.Modifier.settingsItemClickable {
            onCopyValue(appVersion)
            onVersionClick()
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SettingsBuildTimeItem(onCopyValue: (String) -> Unit) {
    val buildTime = convertTimestampToDate(BuildConfig.BUILD_TIMESTAMP)

    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Timer,
                contentDescription = stringResource(CoreCommonR.string.settings_build_time),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = {
            Text(
                text = stringResource(CoreCommonR.string.common_build_time),
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = { Text(buildTime) },
        modifier = androidx.compose.ui.Modifier.settingsItemClickable {
            onCopyValue(buildTime)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SettingsDisclaimerButtonItem(onOpenDisclaimer: () -> Unit) {
    // 拾光音乐：免责声明改为居中按钮，点击后弹窗展示完整内容
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        FilledTonalButton(onClick = onOpenDisclaimer) {
            Text(
                text = stringResource(AppR.string.disclaimer_button_title),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
internal fun SettingsDisclaimerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(AppR.string.disclaimer_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(AppR.string.disclaimer_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(AppR.string.disclaimer_summary_content_body),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(AppR.string.disclaimer_section1_body),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(AppR.string.disclaimer_section3_bullet3),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(AppR.string.disclaimer_section4_bullet3),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(AppR.string.disclaimer_section6_bullet1),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(AppR.string.disclaimer_section9_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                // 拾光音乐：本项目补充免责条款
                Text(
                    text = stringResource(AppR.string.disclaimer_project_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(AppR.string.disclaimer_project_license_body),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(AppR.string.disclaimer_project_source_body),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(AppR.string.disclaimer_project_no_media_body),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(AppR.string.disclaimer_project_local_data_body),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(AppR.string.disclaimer_section9_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        }
    )
}

@Composable
private fun SettingsGitHubItem(
    title: String,
    supporting: String,
    onOpenGitHubRepo: () -> Unit
) {
    ListItem(
        leadingContent = {
            Icon(
                painter = painterResource(id = R.drawable.ic_github),
                contentDescription = stringResource(CoreCommonR.string.common_github),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        modifier = androidx.compose.ui.Modifier.settingsItemClickable(onClick = onOpenGitHubRepo),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
