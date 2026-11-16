package moe.ouom.neriplayer.ui.screen

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingProgressInfoSegment
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingProgressOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingSeekActionOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.buildNowPlayingProgressInfoPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.buildNowPlayingProgressInfoSegments
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.nowplaying.isNowPlayingPhoneLandscape
import moe.ouom.neriplayer.ui.screen.nowplaying.nowPlayingProgressFraction
import moe.ouom.neriplayer.ui.screen.nowplaying.nowPlayingSourceLabelRes
import moe.ouom.neriplayer.ui.screen.nowplaying.nowPlayingVisibleProgressInfoSegments
import moe.ouom.neriplayer.ui.screen.nowplaying.shouldReserveNowPlayingProgressInfoSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingPlaybackControlsTest {
    @Test
    fun `progress presentation keeps its row while next track metadata is loading`() {
        val info = PlaybackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            qualityLabel = "Hi-Res",
            codecLabel = "FLAC",
            sampleRateHz = 96000,
            bitDepth = 24
        )
        val loaded = buildNowPlayingProgressInfoPresentation(info, true, true, true, 1f, false)
        val loading = buildNowPlayingProgressInfoPresentation(null, true, true, true, 1f, false)
        assertEquals(listOf("Hi-Res", "FLAC", "96 kHz | 24 bit"), loaded.segments.map { it.label })
        assertTrue(loaded.reserveRowHeight)
        assertTrue(loading.segments.isEmpty())
        assertTrue(loading.reserveRowHeight)

        val disabled = buildNowPlayingProgressInfoPresentation(info, false, false, false, 1f, false)
        assertTrue(disabled.segments.isEmpty())
        assertFalse(disabled.reserveRowHeight)
        val speedOnly = buildNowPlayingProgressInfoPresentation(null, false, false, false, 1.25f, false)
        assertTrue(speedOnly.segments.isEmpty())
        assertTrue(speedOnly.reserveRowHeight)
        val phoneLandscape = buildNowPlayingProgressInfoPresentation(info, true, true, true, 1.25f, true)
        assertTrue(phoneLandscape.segments.isEmpty())
        assertFalse(phoneLandscape.reserveRowHeight)
    }

    @Test
    fun `information space follows display settings rather than transient audio data`() {
        listOf(
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, true)
        ).forEach { (quality, codec, spec) ->
            assertTrue(shouldReserveNowPlayingProgressInfoSpace(quality, codec, spec, 1f, false))
            assertFalse(shouldReserveNowPlayingProgressInfoSpace(quality, codec, spec, 1f, true))
        }
        assertFalse(shouldReserveNowPlayingProgressInfoSpace(false, false, false, 1f, false))
        assertTrue(shouldReserveNowPlayingProgressInfoSpace(false, false, false, 1.25f, false))
        assertFalse(shouldReserveNowPlayingProgressInfoSpace(false, false, false, 1.004f, false))
        assertFalse(shouldReserveNowPlayingProgressInfoSpace(true, true, true, 1.25f, true))
    }

    @Test
    fun `only phone landscape hides audio badges and rotating back restores the original information`() {
        val segments = listOf(
            NowPlayingProgressInfoSegment("高清环绕声", highlighted = true),
            NowPlayingProgressInfoSegment("FLAC"),
            NowPlayingProgressInfoSegment("96 kHz | 24 bit")
        )

        assertTrue(nowPlayingVisibleProgressInfoSegments(
            segments, phoneLandscape = isNowPlayingPhoneLandscape(true, 599)
        ).isEmpty())
        assertSame(segments, nowPlayingVisibleProgressInfoSegments(
            segments, phoneLandscape = isNowPlayingPhoneLandscape(false, 599)
        ))
        listOf(600, 800).forEach { tabletWidth ->
            listOf(true, false).forEach { landscape ->
                assertSame(segments, nowPlayingVisibleProgressInfoSegments(
                    segments, phoneLandscape = isNowPlayingPhoneLandscape(landscape, tabletWidth)
                ))
            }
        }
        assertEquals(3, segments.size)
        assertTrue(segments.first().highlighted)
    }

    @Test
    fun `tablet audio badges still respect individual display settings`() {
        val info = PlaybackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            qualityLabel = "Hi-Res",
            codecLabel = "FLAC",
            sampleRateHz = 96000,
            bitDepth = 24
        )
        val tabletLandscape = isNowPlayingPhoneLandscape(true, 800)
        assertEquals(
            listOf(NowPlayingProgressInfoSegment("Hi-Res", highlighted = true, clickable = true)),
            nowPlayingVisibleProgressInfoSegments(
                buildNowPlayingProgressInfoSegments(info, true, false, false, 1f), tabletLandscape
            )
        )
        assertEquals(
            listOf(NowPlayingProgressInfoSegment("FLAC")),
            nowPlayingVisibleProgressInfoSegments(
                buildNowPlayingProgressInfoSegments(info, false, true, false, 1f), tabletLandscape
            )
        )
        assertEquals(
            listOf(NowPlayingProgressInfoSegment("96 kHz | 24 bit")),
            nowPlayingVisibleProgressInfoSegments(
                buildNowPlayingProgressInfoSegments(info, false, false, true, 1f), tabletLandscape
            )
        )
        assertTrue(nowPlayingVisibleProgressInfoSegments(
            buildNowPlayingProgressInfoSegments(info, false, false, false, 1f), tabletLandscape
        ).isEmpty())
    }

    @Test
    fun `seek owner keeps preview until playback settles`() {
        val owner = NowPlayingProgressOwner(1000L)
        assertEquals(1000L, owner.previewPositionMs(1000L))
        assertNull(owner.previewOverrideMs(1000L))
        assertFalse(owner.isPreviewing)

        assertEquals(5000L, owner.startDrag(0.5f, 10000L))
        assertTrue(owner.isDragging)
        assertTrue(owner.isPreviewing)
        assertEquals(6000L, owner.moveDrag(0.6f, 10000L))
        owner.observePlaybackPosition(1100L)
        assertEquals(6000L, owner.previewOverrideMs(1100L))

        assertEquals(6000L, owner.finishDrag())
        assertFalse(owner.isDragging)
        assertEquals(6000L, owner.pendingSeekPreviewPositionMs)
        assertTrue(owner.isPreviewing)
        owner.observePlaybackPosition(1200L)
        assertEquals(6000L, owner.previewPositionMs(1200L))
        owner.observePlaybackPosition(5900L)
        assertNull(owner.pendingSeekPreviewPositionMs)
        assertFalse(owner.isPreviewing)
        assertNull(owner.previewOverrideMs(5900L))
        owner.observePlaybackPosition(6100L)
        assertEquals(6100L, owner.sliderPosition.toLong())
    }

    @Test
    fun `live progress reads ignore mirrored slider position changes`() {
        val owner = NowPlayingProgressOwner(1000L)
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            Snapshot.sendApplyNotifications()
            observer.observeReads(Any(), { _: Any -> invalidations++ }) {
                assertEquals(1000L, owner.previewPositionMs(1000L))
                assertNull(owner.previewOverrideMs(1000L))
            }

            owner.observePlaybackPosition(2000L)
            Snapshot.sendApplyNotifications()

            assertEquals(0, invalidations)
            assertEquals(2000L, owner.sliderPosition.toLong())
            assertEquals(2000L, owner.previewPositionMs(2000L))
            assertNull(owner.previewOverrideMs(2000L))
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun `dragged progress reads follow slider position changes`() {
        val owner = NowPlayingProgressOwner(1000L)
        owner.startDrag(0.6f, 10000L)
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            Snapshot.sendApplyNotifications()
            observer.observeReads(Any(), { _: Any -> invalidations++ }) {
                assertEquals(6000L, owner.previewPositionMs(1000L))
                assertEquals(6000L, owner.previewOverrideMs(1000L))
            }

            owner.observePlaybackPosition(2000L)
            Snapshot.sendApplyNotifications()
            assertEquals(0, invalidations)

            owner.moveDrag(0.7f, 10000L)
            Snapshot.sendApplyNotifications()

            assertEquals(1, invalidations)
            assertEquals(7000L, owner.previewPositionMs(2000L))
            assertEquals(7000L, owner.previewOverrideMs(2000L))
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun `pending progress reads invalidate when playback reaches the seek preview`() {
        val owner = NowPlayingProgressOwner(1000L)
        owner.startDrag(0.6f, 10000L)
        owner.finishDrag()
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            Snapshot.sendApplyNotifications()
            observer.observeReads(Any(), { _: Any -> invalidations++ }) {
                assertEquals(6000L, owner.previewPositionMs(1000L))
                assertEquals(6000L, owner.previewOverrideMs(1000L))
            }

            owner.observePlaybackPosition(2000L)
            Snapshot.sendApplyNotifications()
            assertEquals(0, invalidations)
            assertEquals(6000L, owner.previewPositionMs(2000L))

            owner.observePlaybackPosition(5900L)
            Snapshot.sendApplyNotifications()

            assertEquals(1, invalidations)
            assertNull(owner.pendingSeekPreviewPositionMs)
            assertEquals(5900L, owner.previewPositionMs(5900L))
            assertNull(owner.previewOverrideMs(5900L))
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun `cancelled seek returns to live position and zero duration stays safe`() {
        val owner = NowPlayingProgressOwner(800L)
        owner.startDrag(0.75f, 10000L)
        owner.cancelDrag(900L)
        assertFalse(owner.isDragging)
        assertNull(owner.pendingSeekPreviewPositionMs)
        assertEquals(900L, owner.previewPositionMs(900L))
        assertEquals(0f, nowPlayingProgressFraction(100L, 0L), 0f)
        assertEquals(0.5f, nowPlayingProgressFraction(500L, 1000L), 0f)
    }

    @Test
    fun `seek action owner dispatches one start move finish sequence`() {
        val progress = NowPlayingProgressOwner(1000L)
        val calls = mutableListOf<String>()
        val actions = NowPlayingSeekActionOwner(
            progress = progress,
            onSeekStart = { calls += "start:$it" },
            onSeekMove = { calls += "move:$it" },
            onSeekEnd = { calls += "end" },
            onSeekTo = { calls += "seek:$it" },
            onFeedback = { calls += "haptic:$it" }
        )
        actions.updatePlayback(10000L, 1000L)
        actions.onValueChangeStarted(0.4f)
        actions.onValueChange(0.5f)
        actions.onValueChangeFinished()
        assertEquals(
            listOf(
                "start:4000", "haptic:${HapticFeedbackEffect.Click}",
                "move:5000", "seek:5000", "end", "haptic:${HapticFeedbackEffect.Confirm}"
            ),
            calls
        )
        actions.updatePlayback(10000L, 2000L)
        actions.onValueChangeStarted(0.8f)
        actions.onValueChangeCanceled()
        assertEquals(2000L, progress.previewPositionMs(2000L))
        assertFalse(progress.isDragging)
    }

    @Test
    fun `seek owner keeps gesture callbacks stable while rebinding current effects`() {
        val progress = NowPlayingProgressOwner(0L)
        val calls = mutableListOf<String>()
        val first = progress.bindSeekActions(
            onSeekStart = { calls += "old:$it" },
            onSeekMove = {},
            onSeekEnd = {},
            onSeekTo = {},
            onFeedback = {}
        )
        val onValueChangeStarted = first.onValueChangeStarted
        val rebound = progress.bindSeekActions(
            onSeekStart = { calls += "current:$it" },
            onSeekMove = {},
            onSeekEnd = {},
            onSeekTo = {},
            onFeedback = {}
        )
        assertSame(first, rebound)
        assertSame(onValueChangeStarted, rebound.onValueChangeStarted)
        rebound.updatePlayback(1000L, 0L)
        rebound.onValueChangeStarted(0.5f)
        assertEquals(listOf("current:500"), calls)
    }

    @Test
    fun `progress badges reflect enabled metadata and nondefault speed`() {
        val info = PlaybackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            qualityLabel = "Hi-Res",
            codecLabel = "FLAC",
            sampleRateHz = 96000,
            bitDepth = 24
        )
        assertEquals(
            listOf(
                NowPlayingProgressInfoSegment("Hi-Res", highlighted = true, clickable = true),
                NowPlayingProgressInfoSegment("1.25x"),
                NowPlayingProgressInfoSegment("FLAC"),
                NowPlayingProgressInfoSegment("96 kHz | 24 bit")
            ),
            buildNowPlayingProgressInfoSegments(info, true, true, true, 1.25f)
        )
        assertEquals(
            listOf(NowPlayingProgressInfoSegment("FLAC")),
            buildNowPlayingProgressInfoSegments(info, false, true, false, 1f)
        )
        assertEquals(
            emptyList<NowPlayingProgressInfoSegment>(),
            buildNowPlayingProgressInfoSegments(null, true, true, true, 1.25f)
        )
        assertEquals(
            emptyList<NowPlayingProgressInfoSegment>(),
            buildNowPlayingProgressInfoSegments(
                info.copy(
                    qualityLabel = " ",
                    codecLabel = " ",
                    sampleRateHz = null,
                    bitDepth = null
                ),
                true, true, true, 1f
            )
        )
    }

    @Test
    fun `source label prefers channel id so LX playback shows the real platform`() {
        // 经 LX 解析的 QQ / 酷狗 / 汽水，channelId 仍在，应显示具体平台而非笼统的 LX
        assertEquals(
            CoreCommonR.string.settings_lx_platform_tx,
            nowPlayingSourceLabelRes("qq", PlaybackAudioSource.LX)
        )
        assertEquals(
            CoreCommonR.string.settings_lx_platform_kg,
            nowPlayingSourceLabelRes("kugou", PlaybackAudioSource.LX)
        )
        assertEquals(
            CoreCommonR.string.settings_lx_platform_qs,
            nowPlayingSourceLabelRes("qishui", null)
        )
        assertEquals(
            CoreCommonR.string.nowplaying_netease_cloud,
            nowPlayingSourceLabelRes("netease", PlaybackAudioSource.LX)
        )
        assertEquals(
            CoreCommonR.string.nowplaying_netease_cloud,
            nowPlayingSourceLabelRes(null, PlaybackAudioSource.NETEASE)
        )
        assertEquals(
            CoreCommonR.string.nowplaying_bilibili,
            nowPlayingSourceLabelRes(null, PlaybackAudioSource.BILIBILI)
        )
        // LX 但没有 channelId 时退回通用 LX 标识
        assertEquals(
            CoreCommonR.string.settings_lx_source,
            nowPlayingSourceLabelRes("lx", PlaybackAudioSource.LX)
        )
        assertNull(nowPlayingSourceLabelRes(null, null))
    }

    @Test
    fun `quality badge is clickable and source becomes the first info segment`() {
        val info = PlaybackAudioInfo(
            source = PlaybackAudioSource.LX,
            qualityKey = "320k",
            qualityLabel = "320k",
            qualityOptions = listOf(
                moe.ouom.neriplayer.data.model.playback.PlaybackQualityOption("128k", "128k"),
                moe.ouom.neriplayer.data.model.playback.PlaybackQualityOption("320k", "320k")
            )
        )
        val segments = buildNowPlayingProgressInfoSegments(
            info, true, true, true, 1f, sourceLabel = "汽水"
        )
        // 来源在最前，音质段可点击
        assertEquals("汽水", segments.first().label)
        assertFalse(segments.first().clickable)
        val quality = segments.first { it.label == "320k" }
        assertTrue(quality.highlighted)
        assertTrue(quality.clickable)
    }

    @Test
    fun `source label alone reserves a row in portrait but not phone landscape`() {
        val portrait = buildNowPlayingProgressInfoPresentation(
            null, false, false, false, 1f, false, sourceLabel = "网易云"
        )
        assertEquals(listOf("网易云"), portrait.segments.map { it.label })
        assertTrue(portrait.reserveRowHeight)
        val landscape = buildNowPlayingProgressInfoPresentation(
            null, false, false, false, 1f, true, sourceLabel = "网易云"
        )
        assertTrue(landscape.segments.isEmpty())
        assertFalse(landscape.reserveRowHeight)
    }
}
