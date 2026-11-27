package moe.ouom.neriplayer.data.model.settings.appearance

/**
 * 播放页进度条样式（独立于播放页模板，可单独选择）。
 *
 * 借鉴 Halcyon 的做法：模板（布局）与进度条样式解耦，用户可自由组合。
 * - WAVE：波形进度条，播放时有波浪动画（项目原有样式）
 * - SEGMENTED：分段刻度轨道（类似 Halcyon 的分段刻度）
 * - CAPSULE：粗胶囊轨道 + 胶囊拇指
 */
enum class NowPlayingProgressStyle(val storageValue: String) {
    WAVE("wave"),
    SEGMENTED("segmented"),
    CAPSULE("capsule");

    companion object {
        const val DEFAULT_STORAGE_VALUE = "wave"

        fun fromStorage(value: String?): NowPlayingProgressStyle =
            entries.firstOrNull { it.storageValue == value } ?: WAVE
    }
}
