package moe.ouom.neriplayer.core.player

/**
 * 歌词相关的运行时开关。
 *
 * 单独成文件，避免 PlayerManager 继续膨胀。
 */
internal object PlayerLyricsPreferences {
    /**
     * 跨歌词源合并：逐字歌词优先，其余行用其它源补齐。
     *
     * 默认关闭以保持原有单源行为不变。
     */
    @Volatile
    var mergeLyricsAcrossSources: Boolean = false
}
