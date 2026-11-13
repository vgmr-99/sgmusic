package moe.ouom.neriplayer.data.model.settings.appearance

/**
 * 播放页视觉样式。
 *
 * Classic 传统布局：封面在上、控制条在下，跟随应用主题着色。
 * Expressive 强调色铺底的大色块风格，封面更大、对比更强。
 * AppleMusic 沉浸风格：深色渐变背景 + 浮动封面 + 逐字歌词占据主视觉。
 */
enum class NowPlayingStyle(val storageValue: String) {
    Classic("classic"),
    Expressive("expressive"),
    AppleMusic("apple_music");

    companion object {
        const val DEFAULT_STORAGE_VALUE = "classic"

        fun fromStorage(value: String?): NowPlayingStyle =
            entries.firstOrNull { it.storageValue == value } ?: Classic
    }
}
