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
 */

import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * wrapper-android 3.2.3 版 LX Runtime（PoC 升级版）
 * 包名：com.whl.quickjs.android.QuickJSLoader + com.whl.quickjs.wrapper.QuickJSContext
 * ── 最重要的两条库内铁律 ────────────────────────────
 * 1) QuickJSContext.create() 与后续一切 evaluate 必须在同一线程（`Must be call same thread like create`）。
 *    因此整个 LxRuntime 固定在一个单线程 dispatcher 上跑，轮询等待用 Thread.sleep 而非 delay。
 * 2) 脚本里调用 console 会触发 `you should be set a stdout of platform to console.stdout` 异常，
 *    必须 setConsole 接到日志桥日志才算合格。
 * 其余约束（照 lx-music-mobile / walkman-tv 的契约）：
 * 3) inited 必须 20s 超时；request/search 也要超时
 * 4) preload.js 定义全局 lx，脚本只是调 lx.on("request", ...)
 */
class LxRuntime(
    private val http: LxHttpClient,
    private val preloadJs: String,
) : AutoCloseable {

    @Volatile var isInited = false
        private set

    private val ctxRef = AtomicReference<QuickJSContext?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lx-js-$nameSeq")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val jsDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    companion object {
        private val nameSeq = java.util.concurrent.atomic.AtomicLong(0)
    }

    private fun createContext(): QuickJSContext {
        QuickJSLoader.init()
        val ctx = QuickJSContext.create()
        // 不设置 console 时，脚本里任意 console.log 都会抛
        // `When invoke console stuff, you should be set a stdout` 直接把初始化打崩。
        ctx.setConsole(object : QuickJSContext.Console {
            override fun log(message: String) { NPLogger.d("LX-JS", message) }
            override fun info(message: String) { NPLogger.d("LX-JS", message) }
            override fun warn(message: String) { NPLogger.w("LX-JS", message) }
            override fun error(message: String) { NPLogger.e("LX-JS", message) }
        })
        ctxRef.set(ctx)
        return ctx
    }

    /** 等待轮询的阻塞睡眠：为了保住 QuickJS 的同线程亲和性，这里蹲在单线程 dispatcher 上。 */
    private fun pumpSleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    suspend fun init(scriptText: String, scriptInfo: CurrentScriptInfo): LxResult<LxSourceInfo> =
        withContext(jsDispatcher) {
            try {
                val ctx = createContext()
                setupBridge(ctx)
                ctx.evaluate(preloadJs)
                val infoJson = JSONObject(mapOf(
                    "name" to (scriptInfo.name ?: "poc"),
                    "version" to (scriptInfo.version ?: "0.1"),
                )).toString()
                ctx.evaluate("globalThis.__lx_script_info = " + infoJson)
                ctx.evaluate(scriptText)

                // VM 混淆源自举偏慢：inited 超时放宽到 60s，避免冷启动误判
                val deadline = System.currentTimeMillis() + 60_000
                var initedStr = "{}"
                var loggedWaiting = false
                // NM timer tick: pump 之前必须先给已到时间的任务机会
                val pump = "if (globalThis.__lx_tickTimers) globalThis.__lx_tickTimers();"
                while (System.currentTimeMillis() < deadline) {
                    ctx.evaluate(pump)
                    val v = ctx.evaluate("JSON.stringify(globalThis.__lx_inited || {})")
                    if (v is String) initedStr = v
                    if (initedStr != "{}") break
                    if (!loggedWaiting && System.currentTimeMillis() - (deadline - 60_000) > 5_000) {
                        loggedWaiting = true
                        NPLogger.d("LxRuntime", "waiting inited (5s+)")
                    }
                    pumpSleep(120)
                }
                if (initedStr == "{}") {
                    return@withContext LxResult.Err("timeout", "inited 60s timeout, check desktop-vs-mobile source mismatch")
                }
                isInited = true
                NPLogger.d("LxRuntime", "inited received: ${initedStr.take(200)}")
                LxResult.Ok(parseInInited(initedStr))
            } catch (e: Exception) {
                NPLogger.e("LxRuntime", "init fail", e)
                LxResult.Err("init_fail", (e.message ?: e.toString()))
            }
        }

    /**
     * 请求播放地址。
     *
     * 洛雪协议：info = { type: <音质>, musicInfo: <音乐信息对象> }。
     * 脚本读的是 info.musicInfo，只传 songmid 的话脚本会直接报「缺少musicInfo」。
     */
    suspend fun requestMusicUrl(
        musicInfo: LxMusicInfo,
        platform: LxPlatform,
        quality: LxQuality,
        timeoutMs: Long = 20_000,
    ): LxResult<LxMusicUrl> = withContext(jsDispatcher) {
        val ctx = ctxRef.get() ?: return@withContext LxResult.Err("no_ctx", "runtime not inited")
        try {
            val info = JSONObject()
                .put("type", quality.code)
                .put("musicInfo", musicInfo.toJson())
            val payload = JSONObject()
                .put("action", "musicUrl")
                .put("source", platform.code)
                .put("info", info)
                .toString()
            val escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"")
            ctx.evaluate("globalThis.__lx_req = JSON.parse(\"$escaped\");")
            // 真正触发脚本的 request 处理器；结果由 preload 的 __lx_dispatch 桥接到 __lx_lastMusicUrl
            ctx.evaluate("try { globalThis.__lx_dispatch(globalThis.__lx_req); } catch (e) { globalThis.__lx_lastMusicUrl = { error: String(e) }; }")
            val pump = "if (globalThis.__lx_tickTimers) globalThis.__lx_tickTimers();"
            val deadline = System.currentTimeMillis() + timeoutMs
            var last = ""
            while (System.currentTimeMillis() < deadline) {
                ctx.evaluate(pump)
                val v = ctx.evaluate("JSON.stringify(globalThis.__lx_lastMusicUrl || {})")
                if (v is String) last = v
                // 拿到地址或错误就收工，别白等满超时（脚本拒绝时是 {"error":...}）
                if (last.contains("url") || last.contains("error")) break
                pumpSleep(80)
            }
            if (last.isEmpty() || last == "{}") {
                return@withContext LxResult.Err("timeout", "musicUrl timeout or empty")
            }
            // 社区脚本有两种返回形态：桌面版的 {url, quality, headers} 对象，
            // 移动版的纯 URL 字符串，两种都要接住。
            if (!last.trimStart().startsWith("{")) {
                val raw = last.trim().trim('"')
                return@withContext if (raw.isEmpty()) {
                    LxResult.Err("empty", last)
                } else {
                    LxResult.Ok(LxMusicUrl(raw))
                }
            }
            val payloadJson = JSONObject(last)
            payloadJson.optString("error").takeIf { it.isNotBlank() }?.let {
                return@withContext LxResult.Err("script_error", it)
            }
            val url = payloadJson.optString("url", "")
            if (url.isEmpty()) {
                LxResult.Err("empty", last)
            } else {
                LxResult.Ok(LxMusicUrl(url = url, headers = payloadJson.readHeaders()))
            }
        } catch (e: Exception) {
            LxResult.Err("request_fail", (e.message ?: e.toString()))
        }
    }

    /**
     * 通用请求：按 action 派发给脚本并等待结果。
     *
     * 音源接口自检用它逐个探测 search/musicUrl/lyric/pic，
     * 结果统一从 __lx_lastResults[action] 取。
     */
    suspend fun request(
        action: String,
        source: LxPlatform,
        info: JSONObject,
        timeoutMs: Long = 12_000,
    ): LxResult<String> = withContext(jsDispatcher) {
        val ctx = ctxRef.get() ?: return@withContext LxResult.Err("no_ctx", "runtime not inited")
        try {
            val payload = JSONObject()
                .put("action", action)
                .put("source", source.code)
                .put("info", info)
                .toString()
            val escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"")
            ctx.evaluate("globalThis.__lx_req = JSON.parse(\"$escaped\");")
            ctx.evaluate("globalThis.__lx_lastResults = {};")
            ctx.evaluate(
                "try { globalThis.__lx_dispatch(globalThis.__lx_req); } " +
                    "catch (e) { globalThis.__lx_lastResults = { error: String(e) }; }"
            )
            val pump = "if (globalThis.__lx_tickTimers) globalThis.__lx_tickTimers();"
            val deadline = System.currentTimeMillis() + timeoutMs
            var last = ""
            while (System.currentTimeMillis() < deadline) {
                ctx.evaluate(pump)
                val v = ctx.evaluate("JSON.stringify(globalThis.__lx_lastResults || {})")
                if (v is String) last = v
                if (last.isNotEmpty() && last != "{}") break
                pumpSleep(80)
            }
            if (last.isEmpty() || last == "{}") {
                return@withContext LxResult.Err("timeout", "$action timeout or empty")
            }
            val payloadJson = JSONObject(last)
            if (!payloadJson.has(action)) {
                return@withContext LxResult.Err("no_result", "$action 没有返回结果")
            }
            val value = payloadJson.opt(action)
            if (value == null || value === JSONObject.NULL) {
                return@withContext LxResult.Err("no_result", "$action 没有返回内容")
            }
            if (value is JSONObject && value.has("error")) {
                return@withContext LxResult.Err("script_error", value.optString("error"))
            }
            LxResult.Ok(value.toString())
        } catch (e: Exception) {
            LxResult.Err("request_fail", (e.message ?: e.toString()))
        }
    }

    /**
     * 按歌名搜索，返回候选列表（供 LxIdMatcher 模糊匹配）。
     * LX search 契约：请求 {action:"search", source, info:{searchParam:{keyword, page, typePage}}}，
     * 回调返回 {list:[{songmid, name, singer, albumName, encTime|duration, ...}], total, page, typePage}。
     * duration 单位：洛雪各源通常为秒，本对齐毫秒存储（秒 < 10000 时 ×1000）。
     */
    suspend fun search(
        platform: LxPlatform,
        keyword: String,
        timeoutMs: Long = 12_000,
    ): LxResult<List<LxIdMatcher.Candidate>> = withContext(jsDispatcher) {
        val ctx = ctxRef.get() ?: return@withContext LxResult.Err("no_ctx", "runtime not inited")
        try {
            val payload = JSONObject()
                .put("action", "search")
                .put("source", platform.code)
                .put("info", JSONObject()
                    .put("searchParam", JSONObject()
                        .put("keyword", keyword)
                        .put("page", 1)
                        .put("typePage", 1)
                    )
                )
                .toString()
            val escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"")
            ctx.evaluate("globalThis.__lx_search_req = JSON.parse(\"$escaped\");")
            ctx.evaluate("globalThis.__lx_search_result = null;")
            ctx.evaluate("try { globalThis.__lx_dispatch(globalThis.__lx_search_req); } catch (e) { globalThis.__lx_search_result = {}; }")
            val pump = "if (globalThis.__lx_tickTimers) globalThis.__lx_tickTimers();"
            val deadline = System.currentTimeMillis() + timeoutMs
            var last = ""
            while (System.currentTimeMillis() < deadline) {
                ctx.evaluate(pump)
                val v = ctx.evaluate("JSON.stringify(globalThis.__lx_search_result || {})")
                if (v is String) last = v
                if (last.isNotEmpty() && last != "{}") break
                pumpSleep(100)
            }
            if (last.isEmpty() || last == "{}") {
                return@withContext LxResult.Err("timeout", "search timeout or empty")
            }
            // 脚本 reject 会带上 error 字段，这里直接透传给调用方，避免把"不支持"误报成超时
            val errorMessage = runCatching { JSONObject(last).optString("error") }.getOrNull().orEmpty()
            if (errorMessage.isNotBlank()) {
                return@withContext LxResult.Err("script_error", errorMessage)
            }
            LxResult.Ok(parseSearchCandidates(last))
        } catch (e: Exception) {
            LxResult.Err("search_fail", (e.message ?: e.toString()))
        }
    }

    private fun parseSearchCandidates(text: String): List<LxIdMatcher.Candidate> {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val list = obj.optJSONArray("list") ?: obj.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<LxIdMatcher.Candidate>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val songmid = item.optString("songmid", "").ifBlank { item.optString("hash", "") }
            if (songmid.isBlank()) continue
            val title = item.optString("name", "").ifBlank { item.optString("title", "") }
            val singerRaw = item.optString("singer", "").ifBlank { item.optString("artist", "") }
            val singer = singerRaw.split(",", "/", "&", "、").first().trim()
            val durSec = when {
                item.has("encTime") -> item.optLong("encTime", 0)
                item.has("duration") -> item.optLong("duration", 0)
                else -> 0L
            }
            val durationMs = when {
                durSec <= 0L -> 0L
                durSec < 10_000L -> durSec * 1_000L
                else -> durSec
            }
            val picUrl = listOf("picUrl", "albumPic", "cover", "pic", "albumPicUrl", "blurPicUrl")
                .firstOrNull { item.has(it) }
                ?.let { item.optString(it).takeIf { p -> p.isNotBlank() } }
            val album = item.optString("albumName", "").ifBlank { item.optString("album", "") }
            out.add(
                LxIdMatcher.Candidate(
                    songmid = songmid,
                    title = title,
                    artist = singer,
                    durationMs = durationMs,
                    picUrl = picUrl,
                    album = album,
                )
            )
        }
        return out
    }

    /** 读取脚本返回的 headers 对象（部分音源的地址需要 Referer 才能拉流） */
    private fun JSONObject.readHeaders(): Map<String, String> {
        val obj = optJSONObject("headers") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.optString(key, "").trim()
            if (value.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun parseInInited(text: String): LxSourceInfo {        val obj = JSONObject(text)
        val sources = mutableMapOf<String, List<String>>()
        val srcObj = obj.optJSONObject("sources")
        if (srcObj != null) {
            val keys = srcObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                // 两种协议形态：
                //  旧版: "kw": ["128k", ...]（数组直接就是音质）
                //  新版: "kw": {"actions":[...], "qualitys":[...]}（对象里取 qualitys）
                val entry = srcObj.optJSONObject(k)
                val arr = srcObj.optJSONArray(k) ?: entry?.optJSONArray("qualitys")
                val list = mutableListOf<String>()
                if (arr != null) for (i in 0 until arr.length()) list.add(arr.optString(i))
                sources[k] = list
            }
        }
        val actions = mutableListOf<String>()
        val actArr = obj.optJSONArray("actions")
        if (actArr != null) for (i in 0 until actArr.length()) actions.add(actArr.optString(i))
        return LxSourceInfo(
            status = obj.optBoolean("status", true),
            env = obj.optString("env", "mobile"),
            sources = sources,
            actions = actions,
            alert = if (obj.has("alert") && !obj.isNull("alert")) obj.optString("alert") else null,
        )
    }

    private fun setupBridge(ctx: QuickJSContext) {
        // setTimeout/setInterval 已在 preload.js 自研队列（需要时再用，见注释）；原 native 侧 Scheduling 已废弃
        val cryptoObj = ctx.createNewJSObject()

        val scheduler = java.util.concurrent.ScheduledThreadPoolExecutor(2) { r ->
            Thread(r, "lx-timers-$nameSeq").apply { isDaemon = true }
        }
        val schedulerMap = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.ScheduledFuture<*>>()
        val schedulerSeq = java.util.concurrent.atomic.AtomicInteger(0)
        val timerObj = ctx.createNewJSObject()
        timerObj.setProperty("setTimeout", JSCallFunction { args ->
            val delayMs = args.getOrNull(1)?.toString()?.toLongOrNull() ?: 0L
            val id = schedulerSeq.incrementAndGet()
            val future = scheduler.schedule(
                {},
                delayMs.coerceAtLeast(0L),
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            schedulerMap[id] = future
            id
        })
        timerObj.setProperty("clearTimeout", JSCallFunction { args ->
            val id = args.getOrNull(0)?.toString()?.toIntOrNull()
            if (id != null) schedulerMap.remove(id)?.cancel(false)
            null
        })
        timerObj.setProperty("setInterval", JSCallFunction { args ->
            val periodMs = args.getOrNull(1)?.toString()?.toLongOrNull() ?: 0L
            val id = schedulerSeq.incrementAndGet()
            val future = scheduler.scheduleAtFixedRate(
                {},
                0L,
                periodMs.coerceAtLeast(1L),
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            schedulerMap[id] = future
            id
        })
        timerObj.setProperty("clearInterval", JSCallFunction { args ->
            val id = args.getOrNull(0)?.toString()?.toIntOrNull()
            if (id != null) schedulerMap.remove(id)?.cancel(false)
            null
        })
        ctx.getGlobalObject().setProperty("__lx_native_timers", timerObj)
        cryptoObj.setProperty("aesDecrypt", JSCallFunction { args -> LxCrypto.aesCbcDecrypt(args.getOrNull(0)?.toString().orEmpty(), args.getOrNull(1)?.toString().orEmpty(), args.getOrNull(2)?.toString().orEmpty()) })
        cryptoObj.setProperty("b64enc", JSCallFunction { args -> LxCrypto.base64Encode((args.getOrNull(0)?.toString().orEmpty()).toByteArray()) })
        cryptoObj.setProperty("b64dec", JSCallFunction { args -> String(LxCrypto.base64Decode(args.getOrNull(0)?.toString().orEmpty())) })
        cryptoObj.setProperty("randomBytes", JSCallFunction { args -> LxCrypto.base64Encode(LxCrypto.randomBytes(args.getOrNull(0)?.toString()?.toIntOrNull() ?: 16)) })
        ctx.getGlobalObject().setProperty("__lx_native_crypto_bridge", cryptoObj)

        val httpObj = ctx.createNewJSObject()
        httpObj.setProperty("request", JSCallFunction { args ->
            runBlocking {
                // preload 传 JSON 字符串 {method, url, headers, body, timeoutMs}；旧调用 (method, url) 也兼容理解为两参数
                val rawOption = args.getOrNull(0)?.toString().orEmpty()
                val opt = runCatching { JSONObject(rawOption) }.getOrNull()
                val method = opt?.optString("method", "GET") ?: args.getOrNull(0)?.toString() ?: "GET"
                val url = opt?.optString("url", "") ?: args.getOrNull(1)?.toString() ?: ""
                val headers = mutableMapOf<String, String>()
                val hObj = opt?.optJSONObject("headers")
                if (hObj != null) {
                    val keys = hObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        headers[k] = hObj.optString(k)
                    }
                }
                val bodyText = opt?.optString("body", "")?.takeIf { it.isNotEmpty() }
                val r = http.request(method, url, headers, bodyText)
                JSONObject(mapOf(
                    "statusCode" to r.statusCode,
                    "headers" to r.headers,
                    "body" to (r.body ?: ""),
                    "error" to (r.error ?: ""),
                )).toString()
            }
        })
        ctx.getGlobalObject().setProperty("__lx_native_http", httpObj)
    }

    override fun close() {
        try { ctxRef.get()?.destroy() } catch (_: Exception) {}
        runCatching { executor.shutdown() }
    }
}
