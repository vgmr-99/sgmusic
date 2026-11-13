package moe.ouom.neriplayer.data.model.settings.appearance

/**
 * Whole-app visual language. [Material] keeps the Material You look,
 * [Clean] is the preview theme with neutral surfaces, continuous corners and glass chrome.
 */
enum class UiStyle(val storageValue: String) {
    Material("material"),
    Clean("clean");

    companion object {
        // 拾光音乐内置默认简洁界面风格，不再在首次启动或设置里提供风格切换入口
        const val DEFAULT_STORAGE_VALUE = "clean"

        fun fromStorage(value: String?): UiStyle =
            entries.firstOrNull { it.storageValue == value } ?: Material
    }
}
