package moe.ouom.neriplayer.core.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归测试：YouTube 重定向 Cookie 解析，修复 "Too many follow-up requests: 21"。 */
class RedirectCookieInterceptorTest {

    @Test
    fun `parses plain cookie header`() {
        val result = parseRedirectCookieHeader("VISITOR_INFO1_LIVE=abc123; SOCS=CAI; CONSENT=YES+cb")
        assertEquals("abc123", result["VISITOR_INFO1_LIVE"])
        assertEquals("CAI", result["SOCS"])
        assertEquals("YES+cb", result["CONSENT"])
    }

    @Test
    fun `skips set-cookie attribute segments`() {
        val result = parseRedirectCookieHeader(
            "VISITOR_INFO1_LIVE=abc; Path=/; Domain=.youtube.com; Expires=Wed, 21 Oct 2030 07:28:00 GMT; Secure; HttpOnly; SameSite=None"
        )
        assertEquals("abc", result["VISITOR_INFO1_LIVE"])
        assertEquals(1, result.size)
    }

    @Test
    fun `strips quoted cookie values`() {
        assertEquals("abc", parseRedirectCookieHeader("SESSION=\"abc\"")["SESSION"])
        assertEquals("xyz", parseRedirectCookieHeader("TOKEN='xyz'")["TOKEN"])
    }

    @Test
    fun `handles blank and malformed input`() {
        assertTrue(parseRedirectCookieHeader(null).isEmpty())
        assertTrue(parseRedirectCookieHeader("").isEmpty())
        assertTrue(parseRedirectCookieHeader("no-equals-sign; =novalue").isEmpty())
    }
}
