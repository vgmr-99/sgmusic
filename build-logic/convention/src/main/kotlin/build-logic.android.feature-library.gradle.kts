import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import java.time.Duration

plugins {
    id("build-logic.android.library")
}

android {
    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
        }
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

/**
 * 已知会互相等待的既有坏测试。
 *
 * PlayerRestoredPlaybackResumeAdmissionTest 是 Robolectric 测试，内部用
 * `runTest { Dispatchers.setMain(StandardTestDispatcher(...)) }` 接管主线程调度器；
 * 与 Robolectric 自己的沙箱主线程互相等待时，测试线程会阻塞在
 * `Sandbox.runOnMainThread` 的 FutureTask 上，整轮回归无限期挂起
 * （干净分支上同样复现，与业务改动无关）。
 *
 * 默认跳过，避免一次回归把整轮构建拖死；需要单独复现这个坏类时用
 * `-PincludeKnownBrokenTests=true` 打开（单跑该类不会挂死）。
 */
val knownBrokenTestPatterns = listOf(
    "*PlayerRestoredPlaybackResumeAdmissionTest*"
)

tasks.withType<Test>().configureEach {
    systemProperty("java.io.tmpdir", temporaryDir.absolutePath)
    listOf(
        "runNeteaseSmoke", "runYouTubePlaybackSmoke", "youtubeSmokeVideoId",
        "youtubeSmokeForceRefresh", "youtubeSmokeCookieFile"
    ).forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
    // 单测卡死时不能让整轮构建无限期挂着：到点强制中断并把任务标记为失败
    timeout.set(Duration.ofMinutes(30))
    if (!providers.gradleProperty("includeKnownBrokenTests").orNull.toBoolean()) {
        filter {
            knownBrokenTestPatterns.forEach { pattern -> excludeTestsMatching(pattern) }
        }
    }
}

val coverageClasses = tasks.register<CoverageClassesJar>("coverageClassesJar") {
    archiveClassifier.set("coverage")
    from(projectDirectories)
    from(projectJars.map { jars -> jars.map { zipTree(it.asFile) } })
}

androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
        .use(coverageClasses)
        .toGet(ScopedArtifact.CLASSES, CoverageClassesJar::projectJars, CoverageClassesJar::projectDirectories)
}

configurations.create("coverageClassesElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(coverageClasses)
}

configurations.create("coverageExecutionElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(providers.provider {
        tasks.named<Test>("testDebugUnitTest").get()
            .extensions.getByType<JacocoTaskExtension>().destinationFile
            ?: error("Debug JVM coverage destination is missing for $path")
    }) {
        builtBy("testDebugUnitTest")
    }
}
