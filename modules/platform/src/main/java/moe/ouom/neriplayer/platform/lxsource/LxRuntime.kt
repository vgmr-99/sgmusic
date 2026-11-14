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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
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
 * 3) inited 必须超时；request/search 也要超时
 * 4) preload.js 定义全局 lx + Node/CommonJS 兼容层，脚本可以是：
 *    - mobile 契约：lx.on('request', handler) + lx.send('inited', ...)
 *    - desktop/CeruMusic 契约：module.exports.musicUrl(source, musicInfo, quality)
 *    - converter 包装后的 adapter：两者都具备
 *
 * 超时/异常隔离说明：wrapper-android 3.2.3 未暴露「中断一段正在执行的 JS」的 API
 * （没有 interrupt/terminate，死循环无法从宿主侧打断），所以无法做真正的 evaluate 超时。
 * 这里保留原有策略：每次 evaluate 用 try/catch 隔离异常，超时靠宿主轮询 deadline +
 * LxSourceTester 的 withTimeoutOrNull 丢弃卡死实例；绝不留下「必崩」的调用。
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

        private val URL_KEYS = listOf("url", "src", "playUrl", "play_url", "location", "audioUrl", "audio_url")
        private val NESTED_KEYS = listOf("data", "body", "result", "response")
        private val HEADER_KEYS = listOf("requestHeaders", "httpHeaders", "headers")

        /** 搜索结果信封 key 的兼容顺序（对齐 Mint-Music 的 _parsePluginSearchResult） */
        private val SEARCH_ENVELOPE_KEYS = listOf("list", "songs", "items", "records", "data", "result", "body")

        /** 脚本没声明音质时的缺省档位（洛雪约定） */
        private val DEFAULT_QUALITIES = listOf("128k", "320k", "flac")

        /** JSCallFunction 的 args 在不同 wrapper 版本里可能是 Array 或 List，统一按 Any? 读取。 */
        private fun readArg(args: Any?, index: Int): String = when (args) {
            is Array<*> -> args.getOrNull(index)?.toString().orEmpty()
            is List<*> -> args.getOrNull(index)?.toString().orEmpty()
            else -> if (index == 0) args?.toString().orEmpty() else ""
        }

        /** 把任意字符串安全嵌进 JS 双引号字面量（含 U+2028/U+2029 与 BOM）。 */
        private fun jsStringLiteral(s: String): String = buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\u2028' -> append("\\u2028")
                    '\u2029' -> append("\\u2029")
                    '\uFEFF' -> append("\\uFEFF")
                    else -> if (c < ' ') {
                        append("\\u")
                        append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        append(c)
                    }
                }
            }
            append('"')
        }

        /**
         * 未包装的 desktop 脚本不发 inited：补一份能力声明。
         *
         * converter 包装过的脚本由 adapter 自己兜底（adapter 知道脚本有没有注册
         * request handler），这里只在没有 adapter 时生效，避免与真实 inited 抢跑。
         */
        private val ENSURE_INITED_JS = """
            (function () {
              if (globalThis.__lxAdapter) return;
              if (globalThis.__lx_inited) return;
              var ex = globalThis.module && globalThis.module.exports;
              if (!ex || typeof ex.musicUrl !== 'function') return;
              var q = ['128k', '320k', 'flac'];
              var mk = function (name) { return { name: name, type: 'music', actions: ['musicUrl'], qualitys: q, qualities: q, types: q }; };
              globalThis.lx.send('inited', {
                status: true,
                actions: ['musicUrl'],
                sources: {
                  kw: mk('kuwo'), kg: mk('kugou'), tx: mk('qq'), wy: mk('netease'),
                  mg: mk('migu'), qs: mk('qishui')
                }
              });
              if (globalThis.__lx_inited) globalThis.__lx_inited.__lxFallback = true;
            })();
        """.trimIndent()

        /** 等音源声明的最长时间：VM 混淆源自举偏慢，冷启动别误判 */
        private const val INIT_TIMEOUT_MS = 60_000L

        /**
         * 脚本注册了 request handler 但迟迟不发 inited 时的宽限期。
         *
         * Mint 的判定条件是「注册了 handler 即视为可用音源」，不发 inited 也能取流；
         * 这里给它 10 秒补齐 sources 的机会，超时后按 Mint 行为放行。
         */
        private const val INIT_FALLBACK_GRACE_MS = 10_000L
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

    /** 轮询用的容错求值：单次异常不应该打断整个等待循环（wrapper 没有中断 API，只能这样隔离）。 */
    private fun evalString(ctx: QuickJSContext, code: String): String? = try {
        ctx.evaluate(code) as? String
    } catch (e: Exception) {
        NPLogger.w("LxRuntime", "eval error: ${e.message}")
        null
    }

    suspend fun init(scriptText: String, scriptInfo: CurrentScriptInfo): LxResult<LxSourceInfo> =
        withContext(jsDispatcher) {
            try {
                val ctx = createContext()
                setupBridge(ctx)
                ctx.evaluate(preloadJs)
                val infoJson = JSONObject()
                    .put("name", scriptInfo.name ?: "poc")
                    .put("version", scriptInfo.version ?: "0.1")
                    .put("author", scriptInfo.author ?: "")
                    .put("description", scriptInfo.description ?: "")
                    .put("homepage", scriptInfo.srcUrl ?: "")
                    .toString()
                ctx.evaluate("globalThis.__lx_script_info = " + infoJson)
                // 原始脚本（mobile / desktop / converter adapter 均可）单独求值。
                // converter 包装过的 adapter 内部已经把原稿放进块级作用域，原样执行；
                // 未包装的脚本整体套一层块作用域，顶层 const/let 不会与宿主冲突。
                ctx.evaluate(scriptTextForEval(scriptText))
                // 未包装的 desktop 脚本不发 inited，这里补一次
                evalString(ctx, ENSURE_INITED_JS)

                // 轮询等待音源声明：sources 就绪即返回
                val deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS
                val start = System.currentTimeMillis()
                var initedStr = ""
                var capability = ScriptCapability()
                val pump = "if (globalThis.__lx_tickTimers) globalThis.__lx_tickTimers();"
                var tick = 0
                while (System.currentTimeMillis() < deadline) {
                    evalString(ctx, pump)
                    // 每 ~10 个 tick 兜底一次，覆盖脚本在 setTimeout 里才导出 musicUrl 的情况
                    if (tick++ % 10 == 0) evalString(ctx, ENSURE_INITED_JS)
                    val v = evalString(ctx, "JSON.stringify(globalThis.__lx_inited || null)")
                    if (!v.isNullOrBlank() && v != "null") initedStr = v
                    val inited = parseJsonObject(initedStr)
                    capability = readCapability(ctx)
                    if (hasSources(inited)) {
                        if (!isFallbackInited(inited)) break
                        // 兜底声明只在脚本确实没注册 request handler 时才采用；
                        // 注册了 handler 说明脚本可能稍后才发真正的 inited，继续等。
                        if (!capability.hasHandler) break
                    } else {
                        // 脚本还没发 inited：只要它注册了 request handler（或自己导出了
                        // musicUrl），就已经具备取流能力 —— 与 Mint 的 __lxEnsureReady 一致。
                        // 再给一段宽限期，等它把 sources 补上；宽限期内补上就按声明走。
                        if (capability.usable &&
                            System.currentTimeMillis() - start >= INIT_FALLBACK_GRACE_MS
                        ) break
                    }
                    pumpSleep(120)
                }
                val inited = parseJsonObject(initedStr)
                var scriptHasHandler = capability.hasHandler
                var scriptHasMusicUrl = capability.scriptMusicUrl
                if (!hasSources(inited)) {
                    val state = readAdapterState(ctx)
                    val initError = state?.optString("initError", "")?.takeIf { it.isNotBlank() }
                    if (!capability.usable) {
                        if (initError != null) {
                            return@withContext LxResult.Err("init_fail", "LX 音源初始化失败: $initError")
                        }
                        // 把「为什么脚本没被识别」写进错误里，用户在设置页就能看到，
                        // 不必抓 logcat 才知道是少了 inited 还是少了 request handler。
                        val handlerHint = if (capability.hasHandler) {
                            "；脚本已注册 lx.on('request')，但没收到 lx.send('inited')"
                        } else {
                            "；脚本也没有注册 lx.on('request') 事件"
                        }
                        return@withContext LxResult.Err(
                            "no_sources",
                            "脚本未声明任何可用音源（未收到 lx.send('inited') 或 sources 为空）$handlerHint",
                        )
                    }
                    // 脚本能取流但没声明 sources：按 Mint 的行为放行，
                    // 识别状态里标记 fallbackAllSources，宿主对所有平台都尝试取流。
                    NPLogger.w(
                        "LxRuntime",
                        "脚本可取流但未声明 sources，按 Mint 行为放行" +
                            "（handler=${capability.hasHandler}, musicUrl=${capability.scriptMusicUrl}）",
                    )
                }
                isInited = true
                NPLogger.d("LxRuntime", "inited received: ${initedStr.take(200)}")
                LxResult.Ok(parseInited(initedStr, scriptHasHandler, scriptHasMusicUrl))
            } catch (e: Exception) {
                NPLogger.e("LxRuntime", "init fail", e)
                LxResult.Err("init_fail", (e.message ?: e.toString()))
            }
        }

    /**
     * 求值用的脚本文本。
     *
     * adapter 包装过的脚本自带块级作用域（原稿已在 adapter 内部被包住），
     * 原样执行；未包装的脚本整体套一层 `{ ... }`，顶层 const/let 不会撞到宿主全局。
     */
    private fun scriptTextForEval(scriptText: String): String {
        val text = scriptText.removePrefix("\uFEFF")
        if (text.trimStart().startsWith(LxScriptConverter.ADAPTER_MARKER)) return text
        return "{\n$text\n}\n;void 0;"
    }

    /** 读取 adapter 暴露给宿主的运行期状态（无 adapter 时返回 null） */
    private fun readAdapterState(ctx: QuickJSContext): JSONObject? {
        val raw = evalString(
            ctx,
            "JSON.stringify(globalThis.__lxAdapterState ? globalThis.__lxAdapterState() : null)",
        )
        if (raw.isNullOrBlank() || raw == "null") return null
        return parseJsonObject(raw)
    }

    /**
     * 脚本的取流能力。
     *
     * @param hasHandler 注册了 `lx.on('request', handler)`
     * @param scriptMusicUrl 脚本自己导出了 `module.exports.musicUrl`
     */
    private data class ScriptCapability(
        val hasHandler: Boolean = false,
        val scriptMusicUrl: Boolean = false,
    ) {
        /** 具备取流能力（对齐 Mint 的 __lxEnsureReady 判定） */
        val usable: Boolean get() = hasHandler || scriptMusicUrl
    }

    /**
     * 读取脚本的取流能力，**同时兼容 adapter 包装过与未包装的脚本**。
     *
     * - adapter 包装过：读 `__lxAdapterState()`
     * - 未包装：读 preload 的 `__lx_hasRequestHandler()`，
     *   并检查 `module.exports.musicUrl` 是否为脚本自己写的
     *   （preload 装的是宿主兜底实现，带 __lxIsHostDefault 标记，不算）。
     */
    private fun readCapability(ctx: QuickJSContext): ScriptCapability {
        val state = readAdapterState(ctx)
        if (state != null) {
            return ScriptCapability(
                hasHandler = state.optBoolean("hasHandler", false),
                scriptMusicUrl = state.optBoolean("scriptMusicUrl", false),
            )
        }
        val hasHandler = evalString(
            ctx,
            "globalThis.__lx_hasRequestHandler && globalThis.__lx_hasRequestHandler() ? '1' : '0'",
        ) == "1"
        val scriptMusicUrl = evalString(
            ctx,
            "(function () {" +
                "var ex = globalThis.module && globalThis.module.exports;" +
                "if (!ex || typeof ex.musicUrl !== 'function') return '0';" +
                "if (ex.musicUrl.__lxIsHostDefault) return '0';" +
                "return '1';" +
                "})()",
        ) == "1"
        return ScriptCapability(hasHandler = hasHandler, scriptMusicUrl = scriptMusicUrl)
    }

    private fun parseJsonObject(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /** inited 声明里是否已经有可用音源（sources 至少一个 key） */
    private fun hasSources(inited: JSONObject?): Boolean {
        val sources = inited?.optJSONObject("sources") ?: return false
        return sources.length() > 0
    }

    /** 该 inited 是否只是宿主补的兜底声明（脚本自己没发 inited） */
    private fun isFallbackInited(inited: JSONObject?): Boolean =
        inited?.optBoolean("__lxFallback", false) == true

    /**
     * 请求播放地址：统一走 module.exports.musicUrl(source, musicInfo, quality)。
     *
     * - desktop/CeruMusic 脚本：脚本自己导出该函数，直接调用
     * - mobile 脚本（lx.on('request')）：preload 的默认 module.exports.musicUrl 会把
     *   {source, action:'musicUrl', info} 派发给脚本的 request handler
     * - converter 包装后的 adapter：两者都有
     *
     * 结果先经 JS 侧 __lxNormalizeMusicUrlResult 收敛成
     * { source, action, data:{type,url}, headers? }，Kotlin 侧再做一次宽松解析兜底，
     * 兼容 {url}、{data:{url}}、{body:{data:{url}}}、裸字符串、多重 JSON 字符串包裹。
     */
    suspend fun requestMusicUrl(
        musicInfo: LxMusicInfo,
        platform: LxPlatform,
        quality: LxQuality,
        timeoutMs: Long = 20_000,
    ): LxResult<LxMusicUrl> = withContext(jsDispatcher) {
        val ctx = ctxRef.get() ?: return@withContext LxResult.Err("no_ctx", "runtime not inited")
        try {
            // 既没注册 request handler、脚本自己也没导出 musicUrl：直接报出来，
            // 不然用户只看到一句「超时」，根本不知道脚本压根没接上取流
            if (!hasUsableHandler(ctx)) {
                return@withContext LxResult.Err("no_handler", "LX 音源未注册 request 事件")
            }
            val info = JSONObject()
                .put("type", quality.code)
                .put("musicInfo", musicInfo.toJson())
            val payload = JSONObject()
                .put("action", "musicUrl")
                .put("source", platform.code)
                .put("info", info)
                .toString()

            val call = """
                globalThis.__lx_req = JSON.parse(${jsStringLiteral(payload)});
                globalThis.__lx_lastMusicUrl = null;
                globalThis.__lx_musicUrlDone = false;
                (function () {
                  var ex = globalThis.module && globalThis.module.exports;
                  var fn = (ex && typeof ex.musicUrl === 'function') ? ex.musicUrl : null;
                  var p;
                  if (!fn) {
                    p = Promise.reject(new Error('musicUrl not exported'));
                  } else {
                    try {
                      p = fn(${jsStringLiteral(platform.code)}, JSON.parse(${jsStringLiteral(musicInfo.toJson().toString())}), ${jsStringLiteral(quality.code)});
                    } catch (e) { p = Promise.reject(e); }
                  }
                  Promise.resolve(p).then(function (v) {
                    try {
                      globalThis.__lx_lastMusicUrl = globalThis.__lxNormalizeMusicUrlResult(v, globalThis.__lx_req);
                    } catch (e) {
                      // 归一化失败不直接判死：原始返回值交给 Kotlin 侧的宽松解析兜底
                      globalThis.__lx_lastMusicUrl = {
                        __lxRaw: v,
                        __lxError: String((e && e.message) || e)
                      };
                    }
                    globalThis.__lx_musicUrlDone = true;
                  }, function (e) {
                    globalThis.__lx_lastMusicUrl = { error: String((e && (e.message || e)) || e) };
                    globalThis.__lx_musicUrlDone = true;
                  });
                })();
            """.trimIndent()
            ctx.evaluate(call)

            val pump = "if (globalThis.__lx_tickTimers) globalThis.__lx_tickTimers();"
            val readSlot = "JSON.stringify(globalThis.__lx_lastMusicUrl === undefined ? null : globalThis.__lx_lastMusicUrl)"
            val deadline = System.currentTimeMillis() + timeoutMs
            var last = ""
            while (System.currentTimeMillis() < deadline) {
                evalString(ctx, pump)
                val v = evalString(ctx, readSlot)
                if (v != null) last = v
                // 槽被写过（成功或 error）就收工，别白等满超时
                val done = evalString(ctx, "globalThis.__lx_musicUrlDone ? '1' : '0'")
                if (done == "1" || (last.isNotEmpty() && last != "{}" && last != "null")) break
                pumpSleep(80)
            }
            parseMusicUrlResult(last)
        } catch (e: Exception) {
            LxResult.Err("request_fail", (e.message ?: e.toString()))
        }
    }

    /**
     * 脚本是否具备取流能力：
     * - 注册了 lx.on('request') 的 handler，或
     * - 脚本自己导出了 module.exports.musicUrl（desktop/CeruMusic 风格）
     *
     * adapter 自己装的兜底 musicUrl 不算（那是桥，不是脚本的实现）。
     */
    private fun hasUsableHandler(ctx: QuickJSContext): Boolean = readCapability(ctx).usable

    /** 宽松解析归一化结果：url / headers 都按嵌套 + 数组递归提取。 */
    private fun parseMusicUrlResult(raw: String): LxResult<LxMusicUrl> {
        if (raw.isBlank() || raw == "{}" || raw == "null") {
            return LxResult.Err("timeout", "musicUrl timeout or empty")
        }
        val trimmed = raw.trim()
        // 裸 http 字符串（JSON 字符串或纯字符串）
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            val url = trimmed.trim('"').trim()
            return if (url.startsWith("http://") || url.startsWith("https://")) {
                LxResult.Ok(LxMusicUrl(url))
            } else {
                LxResult.Err("empty", raw)
            }
        }
        return try {
            val decoded = parseLoose(trimmed)
            val obj = decoded as? JSONObject
            // JS 侧归一化失败时会把原始返回值塞进 __lxRaw，交给这里的宽松解析兜底
            if (obj != null && obj.has("__lxRaw")) {
                val rawValue = obj.opt("__lxRaw")
                val url = findUrl(rawValue, 0)
                if (url.isBlank()) {
                    val reason = obj.optString("__lxError", "").ifBlank { "脚本没有返回播放地址" }
                    return LxResult.Err("script_error", reason)
                }
                return LxResult.Ok(LxMusicUrl(url = url, headers = findHeaders(rawValue, 0)))
            }
            val err = obj?.optString("error", "").orEmpty()
            if (err.isNotBlank()) return LxResult.Err("script_error", err)
            // 归一化结果里的 data.type / quality / type / format / level 只用于找 url 时跳过，
            // LxMusicUrl 本身不携带音质（音质由调用方 LxQuality 决定）。
            val url = findUrl(decoded, 0)
            if (url.isBlank()) LxResult.Err("empty", raw)
            else LxResult.Ok(LxMusicUrl(url = url, headers = findHeaders(decoded, 0)))
        } catch (e: Exception) {
            LxResult.Err("parse_fail", e.message ?: raw)
        }
    }

    /** 把「JSON 字符串 / 多重包裹的字符串」解析成 JSONObject/JSONArray/String，失败原样返回。 */
    private fun parseLoose(v: Any?): Any? {
        if (v !is String) return v
        val t = v.trim()
        if (t.isEmpty()) return t
        // 只有 JSON 结构（对象 / 数组 / 字符串字面量）才交给 JSONTokener。
        // org.json 的 nextValue() 把 '/' 当作分隔符，直接传 "http://host/x.mp3"
        // 会被截成 "http:"，后续再也不是合法 URL，导致明明拿到直链却判成 empty。
        val first = t.firstOrNull()
        if (first != '{' && first != '[' && first != '"' && first != '\'') return t
        return try {
            JSONTokener(t).nextValue()
        } catch (_: Exception) {
            t
        }
    }

    private fun findUrl(v: Any?, depth: Int): String {
        if (v == null || depth > 8) return ""
        // 明文直链先原样判定，不要先过 JSONTokener（'/' 会被当分隔符截断）
        if (v is String) {
            val direct = v.trim()
            if (direct.startsWith("http://") || direct.startsWith("https://")) return direct
        }
        val value = parseLoose(v)
        if (value is String) {
            val t = value.trim()
            return if (t.startsWith("http://") || t.startsWith("https://")) t else ""
        }
        if (value is JSONArray) {
            for (i in 0 until value.length()) {
                val u = findUrl(value.opt(i), depth + 1)
                if (u.isNotEmpty()) return u
            }
            return ""
        }
        if (value is JSONObject) {
            for (k in URL_KEYS) {
                val u = findUrl(value.opt(k), depth + 1)
                if (u.isNotEmpty()) return u
            }
            for (k in NESTED_KEYS) {
                val u = findUrl(value.opt(k), depth + 1)
                if (u.isNotEmpty()) return u
            }
        }
        return ""
    }

    private fun findHeaders(v: Any?, depth: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (v == null || depth > 8) return out
        val value = parseLoose(v)
        if (value is JSONObject) {
            for (k in HEADER_KEYS) {
                val h = value.optJSONObject(k) ?: continue
                val keys = h.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val s = h.optString(key, "").trim()
                    if (s.isNotEmpty()) out[key] = s
                }
            }
            for (k in NESTED_KEYS) out.putAll(findHeaders(value.opt(k), depth + 1))
        } else if (value is JSONArray) {
            for (i in 0 until value.length()) out.putAll(findHeaders(value.opt(i), depth + 1))
        }
        return out
    }

    /**
     * 脚本是否注册了 lx.on('request') 的 handler。
     *
     * 这是「能不能取流」的第一条件（另一个条件是发过 inited），
     * 自检报告用它帮用户定位「脚本没被识别 / 脚本没接上取流」。
     */
    suspend fun hasRequestHandler(): Boolean = withContext(jsDispatcher) {
        val ctx = ctxRef.get() ?: return@withContext false
        evalString(
            ctx,
            "globalThis.__lx_hasRequestHandler && globalThis.__lx_hasRequestHandler() ? '1' : '0'",
        ) == "1"
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
                evalString(ctx, pump)
                val v = evalString(ctx, "JSON.stringify(globalThis.__lx_lastResults || {})")
                if (v != null) last = v
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
     * 请求歌词。
     *
     * 对齐 Mint-Music 的 `_getLyricWithHandler`：直接派发 request handler，
     * **不**走 `module.exports.getLyric` 的 actions 声明校验 ——
     * 很多脚本实现了歌词却没在 inited 里声明 actions，走声明校验会直接判空。
     * action 依次尝试 lyric → musicLyric → getLyric。
     */
    suspend fun requestLyric(
        platform: LxPlatform,
        musicInfo: LxMusicInfo,
        timeoutMs: Long = 12_000,
    ): LxResult<String> = withContext(jsDispatcher) {
        val ctx = ctxRef.get() ?: return@withContext LxResult.Err("no_ctx", "runtime not inited")
        if (!hasUsableHandler(ctx)) {
            return@withContext LxResult.Err("no_handler", "LX 音源未注册 request 事件")
        }
        // 洛雪 handler 期望 info 里既带 musicInfo，也把 musicInfo 的字段平铺进来
        val info = JSONObject()
        .put("musicInfo", musicInfo.toJson())
        .put("type", "lrc")
        musicInfo.toJson().let { flat ->
            flat.keys().forEach { key ->
                if (key != "musicInfo" && key != "type") {
                    info.put(key, flat.opt(key))
                }
            }
        }
        for (action in listOf("lyric", "musicLyric", "getLyric")) {
            val r = request(action, platform, info, timeoutMs)
            if (r is LxResult.Ok) {
                val text = r.value
                if (text.isNotBlank() && text != "null" && text != "undefined") {
                    return@withContext r
                }
            }
        }
        LxResult.Err("no_result", "脚本没有返回歌词")
    }

    /**
     * 请求封面。
     *
     * 对齐 Mint-Music 的 `getPic`：action 依次尝试 musicPic → pic。
     */
    suspend fun requestPic(
        platform: LxPlatform,
        musicInfo: LxMusicInfo,
        timeoutMs: Long = 12_000,
    ): LxResult<String> = withContext(jsDispatcher) {
        val ctx = ctxRef.get() ?: return@withContext LxResult.Err("no_ctx", "runtime not inited")
        if (!hasUsableHandler(ctx)) {
            return@withContext LxResult.Err("no_handler", "LX 音源未注册 request 事件")
        }
        val info = JSONObject().put("musicInfo", musicInfo.toJson())
        for (action in listOf("musicPic", "pic")) {
            val r = request(action, platform, info, timeoutMs)
            if (r is LxResult.Ok) {
                val text = r.value.trim()
                if (text.isNotEmpty() && text != "null" && text != "undefined") {
                    return@withContext r
                }
            }
        }
        LxResult.Err("no_result", "脚本没有返回封面")
    }

    /**
     * 按歌名搜索，返回候选列表（供 LxIdMatcher 模糊匹配）。
     * mobile 契约：action "search" + info.searchParam{keyword,page,typePage}。
     * desktop/CeruMusic 契约见 [searchDesktop]，由 LxSourceRepository 依次尝试两种。
     */
    suspend fun search(
        platform: LxPlatform,
        keyword: String,
        timeoutMs: Long = 12_000,
    ): LxResult<List<LxIdMatcher.Candidate>> = withContext(jsDispatcher) {
        val ctx = ctxRef.get() ?: return@withContext LxResult.Err("no_ctx", "runtime not inited")
        val info = JSONObject()
            .put("searchParam", JSONObject()
                .put("keyword", keyword)
                .put("page", 1)
                .put("typePage", 1))
        searchOnce(ctx, "search", info, platform.code, timeoutMs)
    }

    /** desktop/CeruMusic 契约：action "musicSearch" + info{keyword,page,limit,pagesize}。 */
    suspend fun searchDesktop(
        platform: LxPlatform,
        keyword: String,
        timeoutMs: Long = 12_000,
    ): LxResult<List<LxIdMatcher.Candidate>> = withContext(jsDispatcher) {
        val ctx = ctxRef.get() ?: return@withContext LxResult.Err("no_ctx", "runtime not inited")
        val info = JSONObject()
            .put("keyword", keyword)
            .put("page", 1)
            .put("limit", 30)
            .put("pagesize", 30)
            .put("typePage", 1)
        searchOnce(ctx, "musicSearch", info, platform.code, timeoutMs)
    }

    private fun searchOnce(
        ctx: QuickJSContext,
        action: String,
        info: JSONObject,
        sourceCode: String,
        timeoutMs: Long,
    ): LxResult<List<LxIdMatcher.Candidate>> {
        return try {
            // desktop/CeruMusic 脚本只导出 musicUrl、不注册 lx.on('request')，
            // 没有 handler 就直接失败，别白等满超时（由 locator 兜底定位）
            val hasHandler = evalString(
                ctx, "globalThis.__lx_hasRequestHandler && globalThis.__lx_hasRequestHandler() ? '1' : '0'"
            )
            if (hasHandler != "1") {
                return LxResult.Err("no_handler", "no request handler for $action")
            }
            val payload = JSONObject()
                .put("action", action)
                .put("source", sourceCode)
                .put("info", info)
                .toString()
            ctx.evaluate("globalThis.__lx_search_req = JSON.parse(${jsStringLiteral(payload)});")
            ctx.evaluate("globalThis.__lx_search_result = null;")
            ctx.evaluate(
                "try { globalThis.__lx_dispatch(globalThis.__lx_search_req); } " +
                    "catch (e) { globalThis.__lx_search_result = { error: String(e) }; }"
            )
            val pump = "if (globalThis.__lx_tickTimers) globalThis.__lx_tickTimers();"
            val deadline = System.currentTimeMillis() + timeoutMs
            var last = ""
            while (System.currentTimeMillis() < deadline) {
                evalString(ctx, pump)
                val v = evalString(ctx, "JSON.stringify(globalThis.__lx_search_result || {})")
                if (v != null) last = v
                if (last.isNotEmpty() && last != "{}") break
                pumpSleep(100)
            }
            if (last.isEmpty() || last == "{}") {
                return LxResult.Err("timeout", "$action timeout or empty")
            }
            val errorMessage = runCatching { JSONObject(last).optString("error") }.getOrNull().orEmpty()
            if (errorMessage.isNotBlank()) {
                return LxResult.Err("script_error", errorMessage)
            }
            LxResult.Ok(parseSearchCandidates(last, sourceCode))
        } catch (e: Exception) {
            LxResult.Err("search_fail", (e.message ?: e.toString()))
        }
    }

    private fun parseSearchCandidates(text: String, sourceCode: String): List<LxIdMatcher.Candidate> {
        val list = extractResultList(parseLoose(text)) ?: return emptyList()
        val out = mutableListOf<LxIdMatcher.Candidate>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val songmid = extractSongId(item, sourceCode)
            if (songmid.isBlank()) continue
            val title = firstNonBlank(
                item.optString("name", ""),
                item.optString("songname", ""),
                item.optString("title", ""),
                item.optString("songName", ""),
            )
            val singer = extractArtist(item)
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
            val picUrl = extractPicUrl(item, sourceCode)
            val album = firstNonBlank(
                item.optString("albumName", ""),
                item.optJSONObject("album")?.optString("name", "").orEmpty(),
                item.optString("album", ""),
                item.optString("album_name", ""),
            )
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

    /**
     * 从返回体里剥出候选列表。
     *
     * 信封 key 顺序对齐 Mint-Music 的 `_parsePluginSearchResult`：
     * list → songs → items → records → data → result → body。
     * 社区脚本把这几个名字混着用，只认一个必然漏掉一半脚本。
     */
    private fun extractResultList(decoded: Any?): JSONArray? {
        var node: Any? = decoded
        var guard = 0
        while (guard++ < 5) {
            if (node is JSONArray) return node
            val obj = node as? JSONObject ?: return null
            var matched = false
            for (key in SEARCH_ENVELOPE_KEYS) {
                val value = obj.opt(key)
                if (value is JSONArray) return value
                if (value is JSONObject) {
                    node = value
                    matched = true
                    break
                }
            }
            if (!matched) return null
        }
        return null
    }

    /**
     * 按平台提取歌曲 id。
     *
     * 洛雪协议里各平台的 id 落在不同字段，取错字段脚本就换不出地址：
     * - wy → songId / songmid
     * - kg → hash（FileHash）
     * - kw → rid
     * - tx → mid（strMediaMid）
     * - mg → copyrightId
     */
    private fun extractSongId(item: JSONObject, sourceCode: String): String {
        fun v(key: String): String = item.optString(key, "").trim()
        val byPlatform = when (sourceCode.lowercase()) {
            "wy" -> listOf(v("songmid"), v("songId"), v("id"), v("hash"))
            "kg" -> listOf(v("hash"), v("FileHash"), v("songmid"), v("id"))
            "kw" -> listOf(v("rid"), v("songmid"), v("hash"), v("id"))
            "tx" -> listOf(v("mid"), v("strMediaMid"), v("songmid"), v("id"))
            "mg" -> listOf(v("copyrightId"), v("songmid"), v("id"), v("songId"))
            else -> listOf(v("songmid"), v("hash"), v("rid"), v("mid"), v("copyrightId"), v("id"))
        }
        return byPlatform.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    /** 歌手字段兼容：singer > artist（数组用 / 连接）> artists[].name */
    private fun extractArtist(item: JSONObject): String {
        fun clean(raw: String): String =
            raw.split(",", "/", "&", "、").first().trim()
        val singer = item.optString("singer", "").trim()
        if (singer.isNotEmpty()) return clean(singer)
        val artist = item.optString("artist", "").trim()
        if (artist.isNotEmpty()) return clean(artist)
        val artists = item.optJSONArray("artists")
        if (artists != null && artists.length() > 0) {
            val names = mutableListOf<String>()
            for (i in 0 until artists.length()) {
                val name = artists.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
                if (name.isNotEmpty()) names.add(name)
            }
            if (names.isNotEmpty()) return names.joinToString("/")
        }
        return ""
    }

    /** 封面字段兼容（对齐 Mint 的 _parsePluginSearchResult 条目字段表） */
    private fun extractPicUrl(item: JSONObject, sourceCode: String): String? {
        val direct = listOf(
            "picUrl", "pic", "cover", "img", "albumImg", "coverImg",
            "pic_id", "img3", "img2", "img1",
        )
        for (key in direct) {
            val value = item.optString(key, "").trim()
            if (value.isNotEmpty()) {
                return when {
                    // 网易云只有 pic_id：拼出标准封面地址
                    key == "pic_id" -> "https://p3.music.126.net/${value}/${value}_300x300.jpg"
                    // 咪咕的 img3/img2/img1 是裸文件名
                    key.startsWith("img") && sourceCode.equals("mg", true) ->
                        "http://d.musicapp.migu.cn/${value}"
                    else -> value
                }
            }
        }
        val album = item.optJSONObject("album")
        if (album != null) {
            for (key in listOf("picUrl", "pic", "cover", "img")) {
                val value = album.optString(key, "").trim()
                if (value.isNotEmpty()) return value
            }
        }
        val al = item.optJSONObject("al")
        if (al != null) {
            val value = al.optString("picUrl", "").trim()
            if (value.isNotEmpty()) return value
        }
        // 酷我：只有 rid，封面要按 artistpicserver 规则拼
        if (sourceCode.equals("kw", true)) {
            val rid = item.optString("rid", "").trim()
            if (rid.isNotEmpty()) {
                return "http://artistpicserver.kuwo.cn/pic.web?rid=$rid&size=300"
            }
        }
        return null
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /**
     * 解析 inited 声明（按洛雪插件契约）：
     * - sources 的 key 原样保留（宿主按 key 匹配平台）
     * - 音质取 qualitys -> qualities -> types，缺省 ['128k','320k','flac']
     * - actions 取 source 自己的 actions -> 顶层 sourceActions -> 顶层 actions，
     *   缺省 ['musicUrl']，一律转小写（比较时大小写不敏感）
     * - 兼容旧形态 `"kw": ["128k", ...]`
     */
    private fun parseInited(
        text: String,
        hasRequestHandler: Boolean = false,
        hasScriptMusicUrl: Boolean = false,
    ): LxSourceInfo {
        val obj = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        val root = obj.optJSONObject("data")?.takeIf { it.has("sources") } ?: obj
        val globalActions = readStringList(root.optJSONArray("actions") ?: obj.optJSONArray("actions"))
            ?.map { it.lowercase() }
        val declaredActions = obj.optJSONObject("sourceActions") ?: root.optJSONObject("sourceActions")
        val sources = mutableMapOf<String, List<String>>()
        val sourceNames = mutableMapOf<String, String>()
        val sourceActions = mutableMapOf<String, List<String>>()
        val srcObj = root.optJSONObject("sources") ?: obj.optJSONObject("sources")
        if (srcObj != null) {
            val keys = srcObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val entry = srcObj.optJSONObject(k)
                // 旧版形态："kw": ["128k", ...]；新版形态："kw": { qualitys/qualities/types: [...] }
                val arr = srcObj.optJSONArray(k)
                    ?: entry?.optJSONArray("qualitys")
                    ?: entry?.optJSONArray("qualities")
                    ?: entry?.optJSONArray("types")
                val list = mutableListOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val code = arr.optString(i)
                        if (code.isNotBlank()) list.add(code)
                    }
                } else if (entry != null) {
                    // types 也可能是 {"128k": {...}} 这样的对象，取 key 作为音质档
                    val typesObj = entry.optJSONObject("types")
                        ?: entry.optJSONObject("qualitys")
                        ?: entry.optJSONObject("qualities")
                    if (typesObj != null) {
                        val qk = typesObj.keys()
                        while (qk.hasNext()) {
                            val code = qk.next()
                            if (code.isNotBlank()) list.add(code)
                        }
                    }
                }
                if (list.isEmpty()) list.addAll(DEFAULT_QUALITIES)
                sources[k] = list
                entry?.optString("name", "")?.trim()?.takeIf { it.isNotBlank() }?.let { sourceNames[k] = it }
                val actions = readStringList(entry?.optJSONArray("actions"))
                    ?: readStringList(declaredActions?.optJSONArray(k))
                    ?: globalActions
                    ?: listOf("musicUrl")
                sourceActions[k] = actions.map { it.trim().lowercase() }
            }
        }
        return LxSourceInfo(
            status = obj.optBoolean("status", true),
            env = obj.optString("env", "mobile"),
            sources = sources,
            sourceActions = sourceActions,
            sourceNames = sourceNames,
            actions = globalActions ?: emptyList(),
            alert = if (obj.has("alert") && !obj.isNull("alert")) obj.optString("alert") else null,
            hasRequestHandler = hasRequestHandler,
            hasScriptMusicUrl = hasScriptMusicUrl,
            // 脚本能取流但没声明 sources：对所有平台都放行（对齐 Mint）
            fallbackAllSources = sources.isEmpty() && (hasRequestHandler || hasScriptMusicUrl),
        )
    }

    private fun readStringList(array: JSONArray?): List<String>? {
        if (array == null) return null
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) out.add(value)
        }
        return out.ifEmpty { null }
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
            val delayMs = readArg(args, 1).toLongOrNull() ?: 0L
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
            val id = readArg(args, 0).toIntOrNull()
            if (id != null) schedulerMap.remove(id)?.cancel(false)
            null
        })
        timerObj.setProperty("setInterval", JSCallFunction { args ->
            val periodMs = readArg(args, 1).toLongOrNull() ?: 0L
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
            val id = readArg(args, 0).toIntOrNull()
            if (id != null) schedulerMap.remove(id)?.cancel(false)
            null
        })
        ctx.getGlobalObject().setProperty("__lx_native_timers", timerObj)

        // crypto 桥：全部走 LxCrypto 的「base64 进 / base64 出」柔性函数，
        // 修掉旧实现 aesDecrypt 只接 3 参、JS 却传 1 参的硬 bug。
        cryptoObj.setProperty("md5", JSCallFunction { args -> LxCrypto.md5(readArg(args, 0)) })
        cryptoObj.setProperty("aesEncrypt", JSCallFunction { args ->
            LxCrypto.aesEncryptFlexible(readArg(args, 0), readArg(args, 1), readArg(args, 2), readArg(args, 3))
        })
        cryptoObj.setProperty("aesDecrypt", JSCallFunction { args ->
            LxCrypto.aesDecryptFlexible(readArg(args, 0), readArg(args, 1), readArg(args, 2), readArg(args, 3))
        })
        cryptoObj.setProperty("rsaEncrypt", JSCallFunction { args ->
            LxCrypto.rsaEncryptFlexible(readArg(args, 0), readArg(args, 1))
        })
        cryptoObj.setProperty("randomBytes", JSCallFunction { args ->
            LxCrypto.base64Encode(LxCrypto.randomBytes(readArg(args, 0).toIntOrNull() ?: 16))
        })
        cryptoObj.setProperty("b64enc", JSCallFunction { args ->
            LxCrypto.base64Encode(readArg(args, 0).toByteArray())
        })
        cryptoObj.setProperty("b64dec", JSCallFunction { args ->
            String(LxCrypto.base64Decode(readArg(args, 0)))
        })
        ctx.getGlobalObject().setProperty("__lx_native_crypto_bridge", cryptoObj)

        // zlib 桥（新增）：preload 的 lx.utils.zlib 转发到这里
        val zlibObj = ctx.createNewJSObject()
        zlibObj.setProperty("inflate", JSCallFunction { args -> LxCrypto.inflateFlexible(readArg(args, 0)) })
        zlibObj.setProperty("deflate", JSCallFunction { args -> LxCrypto.deflateFlexible(readArg(args, 0)) })
        ctx.getGlobalObject().setProperty("__lx_native_zlib_bridge", zlibObj)

        val httpObj = ctx.createNewJSObject()
        httpObj.setProperty("request", JSCallFunction { args ->
            runBlocking {
                // preload 传 JSON 字符串 {method, url, headers, body, form, timeoutMs,
                // followRedirects, maxRedirects}；旧调用 (method, url) 也兼容理解为两参数
                val rawOption = readArg(args, 0)
                val opt = runCatching { JSONObject(rawOption) }.getOrNull()
                val method = opt?.optString("method", "GET")?.takeIf { it.isNotBlank() }
                    ?: readArg(args, 0).ifEmpty { "GET" }
                val url = opt?.optString("url", "") ?: readArg(args, 1)
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
                // 脚本传的 timeout 必须真的生效：洛雪脚本常用 timeout: 3000 做短超时重试
                val timeoutMs = opt?.optLong("timeoutMs", 0L)?.takeIf { it > 0L } ?: 20_000L
                val formObj = opt?.optJSONObject("form")
                val form = if (formObj != null) {
                    val map = mutableMapOf<String, String>()
                    val keys = formObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = formObj.optString(k)
                    }
                    map
                } else {
                    null
                }
                val followRedirects = opt?.optBoolean("followRedirects", true) ?: true
                val maxRedirects = opt?.optInt("maxRedirects", 5) ?: 5
                val r = http.request(
                    method = method,
                    url = url,
                    headers = headers,
                    body = bodyText,
                    timeoutMs = timeoutMs,
                    form = form,
                    followRedirects = followRedirects,
                    maxRedirects = maxRedirects,
                )
                JSONObject().apply {
                    put("statusCode", r.statusCode)
                    put("headers", JSONObject(r.headers))
                    put("body", r.body ?: "")
                    put("bodyBase64", r.bodyBase64 ?: "")
                    put("bytes", r.bodySize)
                    put("error", r.error ?: "")
                    put("isTimeout", r.isTimeout)
                }.toString()
            }
        })
        ctx.getGlobalObject().setProperty("__lx_native_http", httpObj)
    }

    override fun close() {
        try { ctxRef.get()?.destroy() } catch (_: Exception) {}
        runCatching { executor.shutdown() }
    }
}
