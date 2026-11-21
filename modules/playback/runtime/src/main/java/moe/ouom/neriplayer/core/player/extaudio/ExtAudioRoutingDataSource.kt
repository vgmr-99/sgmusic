package moe.ouom.neriplayer.core.player.extaudio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import moe.ouom.neriplayer.common.logging.NPLogger
import java.io.IOException
import java.util.Locale

/**
 * 只在「本地文件 + 扩展格式」时切到 [ExtAudioPcmDataSource]，其余一律走原链路。
 *
 * 这是安全边界：在线音源（http/https 等）永远不会进入新增的 native 解码器。
 */
internal class ExtAudioRoutingDataSource(
    private val context: Context,
    private val upstreamFactory: DataSource.Factory,
) : DataSource {

    private var delegate: DataSource? = null
    private var transferListener: TransferListener? = null

    override fun addTransferListener(transferListener: TransferListener) {
        this.transferListener = transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        val useExt = ExtAudioNative.isAvailable() && isExtendedLocalAudio(dataSpec.uri)
        NPLogger.d(
            "ExtAudio",
            "route: useExt=$useExt, available=${ExtAudioNative.isAvailable()}, uri=${dataSpec.uri}"
        )
        val chosen: DataSource = if (useExt) {
            ExtAudioPcmDataSource(context)
        } else {
            upstreamFactory.createDataSource()
        }
        delegate = chosen
        transferListener?.let(chosen::addTransferListener)
        return try {
            chosen.open(dataSpec)
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: throw IOException("extaudio: not opened")

    override fun getUri(): Uri? = delegate?.getUri()

    override fun close() {
        delegate?.close()
        delegate = null
    }

    private fun isExtendedLocalAudio(uri: Uri): Boolean =
        shouldUseExtAudio(uri.scheme, displayName(uri))

    private fun displayName(uri: Uri): String? {
        uri.lastPathSegment?.takeIf { it.contains('.') }?.let { return it }
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }

}

internal val LOCAL_AUDIO_SCHEMES = setOf("file", "content")
internal val EXTENDED_AUDIO_EXTENSIONS = setOf("ape", "wma", "asf", "dsf", "dff", "aiff", "aif")

/** 纯函数，便于单测锁定「网络音源绝不进入扩展解码器」这条安全边界。 */
internal fun shouldUseExtAudio(scheme: String?, displayName: String?): Boolean {
    if (scheme != null && scheme.lowercase(Locale.ROOT) !in LOCAL_AUDIO_SCHEMES) return false
    val name = displayName?.lowercase(Locale.ROOT) ?: return false
    return name.substringAfterLast('.', "") in EXTENDED_AUDIO_EXTENSIONS
}

/** 包装既有工厂，仅替换 data source，不改 Media3 的 Extractor / Renderer 配置。 */
internal class ExtAudioRoutingDataSourceFactory(
    private val context: Context,
    private val upstream: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        ExtAudioRoutingDataSource(context, upstream)
}
