import com.android.build.api.dsl.ApplicationExtension
plugins {
    id("com.android.application")
    id("build-logic.android.base")
}

extensions.findByType(ApplicationExtension::class)?.run {
    defaultConfig {
        versionCode = Common.getBuildVersionCode(providers.gradleProperty("buildTimestamp").orNull)
        versionName = Common.getBuildVersionName(project)
        // 对外展示的语义化版本号（1.4.0），由 gradle.properties 的 appVersion 决定。
        // 与 versionName 分开：versionName 是构建身份（<git短哈希>.<时间戳>），
        // CI 的 release_artifacts.py 依赖它的时间戳后缀，不可改动。
        buildConfigField("String", "APP_VERSION", "\"${Common.getAppVersion(project)}\"")
    }

    androidResources {
        localeFilters += setOf("zh", "en")
    }
}
