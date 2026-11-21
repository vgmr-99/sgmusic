package moe.ouom.neriplayer.ui.component.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

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
 * 回顶动作的注册入口。
 *
 * 形态现在由脚手架层的垂直手势驱动，不再看列表滚动位置；
 * 这个类只保留「当前页面怎么回到顶部」的注册与调用，
 * 收紧态圆钮点下去时用它。滚动上报接口保留为 no-op，
 * 避免改动所有调用点，同时杜绝它们反过来改形态。
 */
internal class BottomDockScrollReporter() {
    /**
     * 当前页面的"回到顶部"动作，由页面在注册滚动上报时提供。
     *
     * 收紧态底栏会收成一颗当前标签的圆钮，点它就回到该页顶部。
     */
    var scrollToTop: (() -> Unit)? = null

    /**
     * 点收紧态圆钮时的附加动作，由脚手架层提供。
     *
     * 手势驱动形态后，滚动不再影响两栏形态；以前「滚到顶部自然展开」的联动没了，
     * 所以点圆钮要显式把形态恢复成展开，否则用户点了钮还是收着，读不懂反馈。
     */
    var onDockTap: (() -> Unit)? = null

    /** 请求回到当前页面顶部，并恢复展开态。 */
    fun requestScrollToTop() {
        onDockTap?.invoke()
        scrollToTop?.invoke()
    }

    /**
     * 保留的滚动上报接口：现在什么都不做。
     *
     * @param owner 页面标识（已忽略）
     * @param firstItemTopPx 首个可见 item 的相对位置（已忽略）
     */
    fun report(owner: Any, firstItemTopPx: Int) = Unit
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

/**
 * 垂直手势的方向判定。
 *
 * 底部两栏的形态改成**手势驱动**后，判定只看「手指往哪个方向划了一段距离」，
 * 不再看列表滚到哪：滚不动的页面、内容很短的子页面、NavHost 里的独立路由，
 * 都跟主 Tab 一样能收紧/恢复。
 *
 * 采用「一次性切换」：手势越过阈值就返回一次方向，调用方把形态切过去并保持；
 * 同一次手势里后续位移不再重复触发，必须抬手再划才会反向。
 *
 * @param dragY 从按下到现在累计的垂直位移，屏幕坐标向下为正
 * @param thresholdPx 触发阈值（像素）
 * @return 上滑（dragY 为负）返回 [DockSwipeDirection.COLLAPSE]，
 *         下滑返回 [DockSwipeDirection.EXPAND]，没到阈值返回 null
 */
internal enum class DockSwipeDirection { COLLAPSE, EXPAND }

/** 手势收紧阈值：约 40dp，够过滤轻碰，又不必划完整屏。 */
internal val DockSwipeThreshold: Dp = 40.dp

internal fun resolveDockSwipeDirection(
    dragY: Float,
    thresholdPx: Float
): DockSwipeDirection? {
    if (thresholdPx <= 0f || abs(dragY) < thresholdPx) return null
    return if (dragY < 0f) DockSwipeDirection.COLLAPSE else DockSwipeDirection.EXPAND
}

/**
 * 全局垂直手势检测器：只观察、不消费。
 *
 * 挂在脚手架的内容区外层，因此主 Tab 和所有子页面共用同一套形态。
 * 关键是 [PointerEventPass.Initial] 里读位移但**不调用 consume()**：
 *  · 列表仍能正常滚动（事件继续往下传）；
 *  · 播放条自己那套「上滑展开播放页」手势也不受影响；
 *  · 用户只是划一下页面，形态就跟着切换，跟列表有没有内容无关。
 *
 * 一次手势只触发一次（[fired] 锁存），避免拖动过程中反复切换造成抖动。
 */
internal fun Modifier.bottomDockSwipeDetector(
    enabled: Boolean,
    thresholdPx: Float,
    onDirection: (DockSwipeDirection) -> Unit
): Modifier = pointerInput(enabled, thresholdPx) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var lastY = down.position.y
        var dragY = 0f
        var fired = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            // 用绝对坐标差分而不是 positionChange()：
            // positionChange 在同一事件流里被别的 pointerInput 读过之后可能归零，
            // 而变化的绝对坐标是谁都能重复读的，不受内层是否消费影响。
            val currentY = change.position.y
            dragY += currentY - lastY
            lastY = currentY
            if (!fired) {
                resolveDockSwipeDirection(dragY, thresholdPx)?.let { direction ->
                    onDirection(direction)
                    fired = true
                }
            }
        }
    }
}

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
