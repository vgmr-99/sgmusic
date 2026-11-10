package moe.ouom.neriplayer.ui.settings.route

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.model.settings.appearance.ThemeMode
import moe.ouom.neriplayer.data.ltw.ListenTogetherSessionManager
import moe.ouom.neriplayer.api.ltw.http.ListenTogetherApi
import moe.ouom.neriplayer.ui.settings.owner.AppUsbExclusiveSettingsActions
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.navigation.Destinations

internal fun isAppSettingsVisible(selectedMainTabRoute: String, playbackOpen: Boolean): Boolean =
    selectedMainTabRoute == Destinations.Settings.route && !playbackOpen

internal class AppSettingsHostEnvironment(
    val isDarkTheme: Boolean,
    val themeMode: ThemeMode,
    val onThemeToggleRequest: (Offset, Float) -> Unit,
    val onThemeModeRequest: (ThemeMode, Offset, Float) -> Unit,
    val backgroundImageAlpha: Float,
    val defaultStartDestination: String,
    val homeHasRecentUsage: Boolean,
    val onBeforeLanguageRestart: () -> Unit,
    val onLanguageChanged: (LanguageManager.Language) -> Unit,
    val coherentFeedbackEnabled: Boolean,
    val settingsVisible: Boolean = true,
    /**
     * 拾光音乐：外部（如“用拾光音乐打开 .js”导入成功后）要求设置页跳到指定子页。
     * 由 NeriApp 持有，设置页消费后自行清空，避免重复跳转。
     */
    val pendingSettingsPage: MutableStateFlow<moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage?> =
        MutableStateFlow(null)
)

internal class AppSettingsHostBindings(
    val repository: SettingsRepository,
    val listenTogether: AppSettingsListenTogetherDependencies,
    val state: AppSettingsRouteState,
    val appearanceActions: AppAppearanceSettingsActions,
    val lyricsActions: AppLyricSettingsActions,
    val qualityActions: AppAudioQualitySettingsActions,
    val playbackActions: AppPlaybackSettingsActions,
    val usbActions: AppUsbExclusiveSettingsActions,
    val homeActions: AppHomeSettingsActions,
    val storageActions: AppStorageSettingsActions,
    val environment: AppSettingsHostEnvironment
)

internal class AppSettingsListenTogetherDependencies(
    val preferences: ListenTogetherPreferences,
    val api: ListenTogetherApi,
    val sessionManager: ListenTogetherSessionManager
)
