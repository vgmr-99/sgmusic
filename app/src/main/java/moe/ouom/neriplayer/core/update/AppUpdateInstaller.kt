package moe.ouom.neriplayer.core.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import moe.ouom.neriplayer.common.logging.NPLogger
import java.io.File

/**
 * APK 下载与安装。
 *
 * 下载走系统 [DownloadManager]：自带通知栏进度、断点续传、失败重试，
 * 不需要自建前台服务。安装通过 FileProvider 授予读权限后拉起系统安装器，
 * 系统会自动校验签名一致才允许覆盖安装。
 */
object AppUpdateInstaller {

    private const val TAG = "AppUpdate"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val UPDATE_DIR = "update_apk"

    /** 进程内记录的下载任务 id；-1 表示没有进行中/已完成的下载。 */
    @Volatile
    var lastDownloadId: Long = -1L
        private set

    /**
     * 更新包落地目录：Android/data/<pkg>/files/Download/update_apk。
     * 与 DownloadManager 的 setDestinationInExternalFilesDir 保持同一路径，
     * file_paths.xml 里的 external-files-path 条目按此配置。
     */
    fun apkDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, Environment.DIRECTORY_DOWNLOADS)
        return File(base, UPDATE_DIR).apply { mkdirs() }
    }

    /** 预期的本地 APK 路径（按版本命名，便于区分残留的旧包）。 */
    fun expectedApkFile(context: Context, version: String): File =
        File(apkDir(context), "Timbre-$version.apk")

    /**
     * 用系统 DownloadManager 开始下载更新包。
     * @return downloadId，失败返回 -1（调用方提示改走网页下载）。
     */
    fun enqueueDownload(context: Context, info: AppUpdateInfo): Long {
        val url = info.apkUrl ?: return -1L
        return runCatching {
            val target = expectedApkFile(context, info.latestVersion)
            if (target.exists()) target.delete() // 覆盖旧包，避免 DownloadManager 拒绝重名目标
            val request = DownloadManager.Request(url.toUri()).apply {
                setTitle("Timbre ${info.latestVersion}")
                setDescription("正在下载更新包")
                setMimeType(APK_MIME)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // 应用卸载后不残留下载目录
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "$UPDATE_DIR/Timbre-${info.latestVersion}.apk")
                setAllowedOverMetered(true)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            lastDownloadId = dm.enqueue(request)
            lastDownloadId
        }.onFailure {
            NPLogger.w(TAG, "enqueue update download failed: ${it.message}")
        }.getOrDefault(-1L)
    }

    /**
     * 查询记录中的下载任务状态。
     * @return null 表示没有任务或查询失败；STATUS_* 常量见 [DownloadManager]。
     */
    fun queryStatus(context: Context): Int? {
        val id = lastDownloadId
        if (id < 0) return null
        return runCatching {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return null
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        }.getOrNull()
    }

    /** 拿已完成的下载文件位置（DownloadManager 实际落盘处）。 */
    fun downloadedUri(context: Context): Uri? {
        val id = lastDownloadId
        if (id < 0) return null
        return runCatching {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.getUriForDownloadedFile(id)
        }.getOrNull()
    }

    /** 是否已具备「安装未知应用」授权（Android 8.0+ 才需要）。 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 跳到系统的「允许安装未知应用」设置页。 */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri()
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 拉起系统安装器。调用前应先用 [canInstall] 确认权限。
     * 优先用 [downloadedUri]（DownloadManager 的 content URI，自带读权限），
     * 传 null 时退回本地文件 + FileProvider。
     * @return true 表示已发起安装意图。
     */
    fun installApk(context: Context, apk: File? = null): Boolean {
        return runCatching {
            val uri = downloadedUri(context)
                ?: FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apk ?: error("no downloaded file available")
                )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.onFailure {
            NPLogger.w(TAG, "launch installer failed: ${it.message}")
        }.getOrDefault(false)
    }

    /** 清掉本应用更新目录里的残留安装包（保留空间，不影响 DownloadManager 的任务）。 */
    fun cleanStaleApks(context: Context, keepVersion: String? = null) {
        runCatching {
            apkDir(context).listFiles()?.forEach { file ->
                if (keepVersion == null || file.name != "Timbre-$keepVersion.apk") {
                    file.delete()
                }
            }
        }
    }
}
