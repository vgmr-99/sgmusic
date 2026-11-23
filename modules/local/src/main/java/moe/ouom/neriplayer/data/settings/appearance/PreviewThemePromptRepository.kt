package moe.ouom.neriplayer.data.settings.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.dataStore

private val PREVIEW_THEME_PROMPT_ANSWERED = booleanPreferencesKey("preview_theme_prompt_answered")

class PreviewThemePromptRepository(
    private val dataStore: DataStore<Preferences>,
    private val onUiStyleChanged: (UiStyle) -> Unit = {}
) {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.dataStore,
        onUiStyleChanged = context.applicationContext.let { appContext ->
            { style -> persistThemeUiStyle(appContext, style) }
        }
    )

    /**
     * 拾光音乐内置默认简洁主题（悬浮底栏 + 可收紧播放栏），不再提供「预览版 vs 旧主题」
     * 的二选一弹窗，因此这里恒为 false。新安装的 ui_style 默认值由 AutoSettingsSchema
     * 的 DEFAULT_STORAGE_VALUE = "clean" 保证。
     *
     * 保留该 repository / flow 是为了不影响既有调用点与测试接口，answer() 也保留以便
     * 将来需要时恢复，但当前不会有任何界面触发它。
     */
    val shouldPromptFlow: Flow<Boolean> = flowOf(false)

    suspend fun answer(enable: Boolean) {
        dataStore.edit { preferences ->
            preferences[PREVIEW_THEME_PROMPT_ANSWERED] = true
            if (enable) preferences[SettingsKeys.UI_STYLE] = UiStyle.Clean.storageValue
        }
        if (enable) onUiStyleChanged(UiStyle.Clean)
    }
}
