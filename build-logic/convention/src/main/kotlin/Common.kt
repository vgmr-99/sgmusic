import org.gradle.api.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Common {
    fun getBuildVersionCode(buildTimestamp: String? = null): Int {
        val buildDate = buildTimestamp?.let { value ->
            val timestamp = value.toLongOrNull()?.takeIf { it > 0L }
                ?: throw IllegalArgumentException("buildTimestamp must be positive epoch milliseconds")
            Date(timestamp)
        } ?: Date()
        val appVerCode: Int by lazy {
            val versionCode = SimpleDateFormat("yyMMddHH", Locale.ENGLISH).format(buildDate)
            versionCode.toInt()
        }
        return appVerCode
    }

    private fun getCurrentDate(project: Project): String {
        val override = project.findProperty("buildVersionTimestamp") as String?
        if (!override.isNullOrBlank()) {
            return override
        }

        val sdf = SimpleDateFormat("MMddHHmm", Locale.ENGLISH)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Taipei")
        return sdf.format(Date())
    }


    private fun getShortGitRevision(): String {
        val command = "git rev-parse --short HEAD"
        // 没有安装 git 时不能中断构建, 版本号退回 no_commit
        val process = runCatching {
            val processBuilder = ProcessBuilder(*command.split(" ").toTypedArray())
            processBuilder.start()
        }.getOrNull() ?: return "no_commit"

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        return if (exitCode == 0) {
            output.trim()
        } else {
            "no_commit"
        }
    }

    fun getBuildVersionName(project: Project): String {
        return "${getShortGitRevision()}.${getCurrentDate(project)}"
    }

    /**
     * 对外展示的语义化版本号，来自 gradle.properties 的 appVersion（默认 1.4.0）。
     * 规则：大更新改第二位数，小更新改第三位数。
     */
    fun getAppVersion(project: Project): String {
        val configured = project.findProperty("appVersion") as String?
        if (!configured.isNullOrBlank()) return configured.trim()
        return DEFAULT_APP_VERSION
    }

    private const val DEFAULT_APP_VERSION = "1.4.0"
}

