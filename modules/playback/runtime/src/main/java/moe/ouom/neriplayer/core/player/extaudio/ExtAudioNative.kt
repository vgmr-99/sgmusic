package moe.ouom.neriplayer.core.player.extaudio

/**
 * 本地稀有格式解码桥（APE / WMA / DSD / AIFF）。
 *
 * 安全边界：
 * - 对应 native 库只编入了 file 协议，无法打开网络 URL；
 * - [open] 传入的必须是本机可读文件路径（或 /proc/self/fd/N）；
 * - 所有返回值都由 native 侧做句柄魔数与长度校验，失败返回 0 / false / -2。
 *
 * 加载失败时 [isAvailable] 为 false，播放器会原样走既有 Media3 链路。
 */
internal object ExtAudioNative {
    private val available: Boolean = runCatching {
        System.loadLibrary("extaudio")
        true
    }.getOrElse { false }

    fun isAvailable(): Boolean = available

    /** 返回 0 表示打开失败；成功返回句柄，必须由 [close] 释放。 */
    external fun open(path: String): Long

    /**
     * 从文件描述符打开（content:// / SAF 场景）。native 侧会 dup 一份 fd，
     * 因此调用方在 open 返回后可以立即关闭自己的 ParcelFileDescriptor。
     * 返回 0 表示打开失败。
     */
    external fun openFd(fd: Int): Long

    external fun sampleRate(handle: Long): Int
    external fun channelCount(handle: Long): Int
    external fun durationUs(handle: Long): Long

    /** 返回写入字节数；-1 表示 EOF；-2 表示错误。 */
    external fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** positionUs 为微秒；成功返回 true。 */
    external fun seek(handle: Long, positionUs: Long): Boolean

    external fun close(handle: Long)
}
