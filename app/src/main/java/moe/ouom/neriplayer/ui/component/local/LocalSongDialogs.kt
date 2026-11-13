package moe.ouom.neriplayer.ui.component.local

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
 * File: moe.ouom.neriplayer.ui.component/LocalSongDialogs
 * Updated: 2026/3/23
 */


import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.data.model.local.LocalMediaDetails
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.util.format.convertTimestampToDate
import moe.ouom.neriplayer.util.format.formatDuration
import moe.ouom.neriplayer.common.format.formatFileSize
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.ui.i18n.asString

internal data class LocalSongDetailsLoadState(
    val details: LocalMediaDetails?,
    val error: UiText?
)

internal fun resolveLocalSongDetailsLoadState(
    details: LocalMediaDetails?,
    unavailableMessage: UiText
): LocalSongDetailsLoadState {
    return if (details != null) {
        LocalSongDetailsLoadState(details = details, error = null)
    } else {
        LocalSongDetailsLoadState(details = null, error = unavailableMessage)
    }
}

@Composable
fun LocalSongDetailsDialog(
    song: SongItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember(song) { SnackbarHostState() }
    var details by remember(song) { mutableStateOf<LocalMediaDetails?>(null) }
    var error by remember(song) { mutableStateOf<UiText?>(null) }

    LaunchedEffect(song) {
        details = null
        error = null
        runCatching { withContext(Dispatchers.IO) { LocalMediaSupport.inspect(context, song) } }
            .onSuccess {
                val loadState = resolveLocalSongDetailsLoadState(
                    details = it,
                    unavailableMessage = UiText.Resource(CoreCommonR.string.local_song_details_unavailable)
                )
                details = loadState.details
                error = loadState.error
            }
            .onFailure { error = UiText.Literal(it.message ?: it.javaClass.simpleName) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.local_song_details_title)) },
        text = {
            fun copyValue(value: String) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", value)))
                    snackbarHostState.showNeriSnackbar(composeResources.getString(CoreCommonR.string.toast_copied))
                }
            }

            @Composable
            fun CopyableDetail(label: String, value: String?, mono: Boolean = false) {
                DetailsRow(label, value, mono, onClick = { value?.let(::copyValue) })
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // details 是委托属性，when 分支内无法 smart-cast；先取局部快照拿到稳定引用
                    val currentDetails = details
                    when {
                        currentDetails == null && error == null -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator()
                                Text(stringResource(CoreCommonR.string.local_song_details_loading))
                            }
                        }

                        error != null -> {
                            Text(
                                text = stringResource(CoreCommonR.string.local_song_details_failed, error?.asString().orEmpty()),
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        currentDetails != null -> {
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_display_name), currentDetails.displayName)
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_title), currentDetails.title)
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_artist), currentDetails.artist)
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_album), currentDetails.album)
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_album_artist), currentDetails.albumArtist)
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_composer), currentDetails.composer)
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_genre), currentDetails.genre)
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_year), currentDetails.year?.toString())
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_track), currentDetails.trackNumber?.toString())
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_disc), currentDetails.discNumber?.toString())
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_duration), formatDuration(currentDetails.durationMs))
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_extension), currentDetails.fileExtension)
                            CopyableDetail(
                                stringResource(CoreCommonR.string.local_song_detail_bitrate),
                                currentDetails.bitrateKbps?.let { "$it kbps" }
                            )
                            CopyableDetail(
                                stringResource(CoreCommonR.string.local_song_detail_sample_rate),
                                currentDetails.sampleRateHz?.let { "$it Hz" }
                            )
                            CopyableDetail(
                                stringResource(CoreCommonR.string.local_song_detail_channels),
                                currentDetails.channelCount?.toString()
                            )
                            CopyableDetail(
                                stringResource(CoreCommonR.string.local_song_detail_bit_depth),
                                currentDetails.bitsPerSample?.let { "$it-bit" }
                            )
                            CopyableDetail(
                                stringResource(CoreCommonR.string.local_song_detail_size),
                                currentDetails.sizeBytes?.let(::formatFileSize)
                            )
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_mime), currentDetails.mimeType)
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_audio_mime), currentDetails.audioMimeType)
                            CopyableDetail(
                                stringResource(CoreCommonR.string.local_song_detail_modified),
                                currentDetails.lastModifiedMs?.let(::convertTimestampToDate)
                            )
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_cover), currentDetails.coverSource)
                            CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_lyric), currentDetails.lyricSource)
                            CopyableDetail(
                                stringResource(CoreCommonR.string.local_song_detail_has_lyrics),
                                if (currentDetails.lyricContent.isNullOrBlank()) {
                                    stringResource(CoreCommonR.string.local_song_detail_no_lyrics_value)
                                } else {
                                    stringResource(CoreCommonR.string.local_song_detail_has_lyrics_value)
                                }
                            )
                            CopyableDetail(
                                stringResource(CoreCommonR.string.local_song_detail_path),
                                currentDetails.filePath,
                                mono = true
                            )
                            CopyableDetail(
                                stringResource(CoreCommonR.string.local_song_detail_lyric_path),
                                currentDetails.lyricPath,
                                mono = true
                            )
                            if (currentDetails.filePath.isNullOrBlank()) {
                                CopyableDetail(stringResource(CoreCommonR.string.local_song_detail_uri), currentDetails.sourceUri.toString(), mono = true)
                            }
                        }
                    }
                }
                NeriSnackbarHost(
                    hostState = snackbarHostState,
                    applyNavigationBarsPadding = false,
                    applyImePadding = false
                )
            }
        },
        confirmButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        }
    )
}

@Composable
fun LocalSongSyncConfirmDialog(
    actionLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.local_song_sync_confirm_title)) },
        text = { Text(stringResource(CoreCommonR.string.local_song_sync_confirm_message, actionLabel)) },
        confirmButton = {
            HapticTextButton(onClick = onConfirm) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

@Composable
private fun DetailsRow(
    label: String,
    value: String?,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    if (value.isNullOrBlank()) return
    Column(
        modifier = Modifier
            .then(
                if (NeriDesign.isClean) Modifier.clip(NeriDesign.tokens.cardShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                else Modifier
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .fillMaxWidth()
            .padding(horizontal = if (NeriDesign.isClean) 12.dp else 0.dp,
                vertical = if (NeriDesign.isClean) 10.dp else 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
            } else {
                MaterialTheme.typography.bodyMedium
            }
        )
    }
}
