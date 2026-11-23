package moe.ouom.neriplayer.platform.youtube.api.potoken

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.common.io.writeTextAtomically
import moe.ouom.neriplayer.common.logging.NPLogger

internal const val PO_TOKEN_CACHE_VERSION_CURRENT = 1

@Serializable
internal data class PersistedPoTokenEntry(
    val token: String = "",
    val expiresAtMs: Long = 0L
)

/**
 * 落盘的 Web PoToken 缓存。
 *
 * PoToken 与 cookie 同属会话凭据，只写入应用私有目录（filesDir），
 * 并通过 authFingerprint 绑定身份：账号切换后整份缓存失效，避免串号使用上一个身份的 token。
 */
@Serializable
internal data class PersistedPoTokenCache(
    val version: Int = PO_TOKEN_CACHE_VERSION_CURRENT,
    val authFingerprint: String = "",
    val savedAtMs: Long = 0L,
    val tokens: Map<String, PersistedPoTokenEntry> = emptyMap(),
    val cacheKeyIndex: Map<String, String> = emptyMap(),
    val sessionScopedCacheKeys: Map<String, String> = emptyMap()
)

/**
 * 校验并清洗落盘缓存：版本/身份不符、已过期或空白 token 一律丢弃，索引同步清理。
 * 提取为纯函数，便于单测覆盖（不依赖 Android Context）。
 */
internal fun sanitizePersistedPoTokenCache(
    cache: PersistedPoTokenCache,
    currentAuthFingerprint: String,
    nowMs: Long,
    maxSize: Int
): PersistedPoTokenCache? {
    if (cache.version != PO_TOKEN_CACHE_VERSION_CURRENT) return null
    if (cache.authFingerprint.isBlank() || cache.authFingerprint != currentAuthFingerprint) return null
    val usable = cache.tokens.filter { (_, entry) ->
        entry.token.isNotBlank() && entry.expiresAtMs > nowMs
    }
    if (usable.isEmpty()) return null
    val trimmed = if (usable.size > maxSize) {
        usable.toList().take(maxSize).toMap()
    } else {
        usable
    }
    return cache.copy(
        tokens = trimmed,
        cacheKeyIndex = cache.cacheKeyIndex.filterValues { it in trimmed },
        sessionScopedCacheKeys = cache.sessionScopedCacheKeys.filterValues { it in trimmed }
    )
}

/**
 * 把 Web PoToken 缓存留到下次冷启动。
 *
 * 现状：tokenCache 只在内存，TTL 6 小时，进程重建即丢失；匿名态每次冷启动都要重新起
 * WebView 跑 JS 挑战铸 token（十几秒），这是"未登录首播慢"的直接原因。落盘后冷启动
 * 直接命中有效 token，无需 WebView。
 */
internal class YouTubePoTokenStore(
    context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) {
    companion object {
        private const val TAG = "YouTubePoTokenStore"
    }

    private val storeFile: File by lazy {
        File(File(context.filesDir, "youtube"), "po_token_cache.json")
    }

    fun load(
        currentAuthFingerprint: String,
        nowMs: Long,
        maxSize: Int
    ): PersistedPoTokenCache? {
        val file = storeFile
        if (!file.exists()) return null
        val cache = runCatching {
            json.decodeFromString<PersistedPoTokenCache>(file.readText())
        }.onFailure { error ->
            NPLogger.w(TAG, "load failed, dropping cache: ${error.message}")
            runCatching { file.delete() }
        }.getOrNull() ?: return null
        val sanitized = sanitizePersistedPoTokenCache(
            cache = cache,
            currentAuthFingerprint = currentAuthFingerprint,
            nowMs = nowMs,
            maxSize = maxSize
        )
        if (sanitized == null) {
            NPLogger.d(TAG, "discard stale/mismatched cache")
        }
        return sanitized
    }

    fun save(cache: PersistedPoTokenCache) {
        runCatching {
            val file = storeFile
            file.parentFile?.mkdirs()
            file.writeTextAtomically(json.encodeToString(cache))
        }.onFailure { error ->
            NPLogger.w(TAG, "save failed: ${error.message}")
        }
    }

    fun clear() {
        runCatching { storeFile.delete() }
            .onFailure { error -> NPLogger.w(TAG, "clear failed: ${error.message}") }
    }
}
