package moe.ouom.neriplayer.data.model.navigation

/**
 * 外部请求跳转到指定设置子页的目标。
 *
 * 设置页枚举本身是 app 模块内部类型，而 NeriApp 是公开入口，
 * 因此这里用公开枚举做跨层传递，再由 app 层映射到具体设置页。
 */
enum class SettingsPageTarget {
    /** 拾光音乐：LX 自定义源管理页。 */
    LxSources
}
