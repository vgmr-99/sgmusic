package moe.ouom.neriplayer.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 一次版本检查的结果。
 *
 * @property latestVersion 远端最新版本号（不含 v 前缀，如 "1.4.4"）
 * @property currentVersion 当前安装的版本号
 * @property releaseNotes Release 说明原文（Markdown 纯文本展示）
 * @property apkUrl APK 下载直链（Release 资产里的 Timbre-*.apk）
 * @property releaseUrl Release 网页地址（无 APK 资产时的兜底入口）
 */
data class AppUpdateInfo(
    val latestVersion: String,
    val currentVersion: String,
    val releaseNotes: String,
    val apkUrl: String?,
    val releaseUrl: String,
)

/**
 * 版本检测的纯逻辑，保持无 Android 依赖以便单测。
 */
internal object AppUpdateLogic {

    /** 本应用的 GitHub 仓库（owner/repo），Release 发布与更新检测共用。 */
    const val GITHUB_REPO = "vgmr-99/sgmusic"

    /** Release 资产里 APK 的文件名前缀。 */
    const val APK_ASSET_PREFIX = "Timbre-"

    /**
     * 把 "1.4.10" / "v1.4.10" 解析成数字段；解析失败返回 null。
     * 只取数字段，忽略 "1.4.4-beta" 这类后缀（本项目暂无预发布）。
     */
    internal fun parseSemanticVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        val core = cleaned.substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        val parts = core.split('.')
        if (parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } }) return null
        return parts.map { it.toInt() }
    }

    /**
     * 语义化比较：latest 是否严格大于 current。
     * 按数字段比较、短的一方补 0，保证 1.4.10 > 1.4.9、1.5 > 1.4.9。
     * 任一版本解析失败时返回 false（宁可不提示，也不误报）。
     */
    internal fun isVersionNewer(latest: String, current: String): Boolean {
        val a = parseSemanticVersion(latest) ?: return false
        val b = parseSemanticVersion(current) ?: return false
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * 解析 GitHub `/releases/latest` 响应体为 [AppUpdateInfo]。
     * APK 资产按 [APK_ASSET_PREFIX] 前缀匹配；没有匹配资产时 apkUrl 为 null，
     * 上层引导用户走 releaseUrl 网页下载。
     */
    internal fun parseGitHubRelease(
        jsonBody: String,
        currentVersion: String,
    ): AppUpdateInfo? {
        return runCatching {
            val root = Json.parseToJsonElement(jsonBody).jsonObject
            val tagName = root["tag_name"]?.jsonPrimitive?.content ?: return null
            if (root["draft"]?.jsonPrimitive?.content == "true") return null
            val notes = root["body"]?.jsonPrimitive?.content.orEmpty()
            val htmlUrl = root["html_url"]?.jsonPrimitive?.content
                ?: "https://github.com/$GITHUB_REPO/releases"
            val apkUrl = root["assets"]
                ?.jsonArray
                ?.asSequence()
                ?.map { it.jsonObject }
                ?.filter { !it["name"]?.jsonPrimitive?.content.isNullOrBlank() }
                ?.firstOrNull { (it["name"]!!.jsonPrimitive.content).startsWith(APK_ASSET_PREFIX) }
                ?.get("browser_download_url")
                ?.jsonPrimitive?.content
            AppUpdateInfo(
                latestVersion = tagName,
                currentVersion = currentVersion,
                releaseNotes = notes,
                apkUrl = apkUrl,
                releaseUrl = htmlUrl,
            )
        }.getOrNull()
    }
}

/**
 * 启动静默检查与关于页手动检查共用的结果总线。
 *
 * 启动检查成功发现新版本时写入 [pendingUpdate]，由 MainActivity 顶层的
 * 弹窗宿主收集并展示；用户关掉弹窗后置回 null。手动检查不经过这个总线，
 * 直接在关于页就地弹窗。
 */
object AppUpdateNotifier {
    private val _pendingUpdate = MutableStateFlow<AppUpdateInfo?>(null)
    val pendingUpdate: StateFlow<AppUpdateInfo?> = _pendingUpdate

    fun publish(info: AppUpdateInfo) {
        _pendingUpdate.value = info
    }

    fun dismiss() {
        _pendingUpdate.value = null
    }
}

/**
 * 版本检查器：请求 GitHub Releases latest 接口并解析。
 *
 * 设计取向：
 * - 网络失败/解析失败一律返回 null，调用方静默跳过——更新检查绝不能打扰使用；
 * - 超时远短于播放链路：用户等弹窗的耐心只有几秒；
 * - 复用 [AppContainer.sharedOkHttpClient]（自带代理支持）时由调用方传入。
 */
class AppUpdateChecker(
    private val client: OkHttpClient,
    /** 可选镜像前缀（如 ghproxy 类），拼在 API 与 APK 直链前，空串表示直连。 */
    private val mirrorPrefix: String = "",
) {

    /** 检查用轻量客户端：在共享客户端连接池基础上缩短超时。 */
    private val checkClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    suspend fun checkLatestRelease(currentVersion: String): AppUpdateInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${mirrorPrefix}https://api.github.com/repos/${AppUpdateLogic.GITHUB_REPO}/releases/latest"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                checkClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val body = response.body?.string() ?: return@runCatching null
                    val info = AppUpdateLogic.parseGitHubRelease(body, currentVersion)
                        ?: return@runCatching null
                    // 把镜像前缀同步拼到 APK 直链上（下载走同一通道）
                    if (info.apkUrl != null && mirrorPrefix.isNotEmpty()) {
                        info.copy(apkUrl = mirrorPrefix + info.apkUrl)
                    } else {
                        info
                    }
                }
            }.getOrNull()?.takeIf {
                AppUpdateLogic.isVersionNewer(it.latestVersion, currentVersion)
            }
        }
}
