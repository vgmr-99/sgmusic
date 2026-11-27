package moe.ouom.neriplayer.data.model.settings.appearance

/**
 * 播放页播放按键控件样式（独立于播放页模板，可单独选择）。
 *
 * 与 [NowPlayingProgressStyle] 一样借鉴 Halcyon 的做法：模板（布局）与控件样式解耦，
 * 用户可以自由组合「三套模板 × 三类控件样式」。
 *
 * - CLASSIC：描边图标 + 实心圆形播放键（项目原有观感）
 * - RING：播放键为空心圆环，次级键保持纯图标
 * - FLAT：去掉所有底色，纯图标形态
 */
enum class NowPlayingButtonStyle(val storageValue: String) {
    CLASSIC("classic"),
    RING("ring"),
    FLAT("flat");

    companion object {
        const val DEFAULT_STORAGE_VALUE = "classic"

        fun fromStorage(value: String?): NowPlayingButtonStyle =
            entries.firstOrNull { it.storageValue == value } ?: CLASSIC
    }
}
