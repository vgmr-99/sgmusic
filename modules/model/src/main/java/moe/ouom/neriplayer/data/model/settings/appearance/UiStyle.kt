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

        fun fromStorage(value: String?): UiStyle {
            if (value == null) {
                // 拾光音乐默认简洁主题：无存储值时按默认 Clean（悬浮底栏 + 可收紧播放栏）
                return entries.firstOrNull { it.storageValue == DEFAULT_STORAGE_VALUE } ?: Material
            }
            // 未知的非空值保守回退 Material，避免把损坏数据误判成 Clean
            return entries.firstOrNull { it.storageValue == value } ?: Material
        }
    }
}
