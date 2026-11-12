package moe.ouom.neriplayer.platform.lxsource

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
 * File: moe.ouom.neriplayer.platform.lxsource/LxSourceTester
 * Created: 2026/11/12
 */

import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.logging.NPLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 检测模式
 *
 * - QUICK：每个平台只测一档基础音质，用来快速判断脚本能不能出流
 * - FULL：每个平台逐档测 128k / 320k / 无损 / 母带
 */
enum class LxTestMode {
    QUICK,
    FULL
}

/**
 * 一次"平台 × 音质"的检测结果
 *
 * @param platform 平台
 * @param quality 音质档位
 * @param ok 是否解析成功
 * @param skipped 是否跳过（平台不支持该音质，或定位不到测试歌曲）
 * @param detail 简要说明：失败原因或结果摘要
 * @param elapsedMs 耗时
 */
data class LxQualityProbe(
    val platform: LxPlatform,
    val quality: LxQuality,
    val ok: Boolean,
    val skipped: Boolean = false,
    val detail: String = "",
    val elapsedMs: Long = 0L
)

/**
 * 音源音质检测报告
 *
 * @param sourceName 音源名
 * @param initialized 脚本是否初始化成功
 * @param initError 初始化失败原因
 * @param probes 逐项检测结果，顺序即表格行序
 * @param timedOut 是否因为脚本卡死而中止
 * @param cancelled 是否被用户取消
 */
data class LxSourceTestReport(
    val sourceName: String,
    val initialized: Boolean,
    val initError: String? = null,
    val probes: List<LxQualityProbe> = emptyList(),
    val timedOut: Boolean = false,
    val cancelled: Boolean = false
) {
    val passedCount: Int get() = probes.count { it.ok }
    val checkedCount: Int get() = probes.count { !it.skipped }

    /** 表格行序：按平台首次出现的顺序 */
    val platforms: List<LxPlatform> get() = probes.map { it.platform }.distinct()

    /** 某个平台下某一档音质的检测结果 */
    fun probe(platform: LxPlatform, quality: LxQuality): LxQualityProbe? =
        probes.firstOrNull { it.platform == platform && it.quality == quality }
}

/**
 * 音源音质检测。
 *
 * 用一个独立的 QuickJS 实例跑脚本，对"脚本支持的每个平台 × 每档音质"逐项做取流检测：
 * - 先用各平台公开搜索接口定位同一首测试歌曲，拿到该平台真实的 songmid
 * - 再让脚本用这个 songmid 换取播放地址，并对地址做一次范围请求确认真的能拉到流
 * - 实例与播放链路完全隔离，检测不会打断当前播放
 * - 每项都有超时；脚本卡死时中止剩余检测并丢弃该实例
 */
class LxSourceTester(
    private val http: LxHttpClient,
    private val preloadJs: String,
    private val locator: LxTestSongLocator? = null,
    private val probeClient: OkHttpClient? = null
) {

    companion object {
        private const val TAG = "LxSourceTester"
        private const val DEFAULT_KEYWORD = "晴天 周杰伦"
        private const val DEFAULT_PROBE_TIMEOUT_MS = 12_000L
        private const val URL_VERIFY_TIMEOUT_MS = 10_000L

        /** 脚本自身超时后留的余量：让脚本先报"该项超时"，而不是整轮被判成卡死 */
        private const val PROBE_GRACE_MS = 2_000L
    }

    suspend fun test(
        scriptText: String,
        scriptInfo: CurrentScriptInfo,
        mode: LxTestMode = LxTestMode.FULL,
        keyword: String = DEFAULT_KEYWORD,
        probeTimeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
        onProgress: (LxQualityProbe) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): LxSourceTestReport {
        val sourceName = scriptInfo.name ?: "音源"
        val runtime = LxRuntime(http, preloadJs)
        var timedOut = false
        var cancelled = false
        try {
            val init = runtime.init(scriptText, scriptInfo)
            if (init is LxResult.Err) {
                return LxSourceTestReport(
                    sourceName = sourceName,
                    initialized = false,
                    initError = init.message
                )
            }
            val info = (init as LxResult.Ok).value
            val platforms = info.sources.keys
                .mapNotNull { code -> LxPlatform.entries.firstOrNull { it.code == code } }
                .filter { it != LxPlatform.LOCAL }

            val probes = mutableListOf<LxQualityProbe>()
            for (platform in platforms) {
                if (isCancelled()) {
                    cancelled = true
                    break
                }
                val declared = info.qualities(platform)
                val targets = when (mode) {
                    // 快速检测：优先 128k，脚本没声明就退到它自己声明的第一档
                    LxTestMode.QUICK -> listOf(
                        LxQuality.Q128.takeIf { it.code in declared }
                            ?: declared.firstNotNullOfOrNull { code ->
                                LxQuality.entries.firstOrNull { it.code == code }
                            }
                            ?: LxQuality.Q128
                    )
                    LxTestMode.FULL -> LxQuality.STANDARD_ORDER
                }
                val song = locator?.locate(platform, keyword)
                for (quality in targets) {
                    if (isCancelled()) {
                        cancelled = true
                        break
                    }
                    val probe = if (declared.isNotEmpty() && quality.code !in declared) {
                        LxQualityProbe(
                            platform = platform,
                            quality = quality,
                            ok = false,
                            skipped = true,
                            detail = "脚本未声明该音质"
                        )
                    } else if (song == null) {
                        LxQualityProbe(
                            platform = platform,
                            quality = quality,
                            ok = false,
                            skipped = true,
                            detail = "定位不到测试歌曲"
                        )
                    } else {
                        val result = probeQuality(
                            runtime = runtime,
                            platform = platform,
                            quality = quality,
                            song = song,
                            timeoutMs = probeTimeoutMs
                        )
                        if (result == null) {
                            timedOut = true
                            cancelled = true
                            break
                        }
                        result
                    }
                    probes += probe
                    onProgress(probe)
                }
                if (cancelled) break
            }
            return LxSourceTestReport(
                sourceName = sourceName,
                initialized = true,
                probes = probes,
                timedOut = timedOut,
                cancelled = cancelled
            )
        } catch (error: Exception) {
            NPLogger.e(TAG, "音源检测失败: $sourceName", error)
            return LxSourceTestReport(
                sourceName = sourceName,
                initialized = false,
                initError = error.message ?: error.toString()
            )
        } finally {
            // 脚本卡死时 JS 线程还停在 evaluate 里，这时销毁上下文会崩，只能丢弃这个实例
            if (!timedOut) {
                runCatching { runtime.close() }
            }
        }
    }

    /** 单项音质检测；返回 null 表示脚本卡死 */
    private suspend fun probeQuality(
        runtime: LxRuntime,
        platform: LxPlatform,
        quality: LxQuality,
        song: LxTestSongRef,
        timeoutMs: Long
    ): LxQualityProbe? {
        val info = JSONObject()
            .put("songmid", song.songmid)
            .put("hash", song.hash)
            .put("type", quality.code)
        val start = System.currentTimeMillis()
        val result = withTimeoutOrNull(timeoutMs + PROBE_GRACE_MS) {
            runtime.request("musicUrl", platform, info, timeoutMs)
        }
        val elapsed = System.currentTimeMillis() - start
        if (result == null) {
            // 脚本线程彻底卡死（连自己的超时都没能返回），只能中止整轮检测
            return null
        }
        return when (result) {
            is LxResult.Err -> LxQualityProbe(
                platform = platform,
                quality = quality,
                ok = false,
                detail = result.message,
                elapsedMs = elapsed
            )
            is LxResult.Ok -> {
                val payload = runCatching { JSONObject(result.value) }.getOrNull()
                if (payload == null) {
                    return LxQualityProbe(
                        platform = platform,
                        quality = quality,
                        ok = false,
                        detail = "脚本返回的不是对象",
                        elapsedMs = elapsed
                    )
                }
                payload.optString("error").takeIf { it.isNotBlank() }?.let { error ->
                    return LxQualityProbe(
                        platform = platform,
                        quality = quality,
                        ok = false,
                        detail = error,
                        elapsedMs = elapsed
                    )
                }
                val url = payload.optString("url", "").trim()
                if (url.isEmpty()) {
                    return LxQualityProbe(
                        platform = platform,
                        quality = quality,
                        ok = false,
                        detail = "脚本没有返回播放地址",
                        elapsedMs = elapsed
                    )
                }
                val headers = payload.optJSONObject("headers")
                val verify = verifyPlayableUrl(url, headers)
                LxQualityProbe(
                    platform = platform,
                    quality = quality,
                    ok = verify.ok,
                    detail = verify.detail,
                    elapsedMs = elapsed + verify.elapsedMs
                )
            }
        }
    }

    /** 校验地址真的能拉到流；只取头两个字节，避免整首下载 */
    private suspend fun verifyPlayableUrl(
        url: String,
        headers: JSONObject?
    ): UrlVerification {
        val client = probeClient ?: return UrlVerification(true, "未启用地址校验", 0L)
        val start = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-1")
            .apply {
                headers?.let { obj ->
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        obj.optString(key).takeIf { it.isNotBlank() }?.let { header(key, it) }
                    }
                }
            }
            .build()
        return runCatching {
            val call = client.newCall(request)
            // 单个地址的校验不能拖住整轮检测：给这次调用单独加超时
            call.timeout().timeout(URL_VERIFY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                val elapsed = System.currentTimeMillis() - start
                if (response.isSuccessful) {
                    UrlVerification(true, "HTTP ${response.code}", elapsed)
                } else {
                    UrlVerification(false, "HTTP ${response.code}", elapsed)
                }
            }
        }.getOrElse { error ->
            UrlVerification(false, error.message ?: "地址不可访问", System.currentTimeMillis() - start)
        }
    }

    private data class UrlVerification(
        val ok: Boolean,
        val detail: String,
        val elapsedMs: Long
    )
}
