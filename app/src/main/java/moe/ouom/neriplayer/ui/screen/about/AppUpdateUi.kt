package moe.ouom.neriplayer.ui.screen.about

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.BuildConfig
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.update.AppUpdateChecker
import moe.ouom.neriplayer.core.update.AppUpdateInfo
import moe.ouom.neriplayer.core.update.AppUpdateInstaller
import moe.ouom.neriplayer.core.update.AppUpdateNotifier
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.platform.tryStartActivity
import java.util.concurrent.atomic.AtomicBoolean

/** 检查更新流程的 UI 状态。 */
internal enum class AppUpdateUiState {
    /** 初始：未检查。 */
    Idle,

    /** 正在请求远端版本。 */
    Checking,

    /** 远端没有比当前更新的版本，或检查失败（用 [AppUpdateController.checkFailed] 区分）。 */
    UpToDate,

    /** 发现新版本，等待用户决定下载。 */
    Available,

    /** 更新包下载中。 */
    Downloading,

    /** 更新包就绪，可拉起安装。 */
    ReadyToInstall,

    /** 下载失败，可重试。 */
    DownloadFailed,
}

/**
 * 关于页「检查更新」的状态控制器。
 *
 * 职责：发起检查 → 展示结果 → 下载 → 拉起安装。
 * 下载用系统 DownloadManager（通知栏自带进度），完成后轮询状态转为可安装。
 */
@Stable
internal class AppUpdateController(
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(AppUpdateUiState.Idle)
        private set

    var info by mutableStateOf<AppUpdateInfo?>(null)
        private set

    /** 检查请求本身失败（网络等），区别于「已是最新」。 */
    var checkFailed by mutableStateOf(false)
        private set

    private var pollJob: Job? = null

    /** 发起一次版本检查（纯网络调用，不需要 context）。 */
    fun check() {
        if (state == AppUpdateUiState.Checking) return
        state = AppUpdateUiState.Checking
        checkFailed = false
        scope.launch(Dispatchers.IO) {
            val checker = AppUpdateChecker(AppContainer.sharedOkHttpClient)
            val result = checker.checkLatestRelease(BuildConfig.VERSION_NAME)
            withContext(Dispatchers.Main) {
                info = result
                state = if (result != null) {
                    AppUpdateUiState.Available
                } else {
                    checkFailed = true
                    AppUpdateUiState.UpToDate
                }
            }
        }
    }

    /** 开始下载更新包（DownloadManager 需要应用 context）。 */
    fun download(context: Context) {
        val target = info ?: return
        if (state == AppUpdateUiState.Downloading) return
        val id = AppUpdateInstaller.enqueueDownload(context, target)
        if (id < 0L) {
            state = AppUpdateUiState.DownloadFailed
            return
        }
        state = AppUpdateUiState.Downloading
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(2_000)
                when (AppUpdateInstaller.queryStatus(context)) {
                    null -> return@launch
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        state = AppUpdateUiState.ReadyToInstall
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        state = AppUpdateUiState.DownloadFailed
                        return@launch
                    }
                }
            }
        }
    }

    /** 点击「安装」：无权限先引导授权（返回 false），有权限直接拉起安装器。 */
    fun onInstallClick(context: Context): Boolean {
        if (!AppUpdateInstaller.canInstall(context)) {
            AppUpdateInstaller.requestInstallPermission(context)
            return false
        }
        return AppUpdateInstaller.installApk(context)
    }

    /** 关闭弹窗时回到初始态，保留 info 供下次打开展示。 */
    fun reset() {
        pollJob?.cancel()
        state = AppUpdateUiState.Idle
        checkFailed = false
    }
}

@Composable
internal fun rememberAppUpdateController(): AppUpdateController {
    val scope = rememberCoroutineScope()
    return remember(scope) { AppUpdateController(scope) }
}

/**
 * 关于页的检查更新弹窗。
 *
 * 一个弹窗承载全部状态：Checking / UpToDate / Available / Downloading /
 * ReadyToInstall / DownloadFailed，按 [controller.state] 切换内容与按钮。
 */
@Composable
internal fun AppUpdateDialog(
    controller: AppUpdateController,
    onDismiss: () -> Unit,
) {
    val state = controller.state
    val info = controller.info
    val appContext = LocalContext.current.applicationContext

    AlertDialog(
        onDismissRequest = {
            controller.reset()
            onDismiss()
        },
        title = {
            Text(
                when {
                    state == AppUpdateUiState.Checking ->
                        stringResource(R.string.about_update_checking)
                    state == AppUpdateUiState.ReadyToInstall ->
                        stringResource(R.string.about_update_ready_title)
                    state == AppUpdateUiState.Downloading ->
                        stringResource(R.string.about_update_downloading_title)
                    state == AppUpdateUiState.DownloadFailed ->
                        stringResource(R.string.about_update_download_failed_title)
                    info != null ->
                        stringResource(R.string.about_update_new_version_title, info.latestVersion)
                    else ->
                        stringResource(R.string.about_update_up_to_date_title)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    state == AppUpdateUiState.Checking ->
                        Text(stringResource(R.string.about_update_checking_message))

                    state == AppUpdateUiState.Downloading ->
                        Text(stringResource(R.string.about_update_downloading_message))

                    state == AppUpdateUiState.ReadyToInstall ->
                        Text(stringResource(R.string.about_update_ready_message))

                    state == AppUpdateUiState.DownloadFailed ->
                        Text(stringResource(R.string.about_update_download_failed_message))

                    info != null && state == AppUpdateUiState.Available -> {
                        Text(
                            stringResource(
                                R.string.about_update_current_version,
                                info.currentVersion
                            )
                        )
                        if (info.releaseNotes.isNotBlank()) {
                            Text(info.releaseNotes)
                        }
                    }

                    else -> Text(
                        if (controller.checkFailed) {
                            stringResource(R.string.about_update_check_failed_message)
                        } else {
                            stringResource(
                                R.string.about_update_up_to_date_message,
                                BuildConfig.VERSION_NAME
                            )
                        }
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                AppUpdateUiState.Available -> HapticTextButton(
                    onClick = {
                        if (info?.apkUrl != null) {
                            controller.download(appContext)
                        } else {
                            // Release 没挂 APK 资产，引导去网页下载
                            info?.releaseUrl?.let { url ->
                                appContext.tryStartActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                            }
                        }
                    }
                ) {
                    Text(
                        if (info?.apkUrl != null) {
                            stringResource(R.string.about_update_action_download)
                        } else {
                            stringResource(R.string.about_update_action_open_release)
                        }
                    )
                }
                AppUpdateUiState.ReadyToInstall -> HapticTextButton(
                    onClick = { controller.onInstallClick(appContext) }
                ) {
                    Text(stringResource(R.string.about_update_action_install))
                }
                AppUpdateUiState.DownloadFailed -> HapticTextButton(
                    onClick = { controller.download(appContext) }
                ) {
                    Text(stringResource(R.string.about_update_action_retry))
                }
                else -> Unit
            }
        },
        dismissButton = {
            HapticTextButton(onClick = {
                controller.reset()
                onDismiss()
            }) {
                Text(stringResource(R.string.about_update_action_close))
            }
        }
    )
}

/**
 * 启动静默检查的弹窗宿主：主界面顶层放一次即可。
 *
 * 进程内只检查一次：启动延迟数秒后请求远端版本，发现新版本时经
 * [AppUpdateNotifier] 弹窗提示（确认按钮跳 Release 网页，完整下载流程
 * 在关于页里做）；检查失败完全静默，不打扰使用。
 */
@Composable
fun AppUpdateAutoCheckHost() {
    val pending by AppUpdateNotifier.pendingUpdate.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val update = pending ?: return
    AlertDialog(
        onDismissRequest = { AppUpdateNotifier.dismiss() },
        title = {
            Text(stringResource(R.string.about_update_new_version_title, update.latestVersion))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(
                        R.string.about_update_current_version,
                        update.currentVersion
                    )
                )
                if (update.releaseNotes.isNotBlank()) {
                    Text(update.releaseNotes)
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = {
                context.tryStartActivity(
                    Intent(Intent.ACTION_VIEW, update.releaseUrl.toUri())
                )
                AppUpdateNotifier.dismiss()
            }) {
                Text(stringResource(R.string.about_update_action_view_release))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = { AppUpdateNotifier.dismiss() }) {
                Text(stringResource(R.string.about_update_action_dismiss))
            }
        }
    )
}
