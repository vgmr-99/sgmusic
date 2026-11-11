import java.io.File

/**
 * 质量门禁脚本依赖 Python，但不同平台可执行名不同：
 * - Linux / macOS 通常是 `python3`
 * - Windows 上 `python3` 常常不存在，只有 `python`
 *
 * 这里统一按优先级探测，找不到时回落到平台默认值并给出可读的错误提示。
 */
object PythonLauncher {

    /** 依次尝试的可执行名。 */
    private val candidates = listOf("python3", "python")

    /**
     * 解析当前环境可用的 Python 命令。
     *
     * 先看环境变量 `PYTHON` / `PYTHON3`，再按候选名探测系统 PATH。
     */
    fun resolve(
        environment: Map<String, String> = System.getenv(),
        probe: (String) -> Boolean = { command -> isExecutableAvailable(command) }
    ): String {
        val override = sequenceOf("PYTHON", "PYTHON3")
            .mapNotNull { environment[it]?.trim() }
            .firstOrNull { it.isNotEmpty() }
        if (override != null && probe(override)) return override

        val found = candidates.firstOrNull { probe(it) }
        if (found != null) return found

        // 探测失败时给出平台默认值，至少让命令本身能被用户手工修正
        return if (isWindows()) "python" else "python3"
    }

    /** Windows 上只有 `python`/`python.exe`，`python3` 通常是 Microsoft Store 的占位别名。 */
    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

    /**
     * 判断命令是否可用。
     *
     * 只做存在性探测，不真正执行，避免门禁脚本被意外触发。
     */
    private fun isExecutableAvailable(command: String): Boolean {
        val separator = if (isWindows()) ";" else ":"
        val path = System.getenv("PATH").orEmpty()
            .split(separator)
            .filter { it.isNotBlank() }
        if (path.isEmpty()) return false
        val extensions = if (isWindows()) {
            listOf("", ".exe", ".bat", ".cmd")
        } else {
            listOf("")
        }
        return path.any { dir ->
            extensions.any { extension ->
                File(dir, "$command$extension").isFile
            }
        }
    }
}
