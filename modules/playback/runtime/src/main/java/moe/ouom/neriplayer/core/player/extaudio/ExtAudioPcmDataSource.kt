package moe.ouom.neriplayer.core.player.extaudio

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import moe.ouom.neriplayer.common.logging.NPLogger
import java.io.File
import java.io.IOException

/**
 * 把本地 APE / WMA / DSD / AIFF 解码成 PCM，并在前面拼一个 WAV 头，
 * 于是 Media3 自带的 WavExtractor 与后续平台 PCM 渲染链可以原样复用。
 *
 * 只读本地文件；网络 URI 会被 [ExtAudioRoutingDataSource] 挡在门外。
 */
internal class ExtAudioPcmDataSource(
    private val context: Context,
) : DataSource {

    private var handle = 0L
    private var header = ByteArray(0)
    private var headerPos = 0
    private var sampleRate = 0
    private var channels = 0
    private var pcmBytes = 0
    private var openedUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        handle = if (scheme == "content") {
            // SAF/content:// 不能走 /proc/self/fd（部分机型 FFmpeg 打不开），
            // 直接把 fd 交给 native 的自定义 AVIO 回调读取。
            val descriptor = try {
                context.contentResolver.openFileDescriptor(dataSpec.uri, "r")
            } catch (t: Throwable) {
                NPLogger.e("ExtAudio", "openFileDescriptor failed, uri=${dataSpec.uri}: ${t.message}")
                null
            }
            if (descriptor == null) {
                release()
                throw IOException("extaudio: cannot open content uri")
            }
            val h = ExtAudioNative.openFd(descriptor.fd)
            // native 已 dup fd，这里立刻关掉调用方的 fd
            descriptor.close()
            h
        } else {
            val path = dataSpec.uri.path
            if (path.isNullOrBlank()) {
                release()
                throw IOException("extaudio: missing path")
            }
            ExtAudioNative.open(path)
        }
        if (handle == 0L) {
            NPLogger.e("ExtAudio", "native open failed, uri=${dataSpec.uri}, scheme=$scheme")
            release()
            throw IOException("extaudio: decode open failed")
        }
        sampleRate = ExtAudioNative.sampleRate(handle)
        channels = ExtAudioNative.channelCount(handle)
        if (sampleRate <= 0 || channels <= 0) {
            release()
            throw IOException("extaudio: invalid audio format")
        }
        val durationUs = ExtAudioNative.durationUs(handle)
        pcmBytes = if (durationUs > 0) {
            (durationUs * sampleRate.toLong() * channels * 2L / 1_000_000L)
                .coerceIn(0L, Int.MAX_VALUE - WAV_HEADER_SIZE.toLong())
                .toInt()
        } else {
            Int.MAX_VALUE - WAV_HEADER_SIZE
        }
        header = buildWavHeader(pcmBytes, sampleRate, channels)
        headerPos = 0
        openedUri = dataSpec.uri
        NPLogger.d(
            "ExtAudio",
            "open ok: uri=${dataSpec.uri}, rate=$sampleRate, ch=$channels, " +
                "durationUs=$durationUs, pcmBytes=$pcmBytes, position=${dataSpec.position}"
        )

        if (dataSpec.position > 0 && !seekTo(dataSpec.position)) {
            // 恢复播放时 FFmpeg 对 APE 的 seek 可能失败；不要让它变成致命 IO 错误，
            // 从头播放并记录日志，避免用户看到「播放失败」。
            NPLogger.w("ExtAudio", "initial seek failed at ${dataSpec.position}, restart from 0")
            headerPos = 0
        }
        return WAV_HEADER_SIZE.toLong() + pcmBytes.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (handle == 0L || length <= 0) return C.RESULT_END_OF_INPUT
        if (headerPos < header.size) {
            val n = minOf(length, header.size - headerPos)
            System.arraycopy(header, headerPos, buffer, offset, n)
            headerPos += n
            return n
        }
        val n = ExtAudioNative.read(handle, buffer, offset, length)
        if (n == -2) {
            NPLogger.e("ExtAudio", "native decode failed, offset=$offset, length=$length, uri=$openedUri")
            throw IOException("extaudio: decode failed")
        }
        return n
    }

    /** Media3 的 seek 通过带新 DataSpec.position 重新 open 实现，这里只在 open 内调用。 */
    private fun seekTo(position: Long): Boolean {
        if (handle == 0L) return false
        if (position < WAV_HEADER_SIZE) {
            headerPos = position.toInt()
            return true
        }
        val pcmPos = position - WAV_HEADER_SIZE
        val bytesPerSecond = sampleRate.toLong() * channels * 2L
        val positionUs = pcmPos * 1_000_000L / bytesPerSecond
        if (!ExtAudioNative.seek(handle, positionUs)) return false
        headerPos = WAV_HEADER_SIZE
        return true
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        release()
    }

    private fun release() {
        if (handle != 0L) {
            runCatching { ExtAudioNative.close(handle) }
            handle = 0L
        }
        openedUri = null
        header = ByteArray(0)
        headerPos = 0
    }

    private companion object {
        const val WAV_HEADER_SIZE = 44

        fun buildWavHeader(dataSize: Int, sampleRate: Int, channels: Int): ByteArray {
            val out = ByteArray(WAV_HEADER_SIZE)
            fun ascii(pos: Int, text: String) {
                for (i in text.indices) out[pos + i] = text[i].code.toByte()
            }
            fun u32(pos: Int, value: Int) {
                for (i in 0..3) out[pos + i] = (value ushr (8 * i)).toByte()
            }
            fun u16(pos: Int, value: Int) {
                out[pos] = value.toByte()
                out[pos + 1] = (value shr 8).toByte()
            }
            val byteRate = sampleRate * channels * 2
            val blockAlign = channels * 2
            ascii(0, "RIFF")
            u32(4, 36 + dataSize)
            ascii(8, "WAVE")
            ascii(12, "fmt ")
            u32(16, 16)
            u16(20, 1) // PCM
            u16(22, channels)
            u32(24, sampleRate)
            u32(28, byteRate)
            u16(32, blockAlign)
            u16(34, 16)
            ascii(36, "data")
            u32(40, dataSize)
            return out
        }
    }
}
