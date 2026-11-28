package moe.ouom.neriplayer.ui.screen.nowplaying

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.PlaybackQualityOption
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.LyricSeekHapticFeedback
import moe.ouom.neriplayer.ui.component.lyrics.rememberLyricSeekHapticFeedback
import moe.ouom.neriplayer.ui.component.playback.WaveformSlider
import moe.ouom.neriplayer.ui.component.playback.rememberDelayedPlaybackWaiting
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetDragBlocker
import moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.ui.screen.playback.resolveLyricPreviewTimeMs
import moe.ouom.neriplayer.ui.screen.playback.shouldReleaseLyricSeekPreview
import moe.ouom.neriplayer.util.format.formatDuration
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 播放渠道来源标识对应的字符串资源。
 *
 * 优先用歌曲的 channelId（能区分经 LX 解析的实际平台，如 qq/kugou/qishui），
 * 拿不到再用 PlaybackAudioSource 兜底。返回 null 表示不展示来源。
 */
internal fun nowPlayingSourceLabelRes(
    channelId: String?,
    audioSource: PlaybackAudioSource?
): Int? {
    when (channelId?.trim()?.lowercase(Locale.US)) {
        "netease", "wy" -> return CoreCommonR.string.nowplaying_netease_cloud
        "qq", "tx" -> return CoreCommonR.string.settings_lx_platform_tx
        "kugou", "kg" -> return CoreCommonR.string.settings_lx_platform_kg
        "qishui", "qs" -> return CoreCommonR.string.settings_lx_platform_qs
        "kw" -> return CoreCommonR.string.settings_lx_platform_kw
        "mg" -> return CoreCommonR.string.settings_lx_platform_mg
    }
    return when (audioSource) {
        PlaybackAudioSource.NETEASE -> CoreCommonR.string.nowplaying_netease_cloud
        PlaybackAudioSource.BILIBILI -> CoreCommonR.string.nowplaying_bilibili
        PlaybackAudioSource.YOUTUBE_MUSIC -> CoreCommonR.string.nowplaying_youtube_music
        PlaybackAudioSource.LOCAL -> CoreCommonR.string.local_files
        PlaybackAudioSource.SUBSONIC -> CoreCommonR.string.server_source
        PlaybackAudioSource.LX -> CoreCommonR.string.settings_lx_source
        null -> null
    }
}

internal data class NowPlayingProgressInfoSegment(
    val label: String,
    val highlighted: Boolean = false,
    /** 音质档位段可点击，点开音质选择弹窗 */
    val clickable: Boolean = false
)

internal data class NowPlayingProgressInfoPresentation(
    val segments: List<NowPlayingProgressInfoSegment>,
    val reserveRowHeight: Boolean
)

internal fun nowPlayingVisibleProgressInfoSegments(
    segments: List<NowPlayingProgressInfoSegment>,
    phoneLandscape: Boolean
): List<NowPlayingProgressInfoSegment> = if (phoneLandscape) emptyList() else segments

internal fun shouldReserveNowPlayingProgressInfoSpace(
    showQualitySwitch: Boolean,
    showAudioCodec: Boolean,
    showAudioSpec: Boolean,
    playbackSpeed: Float,
    phoneLandscape: Boolean
): Boolean = !phoneLandscape && (
    showQualitySwitch || showAudioCodec || showAudioSpec || shouldShowPlaybackSpeedBadge(playbackSpeed)
)

internal class NowPlayingProgressOwner(initialPositionMs: Long) {
    private var seekActions: NowPlayingSeekActionOwner? = null
    var isDragging by mutableStateOf(false)
        private set
    var sliderPosition by mutableFloatStateOf(initialPositionMs.toFloat())
        private set
    var pendingSeekPreviewPositionMs by mutableStateOf<Long?>(null)
        private set

    val isPreviewing: Boolean get() = isDragging || pendingSeekPreviewPositionMs != null

    fun previewPositionMs(playbackPositionMs: Long): Long = resolveLyricPreviewTimeMs(
        isDraggingSlider = isDragging,
        sliderPreviewPositionMs = if (isDragging) sliderPosition.toLong() else playbackPositionMs,
        pendingSeekPreviewPositionMs = pendingSeekPreviewPositionMs,
        playbackPositionMs = playbackPositionMs
    )

    fun previewOverrideMs(playbackPositionMs: Long): Long? =
        previewPositionMs(playbackPositionMs).takeIf { isDragging || pendingSeekPreviewPositionMs != null }

    fun observePlaybackPosition(positionMs: Long) {
        if (isDragging) return
        if (pendingSeekPreviewPositionMs == null) sliderPosition = positionMs.toFloat()
        val pendingPreview = pendingSeekPreviewPositionMs ?: return
        if (shouldReleaseLyricSeekPreview(positionMs, pendingPreview)) {
            pendingSeekPreviewPositionMs = null
        }
    }

    fun startDrag(percentage: Float, durationMs: Long): Long {
        isDragging = true
        sliderPosition = percentage * durationMs
        return sliderPosition.toLong()
    }

    fun moveDrag(percentage: Float, durationMs: Long): Long = startDrag(percentage, durationMs)

    fun finishDrag(): Long {
        val target = sliderPosition.toLong()
        pendingSeekPreviewPositionMs = target
        isDragging = false
        return target
    }

    fun cancelDrag(playbackPositionMs: Long) {
        sliderPosition = playbackPositionMs.toFloat()
        pendingSeekPreviewPositionMs = null
        isDragging = false
    }

    fun bindSeekActions(
        onSeekStart: (Long) -> Unit,
        onSeekMove: (Long) -> Unit,
        onSeekEnd: () -> Unit,
        onSeekTo: (Long) -> Unit,
        onFeedback: (HapticFeedbackEffect) -> Unit
    ): NowPlayingSeekActionOwner {
        val existing = seekActions
        if (existing != null) {
            existing.updateCallbacks(onSeekStart, onSeekMove, onSeekEnd, onSeekTo, onFeedback)
            return existing
        }
        return NowPlayingSeekActionOwner(
            this, onSeekStart, onSeekMove, onSeekEnd, onSeekTo, onFeedback
        ).also { seekActions = it }
    }
}

internal class NowPlayingSeekActionOwner(
    private val progress: NowPlayingProgressOwner,
    private var onSeekStart: (Long) -> Unit,
    private var onSeekMove: (Long) -> Unit,
    private var onSeekEnd: () -> Unit,
    private var onSeekTo: (Long) -> Unit,
    private var onFeedback: (HapticFeedbackEffect) -> Unit
) {
    private var durationMs = 0L
    private var currentPositionMs = 0L

    fun updateCallbacks(
        onSeekStart: (Long) -> Unit,
        onSeekMove: (Long) -> Unit,
        onSeekEnd: () -> Unit,
        onSeekTo: (Long) -> Unit,
        onFeedback: (HapticFeedbackEffect) -> Unit
    ) {
        this.onSeekStart = onSeekStart
        this.onSeekMove = onSeekMove
        this.onSeekEnd = onSeekEnd
        this.onSeekTo = onSeekTo
        this.onFeedback = onFeedback
    }

    fun updatePlayback(durationMs: Long, currentPositionMs: Long) {
        this.durationMs = durationMs
        this.currentPositionMs = currentPositionMs
    }

    val onValueChange: (Float) -> Unit = { percentage ->
        onSeekMove(progress.moveDrag(percentage, durationMs))
    }
    val onValueChangeStarted: (Float) -> Unit = { percentage ->
        onSeekStart(progress.startDrag(percentage, durationMs))
        onFeedback(HapticFeedbackEffect.Click)
    }
    val onValueChangeFinished: () -> Unit = {
        onSeekTo(progress.finishDrag())
        onSeekEnd()
        onFeedback(HapticFeedbackEffect.Confirm)
    }
    val onValueChangeCanceled: () -> Unit = {
        progress.cancelDrag(currentPositionMs)
        onSeekEnd()
    }
}

internal fun buildNowPlayingProgressInfoPresentation(
    audioInfo: PlaybackAudioInfo?,
    showQualitySwitch: Boolean,
    showAudioCodec: Boolean,
    showAudioSpec: Boolean,
    playbackSpeed: Float,
    phoneLandscape: Boolean,
    sourceLabel: String? = null
): NowPlayingProgressInfoPresentation {
    val segments = buildNowPlayingProgressInfoSegments(
        audioInfo, showQualitySwitch, showAudioCodec, showAudioSpec, playbackSpeed, sourceLabel
    )
    return NowPlayingProgressInfoPresentation(
        segments = nowPlayingVisibleProgressInfoSegments(segments, phoneLandscape),
        reserveRowHeight = shouldReserveNowPlayingProgressInfoSpace(
            showQualitySwitch, showAudioCodec, showAudioSpec, playbackSpeed, phoneLandscape
        ) || (!phoneLandscape && !sourceLabel.isNullOrBlank())
    )
}

internal fun buildNowPlayingProgressInfoSegments(
    audioInfo: PlaybackAudioInfo?,
    showQualitySwitch: Boolean,
    showAudioCodec: Boolean,
    showAudioSpec: Boolean,
    playbackSpeed: Float,
    sourceLabel: String? = null
): List<NowPlayingProgressInfoSegment> {
    val sourceSegment = sourceLabel?.takeIf { it.isNotBlank() }
        ?.let { NowPlayingProgressInfoSegment(it) }
    if (audioInfo == null) return listOfNotNull(sourceSegment)
    return listOfNotNull(
        sourceSegment,
        progressQualityBadge(audioInfo, showQualitySwitch),
        progressSpeedBadge(playbackSpeed),
        progressCodecBadge(audioInfo, showAudioCodec),
        progressSpecBadge(audioInfo, showAudioSpec)
    )
}

private fun progressQualityBadge(info: PlaybackAudioInfo, enabled: Boolean): NowPlayingProgressInfoSegment? =
    info.qualityLabel?.takeIf { enabled && it.isNotBlank() }
        ?.let { NowPlayingProgressInfoSegment(it, highlighted = true, clickable = true) }

private fun progressSpeedBadge(speed: Float): NowPlayingProgressInfoSegment? =
    formatNowPlayingPlaybackSpeed(speed)
        .takeIf { shouldShowPlaybackSpeedBadge(speed) }
        ?.let(::NowPlayingProgressInfoSegment)

private fun progressCodecBadge(info: PlaybackAudioInfo, enabled: Boolean): NowPlayingProgressInfoSegment? =
    info.codecLabel?.takeIf { enabled && it.isNotBlank() }
        ?.let(::NowPlayingProgressInfoSegment)

private fun progressSpecBadge(info: PlaybackAudioInfo, enabled: Boolean): NowPlayingProgressInfoSegment? =
    info.specLabel?.takeIf { enabled && it.isNotBlank() }
        ?.let(::NowPlayingProgressInfoSegment)

private fun shouldShowPlaybackSpeedBadge(playbackSpeed: Float): Boolean {
    return (playbackSpeed * 100).roundToInt() != 100
}

private fun formatNowPlayingPlaybackSpeed(playbackSpeed: Float): String {
    return String.format(Locale.US, "%.2fx", playbackSpeed)
}

@Composable
private fun NowPlayingProgressInfoRow(
    segments: List<NowPlayingProgressInfoSegment>,
    highlightedContentColor: Color,
    onQualityClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lineHeight = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() }
    Box(
        // 固定槽高让信息加载和字体回退不再改变封面的可用高度
        modifier = modifier.height(lineHeight).testTag("nowPlayingProgressInfoRow"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .wrapContentHeight(unbounded = true)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 2.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            segments.forEachIndexed { index, segment ->
                NowPlayingProgressInfoCell(
                    index = index,
                    segment = segment,
                    highlightedContentColor = highlightedContentColor,
                    onQualityClick = onQualityClick
                )
            }
        }
    }
}

@Composable
private fun NowPlayingProgressInfoCell(
    index: Int,
    segment: NowPlayingProgressInfoSegment,
    highlightedContentColor: Color,
    onQualityClick: () -> Unit
) {
    if (index > 0) {
        Text(
            text = "  ·  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.46f)
        )
    }
    Text(
        text = segment.label,
        style = MaterialTheme.typography.labelSmall,
        color = progressInfoColor(segment.highlighted, highlightedContentColor),
        modifier = if (segment.clickable) {
            Modifier
                .clip(neriRoundedShape(6.dp))
                .clickable(onClick = onQualityClick)
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .testTag("nowPlayingQualityBadge")
        } else {
            Modifier
        }
    )
}

@Composable
private fun progressInfoColor(highlighted: Boolean, highlight: Color): Color =
    if (highlighted) highlight.copy(alpha = 0.92f)
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)

/**
 * 音质选择小窗：居中、圆角、每项「档位名 / 补充说明（如文件大小）」两行 + 选中打勾。
 * 点击档位即切换并关闭，不需要额外的确定/关闭按钮。
 */
@Composable
fun NowPlayingQualityOptionsDialog(
    title: String,
    selectedKey: String?,
    options: List<PlaybackQualityOption>,
    onDismiss: () -> Unit,
    onSelect: (PlaybackQualityOption) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .wrapContentSize()
                .width(210.dp)
                .padding(horizontal = 8.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                options.forEach { option ->
                    val selected = option.key == selectedKey
                    val detailText = option.detail?.takeIf { it.isNotBlank() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(option) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            if (detailText != null) {
                                Text(
                                    text = detailText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(CoreCommonR.string.common_selected),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VolumeControlSheetContent() {
    val context = LocalContext.current
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }

    val audioDeviceInfo = rememberAudioDeviceInfo()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bottomSheetDragBlocker()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(audioDeviceInfo.first, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(imageVector = audioDeviceInfo.second, contentDescription = audioDeviceInfo.first)
            Slider(
                value = currentVolume.toFloat(),
                onValueChange = {
                    currentVolume = it.toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                },
                valueRange = 0f..maxVolume.toFloat(),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
internal fun NowPlayingProgressSection(
    songKey: String?,
    durationMs: Long,
    lyrics: List<LyricEntry>,
    lyricOffsetMs: Long,
    isPlaying: Boolean,
    isPlaybackWaiting: Boolean,
    playbackSpeed: Float,
    progressInfoPresentation: NowPlayingProgressInfoPresentation,
    seekEnabled: Boolean,
    activeContentColor: Color,
    useWideLandscapeLayout: Boolean,
    onPreviewPositionChange: (Long?) -> Unit,
    onQualityClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    tabletLandscape: Boolean = false,
    progressRowModifier: Modifier = Modifier,
    progressShape: moe.ouom.neriplayer.ui.component.playback.NowPlayingProgressShape =
        moe.ouom.neriplayer.ui.component.playback.NowPlayingProgressShape.WAVE
) {
    val owner = remember(songKey) {
        NowPlayingProgressOwner(PlayerManager.playbackPositionFlow.value)
    }
    val currentPosition by PlayerManager.playbackPositionFlow.collectAsStateWithLifecycle()
    val effectivePreviewPositionMs = owner.previewPositionMs(currentPosition)
    val previewOverridePositionMs = owner.previewOverrideMs(currentPosition)
    val latestOnPreviewPositionChange by rememberUpdatedState(onPreviewPositionChange)
    val lyricSeekHaptic = rememberLyricSeekHapticFeedback(lyrics, lyricOffsetMs)
    val delayedPlaybackWaiting = rememberDelayedPlaybackWaiting(isPlaybackWaiting)

    LaunchedEffect(currentPosition, owner.isDragging, owner.pendingSeekPreviewPositionMs) {
        owner.observePlaybackPosition(currentPosition)
    }
    LaunchedEffect(previewOverridePositionMs) {
        latestOnPreviewPositionChange(previewOverridePositionMs)
    }
    DisposableEffect(Unit) {
        onDispose { latestOnPreviewPositionChange(null) }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        NowPlayingProgressRow(
            owner = owner,
            currentPosition = currentPosition,
            effectivePreviewPositionMs = effectivePreviewPositionMs,
            durationMs = durationMs,
            songKey = songKey,
            isPlaying = isPlaying,
            isPlaybackWaiting = isPlaybackWaiting,
            delayedPlaybackWaiting = delayedPlaybackWaiting,
            playbackSpeed = playbackSpeed,
            seekEnabled = seekEnabled,
            activeContentColor = activeContentColor,
            lyricSeekHaptic = lyricSeekHaptic,
            modifier = Modifier.fillMaxWidth().then(progressRowModifier),
            progressShape = progressShape
        )
        if (progressInfoPresentation.reserveRowHeight || progressInfoPresentation.segments.isNotEmpty()) {
            NowPlayingProgressInfoRow(
                segments = progressInfoPresentation.segments,
                highlightedContentColor = activeContentColor,
                onQualityClick = onQualityClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = when {
                        tabletLandscape -> (-1).dp
                        useWideLandscapeLayout -> (-5).dp
                        else -> (-6).dp
                    })
            )
        }
    }
}

@Composable
private fun NowPlayingProgressRow(
    owner: NowPlayingProgressOwner,
    currentPosition: Long,
    effectivePreviewPositionMs: Long,
    durationMs: Long,
    songKey: String?,
    isPlaying: Boolean,
    isPlaybackWaiting: Boolean,
    delayedPlaybackWaiting: Boolean,
    playbackSpeed: Float,
    seekEnabled: Boolean,
    activeContentColor: Color,
    lyricSeekHaptic: LyricSeekHapticFeedback,
    modifier: Modifier,
    progressShape: moe.ouom.neriplayer.ui.component.playback.NowPlayingProgressShape =
        moe.ouom.neriplayer.ui.component.playback.NowPlayingProgressShape.WAVE
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = formatDuration(effectivePreviewPositionMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NowPlayingSeekSlider(
            owner = owner,
            currentPosition = currentPosition,
            effectivePreviewPositionMs = effectivePreviewPositionMs,
            durationMs = durationMs,
            songKey = songKey,
            isPlaying = isPlaying,
            isPlaybackWaiting = isPlaybackWaiting,
            delayedPlaybackWaiting = delayedPlaybackWaiting,
            playbackSpeed = playbackSpeed,
            seekEnabled = seekEnabled,
            activeContentColor = activeContentColor,
            lyricSeekHaptic = lyricSeekHaptic,
            modifier = Modifier.weight(1f),
            progressShape = progressShape
        )
        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun nowPlayingProgressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

@Composable
private fun rememberNowPlayingSeekActions(
    owner: NowPlayingProgressOwner,
    lyricSeekHaptic: LyricSeekHapticFeedback
): NowPlayingSeekActionOwner {
    return bindNowPlayingSeekActions(owner, lyricSeekHaptic, LocalContext.current)
}

private fun bindNowPlayingSeekActions(
    owner: NowPlayingProgressOwner,
    lyricSeekHaptic: LyricSeekHapticFeedback,
    context: Context
): NowPlayingSeekActionOwner = owner.bindSeekActions(
    lyricSeekHaptic::onSeekStart,
    lyricSeekHaptic::onSeekMove,
    lyricSeekHaptic::onSeekEnd,
    { PlayerManager.seekTo(it) },
    context::performHapticFeedback
)

@Composable
private fun NowPlayingSeekSlider(
    owner: NowPlayingProgressOwner,
    currentPosition: Long,
    effectivePreviewPositionMs: Long,
    durationMs: Long,
    songKey: String?,
    isPlaying: Boolean,
    isPlaybackWaiting: Boolean,
    delayedPlaybackWaiting: Boolean,
    playbackSpeed: Float,
    seekEnabled: Boolean,
    activeContentColor: Color,
    lyricSeekHaptic: LyricSeekHapticFeedback,
    modifier: Modifier,
    progressShape: moe.ouom.neriplayer.ui.component.playback.NowPlayingProgressShape =
        moe.ouom.neriplayer.ui.component.playback.NowPlayingProgressShape.WAVE
) {
    val actions = rememberNowPlayingSeekActions(owner, lyricSeekHaptic)
    actions.updatePlayback(durationMs, currentPosition)
    WaveformSlider(
        modifier = modifier,
        value = nowPlayingProgressFraction(effectivePreviewPositionMs, durationMs),
        onValueChange = actions.onValueChange,
        onValueChangeStarted = actions.onValueChangeStarted,
        onValueChangeFinished = actions.onValueChangeFinished,
        onValueChangeCanceled = actions.onValueChangeCanceled,
        isPlaying = isPlaying,
        enabled = seekEnabled,
        isPlaybackWaiting = delayedPlaybackWaiting,
        isProgressStalled = isPlaybackWaiting,
        isProgressPreviewing = owner.isPreviewing,
        activeTint = activeContentColor,
        durationMs = durationMs,
        playbackSpeed = playbackSpeed,
        playbackSessionKey = songKey,
        shape = progressShape
    )
}
