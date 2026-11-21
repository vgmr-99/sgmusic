package moe.ouom.neriplayer.core.player.engine

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegPreferredMediaCodecSelectorTest {

    @Test
    fun `FFmpeg preferred formats do not query platform decoders`() {
        val delegate = RecordingMediaCodecSelector()
        val selector = FfmpegPreferredMediaCodecSelector(
            delegate = delegate,
            ffmpegPreferredMimeTypes = FFMPEG_PREFERRED_AUDIO_MIME_TYPES.toSet()
        )

        listOf(
            MimeTypes.AUDIO_FLAC,
            MimeTypes.AUDIO_ALAC,
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_VORBIS
        ).forEach { mimeType ->
            assertTrue(mimeType, selector.getDecoderInfos(mimeType, false, false).isEmpty())
        }
        assertTrue(selector.getDecoderInfos("AUDIO/ALAC", false, false).isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun `format without FFmpeg support delegates to platform decoder`() {
        val delegate = RecordingMediaCodecSelector()
        val selector = FfmpegPreferredMediaCodecSelector(
            delegate = delegate,
            ffmpegPreferredMimeTypes = setOf(MimeTypes.AUDIO_FLAC)
        )

        selector.getDecoderInfos(MimeTypes.AUDIO_ALAC, true, true)

        assertEquals(
            listOf(DecoderRequest(MimeTypes.AUDIO_ALAC, true, true)),
            delegate.requests
        )
    }

    @Test
    fun `lossy formats retain the platform decoder selection`() {
        val delegate = RecordingMediaCodecSelector()
        val selector = FfmpegPreferredMediaCodecSelector(
            delegate = delegate,
            ffmpegPreferredMimeTypes = FFMPEG_PREFERRED_AUDIO_MIME_TYPES.toSet()
        )

        selector.getDecoderInfos(MimeTypes.AUDIO_MPEG, false, true)
        selector.getDecoderInfos(MimeTypes.AUDIO_AAC, false, false)
        selector.getDecoderInfos(MimeTypes.AUDIO_OPUS, false, false)
        selector.getDecoderInfos(MimeTypes.AUDIO_AMR_NB, false, false)

        assertEquals(
            listOf(
                DecoderRequest(MimeTypes.AUDIO_MPEG, false, true),
                DecoderRequest(MimeTypes.AUDIO_AAC, false, false),
                DecoderRequest(MimeTypes.AUDIO_OPUS, false, false),
                DecoderRequest(MimeTypes.AUDIO_AMR_NB, false, false)
            ),
            delegate.requests
        )
    }
}

private class RecordingMediaCodecSelector : MediaCodecSelector {
    val requests = mutableListOf<DecoderRequest>()

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        requests += DecoderRequest(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder
        )
        return emptyList()
    }
}

private data class DecoderRequest(
    val mimeType: String,
    val requiresSecureDecoder: Boolean,
    val requiresTunnelingDecoder: Boolean
)
