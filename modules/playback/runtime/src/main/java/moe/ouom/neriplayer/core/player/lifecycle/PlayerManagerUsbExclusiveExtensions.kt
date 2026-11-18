package moe.ouom.neriplayer.core.player.lifecycle

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.settings.usb.UsbExclusivePreferences

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.player.lifecycle/PlayerManagerUsbExclusiveExtensions
 * Created: 2026/11/11
 *
 * USB 独占播放相关的转发入口。
 * 从 PlayerManagerLifecycleExtensions 拆出，避免主文件超出单文件行数上限。
 */

internal fun PlayerManager.handleUsbExclusivePlaybackSettingChanged(enabled: Boolean) {
    usbPlaybackRouteOwner.changeSetting(enabled)
}

internal fun PlayerManager.handleUsbExclusivePreferencesChanged(preferences: UsbExclusivePreferences) {
    usbPlaybackRouteOwner.changePreferences(preferences)
}

internal fun PlayerManager.retryUsbExclusivePlayback(reason: String) {
    usbPlaybackRouteOwner.retry(reason)
}

internal fun PlayerManager.scheduleUsbExclusiveTransportRecovery(reason: String) {
    usbPlaybackRouteOwner.scheduleTransportRecovery(reason)
}

internal fun PlayerManager.markUsbExclusiveNativePathActive(reason: String) {
    usbPlaybackRouteOwner.markNativePathActive(reason)
}

internal fun PlayerManager.tryRecoverUsbExclusivePlaybackAfterNativeTransferFailure(
    reason: String,
    runtimeReport: String
): Boolean = usbPlaybackRouteOwner.recoverAfterTransferFailure(reason, runtimeReport)

internal fun PlayerManager.stopPlaybackAfterUsbExclusiveNativeFailure(reason: String) {
    usbPlaybackRouteOwner.stopAfterNativeFailure(reason)
}

internal fun PlayerManager.prepareUsbExclusiveRouteForManualPlayback(reason: String): Boolean =
    usbPlaybackRouteOwner.prepareManualPlayback(reason)

fun PlayerManager.updateUsbExclusiveForegroundState(
    foreground: Boolean,
    reason: String
) {
    usbExclusiveLivenessOwner.updateForegroundState(foreground, reason)
}

internal fun PlayerManager.recoverUsbExclusivePlaybackIfUnhealthy(
    reason: String,
    forceRecovery: Boolean = false
): Boolean = usbPlaybackRouteOwner.recoverIfUnhealthy(reason, forceRecovery)

fun PlayerManager.recoverUsbExclusivePlaybackOnForeground(reason: String) {
    usbExclusiveLivenessOwner.recoverOnForeground(reason)
}

internal fun PlayerManager.cancelUsbExclusiveRecovery(reason: String) {
    usbExclusiveLivenessOwner.cancelJobs()
    NPLogger.d("NERI-UsbExclusive", "cancelUsbExclusiveRecovery(): reason=$reason")
}

internal fun PlayerManager.resumeInterruptedUsbExclusivePlaybackIfNeeded(reason: String): Boolean =
    usbInterruptedPlaybackOwner.resumeOnSystemRouteIfNeeded(reason)

internal fun PlayerManager.scheduleUsbExclusivePlaybackResumeAfterDeviceAttach(reason: String) {
    usbInterruptedPlaybackOwner.scheduleResumeAfterDeviceAttach(reason)
}

internal fun PlayerManager.clearUsbExclusiveInterruptedPlaybackIntent(reason: String) {
    usbInterruptedPlaybackOwner.clear(reason)
}

internal fun PlayerManager.applyUsbExclusivePlaybackPolicy(
    reconfigureAudioSink: Boolean = false,
    reconfigureReason: String = "usb_policy_changed",
    allowReconfigureWhilePlaying: Boolean = false
) {
    usbPlaybackRouteOwner.applyPolicy(reconfigureAudioSink, reconfigureReason, allowReconfigureWhilePlaying)
}

internal fun PlayerManager.scheduleUsbAudioSinkReconfiguration(
    reason: String,
    allowWhilePlaybackActive: Boolean = false,
    bypassCooldown: Boolean = false
) {
    usbSinkRouteOwner.schedule(reason, allowWhilePlaybackActive, bypassCooldown)
}
