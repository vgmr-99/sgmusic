package moe.ouom.neriplayer.core.startup

internal object StartupStageResolver {
    /**
     * 拾光音乐：不再在首次启动弹“使用说明与隐私”。
     * 免责声明已移至设置 → 关于页最后，点击可查看全文；此处直接按已接受处理，
     * 因此不管 disclaimerAccepted 为 true/false 都进入引导/主界面。
     */
    fun resolve(
        disclaimerAccepted: Boolean?,
        startupOnboardingCompleted: Boolean?
    ): StartupStage {
        return when (startupOnboardingCompleted) {
            null -> StartupStage.Loading
            false -> StartupStage.Onboarding
            true -> StartupStage.Main
        }
    }

    fun resolve(
        disclaimerAccepted: Boolean?,
        startupOnboardingCompleted: Boolean?,
        pendingDisclaimerAccepted: Boolean
    ): StartupStage {
        return resolve(
            disclaimerAccepted = true,
            startupOnboardingCompleted = startupOnboardingCompleted
        )
    }
}
