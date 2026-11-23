package moe.ouom.neriplayer.platform.youtube.api.potoken

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归测试：落盘 PoToken 缓存的校验与清洗（冷启动秒播修复）。 */
class YouTubePoTokenStoreTest {

    private val now = 1_000_000L
    private val fingerprint = "fp-current"

    private fun cache(
        version: Int = PO_TOKEN_CACHE_VERSION_CURRENT,
        fp: String = fingerprint,
        tokens: Map<String, PersistedPoTokenEntry> = mapOf(
            "a" to PersistedPoTokenEntry("tok-a", now + 10_000L)
        ),
        index: Map<String, String> = mapOf("v1|host|fp-current" to "a"),
        session: Map<String, String> = emptyMap()
    ) = PersistedPoTokenCache(version, fp, now, tokens, index, session)

    @Test
    fun `keeps valid cache for current identity`() {
        val result = sanitizePersistedPoTokenCache(cache(), fingerprint, now, 16)
        assertEquals(1, result!!.tokens.size)
        assertEquals("tok-a", result.tokens["a"]?.token)
    }

    @Test
    fun `rejects wrong version`() {
        assertNull(sanitizePersistedPoTokenCache(cache(version = 99), fingerprint, now, 16))
    }

    @Test
    fun `rejects mismatched or blank fingerprint`() {
        assertNull(sanitizePersistedPoTokenCache(cache(fp = "fp-old"), fingerprint, now, 16))
        assertNull(sanitizePersistedPoTokenCache(cache(fp = ""), fingerprint, now, 16))
    }

    @Test
    fun `drops expired and blank tokens`() {
        val result = sanitizePersistedPoTokenCache(
            cache(
                tokens = mapOf(
                    "fresh" to PersistedPoTokenEntry("tok", now + 1L),
                    "expired" to PersistedPoTokenEntry("tok", now - 1L),
                    "blank" to PersistedPoTokenEntry("", now + 10_000L)
                ),
                index = mapOf("k-fresh" to "fresh", "k-expired" to "expired", "k-blank" to "blank")
            ),
            fingerprint, now, 16
        )!!
        assertEquals(setOf("fresh"), result.tokens.keys)
        assertEquals(setOf("k-fresh"), result.cacheKeyIndex.keys)
    }

    @Test
    fun `cleans session index pointing to dropped tokens`() {
        val result = sanitizePersistedPoTokenCache(
            cache(
                tokens = mapOf("a" to PersistedPoTokenEntry("tok-a", now + 10_000L)),
                session = mapOf("s-valid" to "a", "s-stale" to "gone")
            ),
            fingerprint, now, 16
        )!!
        assertEquals(mapOf("s-valid" to "a"), result.sessionScopedCacheKeys)
    }

    @Test
    fun `null when all tokens unusable`() {
        assertNull(
            sanitizePersistedPoTokenCache(
                cache(tokens = mapOf("x" to PersistedPoTokenEntry("tok", now - 1L))),
                fingerprint, now, 16
            )
        )
    }

    @Test
    fun `caps cache size`() {
        val many = (0 until 20).associate { i ->
            "k$i" to PersistedPoTokenEntry("tok$i", now + 10_000L)
        }
        val result = sanitizePersistedPoTokenCache(
            cache(tokens = many, index = emptyMap()),
            fingerprint, now, 5
        )!!
        assertEquals(5, result.tokens.size)
        assertTrue(result.tokens.keys.all { it.startsWith("k") })
    }
}
