package moe.ouom.neriplayer.data.model.settings.appearance

/**
 * 播放页底部功能键（队列 / 定时 / 音量 / 歌词 / 歌单）的容器样式。
 *
 * 与播放键是两种独立控件，因此拆成两个设置项，用户可以分别挑选。
 *
 * - DOCK：整块圆角卡片托底（项目原有观感）
 * - LABEL：图标加文字标签
 * - GLASS：半透明玻璃胶囊，激活项圆形高亮（iOS 26 / Apple Music 观感）
 */
enum class NowPlayingActionBarStyle(val storageValue: String) {
    DOCK("dock"),
    LABEL("label"),
    GLASS("glass");

    companion object {
        const val DEFAULT_STORAGE_VALUE = "dock"

        fun fromStorage(value: String?): NowPlayingActionBarStyle =
            entries.firstOrNull { it.storageValue == value } ?: DOCK
    }
}
