package moe.ouom.neriplayer.ui.component.playback

import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingActionBarStyle
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingButtonStyle
import moe.ouom.neriplayer.data.model.settings.appearance.NowPlayingProgressStyle

/**
 * 播放页控件样式规格。
 *
 * 借鉴 Halcyon 的做法：**模板（布局）与控件样式彻底解耦**。
 * 三类控件各自独立设置，用户可以自由组合：
 * - 进度条：[NowPlayingProgressStyle] → [NowPlayingProgressShape]
 * - 播放按键：[NowPlayingButtonStyle] → 图标风格 + 按钮形态
 * - 底部功能键：[NowPlayingActionBarStyle] → 工具栏容器形态
 *
 * 本文件只定义"解析规则"（纯数据 + 纯函数），不含任何 Compose / Android 依赖，
 * 便于 JVM 单测锁定行为。
 */

/** 进度条形状。 */
enum class NowPlayingProgressShape {
    /** 波形（保留项目原有波形动画与预测）。 */
    WAVE,

    /** 分段刻度轨道（类似 Halcyon 的分段刻度）。 */
    SEGMENTED,

    /** 粗胶囊轨道 + 胶囊拇指。 */
    CAPSULE
}

/** 控制按钮图标风格。 */
enum class NowPlayingIconStyle {
    /** Material 描边图标（Icons.Outlined.*，项目原有）。 */
    OUTLINED,

    /** Material 实心图标（Icons.Filled.*）。 */
    FILLED
}

/** 播放键容器形态。 */
enum class NowPlayingButtonShape {
    /** 实心圆形播放键（项目原有）。 */
    CIRCLE,

    /** 空心圆环播放键。 */
    RING,

    /** 无容器，纯图标。 */
    FLAT
}

/** 底部功能键容器形态。 */
enum class NowPlayingActionBarShape {
    /** 整块圆角卡片托底（项目原有）。 */
    DOCK,

    /** 图标加文字标签。 */
    LABEL,

    /** 半透明玻璃胶囊，激活项圆形高亮。 */
    GLASS,

    /**
     * 无底座纯图标。
     *
     * 仅在用户关掉「工具栏底座」开关时由 DOCK 降级而来，不是用户可直接选择的款式。
     */
    PLAIN
}

/** 播放按键规格：图标风格 + 播放键容器形态。 */
data class NowPlayingButtonSpec(
    val iconStyle: NowPlayingIconStyle,
    val shape: NowPlayingButtonShape
)

/** 把独立的进度条样式设置项映射为绘制形状。 */
internal fun resolveProgressShape(style: NowPlayingProgressStyle): NowPlayingProgressShape =
    when (style) {
        NowPlayingProgressStyle.WAVE -> NowPlayingProgressShape.WAVE
        NowPlayingProgressStyle.SEGMENTED -> NowPlayingProgressShape.SEGMENTED
        NowPlayingProgressStyle.CAPSULE -> NowPlayingProgressShape.CAPSULE
    }

/** 把独立的播放键样式设置项映射为图标风格与容器形态。 */
internal fun resolveNowPlayingButtonSpec(style: NowPlayingButtonStyle): NowPlayingButtonSpec =
    when (style) {
        NowPlayingButtonStyle.CLASSIC -> NowPlayingButtonSpec(
            iconStyle = NowPlayingIconStyle.OUTLINED,
            shape = NowPlayingButtonShape.CIRCLE
        )

        NowPlayingButtonStyle.RING -> NowPlayingButtonSpec(
            iconStyle = NowPlayingIconStyle.OUTLINED,
            shape = NowPlayingButtonShape.RING
        )

        NowPlayingButtonStyle.FLAT -> NowPlayingButtonSpec(
            iconStyle = NowPlayingIconStyle.OUTLINED,
            shape = NowPlayingButtonShape.FLAT
        )
    }

/** 把独立的功能键样式设置项映射为工具栏容器形态。 */
internal fun resolveNowPlayingActionBarShape(
    style: NowPlayingActionBarStyle
): NowPlayingActionBarShape = when (style) {
    NowPlayingActionBarStyle.DOCK -> NowPlayingActionBarShape.DOCK
    NowPlayingActionBarStyle.LABEL -> NowPlayingActionBarShape.LABEL
    NowPlayingActionBarStyle.GLASS -> NowPlayingActionBarShape.GLASS
}

/** 该形态是否需要绘制外层底座。 */
internal fun NowPlayingActionBarShape.requiresSurface(): Boolean =
    this == NowPlayingActionBarShape.DOCK || this == NowPlayingActionBarShape.GLASS

/** 该形态是否为半透明玻璃胶囊（需要亮描边而不是实色卡片）。 */
internal fun NowPlayingActionBarShape.usesGlassSurface(): Boolean =
    this == NowPlayingActionBarShape.GLASS

/** 该形态是否需要显示文字标签。 */
internal fun NowPlayingActionBarShape.usesLabels(): Boolean =
    this == NowPlayingActionBarShape.LABEL

/**
 * 播放键是否带容器底色。
 *
 * FLAT 形态完全没有底，其余形态都要绘制播放键容器，调用方据此决定是否叠加底色。
 */
internal fun NowPlayingButtonShape.hasContainer(): Boolean = this != NowPlayingButtonShape.FLAT
