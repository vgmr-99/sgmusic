package moe.ouom.neriplayer.core.startup

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupStageResolverTest {
    @Test
    fun `uses loading stage while disclaimer state is unknown`() {
        assertEquals(
            StartupStage.Loading,
            StartupStageResolver.resolve(
                disclaimerAccepted = null,
                startupOnboardingCompleted = null
            )
        )
    }

    @Test
    fun `requires disclaimer before onboarding`() {
        // 拾光音乐：免责声明不再拦截启动，false 也直达主界面/引导
        assertEquals(
            StartupStage.Main,
            StartupStageResolver.resolve(
                disclaimerAccepted = false,
                startupOnboardingCompleted = true
            )
        )
    }

    @Test
    fun `uses loading stage while onboarding state is unknown`() {
        assertEquals(
            StartupStage.Loading,
            StartupStageResolver.resolve(
                disclaimerAccepted = true,
                startupOnboardingCompleted = null
            )
        )
    }

    @Test
    fun `routes accepted users to onboarding until it is complete`() {
        assertEquals(
            StartupStage.Onboarding,
            StartupStageResolver.resolve(
                disclaimerAccepted = true,
                startupOnboardingCompleted = false
            )
        )
    }

    @Test
    fun `routes pending disclaimer acceptance without waiting for storage echo`() {
        // 拾光音乐：pending 也直接按 onboarding 状态路由，不再等免责确认
        assertEquals(
            StartupStage.Loading,
            StartupStageResolver.resolve(
                disclaimerAccepted = false,
                startupOnboardingCompleted = null,
                pendingDisclaimerAccepted = true
            )
        )
        assertEquals(
            StartupStage.Main,
            StartupStageResolver.resolve(
                disclaimerAccepted = false,
                startupOnboardingCompleted = true,
                pendingDisclaimerAccepted = true
            )
        )
    }

    @Test
    fun `routes fully prepared users to main stage`() {
        assertEquals(
            StartupStage.Main,
            StartupStageResolver.resolve(
                disclaimerAccepted = true,
                startupOnboardingCompleted = true
            )
        )
    }
}
