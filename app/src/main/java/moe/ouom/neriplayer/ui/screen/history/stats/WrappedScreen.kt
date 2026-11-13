package moe.ouom.neriplayer.ui.screen.history.stats

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.toPlaybackStatsSongItem
import moe.ouom.neriplayer.ui.feedback.startActivityOrShowUnavailable
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR

/**
 * 听歌总结页。
 *
 * 以主流音乐 App 的"年度总结"为原型，按周期展示总览、最常播放与最爱艺人。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WrappedScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedPeriod by remember { mutableStateOf(PlaybackStatsPeriod.YEAR) }
    var sharing by remember { mutableStateOf(false) }
    val state by rememberWrappedState(selectedPeriod)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(AppR.string.wrapped_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreCommonR.string.action_back)
                        )
                    }
                },
                actions = {
                    HapticIconButton(
                        onClick = {
                            if (sharing || state.tracks.isEmpty()) return@HapticIconButton
                            sharing = true
                            scope.launch {
                                val result = runCatching {
                                    shareWrappedSummary(context, state, selectedPeriod)
                                }
                                sharing = false
                                result.exceptionOrNull()?.let {
                                    context.startActivityOrShowUnavailable(
                                        android.content.Intent.createChooser(
                                            android.content.Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    Intent.EXTRA_TEXT,
                                                    context.getString(AppR.string.wrapped_share_text)
                                                )
                                            },
                                            null
                                        )
                                    )
                                }
                            }
                        },
                        enabled = !sharing && state.tracks.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(AppR.string.wrapped_share)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.loading && state.tracks.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.failed && state.tracks.isEmpty() -> {
                    Text(
                        text = stringResource(AppR.string.stats_load_failed),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                state.tracks.isEmpty() -> {
                    Text(
                        text = stringResource(AppR.string.stats_empty),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                else -> {
                    WrappedContent(
                        state = state,
                        selectedPeriod = selectedPeriod,
                        onPeriodSelected = { selectedPeriod = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun WrappedContent(
    state: WrappedState,
    selectedPeriod: PlaybackStatsPeriod,
    onPeriodSelected: (PlaybackStatsPeriod) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StatsPeriodSelector(
                selectedPeriod = selectedPeriod,
                onPeriodSelected = onPeriodSelected
            )
        }
        item {
            WrappedSummaryCard(state)
        }
        item {
            WrappedSectionTitle(stringResource(AppR.string.stats_top_tracks))
        }
        itemsIndexed(
            items = state.tracks.take(WrappedTrackDisplayLimit),
            key = { _, track -> track.identityKey }
        ) { index, track ->
            WrappedTrackRow(rank = index + 1, track = track)
        }
        if (state.artists.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                WrappedSectionTitle(stringResource(AppR.string.wrapped_top_artists))
            }
            itemsIndexed(
                items = state.artists.take(WrappedTrackDisplayLimit),
                key = { _, artist -> artist.name }
            ) { index, artist ->
                WrappedArtistRow(rank = index + 1, artist = artist)
            }
        }
    }
}

@Composable
private fun WrappedSummaryCard(state: WrappedState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = neriRoundedShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(AppR.string.wrapped_summary_headline),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WrappedMetric(
                    label = stringResource(AppR.string.stats_total_plays),
                    value = state.summary.totalPlayCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                WrappedMetric(
                    label = stringResource(AppR.string.stats_total_time),
                    value = formatListenDuration(state.summary.totalListenMs),
                    modifier = Modifier.weight(1f)
                )
                WrappedMetric(
                    label = stringResource(AppR.string.stats_track_count),
                    value = state.summary.trackCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WrappedMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WrappedSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun WrappedTrackRow(rank: Int, track: TrackStat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = neriRoundedShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            AsyncImage(
                model = offlineCachedImageRequest(
                    context = LocalContext.current,
                    data = track.toPlaybackStatsSongItem().displayCoverUrl(),
                    sizePx = 128,
                    allowHardware = false
                ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist.ifBlank { UnknownArtistName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = track.playCount.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun WrappedArtistRow(rank: Int, artist: WrappedArtist) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = neriRoundedShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Text(
                text = stringResource(AppR.string.wrapped_artist_play_count, artist.playCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private suspend fun shareWrappedSummary(
    context: android.content.Context,
    state: WrappedState,
    period: PlaybackStatsPeriod
) {
    val periodLabel = context.getString(period.wrappedLabelRes())
    val file = WrappedShareImage.create(
        context = context,
        periodLabel = periodLabel,
        summary = WrappedShareSummary(
            totalPlayCount = state.summary.totalPlayCount,
            totalListenLabel = formatListenDuration(state.summary.totalListenMs),
            trackCount = state.summary.trackCount,
            topTracks = state.tracks.take(WrappedShareTopLimit),
            topArtists = state.artists.take(WrappedShareTopLimit)
        )
    )
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(
            Intent.EXTRA_TEXT,
            context.getString(AppR.string.wrapped_share_text)
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newUri(context.contentResolver, file.name, uri)
    }
    context.startActivityOrShowUnavailable(Intent.createChooser(sendIntent, null))
}

internal const val WrappedTrackDisplayLimit = 10

/** 周期在分享卡片上的中文标签。 */
internal fun PlaybackStatsPeriod.wrappedLabelRes(): Int = when (this) {
    PlaybackStatsPeriod.DAY -> AppR.string.wrapped_period_day
    PlaybackStatsPeriod.WEEK -> AppR.string.wrapped_period_week
    PlaybackStatsPeriod.MONTH -> AppR.string.wrapped_period_month
    PlaybackStatsPeriod.YEAR -> AppR.string.wrapped_period_year
    PlaybackStatsPeriod.ALL -> AppR.string.wrapped_period_all
}
