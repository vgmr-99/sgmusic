package moe.ouom.neriplayer.ui.component.navigation

import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import kotlin.math.roundToInt

private val FloatingBarHeight = 64.dp
private val FloatingBarHorizontalMargin = DockBarHorizontalMargin
// floats clear of the system gesture strip so a press-and-slide never reads as a home swipe
private val FloatingBarBottomMargin = DockBarBottomMargin
// 紧凑态下胶囊收成的圆钮宽度：够放一个图标加文字
private val CompactTabBarWidth = CompactDockButtonSize
// 收紧过程中非当前标签的淡出进度区间：胶囊收到一半之前先把它们撤掉
private const val CompactItemsFadeSpan = CompactDockItemsFadeSpan

/**
 * Floating capsule tab bar for the clean style, after the iOS 26 tab bar: inset glass capsule
 * with a hairline rim and a soft shadow, a quiet pill behind the selected tab, and on drag a
 * larger glass lens that lifts out of the bar, follows the finger and magnifies the tab below.
 *
 * [compactProgress] 从 0 走到 1 时整条胶囊收窄成当前标签的一颗圆钮，
 * 靠右停靠——页面向下滚动后把屏幕空间让给内容。
 */
@Composable
internal fun CleanFloatingTabBar(
    items: List<Pair<Destinations, ImageVector>>,
    currentDestination: NavDestination?,
    onItemSelected: (Destinations) -> Unit,
    modifier: Modifier = Modifier,
    compactProgress: Float = 0f,
    visibleIndices: List<Int> = items.indices.toList()
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val overBackground = LocalHasCustomBackground.current
    val selectedIndex = items.indexOfFirst { (dest, _) ->
        isNeriNavigationDestinationSelected(currentDestination, dest)
    }
    val tabDrag = remember { CleanTabDragState() }
    val host = remember { LensHost() }
    val latestItems by rememberUpdatedState(items)
    val latestOnItemSelected by rememberUpdatedState(onItemSelected)
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    val density = LocalDensity.current
    val barHeightPx = with(density) { FloatingBarHeight.toPx() }
    val barLayer = rememberGraphicsLayer()
    val lensPath = remember { Path() }
    // 收紧进度：0 = 整条胶囊，1 = 右侧一颗圆钮
    val progress = compactProgress.coerceIn(0f, 1f)
    // 收紧时其余标签先淡出，只留当前标签的图标
    val itemsFadeOut = (1f - progress / CompactItemsFadeSpan).coerceIn(0f, 1f)
    val showLabels = progress < CompactItemsFadeSpan
    // 已经收成一颗圆钮：其余标签不可见，交互也必须跟着收掉。
    //
    // 之前这里有个隐蔽的崩溃式 bug：收紧后非当前标签的 weight 被压到 ~0，
    // 它们全部挤在圆钮最右边缘（各自宽度不到 0.1dp），
    // 而拖拽手势用「离点击位置最近的标签中心」反查下标，
    // 于是点在圆钮右半边就会命中最后一个标签（设置），直接跳到设置页。
    val collapsed = progress >= CompactItemsFadeSpan
    SideEffect { tabDrag.trimItems(items.size) }
    LaunchedEffect(selectedIndex, tabDrag.layoutVersion) {
        tabDrag.settleOn(selectedIndex)
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(
                start = FloatingBarHorizontalMargin,
                end = FloatingBarHorizontalMargin,
                bottom = FloatingBarBottomMargin
            )
            // 容器高度固定：收紧时胶囊在容器里收成圆钮，底栏槽位高度保持不变，
            // 页面内容不会跟着上下跳。
            .height(FloatingBarHeight)
            .onGloballyPositioned { host.coordinates = it }
    ) {
        // 收紧时胶囊缩到一颗圆钮的宽度并靠右停靠
        val fullWidth = maxWidth
        val compactWidth = CompactTabBarWidth
        val barWidth = with(density) {
            val fullPx = fullWidth.toPx()
            val compactPx = compactWidth.toPx()
            val targetPx = fullPx - (fullPx - compactPx) * progress
            targetPx.toDp()
        }
        // 高度一起收，才能从宽胶囊变成正圆；底边不动，收紧后仍然贴着原来的底线
        val barHeight = FloatingBarHeight +
            (CompactDockButtonSize - FloatingBarHeight) * progress
        AdvancedGlassSurface(
            role = AdvancedGlassRole.BottomNavigation,
            modifier = Modifier
                // 贴着容器底边收：收紧后圆钮的底边和展开态胶囊的底边在同一条水平线上
                .align(Alignment.BottomEnd)
                // 圆钮比播放栏矮，抬起来半个身位差，两颗才落在同一条中心线上
                .offset(y = -CompactDockVerticalOffset * progress)
                .width(barWidth)
                // 高度一起收，才能从宽胶囊变成正圆
                .height(barHeight)
                .shadow(
                    elevation = 18.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.06f),
                    spotColor = Color.Black.copy(alpha = if (dark) 0.3f else 0.12f)
                )
                .border(
                    width = 0.5.dp,
                    color = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.7f),
                    shape = CircleShape
                ),
            shape = CircleShape,
            fallbackColor = colors.surfaceContainerLow.copy(alpha = if (overBackground) 0.62f else 0.9f),
            // 底色铺在玻璃层下面：收紧态的圆钮浮在内容上时不会把后面的内容透过来
            baseColor = colors.surfaceContainerLow.copy(alpha = if (overBackground) 0.72f else 0.94f),
            tintColor = colors.surfaceContainerLow
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    // 收紧时把内边距收掉，整颗圆钮都是可点区域
                    .padding(horizontal = 6.dp * (1f - progress))
                    .onGloballyPositioned { tabDrag.bar = it }
                    // 收成一颗圆钮之后不再是「多个标签」，拖拽切换没有意义，
                    // 而且按下位置反查下标会命中被挤到边缘的其它标签，必须停掉。
                    .then(
                        if (collapsed) {
                            Modifier
                        } else {
                            Modifier.cleanTabDragGesture(tabDrag) { index ->
                                if (index == latestSelectedIndex) return@cleanTabDragGesture
                                latestItems.getOrNull(index)?.let { (dest, _) -> latestOnItemSelected(dest) }
                            }
                        }
                    )
                    .drawWithContent {
                        drawBarContentAroundIndicator(
                            layer = barLayer,
                            indicator = if (progress < CompactItemsFadeSpan) {
                                tabDrag.indicatorRect(barHeightPx, density)
                            } else {
                                null
                            },
                            path = lensPath
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, (dest, icon) ->
                    val selected = index == selectedIndex
                    val label = stringResource(dest.labelResId)
                    // 紧凑态：容器已收窄到只剩一颗钮的宽度，非当前项淡出即可。
                    // weight 必须保持为正（Compose 不接受 0），均分变窄后的空间。
                    val keep = index in visibleIndices
                    // 收紧时只留当前标签：其余标签的权重压到 0，宽度全让给当前项，
                    // 胶囊收成圆钮后圆钮里正好是当前标签的图标。
                    val weight = if (selected) {
                        1f
                    } else {
                        1f + (0.001f - 1f) * progress
                    }
                    val alpha = when {
                        !keep -> 0f
                        selected -> 1f
                        else -> itemsFadeOut
                    }
                    Column(
                        modifier = Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .graphicsLayer { this.alpha = alpha }
                            .onGloballyPositioned { tabDrag.reportItem(index, it) }
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                            }
                            .clickable(
                                // 收成圆钮后只有当前标签可见，也只有它可点，
                                // 否则会点到被挤到边缘的隐形标签上。
                                enabled = !collapsed || selected,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                context.performHapticFeedback()
                                onItemSelected(dest)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        TabGlyph(
                            dest = dest,
                            icon = icon,
                            selected = selected,
                            label = label,
                            showLabel = showLabels,
                            // 收紧态只剩一颗圆钮，图标跟着圆钮一起放大
                            iconSize = if (showLabels) 24.dp else 26.dp
                        )
                    }
                }
            }
        }

        CleanTabIndicator(
            tabDrag = tabDrag,
            hostCoordinates = { host.coordinates },
            barLayer = barLayer,
            barHeightPx = barHeightPx,
            dark = dark,
            alpha = itemsFadeOut
        )
    }
}

@Composable
private fun TabGlyph(
    dest: Destinations,
    icon: ImageVector,
    selected: Boolean,
    label: String,
    showLabel: Boolean = true,
    iconSize: Dp = 24.dp
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurface.copy(alpha = 0.72f)
    CompositionLocalProvider(LocalContentColor provides tint) {
        CleanTabIcon(dest, icon, selected, label, iconSize = iconSize)
    }
    if (!showLabel) return
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        ),
        color = tint,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
}

private class LensHost {
    var coordinates: LayoutCoordinates? = null
}
