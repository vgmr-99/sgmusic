package moe.ouom.neriplayer.ui.screen.about

import moe.ouom.neriplayer.core.update.AppUpdateCheckResult
import moe.ouom.neriplayer.core.update.AppUpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 回归测试：检查结果的三种互斥状态不能混用同一标题/正文。 */
class AppUpdateUiMappingTest {
    private val info = AppUpdateInfo(
        latestVersion = "1.4.5",
        currentVersion = "1.4.4",
        releaseNotes = "release notes",
        apkUrl = null,
        releaseUrl = "https://example.test/release",
    )

    @Test
    fun `available result maps to Available with info`() {
        val mapped = mapCheckResult(AppUpdateCheckResult.Available(info))
        assertEquals(AppUpdateUiState.Available, mapped.state)
        assertSame(info, mapped.info)
    }

    @Test
    fun `up to date result maps to UpToDate without info`() {
        val mapped = mapCheckResult(AppUpdateCheckResult.UpToDate)
        assertEquals(AppUpdateUiState.UpToDate, mapped.state)
        assertNull(mapped.info)
    }

    @Test
    fun `failed result maps to CheckFailed without info`() {
        val mapped = mapCheckResult(AppUpdateCheckResult.Failed)
        assertEquals(AppUpdateUiState.CheckFailed, mapped.state)
        assertNull(mapped.info)
    }
}
