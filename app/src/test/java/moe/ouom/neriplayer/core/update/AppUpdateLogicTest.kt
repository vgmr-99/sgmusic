package moe.ouom.neriplayer.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检测纯逻辑单测：语义化版本比较与 GitHub Release 响应解析。
 */
class AppUpdateLogicTest {

    // ---------- parseSemanticVersion ----------

    @Test
    fun `parse handles v prefix and numeric core`() {
        assertEquals(listOf(1, 4, 4), AppUpdateLogic.parseSemanticVersion("1.4.4"))
        assertEquals(listOf(1, 4, 4), AppUpdateLogic.parseSemanticVersion("v1.4.4"))
        assertEquals(listOf(1, 4, 10), AppUpdateLogic.parseSemanticVersion("V1.4.10"))
    }

    @Test
    fun `parse strips pre-release and build suffix`() {
        assertEquals(listOf(1, 4, 5), AppUpdateLogic.parseSemanticVersion("1.4.5-beta"))
        assertEquals(listOf(1, 4, 5), AppUpdateLogic.parseSemanticVersion("1.4.5+build.7"))
    }

    @Test
    fun `parse rejects malformed versions`() {
        assertNull(AppUpdateLogic.parseSemanticVersion(""))
        assertNull(AppUpdateLogic.parseSemanticVersion("abc"))
        assertNull(AppUpdateLogic.parseSemanticVersion("1.4.x"))
        assertNull(AppUpdateLogic.parseSemanticVersion("1..4"))
    }

    // ---------- isVersionNewer ----------

    @Test
    fun `numeric segment comparison not lexicographic`() {
        // 字符串比较会得出 "1.4.9" > "1.4.10" 的错误结论
        assertTrue(AppUpdateLogic.isVersionNewer("1.4.10", "1.4.9"))
        assertFalse(AppUpdateLogic.isVersionNewer("1.4.9", "1.4.10"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(AppUpdateLogic.isVersionNewer("1.4.4", "1.4.4"))
        assertFalse(AppUpdateLogic.isVersionNewer("v1.4.4", "1.4.4"))
    }

    @Test
    fun `shorter version padded with zeros`() {
        assertTrue(AppUpdateLogic.isVersionNewer("1.5", "1.4.9"))
        assertTrue(AppUpdateLogic.isVersionNewer("2.0", "1.9.9"))
        assertFalse(AppUpdateLogic.isVersionNewer("1.4", "1.4.0"))
    }

    @Test
    fun `unparseable input never reports newer`() {
        assertFalse(AppUpdateLogic.isVersionNewer("garbage", "1.4.4"))
        assertFalse(AppUpdateLogic.isVersionNewer("9.9.9", "garbage"))
    }

    // ---------- parseGitHubRelease ----------

    @Test
    fun `parses release with apk asset`() {
        val json = """
            {
              "tag_name": "v1.4.4",
              "draft": false,
              "prerelease": false,
              "body": "拾光音乐 v1.4.4 正式版\n- 修复若干问题",
              "html_url": "https://github.com/vgmr-99/sgmusic/releases/tag/v1.4.4",
              "assets": [
                {
                  "name": "Timbre-1.4.4.apk",
                  "browser_download_url": "https://github.com/vgmr-99/sgmusic/releases/download/v1.4.4/Timbre-1.4.4.apk"
                },
                {
                  "name": "sources.zip",
                  "browser_download_url": "https://github.com/vgmr-99/sgmusic/archive/sources.zip"
                }
              ]
            }
        """.trimIndent()
        val info = AppUpdateLogic.parseGitHubRelease(json, "1.4.3")!!
        assertEquals("v1.4.4", info.latestVersion)
        assertEquals("1.4.3", info.currentVersion)
        assertTrue(info.releaseNotes.contains("正式版"))
        assertEquals(
            "https://github.com/vgmr-99/sgmusic/releases/download/v1.4.4/Timbre-1.4.4.apk",
            info.apkUrl
        )
        assertTrue(info.releaseUrl.endsWith("/v1.4.4"))
    }

    @Test
    fun `apk url null when no matching asset`() {
        val json = """
            {
              "tag_name": "v1.5.0",
              "draft": false,
              "body": "",
              "html_url": "https://github.com/vgmr-99/sgmusic/releases/tag/v1.5.0",
              "assets": [ { "name": "sources.zip", "browser_download_url": "https://x/s.zip" } ]
            }
        """.trimIndent()
        val info = AppUpdateLogic.parseGitHubRelease(json, "1.4.4")!!
        assertNull(info.apkUrl)
    }

    @Test
    fun `draft release rejected`() {
        val json = """{"tag_name":"v2.0.0","draft":true,"body":"","html_url":"https://x","assets":[]}"""
        assertNull(AppUpdateLogic.parseGitHubRelease(json, "1.4.4"))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(AppUpdateLogic.parseGitHubRelease("not json at all", "1.4.4"))
        assertNull(AppUpdateLogic.parseGitHubRelease("""{"no_tag":1}""", "1.4.4"))
    }
}
