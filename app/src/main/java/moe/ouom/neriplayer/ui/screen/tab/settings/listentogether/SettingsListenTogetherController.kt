package moe.ouom.neriplayer.ui.screen.tab.settings.listentogether

import moe.ouom.neriplayer.data.ltw.validation.format

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.data.ltw.ListenTogetherSessionManager
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherInvite
import moe.ouom.neriplayer.data.ltw.invite.parseListenTogetherInvite
import moe.ouom.neriplayer.data.ltw.invite.resolveListenTogetherInviteJoinBaseUrl
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import moe.ouom.neriplayer.data.ltw.validation.validateListenTogetherNickname

internal fun validListenTogetherClipboardInvite(text: String?): String =
    text?.takeIf { parseListenTogetherInvite(it) != null }.orEmpty()

internal fun readSettingsListenTogetherClipboardText(context: Context): String? = runCatching {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?: return@runCatching null
    if (clip.itemCount <= 0) return@runCatching null
    clip.getItemAt(0).coerceToText(context).toString()
}.getOrNull()

@Composable
internal fun rememberSettingsListenTogetherController(
    preferences: ListenTogetherPreferences,
    sessionManager: ListenTogetherSessionManager,
    onMessage: (String) -> Unit
): SettingsListenTogetherController {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val observed = collectSettingsListenTogetherValues(preferences, sessionManager)
    val drafts = rememberSettingsListenTogetherDrafts()
    val currentOnMessage = rememberUpdatedState(onMessage)
    val dependencies = listOf(context, resources, preferences, sessionManager)
    val controller = remember(dependencies) {
        SettingsListenTogetherController(
            context = context,
            resources = resources,
            scope = scope,
            preferences = preferences,
            sessionManager = sessionManager,
            sessionState = observed.session,
            nickname = observed.nickname,
            nicknameInputState = drafts.nicknameInput,
            onMessage = { currentOnMessage.value(it) }
        )
    }
    SyncSettingsListenTogetherDrafts(controller, observed)
    return controller
}

private class SettingsListenTogetherObservedValues(
    val session: State<ListenTogetherSessionState>,
    val nickname: State<String>
)

@Composable
private fun collectSettingsListenTogetherValues(
    preferences: ListenTogetherPreferences,
    sessionManager: ListenTogetherSessionManager
): SettingsListenTogetherObservedValues = SettingsListenTogetherObservedValues(
    session = sessionManager.sessionState.collectAsState(),
    nickname = preferences.nicknameFlow.collectAsState(initial = "")
)

private class SettingsListenTogetherDrafts(
    val nicknameInput: MutableState<String>
)

@Composable
private fun rememberSettingsListenTogetherDrafts(): SettingsListenTogetherDrafts =
    SettingsListenTogetherDrafts(
        nicknameInput = rememberSaveable { mutableStateOf("") }
    )

@Composable
private fun SyncSettingsListenTogetherDrafts(
    controller: SettingsListenTogetherController,
    observed: SettingsListenTogetherObservedValues
) {
    LaunchedEffect(controller, observed.nickname.value, controller.showNicknameDialog) {
        controller.syncNicknameInput()
    }
}

internal class SettingsListenTogetherController(
    private val context: Context,
    private val resources: Resources,
    private val scope: CoroutineScope,
    private val preferences: ListenTogetherPreferences,
    private val sessionManager: ListenTogetherSessionManager,
    private val sessionState: State<ListenTogetherSessionState>,
    private val nickname: State<String>,
    nicknameInputState: MutableState<String>,
    private val onMessage: (String) -> Unit
) {
    var showResetUuidDialog by mutableStateOf(false)
        private set
    var showNicknameDialog by mutableStateOf(false)
        private set
    var showJoinDialog by mutableStateOf(false)
        private set
    var nicknameInput by nicknameInputState
        private set
    var nicknameError by mutableStateOf<String?>(null)
        private set
    var inviteInput by mutableStateOf("")
        private set
    var inviteError by mutableStateOf<String?>(null)
        private set
    var joining by mutableStateOf(false)
        private set

    val openJoinAction: () -> Unit = ::openJoinDialog
    val openNicknameAction: () -> Unit = ::openNicknameDialog
    val openResetUuidAction: () -> Unit = ::openResetUuidDialog
    val dismissJoinAction: () -> Unit = ::dismissJoinDialog
    val dismissNicknameAction: () -> Unit = ::dismissNicknameDialog
    val dismissResetUuidAction: () -> Unit = ::dismissResetUuidDialog
    val updateInviteAction: (String) -> Unit = ::updateInviteInput
    val updateNicknameAction: (String) -> Unit = ::updateNicknameInput

    val isInRoom: Boolean get() = !sessionState.value.roomId.isNullOrBlank()
    val currentNickname: String get() = nickname.value

    fun syncNicknameInput() {
        if (!showNicknameDialog) {
            nicknameInput = nickname.value
            nicknameError = null
        }
    }

    fun openJoinDialog() {
        if (isInRoom) return
        inviteInput = validListenTogetherClipboardInvite(readSettingsListenTogetherClipboardText(context))
        inviteError = null
        showJoinDialog = true
    }

    fun updateInviteInput(value: String) {
        inviteInput = value
        inviteError = null
    }

    fun dismissJoinDialog() {
        if (joining) return
        showJoinDialog = false
        inviteInput = ""
        inviteError = null
    }

    fun confirmJoin() {
        if (joining) return
        val invite = parseListenTogetherInvite(inviteInput)
        if (invite == null) {
            inviteError = resources.getString(CoreCommonR.string.settings_listen_together_join_invite_invalid)
            return
        }
        if (isInRoom) {
            inviteError = resources.getString(CoreCommonR.string.settings_listen_together_join_room_disabled)
            return
        }
        scope.launch { joinRoom(invite) }
    }

    private suspend fun joinRoom(invite: ListenTogetherInvite) {
        joining = true
        inviteError = null
        try {
            val joinBaseUrl = resolveListenTogetherInviteJoinBaseUrl(invite = invite)
            sessionManager.joinRoom(
                baseUrl = joinBaseUrl,
                roomId = invite.roomId,
                userUuid = preferences.getOrCreateUserUuid(),
                nickname = preferences.getOrCreateNickname(),
                joinSecret = invite.joinSecret
            )
            sessionManager.connectWebSocket()
            showJoinDialog = false
            inviteInput = ""
        } catch (error: Exception) {
            inviteError = error.message ?: error.javaClass.simpleName
        } finally {
            joining = false
        }
    }

    fun openNicknameDialog() {
        if (isInRoom) return
        nicknameInput = nickname.value
        nicknameError = null
        showNicknameDialog = true
    }

    fun updateNicknameInput(value: String) {
        nicknameInput = value.take(24)
        nicknameError = validateListenTogetherNickname(nicknameInput)?.format(context)
    }

    fun dismissNicknameDialog() {
        showNicknameDialog = false
        nicknameInput = nickname.value
        nicknameError = null
    }

    fun applyNickname() {
        val value = nicknameInput.trim()
        val validationError = validateListenTogetherNickname(value)
        if (validationError != null) {
            nicknameError = validationError.format(context)
            return
        }
        scope.launch {
            preferences.setNickname(value)
            showNicknameDialog = false
            nicknameError = null
            showMessage(CoreCommonR.string.settings_listen_together_default_nickname_saved)
        }
    }

    fun openResetUuidDialog() {
        if (!isInRoom) showResetUuidDialog = true
    }

    fun dismissResetUuidDialog() {
        showResetUuidDialog = false
    }

    fun resetUuid() {
        scope.launch {
            preferences.resetUserUuid()
            showResetUuidDialog = false
            showMessage(CoreCommonR.string.listen_together_reset_uuid_done)
        }
    }

    private fun showMessage(messageId: Int) {
        onMessage(resources.getString(messageId))
    }
}
