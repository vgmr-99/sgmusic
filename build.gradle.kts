// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

/**
 * 解析质量门禁脚本可用的 Python 命令。
 *
 * 不同平台的可执行名不同：Linux/macOS 通常是 `python3`，Windows 上往往只有 `python`。
 * 根项目无法直接引用 build-logic 的类（includedBuild 互相隔离），
 * 因此这里保留一份精简实现，逻辑与 build-logic 的 PythonLauncher 保持一致。
 */
fun resolvePythonCommand(): String {
    val override = sequenceOf("PYTHON", "PYTHON3")
        .mapNotNull { System.getenv(it)?.trim() }
        .firstOrNull { it.isNotEmpty() }
    if (override != null) return override

    val isWindows = System.getProperty("os.name").orEmpty()
        .contains("windows", ignoreCase = true)
    val candidates = if (isWindows) listOf("python", "python3") else listOf("python3", "python")
    val separator = if (isWindows) ";" else ":"
    val pathEntries = System.getenv("PATH").orEmpty()
        .split(separator)
        .filter { it.isNotBlank() }
    val extensions = if (isWindows) listOf("", ".exe", ".bat", ".cmd") else listOf("")

    return candidates.firstOrNull { candidate ->
        pathEntries.any { dir ->
            extensions.any { extension -> File(dir, "$candidate$extension").isFile }
        }
    } ?: candidates.first()
}

val verifyModuleBoundaries = tasks.register<Exec>("verifyModuleBoundaries") {
    group = "verification"
    description = "Check library dependency direction and owned source budgets."
    workingDir(rootDir)
    commandLine(resolvePythonCommand(), "-B", "tools_pub/quality/module_boundaries.py")
}

tasks.register("verifyModularization") {
    group = "verification"
    description = "Run app and library tests, combined CRAP coverage, lint and module boundary checks."
    dependsOn(verifyModuleBoundaries, ":app:verifyDomainDependencies", ":app:verifyCrap", ":app:lintDebug")
    dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
    rootProject.file("gradle/owned-modules.txt").readLines().filter { it.isNotBlank() }
        .forEach { dependsOn("$it:lintDebug") }
}
