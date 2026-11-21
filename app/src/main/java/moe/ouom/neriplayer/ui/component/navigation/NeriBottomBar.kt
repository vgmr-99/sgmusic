package moe.ouom.neriplayer.ui.component.navigation

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
 * File: moe.ouom.neriplayer.ui.component/NeriBottomBar
 * Created: 2025/8/8
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import moe.ouom.neriplayer.ui.component.navigation.LocalBottomDockScrollReporter
import moe.ouom.neriplayer.ui.component.navigation.navCompactProgress
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotion
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback

internal const val DEFAULT_BOTTOM_BAR_SELECTION_ALPHA = 0.72f
internal const val BOTTOM_BAR_FALLBACK_SCRIM_ALPHA = 0.28f

internal fun resolveBottomBarSelectionAlpha(
    hasCustomBackground: Boolean,
    alwaysUseNewTabStyle: Boolean
): Float = if (hasCustomBackground || alwaysUseNewTabStyle) {
    0f
} else {
    DEFAULT_BOTTOM_BAR_SELECTION_ALPHA
}

/**
 * 按当前主 Tab route 找选中项；route 为空（或没匹配上）时退回 destination hierarchy。
 *
 * 子页面的 hierarchy 里没有主 Tab route，只靠 hierarchy 会找不到当前项，
 * 导致收紧后的圆钮是空白的。selectedTabRoute 由 App 层的权威状态提供。
 */
internal fun resolveSelectedBottomBarIndex(
    items: List<Pair<Destinations, ImageVector>>,
    currentDestination: NavDestination?,
    selectedTabRoute: String?
): Int {
    if (!selectedTabRoute.isNullOrBlank()) {
        val byRoute = items.indexOfFirst { (dest, _) -> dest.route == selectedTabRoute }
        if (byRoute >= 0) return byRoute
    }
    return items.indexOfFirst { (dest, _) ->
        currentDestination?.hierarchy?.any { it.route == dest.route } == true
    }
}

/**
 * 紧凑态下底栏只保留当前标签，其余标签收起。
 *
 * 返回可见标签的下标集合：紧凑态只留当前选中项，展开态全部保留。
 */
internal fun resolveVisibleBottomBarItems(
    items: List<Pair<Destinations, ImageVector>>,
    currentDestination: NavDestination?,
    compact: Boolean,
    selectedTabRoute: String? = null
): List<Int> {
    if (!compact || items.isEmpty()) return items.indices.toList()
    val selectedIndex = resolveSelectedBottomBarIndex(items, currentDestination, selectedTabRoute)
    return if (selectedIndex >= 0) listOf(selectedIndex) else items.indices.toList()
}

internal fun isNeriNavigationDestinationSelected(
    currentDestination: NavDestination?,
    destination: Destinations
): Boolean = currentDestination?.hierarchy?.any { it.route == destination.route } == true

/** 展开态底栏高度，与 Material3 NavigationBar 的默认高度一致。 */
private val BottomBarExpandedHeight = 80.dp

@Composable
fun NeriBottomBar(
    items: List<Pair<Destinations, ImageVector>>,
    currentDestination: NavDestination?,
    onItemSelected: (Destinations) -> Unit,
    modifier: Modifier = Modifier,
    selectAlpha: Float = DEFAULT_BOTTOM_BAR_SELECTION_ALPHA,
    /** 当前所属主 Tab route，子页面里也能准确判定选中项 */
    selectedTabRoute: String? = null
) {
    // 页面向下滚动后底栏收紧，只留当前标签；回到顶部再展开。
    // 连续进度：前半段把导航栏收成一颗当前标签的圆钮
    val dockProgress = LocalBottomDockProgress.current
    val compactProgress by animateFloatAsState(
        targetValue = navCompactProgress(dockProgress),
        animationSpec = NeriMotion.smooth(),
        label = "bottom_bar_compact_progress"
    )
    val compact = compactProgress > 0.5f
    val visibleIndices = resolveVisibleBottomBarItems(items, currentDestination, compact, selectedTabRoute)

    // 展开态是整条导航栏，收紧态是右侧那颗圆钮。
    //
    // 两者同时渲染、互相淡入淡出，而不是硬切换——硬切换的话回顶时圆钮会突然
    // 消失，看起来像"卡了一下"。
    //
    // 导航栏这一侧始终保持全宽：Material3 的 NavigationBarItem 最小宽度 64dp，
    // 一旦把容器收窄到 44dp，它会自动挤出一个溢出菜单（三个点）；
    // CleanFloatingTabBar 更糟，每个 item 会被压成一条竖杠。
    val compactFraction = compactProgress.coerceIn(0f, 1f)
    val buttonFade = ((compactFraction - 0.3f) / 0.45f).coerceIn(0f, 1f)
    val navFade = 1f - buttonFade

    val dockReporter = LocalBottomDockScrollReporter.current
    val currentIndex = resolveSelectedBottomBarIndex(items, currentDestination, selectedTabRoute)
    val currentDest = items.getOrNull(currentIndex)?.first
    val currentIcon = items.getOrNull(currentIndex)?.second

    if (NeriDesign.isClean) {
        // clean 风格：底栏不是淡出，而是从原来的位置横向收成右侧一颗圆钮，
        // 圆钮里就是当前标签的图标。点它回到当前页顶部。
        //
        // 「收成单钮」的判定用 [CompactDockItemsFadeSpan]（非当前标签淡出之后），
        // 而不是等进度过半：收窄过程中非当前标签被压成右侧的隐形细条，
        // 这时候还按「点了哪个标签」去响应，就会误判成最后那个标签（设置）而跳走。
        val collapsed = shouldScrollToTopOnDockTap(compactProgress)
        Box(modifier = modifier.fillMaxWidth()) {
            CleanFloatingTabBar(
                items = items,
                currentDestination = currentDestination,
                onItemSelected = { dest ->
                    if (collapsed) {
                        dockReporter?.requestScrollToTop()
                    } else {
                        onItemSelected(dest)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                compactProgress = compactProgress,
                visibleIndices = visibleIndices,
                selectedTabRoute = selectedTabRoute
            )
        }
        return
    }
    val context = LocalContext.current
    val alwaysShowLabel = selectAlpha != 0f
    val baseBlurRequested = LocalAdvancedGlassController.current.isBaseBlurRequested
    val fallbackScrimAlpha = resolveBottomBarFallbackScrimAlpha(
        selectAlpha = selectAlpha,
        baseBlurRequested = baseBlurRequested
    )
    val clean = NeriDesign.isClean
    val fallbackColor = when {
        clean -> MaterialTheme.colorScheme.surfaceContainerLow.copy(
            alpha = if (LocalHasCustomBackground.current) 0.62f else 0.94f
        )
        fallbackScrimAlpha > 0f -> MaterialTheme.colorScheme.background.copy(alpha = fallbackScrimAlpha)
        else -> Color.Transparent
    }

    // 收紧态：整条收成一颗粒钮的尺寸（正圆），只保留当前标签的图标。
    // 与迷你条收紧后并排停靠，右侧对齐导航栏边距。
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val dockBarWidth = (screenWidthDp - DockBarHorizontalMargin.value * 2f).dp
    // 导航栏始终保持全宽（左右各留一条边距，和展开态的播放栏同宽），
    // 收紧时只做淡出——一旦把容器收窄，Material3 的 item 会被挤出溢出菜单。
    val barWidth = dockBarWidth
    val barHeight = BottomBarExpandedHeight
    // 文字随收紧淡出
    val labelAlpha = (1f - compactProgress.coerceIn(0f, 1f) * 1.8f).coerceIn(0f, 1f)

    // 收紧态要看到一颗实心玻璃圆钮，所以收紧时给不透明的底色

    // 收紧态：彻底不画 Material3 那套，只留一颗玻璃圆钮。
    //
    // Material3 的 NavigationBarItem 最小宽度是 64dp，而圆钮只有 56dp，
    // 放不下时它会自动挤出一个溢出菜单（三个点）。而且它的容器在窄到一定程度时
    // 会把选中项和溢出图标挤在一起，看起来像坏掉。
    // 所以收紧过半之后整个换成只画图标的玻璃条（CleanFloatingTabBar），
    // 它本来就支持紧凑形态，收紧后是一颗正圆，只显示当前标签的图标。
    // 收紧态是一颗独立浮在内容上的圆钮，底色要够实，
    // 否则会和迷你条的圆形封面钮叠在一起糊成一片。
    val dockGlassColor = if (compactFraction > 0f) {
        val base = MaterialTheme.colorScheme.surfaceContainerLow
        val expandedAlpha = if (clean) 0.62f else 0.0f
        base.copy(
            alpha = expandedAlpha + (0.97f - expandedAlpha) * compactFraction
        )
    } else {
        fallbackColor
    }
    // 形状保持长条（收紧后整条淡出，圆钮是另一颗独立控件）
    val dockGlassShape = RectangleShape

    Box(modifier = modifier.fillMaxWidth()) {
    AdvancedGlassSurface(
        role = AdvancedGlassRole.BottomNavigation,
        // 玻璃面跟着一起收窄：展开态是整条胶囊，收紧态是一颗圆形玻璃钮
        modifier = Modifier
            // 收紧时整条导航栏淡出，把位置让给右侧那颗圆钮。
            // 淡出后必须停止接收点击，否则点击会穿透到下层的标签上
            //（圆钮正好压在"设置"那一项上面，点一下就跳到设置页）。
            .graphicsLayer { alpha = navFade }
            .clickable(
                enabled = navFade > 0.5f,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { }
            // 和播放栏用同一套宽度写法（fillMaxWidth + padding），
            // 这样两条在展开态才是真正一样宽、左右边距对齐
            .fillMaxWidth()
            .padding(horizontal = DockBarHorizontalMargin)
            .height(barHeight),
        shape = dockGlassShape,
        fallbackColor = dockGlassColor,
        // tintColor 决定有背景内容时玻璃面的着色。收紧态的圆钮浮在内容上，
        // 必须给一层浅色 tint，否则后面的内容会透过来，圆钮就糊了。
        tintColor = if (clean) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow.copy(
                alpha = 0.95f * compactFraction
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
        NavigationBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .background(Color.Transparent),
            windowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) {
            items.forEachIndexed { index, (dest, icon) ->
                val selected = isNeriNavigationDestinationSelected(currentDestination, dest)
                val label = stringResource(dest.labelResId)
                // 紧凑态只留当前标签
                if (index !in visibleIndices) return@forEachIndexed
                NavigationBarItem(
                    selected = selected,
                    // 淡出后必须停止接收点击，否则点击会穿透到下层内容上
                    //（收紧态圆钮正好压在这一项上面，点一下就会跳到别的页面）
                    enabled = navFade > 0.5f,
                    onClick = {
                        context.performHapticFeedback()
                        onItemSelected(dest)
                    },
                    icon = {
                        if (clean) {
                            CleanTabIcon(dest, icon, selected, label)
                        } else {
                            Icon(icon, contentDescription = label)
                        }
                    },
                    label = {
                        // 收紧态把文字整个撤掉：Material3 在窄容器里放不下
                        // 图标+文字+指示器，会自动挤出一个溢出菜单（三个点）
                        if (labelAlpha > 0.01f) {
                            Text(
                                text = label,
                                style = if (clean) MaterialTheme.typography.labelSmall else LocalTextStyle.current,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.graphicsLayer { alpha = labelAlpha }
                            )
                        }
                    },
                    alwaysShowLabel = (alwaysShowLabel || clean) && labelAlpha > 0.01f,
                    colors = if (clean) cleanNavigationBarItemColors() else NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer.copy(
                            // 收紧态圆钮只有一个图标，不需要 Material3 的药丸指示器
                            alpha = selectAlpha * (1f - compactFraction)
                        ),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                )
            }
        }
        }
    }
}
}

@Composable
private fun cleanNavigationBarItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = Color.Transparent,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)

/** Filled rounded glyph for the active tab, outlined for the rest, with a small spring pop. */
@Composable
internal fun CleanTabIcon(
    destination: Destinations,
    outlinedIcon: ImageVector,
    selected: Boolean,
    contentDescription: String,
    iconSize: Dp = 24.dp
) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            pop.snapTo(0.84f)
            pop.animateTo(1f, NeriMotion.bouncy())
        }
    }
    Icon(
        imageVector = if (selected) cleanSelectedTabIcon(destination) ?: outlinedIcon else outlinedIcon,
        contentDescription = contentDescription,
        modifier = Modifier
            .size(iconSize)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
    )
}

internal fun cleanSelectedTabIcon(destination: Destinations): ImageVector? = when (destination.route) {
    Destinations.Home.route -> Icons.Rounded.Home
    Destinations.Explore.route -> Icons.Rounded.Search
    Destinations.Library.route -> Icons.Rounded.LibraryMusic
    Destinations.Settings.route -> Icons.Rounded.Settings
    Destinations.Debug.route -> Icons.Rounded.BugReport
    else -> null
}

internal fun shouldUseOpaqueBottomBarFallback(
    selectAlpha: Float,
    baseBlurRequested: Boolean
): Boolean = selectAlpha != 0f || baseBlurRequested

internal fun resolveBottomBarFallbackScrimAlpha(
    selectAlpha: Float,
    baseBlurRequested: Boolean
): Float = if (shouldUseOpaqueBottomBarFallback(selectAlpha, baseBlurRequested)) {
    BOTTOM_BAR_FALLBACK_SCRIM_ALPHA
} else {
    0f
}


/**
 * 收紧态的底栏圆钮：一颗浮在内容右下的圆形按钮，里面是当前标签的图标。
 *
 * 不用 Material3 的 NavigationBarItem：它的最小宽度是 64dp，
 * 塞进 56dp 的圆钮里会自动挤出一个溢出菜单（三个点）。
 * 这里自己画，形状、尺寸、图标都完全可控。
 */
@Composable
internal fun CompactDockButton(
    destination: Destinations,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val selectedIcon = cleanSelectedTabIcon(destination) ?: icon
    Box(
        modifier = modifier
            .size(CompactDockButtonSize)
            .clip(CircleShape)
            .background(colors.surfaceContainerLow.copy(alpha = if (dark) 0.72f else 0.9f))
            .border(
                width = 1.dp,
                color = if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.7f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = selectedIcon,
            contentDescription = stringResource(destination.labelResId),
            tint = colors.onBackground,
            modifier = Modifier.size(26.dp)
        )
    }
}
