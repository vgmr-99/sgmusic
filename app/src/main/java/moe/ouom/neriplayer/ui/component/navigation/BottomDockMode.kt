package moe.ouom.neriplayer.ui.component.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 *
 * File: moe.ouom.neriplayer.ui.component.navigation/BottomDockMode
 * Created: 2026/11/20
 *
 * 底部两栏（迷你播放条 + 底部导航）的形态策略。
 *
 * 交互参照 MeowPlayer：页面向下滚动后两栏从"展开"切到"紧凑"，
 * 向上滚回顶部再展开。两栏必须读同一个形态，否则一个展开一个收起会打架，
 * 所以用 [LocalBottomDockMode] 统一广播。
 *
 * 为什么不用 nestedScroll：LazyColumn / LazyGrid 的滚动在最内层就被
 * scrollable 消费掉了，挂在脚手架层的 NestedScrollConnection 收到的
 * available 恒为 0，永远不会触发。所以改为由各页面用
 * [ReportBottomDockScroll] 主动上报自己的滚动偏移。
 */

/** 底部两栏的形态。 */
internal enum class BottomDockMode {
    /** 展开：迷你播放条显示完整信息，底栏显示全部标签。 */
    EXPANDED,

    /** 紧凑：页面向下滚动后两栏收紧，把屏幕空间让给内容。 */
    COMPACT;

    val isCompact: Boolean get() = this == COMPACT
}

/**
 * 把滚动距离换算成底部两栏的收紧进度：0 = 完全展开，1 = 完全收紧。
 *
 * 进度分两段用，对应参考实现的三种形态：
 *  · 0.0 ~ 0.5：导航栏先收成一颗当前标签的圆钮，迷你条仍在，只是队列按钮淡出；
 *  · 0.5 ~ 1.0：迷你条再收成一颗圆形封面钮，两颗圆钮并排靠右。
 *
 * 用的是首个可见 item 距离视口顶部的**绝对像素位置**，而不是 item 下标或
 * firstVisibleItemScrollOffset：
 *  · 下标在首页这种"多个大 section"的布局里跳得太早（滚到第二个 section
 *    视觉上还在首屏，却已经 index>0）；
 *  · firstVisibleItemScrollOffset 在首个 item 变化时会重置，封面图加载引起的
 *    布局变化也会让它突然跳回小值。
 * 绝对位置是连续的：越往下数值越大，回到顶部自然回到 0。
 *
 * @param firstItemTopPx 首个可见 item 上边缘距视口顶部的距离（像素，向上滚为正）
 * @param threshold 触发收紧的距离（像素）
 */
internal fun resolveBottomDockProgress(
    firstItemTopPx: Int,
    threshold: Int
): Float {
    if (threshold <= 0) return 0f
    val scrolled = -firstItemTopPx
    if (scrolled <= 0) return 0f
    // 走满两个阈值距离才完全收紧
    return (scrolled.toFloat() / (threshold * 2f)).coerceIn(0f, 1f)
}

/** 导航栏那一段的收紧进度：前一半完成。 */
internal fun navCompactProgress(dockProgress: Float): Float =
    (dockProgress / 0.5f).coerceIn(0f, 1f)

/** 迷你条那一段的收紧进度：后一半进行。 */
internal fun miniCompactProgress(dockProgress: Float): Float =
    ((dockProgress - 0.5f) / 0.5f).coerceIn(0f, 1f)

/** 默认收紧阈值：滚动约 96dp 后开始收紧。 */
internal val DefaultBottomDockCompactThreshold: Dp = 32.dp

/**
 * 每翻过一个列表 item 记的滚动量（像素）。
 *
 * 用来给"首个可见 item 的偏移"补上单调性——offset 在跨列大 item 滚出视口时会跳变，
 * 加上 index 贡献的这一段之后，进度就只会平滑递增，不会突然回退。
 */
private const val DockScrollStepPerItemPx = 320


/** 形态的全局广播。两栏与页面读同一个值，保证同步。 */
internal val LocalBottomDockMode = compositionLocalOf { BottomDockMode.EXPANDED }

/**
 * 底部两栏的滚动上报入口。
 *
 * 脚手架层创建，各页面用 [ReportBottomDockScroll] 上报自己的滚动偏移。
 */
internal class BottomDockScrollReporter(
    private val thresholdPx: Int,
    private val onProgressChanged: (Float) -> Unit
) {
    /** 当前接管形态的页面。页面切换时由新页面接管。 */
    private var activeOwner: Any? = null

    /** 接管者的滚动位置，用于判断是否需要重新广播。 */
    private var activeOffsetPx: Int = 0

    /** 当前收紧进度，0 = 展开，1 = 完全收紧。 */
    var progress: Float = 0f
        private set

    /**
     * 当前页面的"回到顶部"动作，由页面在注册滚动上报时提供。
     *
     * 收紧态底栏会收成一颗当前标签的圆钮，点它就回到该页顶部。
     */
    var scrollToTop: (() -> Unit)? = null

    /** 请求回到当前页面顶部。 */
    fun requestScrollToTop() {
        scrollToTop?.invoke()
    }

    /**
     * 上报滚动位置。
     *
     * @param owner 页面标识。同一个页面反复上报用同一个令牌。
     * @param firstItemTopPx 首个可见 item 相对视口顶部的距离
     */
    fun report(owner: Any, firstItemTopPx: Int) {
        // 新页面接管：立刻用它的当前位置刷新形态。
        // 页面变为可见时一定会重组（NavHost 切换可见条目），重组里会重新上报，
        // 所以切 Tab、跳独立路由都能正确重置，不会留着上一个页面的形态。
        if (owner !== activeOwner) {
            activeOwner = owner
            activeOffsetPx = firstItemTopPx
            applyProgress()
            return
        }
        if (firstItemTopPx == activeOffsetPx) return
        activeOffsetPx = firstItemTopPx
        applyProgress()
    }

    private fun applyProgress() {
        val next = resolveBottomDockProgress(activeOffsetPx, thresholdPx)
        if (next == progress) return
        progress = next
        onProgressChanged(next)
    }
}

/**
 * 收紧态圆钮的直径。
 *
 * 展开态的播放栏是 64dp 高、260dp 宽的胶囊，圆钮只有 44dp 时两者放在一起
 * 显得头轻脚重、割裂感明显。收到 56dp（接近胶囊高度）后，
 * 圆钮与播放栏的高度比例接近，视觉上才像一组控件。
 */
internal val CompactDockButtonSize = 56.dp

/**
 * 收紧态圆钮相对底栏底边的抬升量。
 *
 * 圆钮比播放栏矮，两颗要落在同一条水平线上就得让圆钮中心对齐播放栏中心，
 * 于是圆钮整体要抬起来半个身位差（(播放栏高 - 圆钮直径) / 2）。
 */
internal val CompactDockVerticalOffset: Dp =
    (moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayerDefaults.Height - CompactDockButtonSize) / 2

/** 收紧态播放栏与圆钮之间的间距。
 *
 * 12dp 时两者像两块互不相干的零件。收到 8dp 后圆钮几乎贴着播放栏右端，
 * 视觉上是一整组，而不是两个分离的控件。
 */
internal val CompactDockButtonGap = 8.dp

/**
 * 导航栏收紧到「只剩当前标签」的进度分界。
 *
 * 超过这个进度，非当前标签的图标已经淡出（alpha = 0），
 * 导航栏实际就是一颗圆钮，交互语义也随之变成「点它回顶」而不是「切标签」。
 *
 * 之所以要单独拎一个常量：导航栏宽度在这个进度上还有 200dp 出头，
 * 看着没完全收完，但里面的标签已经只剩一个了，
 * 这时候再按「位置反查标签」去响应点击就会命中被挤到右边缘的隐形标签。
 */
internal const val CompactDockItemsFadeSpan = 0.35f

/**
 * 收紧态下点导航栏那颗圆钮，要不要走「回到当前页顶部」。
 *
 * 导航栏一旦收成只剩当前标签，它就不再是「一排可点的标签」，
 * 而是「一颗回到顶部的按钮」——这正是收紧态的意义：
 * 把屏幕空间让给内容，同时留一个一键回到顶部的出口。
 *
 * 早期实现用「进度过半」当阈值，问题是收窄过程中非当前标签还在
 * （只是变成右侧的隐形细条），点下去会误判成别的标签而跳走。
 */
internal fun shouldScrollToTopOnDockTap(compactProgress: Float): Boolean =
    compactProgress >= CompactDockItemsFadeSpan

/** 底栏的水平边距：展开态胶囊和收紧态圆钮沿用同一套。 */
internal val DockBarHorizontalMargin = 16.dp

/** 底栏距屏幕底部的距离（不含系统导航栏内边距）。 */
internal val DockBarBottomMargin = 16.dp

/** 上报器的 CompositionLocal，由脚手架层提供。 */
internal val LocalBottomDockScrollReporter = compositionLocalOf<BottomDockScrollReporter?> { null }

/**
 * 底部两栏的收紧进度：0 = 完全展开，1 = 完全收紧。
 *
 * 导航栏在前半段收成圆钮，迷你条在后半段收成圆形封面钮。
 */
internal val LocalBottomDockProgress = compositionLocalOf { 0f }

/**
 * 页面用自己滚动容器的状态上报底部形态。
 *
 * 一页有多个滚动容器时（曲库按来源分列表）传多组，取其中"滚得最远"的那组：
 * 任意一个列表滚下去都该收紧，回到顶部则由那一组决定何时恢复。
 *
 * @param firstItemTopProviders 每个滚动容器的"首个可见 item 距视口顶部距离"读取器。
 *        传 lambda 而不是直接传数值：lambda 每次读都拿到最新的快照值，
 *        snapshotFlow 才能追踪到列表滚动的变化。
 */
@Composable
internal fun ReportBottomDockScroll(
    scrollToTop: (() -> Unit)?,
    vararg firstItemTopProviders: () -> Int
) {
    val reporter = LocalBottomDockScrollReporter.current ?: return
    // 每个调用点一个令牌，用来识别"是哪个页面在接管形态"
    val owner = remember { Any() }
    // 直接在组合期读取快照值：滚动位置变化会让这里重组，
    // 从而触发下面的 SideEffect 重新上报（比 snapshotFlow 更可靠，
    // 也不会被 vararg 闭包捕获到旧值）。
    val top = firstItemTopProviders.minOfOrNull { provider -> provider() } ?: 0
    // SideEffect 在组合提交后执行，每次重组都会跑一次；
    // reporter 内部只在数值真的变化时写状态，所以没有额外重组开销。
    androidx.compose.runtime.SideEffect {
        reporter.report(owner, top)
    }
    // 页面注册"回到顶部"动作：收紧态底栏的那颗圆钮点它就回到本页顶部
    androidx.compose.runtime.SideEffect {
        reporter.scrollToTop = scrollToTop
    }
}

/**
 * 首个可见 item 上边缘距视口顶部的距离（像素）。
 *
 * 向下滚为负，向上滚回顶部为 0。这个值是连续的，不像 firstVisibleItemScrollOffset
 * 会在首个 item 变化时重置。
 */
internal fun LazyGridState.firstVisibleItemTopPx(): Int {
    val items = layoutInfo.visibleItemsInfo
    if (items.isEmpty()) return 0
    val first = items[0]
    // offset 只是"首个可见 item 距视口顶部的距离"，不是累计滚动量：
    // 网格里如果有跨列大 item，它滚出视口后 visibleItemsInfo[0] 会换成下一个 item，
    // offset 随之从很负的值跳回较不负的值，收紧进度会突然回退。
    //
    // 用 index 兜住单调性（每翻过一个 item 记一段固定滚动量），
    // offset 提供 index 之间的连续量，两者相加就得到平滑递增的进度。
    // LazyGrid 的 item 偏移是 IntOffset，取 y 分量
    return first.offset.y - first.index * DockScrollStepPerItemPx
}

/** [LazyGridState.firstVisibleItemTopPx] 的列表版本。 */
internal fun LazyListState.firstVisibleItemTopPx(): Int {
    val items = layoutInfo.visibleItemsInfo
    if (items.isEmpty()) return 0
    val first = items[0]
    // 同 LazyGrid：index 兜住单调性，offset 提供连续量
    return first.offset - first.index * DockScrollStepPerItemPx
}
