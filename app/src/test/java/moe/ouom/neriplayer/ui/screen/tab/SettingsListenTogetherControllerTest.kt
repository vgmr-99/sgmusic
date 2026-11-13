package moe.ouom.neriplayer.ui.screen.tab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.data.ltw.ListenTogetherSessionManager
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.SettingsListenTogetherController
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.listenTogetherIdentityDescriptionId
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.listenTogetherJoinButtonLabelId
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.listenTogetherJoinDescriptionId
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.listenTogetherNicknameDescription
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.readSettingsListenTogetherClipboardText
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.validListenTogetherClipboardInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SettingsListenTogetherControllerTest {
    @Test
    fun `join prefill only accepts a complete invitation`() {
        val invite = "neriplayer://listen-together/join?roomId=GTV42X&secret=secret-value"
        assertEquals(invite, validListenTogetherClipboardInvite(invite))
        assertEquals("", validListenTogetherClipboardInvite("roomId=GTV42X"))
        assertEquals("", validListenTogetherClipboardInvite(null))
    }

    @Test
    fun `clipboard reader tolerates unavailable and empty clips`() {
        val context = mock(Context::class.java)
        assertEquals(null, readSettingsListenTogetherClipboardText(context))

        val clipboard = mock(ClipboardManager::class.java)
        val clip = mock(ClipData::class.java)
        `when`(context.getSystemService(ClipboardManager::class.java)).thenReturn(clipboard)
        `when`(clipboard.primaryClip).thenReturn(clip)
        `when`(clip.itemCount).thenReturn(0)
        assertEquals(null, readSettingsListenTogetherClipboardText(context))
    }

    @Test
    fun `clipboard reader extracts the first text item and handles provider failure`() {
        val context = mock(Context::class.java)
        val clipboard = mock(ClipboardManager::class.java)
        val clip = mock(ClipData::class.java)
        val item = mock(ClipData.Item::class.java)
        `when`(context.getSystemService(ClipboardManager::class.java)).thenReturn(clipboard)
        `when`(clipboard.primaryClip).thenReturn(clip)
        `when`(clip.itemCount).thenReturn(1)
        `when`(clip.getItemAt(0)).thenReturn(item)
        `when`(item.coerceToText(context)).thenReturn("invite text")
        assertEquals("invite text", readSettingsListenTogetherClipboardText(context))

        `when`(clipboard.primaryClip).thenThrow(SecurityException("clipboard denied"))
        assertEquals(null, readSettingsListenTogetherClipboardText(context))
    }

    @Test
    fun `room rows display the active restriction`() {
        assertEquals(
            CoreCommonR.string.settings_listen_together_join_room_disabled,
            listenTogetherJoinDescriptionId(isInRoom = true)
        )
        assertEquals(
            CoreCommonR.string.settings_listen_together_join_room_desc,
            listenTogetherJoinDescriptionId(isInRoom = false)
        )
        assertEquals(
            CoreCommonR.string.listen_together_reset_uuid_disabled,
            listenTogetherIdentityDescriptionId(isInRoom = true)
        )
        assertEquals(
            CoreCommonR.string.settings_listen_together_reset_identity_desc,
            listenTogetherIdentityDescriptionId(isInRoom = false)
        )
        assertEquals(CoreCommonR.string.listen_together_joining_room,
            listenTogetherJoinButtonLabelId(joining = true)
        )
        assertEquals(CoreCommonR.string.listen_together_join_room,
            listenTogetherJoinButtonLabelId(joining = false)
        )
    }

    @Test
    fun `nickname description uses room restriction then current or fallback value`() {
        assertEquals("room locked",
            listenTogetherNicknameDescription(true, "Neri", "room locked", "unset")
        )
        assertEquals("Neri",
            listenTogetherNicknameDescription(false, "Neri", "room locked", "unset")
        )
        assertEquals("unset", listenTogetherNicknameDescription(false, "", "room locked", "unset"))
    }

    @Test
    fun `join dialog rejects invalid invite and a room already in progress`() {
        val fixture = controllerFixture()
        `when`(fixture.resources.getString(CoreCommonR.string.settings_listen_together_join_invite_invalid))
            .thenReturn("invalid invite")
        `when`(fixture.resources.getString(CoreCommonR.string.settings_listen_together_join_room_disabled))
            .thenReturn("already in room")

        fixture.controller.openJoinDialog()
        assertTrue(fixture.controller.showJoinDialog)
        assertEquals("", fixture.controller.inviteInput)
        fixture.controller.confirmJoin()
        assertEquals("invalid invite", fixture.controller.inviteError)

        fixture.controller.updateInviteInput(
            "neriplayer://listen-together/join?roomId=GTV42X&secret=secret-value"
        )
        fixture.session.value = ListenTogetherSessionState(roomId = "OTHER1")
        fixture.controller.confirmJoin()
        assertEquals("already in room", fixture.controller.inviteError)
        fixture.controller.dismissJoinDialog()
        assertFalse(fixture.controller.showJoinDialog)
        assertEquals("", fixture.controller.inviteInput)
    }

    @Test
    fun `join entry remains closed while a room is active`() {
        val fixture = controllerFixture()
        fixture.session.value = ListenTogetherSessionState(roomId = "OTHER1")

        fixture.controller.openJoinDialog()

        assertFalse(fixture.controller.showJoinDialog)
    }

    @Test
    fun `nickname edits stay intact while dialog is open and resync after dismiss`() {
        val fixture = controllerFixture()
        fixture.nickname.value = "savedName"
        fixture.controller.syncNicknameInput()
        assertEquals("savedName", fixture.controller.nicknameInput)

        fixture.controller.openNicknameDialog()
        fixture.controller.updateNicknameInput("editedName")
        fixture.nickname.value = "changedElsewhere"
        fixture.controller.syncNicknameInput()
        assertEquals("editedName", fixture.controller.nicknameInput)

        fixture.controller.dismissNicknameDialog()
        assertEquals("changedElsewhere", fixture.controller.nicknameInput)
    }

    private fun controllerFixture(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    ): ControllerFixture {
        val context = mock(Context::class.java)
        val resources = mock(Resources::class.java)
        val session = mutableStateOf(ListenTogetherSessionState())
        val nickname = mutableStateOf("")
        val controller = SettingsListenTogetherController(
            context = context,
            resources = resources,
            scope = scope,
            preferences = mock(ListenTogetherPreferences::class.java),
            sessionManager = mock(ListenTogetherSessionManager::class.java),
            sessionState = session,
            nickname = nickname,
            nicknameInputState = mutableStateOf(""),
            onMessage = {}
        )
        return ControllerFixture(resources, session, nickname, controller)
    }

    private data class ControllerFixture(
        val resources: Resources,
        val session: androidx.compose.runtime.MutableState<ListenTogetherSessionState>,
        val nickname: androidx.compose.runtime.MutableState<String>,
        val controller: SettingsListenTogetherController
    )
}
