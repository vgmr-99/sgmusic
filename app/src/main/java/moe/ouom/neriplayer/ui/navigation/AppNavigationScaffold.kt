package moe.ouom.neriplayer.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import moe.ouom.neriplayer.ui.component.navigation.CompactDockButton
import moe.ouom.neriplayer.ui.component.navigation.CompactDockButtonGap
import moe.ouom.neriplayer.ui.component.navigation.CompactDockButtonSize
import moe.ouom.neriplayer.ui.component.navigation.DockBarBottomMargin
import moe.ouom.neriplayer.ui.component.navigation.DockBarHorizontalMargin
import moe.ouom.neriplayer.ui.component.navigation.LocalBottomDockProgress
import moe.ouom.neriplayer.ui.component.navigation.navCompactProgress
import moe.ouom.neriplayer.ui.theme.NeriDesign
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.layout.boundsInWindow
import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.processing.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.component.navigation.BottomDockMode
import moe.ouom.neriplayer.ui.component.navigation.BottomDockScrollReporter
import moe.ouom.neriplayer.ui.component.navigation.DefaultBottomDockCompactThreshold
import moe.ouom.neriplayer.ui.component.navigation.LocalBottomDockMode
import moe.ouom.neriplayer.ui.component.navigation.LocalBottomDockScrollReporter
import moe.ouom.neriplayer.ui.component.navigation.NeriBottomBar
import moe.ouom.neriplayer.ui.component.navigation.resolveBottomBarSelectionAlpha
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayer
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayerDefaults
import moe.ouom.neriplayer.ui.component.playback.MiniPlayerTabletControls
import moe.ouom.neriplayer.ui.component.playback.resolvePlaybackWaiting
import moe.ouom.neriplayer.ui.theme.NeriMotion
import moe.ouom.neriplayer.util.platform.PHONE_SMALLEST_SCREEN_WIDTH_DP
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.feedback.AppFeedbackHostEffect
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.ui.util.hasKnownEmptyLocalCover
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.BottomBarLayoutInsets
import moe.ouom.neriplayer.ui.banner.MANAGED_LIBRARY_PROCESSING_DRAG_THRESHOLD
import moe.ouom.neriplayer.ui.banner.MANAGED_LIBRARY_PROCESSING_REVEAL_EDGE
import moe.ouom.neriplayer.ui.banner.MANAGED_LIBRARY_PROCESSING_Z_INDEX
import moe.ouom.neriplayer.ui.MINI_PLAYER_OVERLAY_Z_INDEX
import moe.ouom.neriplayer.ui.banner.ManagedLibraryProcessingBanner
import moe.ouom.neriplayer.ui.banner.OfflineModeBottomBanner
import moe.ouom.neriplayer.ui.banner.AppManagedProcessingBannerOwner
import moe.ouom.neriplayer.ui.banner.AppManagedProcessingBannerPresentation
import moe.ouom.neriplayer.ui.banner.managedProcessingRevealGesture

internal data class AppBottomBarPresentation(
    val items: List<Pair<Destinations, ImageVector>>,
    val currentDestination: NavDestination?,
    val showNowPlaying: Boolean,
    val offlineMode: Boolean,
    val alwaysUseNewTabStyle: Boolean,
    val backgroundImageUri: String?
) {
    val hasCustomBackground: Boolean get() = backgroundImageUri != null
}

internal data class AppMiniPlayerPresentation(
    val song: SongItem?,
    val coverUrl: String?,
    val visualCoverUrl: String?,
    val songVisualKey: String?,
    val visualCoverSongKey: String?,
    val enableBlur: Boolean
) {
    val hasSong: Boolean get() = song != null

    fun title(resources: Resources): String =
        if (song == null) resources.getString(CoreCommonR.string.nowplaying_no_playback) else song.displayName()

    fun artist(): String = if (song == null) "" else song.displayArtist()
}

internal fun shouldShowMiniPlayer(hasSong: Boolean, showNowPlaying: Boolean): Boolean =
    hasSong && !showNowPlaying

internal fun shouldSuppressPlaybackNavigation(
    playbackOpen: Boolean,
    backGestureRevealing: Boolean = false
): Boolean =
    // 底栏和迷你播放器与播放页退出同步恢复，避免两段动画串联等待
    !backGestureRevealing && playbackOpen

internal fun playbackSourceNavigationAction(
    sourceRoute: String?,
    navigate: (String) -> Unit
): (() -> Unit)? = sourceRoute?.let { route -> { navigate(route) } }

internal fun reservedMiniPlayerHeight(hasSong: Boolean, showNowPlaying: Boolean, smallestScreenWidthDp: Int) =
    if (shouldShowMiniPlayer(hasSong, showNowPlaying)) {
        if (smallestScreenWidthDp >= PHONE_SMALLEST_SCREEN_WIDTH_DP) {
            NeriMiniPlayerDefaults.TabletHeight
        } else NeriMiniPlayerDefaults.Height
    } else 0.dp

internal fun appSnackbarStartInset(useNavigationRail: Boolean, playbackOverlayVisible: Boolean) =
    if (useNavigationRail && !playbackOverlayVisible) AppNavigationRailWidth else 0.dp

private fun positiveBottomBarHeight(nextHeight: Int, previousHeight: Int): Int =
    if (nextHeight > 0) nextHeight else previousHeight

/**
 * 收紧态播放栏的槽位底边（距离屏幕底部）。
 *
 * 收紧后播放栏要下移到导航栏那一行，玻璃面的底边和右侧那颗圆钮的底边齐平。
 * 圆钮在 clean 风格下就是底栏胶囊收成的圆钮，它的底边 =
 * 系统导航栏内边距 + 底栏下边距。
 */
internal fun resolveCompactMiniPlayerBottomPadding(
    navigationBarInset: Dp,
    cleanStyle: Boolean
): Dp = if (cleanStyle) {
    navigationBarInset + DockBarBottomMargin
} else {
    // Material 风格底栏是贴着屏幕底部的 80dp 长条，圆钮居中放在里面
    (80.dp - CompactDockButtonSize) / 2
}

/** 收紧态右侧圆钮占掉的位置：播放栏收窄这么多，正好停在圆钮左边。 */
internal fun compactDockReservedWidth(progress: Float): Dp =
    (CompactDockButtonSize + CompactDockButtonGap) * progress.coerceIn(0f, 1f)

@Composable
internal fun AppNavigationScaffold(
    bottomBar: AppBottomBarPresentation,
    miniPlayer: AppMiniPlayerPresentation,
    baseBlurRequested: Boolean,
    snackbarHostState: SnackbarHostState,
    onMainTabSelected: (String) -> Unit,
    onExpandNowPlaying: () -> Unit,
    onOpenCurrentPlaybackSource: (() -> Unit)? = null,
    content: @Composable (BottomBarLayoutInsets) -> Unit
) {
    val configuration = LocalConfiguration.current
    val dragBoundsOwner = remember { AppDragBoundsOwner() }
    val yieldsToIme = cleanFloatingChromeYieldsToIme()
    val miniPlayerReservesSpace = miniPlayer.hasSong && !yieldsToIme
    // 底部两栏形态：页面向下滚动后收紧，回到顶部再展开。两栏读同一个状态，不会各动各的。
    var bottomDockProgress by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val compactThresholdPx = with(density) { DefaultBottomDockCompactThreshold.toPx() }.toInt()
    val dockScrollReporter = remember(compactThresholdPx) {
        BottomDockScrollReporter(
            thresholdPx = compactThresholdPx,
            onProgressChanged = { bottomDockProgress = it }
        )
    }
    val reservedMiniPlayerHeight = reservedMiniPlayerHeight(
        miniPlayerReservesSpace,
        bottomBar.showNowPlaying,
        configuration.smallestScreenWidthDp
    )
    // 当前标签（收紧后底栏会收成它的一颗圆钮）
    val currentTabEntry = bottomBar.items.firstOrNull { (dest, _) ->
        bottomBar.currentDestination?.hierarchy?.any { it.route == dest.route } == true
    }

    CompositionLocalProvider(
        LocalMiniPlayerHeight provides reservedMiniPlayerHeight,
        LocalBottomDockProgress provides bottomDockProgress,
        LocalBottomDockMode provides if (bottomDockProgress >= 0.5f) {
            BottomDockMode.COMPACT
        } else {
            BottomDockMode.EXPANDED
        },
        LocalBottomDockScrollReporter provides dockScrollReporter
    ) {
        AppFeedbackHostEffect(snackbarHostState)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val useNavigationRail = shouldUseAppNavigationRail(configuration.smallestScreenWidthDp, maxWidth)
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                snackbarHost = {
                    NeriSnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.padding(
                            start = appSnackbarStartInset(useNavigationRail, bottomBar.showNowPlaying)
                        ),
                        bottomPadding = LocalMiniPlayerHeight.current
                    )
                },
                bottomBar = {
                    if (!useNavigationRail) {
                        AppScaffoldBottomBar(
                            bottomBar = bottomBar,
                            compactTab = currentTabEntry,
                            yieldsToIme = yieldsToIme,
                            onMainTabSelected = onMainTabSelected,
                            onTabBarBoundsChanged = dragBoundsOwner::updateTabBarBounds
                        )
                    }
                }
            ) { innerPadding ->
                // pages keep their bottom reserve while Now Playing covers them; dropping it made a
                // list scrolled to its end jump as the player opened
                val pageReservedMiniPlayerHeight = reservedMiniPlayerHeight(
                    miniPlayerReservesSpace,
                    showNowPlaying = false,
                    configuration.smallestScreenWidthDp
                )
                val layoutInsets = resolveAppNavigationLayoutInsets(
                    useNavigationRail = useNavigationRail,
                    baseBlurRequested = baseBlurRequested,
                    bottomBarInset = innerPadding.calculateBottomPadding().coerceAtLeast(0.dp),
                    reservedMiniPlayerHeight = pageReservedMiniPlayerHeight
                )
                val dragBounds = dragBoundsOwner.visibleBounds(
                    miniPlayer.hasSong,
                    bottomBar.showNowPlaying || yieldsToIme
                )
                CompositionLocalProvider(
                    LocalMiniPlayerHeight provides layoutInsets.screenBottomInset,
                    LocalMiniPlayerBoundsInRoot provides dragBounds.miniPlayer,
                    LocalBottomTabBarBoundsInRoot provides dragBounds.bottomTabBar.takeUnless { useNavigationRail }
                ) {
                    AppAdaptiveNavigationContent(
                        useNavigationRail = useNavigationRail,
                        showNowPlaying = bottomBar.showNowPlaying,
                        offlineMode = bottomBar.offlineMode,
                        railContent = { AppNavigationRail(bottomBar, onMainTabSelected) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        AppManagedProcessingLayer(
                            layoutInsets = layoutInsets,
                            miniPlayer = miniPlayer,
                            showNowPlaying = bottomBar.showNowPlaying,
                            offlineMode = bottomBar.offlineMode,
                            onExpandNowPlaying = onExpandNowPlaying,
                            onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource,
                            onMiniPlayerBoundsChanged = dragBoundsOwner::updateMiniPlayerBounds,
                            content = content
                        )
                    }
                }
            }
        }
    }
}

/**
 * The clean floating tab bar and mini player sit over a fullscreen glass backdrop; when the IME
 * resizes the window they would ride above the keyboard with a stale blur region, so they step
 * aside until it closes, like iOS does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun cleanFloatingChromeYieldsToIme(): Boolean =
    NeriDesign.isClean && WindowInsets.isImeVisible

@Composable
private fun floatingPlaybackChromeVisible(requested: Boolean): Boolean =
    shouldShowFloatingPlaybackChrome(requested, cleanFloatingChromeYieldsToIme())

internal fun shouldShowFloatingPlaybackChrome(requested: Boolean, yieldsToIme: Boolean): Boolean =
    requested && !yieldsToIme

private fun bottomBarVisibilityTarget(visible: Boolean): Float =
    if (visible) 1f else 0f

private fun bottomBarVisibilityDuration(visible: Boolean): Int =
    if (visible) 280 else 220

private fun Modifier.collapseBottomBarSlot(collapsed: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    // 保留自然测量和动画实例，只释放隐藏chrome占用的Scaffold槽位
    layout(placeable.width, if (collapsed) 0 else placeable.height) {
        placeable.placeRelative(0, 0)
    }
}

@Composable
private fun AppScaffoldBottomBar(
    bottomBar: AppBottomBarPresentation,
    compactTab: Pair<Destinations, ImageVector>?,
    yieldsToIme: Boolean,
    onMainTabSelected: (String) -> Unit,
    onTabBarBoundsChanged: (Rect) -> Unit
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val tabInsets = NavigationBarDefaults.windowInsets
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    var navigationBarHeightPx by remember { mutableIntStateOf(0) }
    val barVisible = shouldShowFloatingPlaybackChrome(!bottomBar.showNowPlaying, yieldsToIme)
    val visibilityProgress by animateFloatAsState(
        targetValue = bottomBarVisibilityTarget(barVisible),
        animationSpec = tween(
            durationMillis = bottomBarVisibilityDuration(barVisible),
            easing = FastOutSlowInEasing
        ),
        label = "bottom_bar_visibility"
    )
    val selectAlpha = resolveBottomBarSelectionAlpha(
        hasCustomBackground = bottomBar.hasCustomBackground,
        alwaysUseNewTabStyle = bottomBar.alwaysUseNewTabStyle
    )
    // 收紧进度：导航栏收成右侧一颗圆钮。
    // clean 风格由底栏自己收（胶囊横向缩成圆钮），Material 风格整条淡出，
    // 由这里单独画一颗圆钮补位——它和底栏同在 bottomBar 槽位里，
    // 层级在播放栏之上，点它不会被下面的标签截走（之前会跳到设置页）。
    val dockProgress by animateFloatAsState(
        targetValue = LocalBottomDockProgress.current,
        animationSpec = NeriMotion.smooth(),
        label = "bottom_bar_compact_button_progress"
    )
    val navProgress = navCompactProgress(dockProgress)
    val buttonFade = ((navProgress - 0.3f) / 0.45f).coerceIn(0f, 1f)
    val dockReporter = LocalBottomDockScrollReporter.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .collapseBottomBarSlot(yieldsToIme)
            .onGloballyPositioned { coordinates ->
                if (!yieldsToIme) {
                    // 拖拽目标使用未位移的槽位，避免IME关闭时入场动画的空边界覆盖目标
                    val slotBounds = coordinates.boundsInRoot()
                    val navigationBounds = slotBounds.copy(
                        top = (slotBounds.bottom - navigationBarHeightPx).coerceAtLeast(slotBounds.top)
                    )
                    onTabBarBoundsChanged(
                        resolveBottomTabDragBounds(
                            navigationBounds,
                            tabInsets.getLeft(density, layoutDirection),
                            tabInsets.getRight(density, layoutDirection),
                            tabInsets.getBottom(density)
                        )
                    )
                }
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { size ->
                    bottomBarHeightPx = positiveBottomBarHeight(size.height, bottomBarHeightPx)
                }
                .graphicsLayer {
                    translationY = (1f - visibilityProgress) * bottomBarHeightPx.toFloat()
                    alpha = visibilityProgress
                }
        ) {
            AnimatedVisibility(visible = bottomBar.offlineMode) {
                OfflineModeBottomBanner()
            }
            NeriBottomBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("appBottomNavigation")
                    .onSizeChanged { navigationBarHeightPx = it.height },
                selectAlpha = selectAlpha,
                items = bottomBar.items,
                currentDestination = bottomBar.currentDestination,
                onItemSelected = { onMainTabSelected(it.route) }
            )
        }

        // Material 风格：底栏整条淡出，右侧补一颗当前标签的圆钮。
        // clean 风格的圆钮是底栏胶囊自己收出来的，这里不用再画一颗。
        if (!NeriDesign.isClean && compactTab != null) {
            CompactDockButton(
                destination = compactTab.first,
                icon = compactTab.second,
                onClick = { dockReporter?.requestScrollToTop() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = DockBarHorizontalMargin,
                        bottom = (80.dp - CompactDockButtonSize) / 2
                    )
                    .graphicsLayer { alpha = buttonFade }
            )
        }
    }
}

@Composable
private fun rememberManagedProcessingBannerSession(): Pair<
        AppManagedProcessingBannerOwner,
        AppManagedProcessingBannerPresentation
> {
    val currentState by ManagedLibraryProcessingCoordinator.state.collectAsStateWithLifecycle()
    val currentProgress by ManagedDownloadStorage.migrationProgressFlow.collectAsStateWithLifecycle()
    val collapsedState = rememberSaveable { mutableStateOf(false) }
    val owner = remember(collapsedState) { AppManagedProcessingBannerOwner(collapsedState) }
    LaunchedEffect(currentState, currentProgress) {
        owner.observe(currentState, currentProgress)
    }
    LaunchedEffect(currentState.operationId) {
        owner.onOperationChanged(currentState)
    }
    return owner to owner.presentation(currentState, currentProgress)
}

@Composable
private fun AppManagedProcessingLayer(
    layoutInsets: BottomBarLayoutInsets,
    miniPlayer: AppMiniPlayerPresentation,
    showNowPlaying: Boolean,
    offlineMode: Boolean,
    onExpandNowPlaying: () -> Unit,
    onOpenCurrentPlaybackSource: (() -> Unit)?,
    onMiniPlayerBoundsChanged: (Rect) -> Unit,
    content: @Composable (BottomBarLayoutInsets) -> Unit
) {
    val (owner, presentation) = rememberManagedProcessingBannerSession()
    val backdrops = LocalAdvancedGlassBackdrops.current
    val contentCaptureModifier = if (backdrops == null) {
        Modifier
    } else {
        Modifier.captureAdvancedGlassBackdrop(backdrops.content)
    }
    val edgePx = with(LocalDensity.current) { MANAGED_LIBRARY_PROCESSING_REVEAL_EDGE.toPx() }
    val thresholdPx = with(LocalDensity.current) { MANAGED_LIBRARY_PROCESSING_DRAG_THRESHOLD.toPx() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = layoutInsets.navContentBottomPadding)
            .clipToBounds()
            .managedProcessingRevealGesture(
                collapsed = presentation.revealGestureEnabled,
                edgePx = edgePx,
                thresholdPx = thresholdPx,
                onExpand = owner::expand
            )
    ) {
        AppManagedProcessingBanner(presentation, owner::collapse)
        Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
            // 页面和拖拽歌曲一起进入底栏模糊取样，播放器自身保持在取样层外
            AppDragOverlayHost(modifier = Modifier.fillMaxSize().then(contentCaptureModifier)) {
                content(layoutInsets)
            }
            AppMiniPlayerOverlay(
                miniPlayer = miniPlayer,
                showNowPlaying = showNowPlaying,
                offlineMode = offlineMode,
                bottomPadding = layoutInsets.miniPlayerBottomPadding,
                onExpandNowPlaying = onExpandNowPlaying,
                onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource,
                onBoundsChanged = onMiniPlayerBoundsChanged
            )
        }
    }
}

private fun managedProcessingBannerEnterTransition() =
    fadeIn(animationSpec = tween(durationMillis = 180)) +
        slideInVertically(
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            initialOffsetY = { -it / 2 }
        ) +
        expandVertically(
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top
        )

private fun managedProcessingBannerExitTransition() =
    fadeOut(animationSpec = tween(durationMillis = 160)) +
        slideOutVertically(
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            targetOffsetY = { -it / 2 }
        ) +
        shrinkVertically(
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top
        )

@Composable
private fun BoxScope.AppManagedProcessingBanner(
    presentation: AppManagedProcessingBannerPresentation,
    onCollapsedChange: (Boolean) -> Unit
) {
    AnimatedVisibility(
        visible = presentation.visible,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .zIndex(MANAGED_LIBRARY_PROCESSING_Z_INDEX),
        enter = managedProcessingBannerEnterTransition(),
        exit = managedProcessingBannerExitTransition()
    ) {
        ManagedLibraryProcessingBanner(
            state = presentation.state,
            migrationProgress = presentation.progress,
            modifier = Modifier,
            interactive = presentation.visible,
            onCollapsedChange = onCollapsedChange
        )
    }
}

private data class AppMiniPlayerPlaybackControls(
    val playbackRequested: Boolean,
    val playing: Boolean,
    val usbPreparing: Boolean,
    val routeMuted: Boolean
)

@Composable
private fun rememberMiniPlayerPlaybackControls(): AppMiniPlayerPlaybackControls {
    val playbackRequested by PlayerManager.playbackControlPlayingFlow.collectAsStateWithLifecycle()
    val playing by PlayerManager.isPlayingFlow.collectAsStateWithLifecycle()
    val usbPreparing by PlayerManager.usbExclusivePlaybackPreparingFlow.collectAsStateWithLifecycle()
    val routeMuted by PlayerManager.audioRouteMuteSuppressedFlow.collectAsStateWithLifecycle()
    return AppMiniPlayerPlaybackControls(
        playbackRequested = playbackRequested,
        playing = playing,
        usbPreparing = usbPreparing,
        routeMuted = routeMuted
    )
}

@Composable
private fun rememberAdaptiveMiniPlayerActions(
    visible: Boolean,
    offlineMode: Boolean,
    onOpenCurrentPlaybackSource: (() -> Unit)?
): MiniPlayerTabletControls? {
    if (LocalConfiguration.current.smallestScreenWidthDp < PHONE_SMALLEST_SCREEN_WIDTH_DP) {
        return null
    }
    return rememberMiniPlayerTabletActions(
        visible = visible,
        offlineMode = offlineMode,
        onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource
    )
}

@Composable
private fun AppMiniPlayerOverlay(
    miniPlayer: AppMiniPlayerPresentation,
    showNowPlaying: Boolean,
    offlineMode: Boolean,
    bottomPadding: Dp,
    onExpandNowPlaying: () -> Unit,
    onOpenCurrentPlaybackSource: (() -> Unit)?,
    onBoundsChanged: (Rect) -> Unit
) {
    val controls = rememberMiniPlayerPlaybackControls()
    val resources = LocalResources.current
    val miniPlayerVisible = floatingPlaybackChromeVisible(
        requested = shouldShowMiniPlayer(miniPlayer.hasSong, showNowPlaying)
    )
    val tabletControls = rememberAdaptiveMiniPlayerActions(
        visible = miniPlayerVisible,
        offlineMode = offlineMode,
        onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource
    )
    // 收紧态：底栏收成右侧一颗圆钮，播放栏下移到底栏那一行并收窄。
    //
    // 两者读同一个进度（导航栏收紧的那一段），所以动作是同步的：
    //  · 展开态：播放栏在导航栏正上方，宽度和导航栏一样；
    //  · 收紧态：导航栏收成圆钮停在右边，播放栏下移到同一行，宽度让出圆钮的位置。
    val dockProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = LocalBottomDockProgress.current,
        animationSpec = NeriMotion.smooth(),
        label = "mini_player_overlay_dock_progress"
    )
    val navProgress = navCompactProgress(dockProgress)
    val navBottomInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    // 展开态：播放栏的槽位底边停在导航栏顶边再高一点的位置，
    // 两者之间留出一小段缝（播放栏玻璃面和导航栏胶囊不贴在一起）。
    val miniPlayerBottomGap = if (NeriDesign.isClean) {
        NeriMiniPlayerDefaults.SurfaceBottomGap
    } else {
        0.dp
    }
    val expandedBottomPadding = bottomPadding + miniPlayerBottomGap
    // 收紧后播放栏的槽位底边：和右侧那颗圆钮的底边齐平。
    // 不能比展开态还高（那会让播放栏往上跳），所以取两者的较小值。
    val compactBottomPadding = minOf(
        resolveCompactMiniPlayerBottomPadding(
            navigationBarInset = navBottomInset,
            cleanStyle = NeriDesign.isClean
        ),
        expandedBottomPadding
    )
    val effectiveBottomPadding = expandedBottomPadding +
        (compactBottomPadding - expandedBottomPadding) * navProgress
    // 右侧给圆钮留出来的位置
    val reservedForDockButton = compactDockReservedWidth(navProgress)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 底栏在 Scaffold 里的层级更高，不提上来的话点击会穿透到它的标签上
            .zIndex(1f)
    ) {
        Row(
            modifier = Modifier
                // 播放栏贴在屏幕底部（再按底距抬起来），不是顶部
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 和导航栏胶囊用同一套左右边距，两者的玻璃面才真正一样宽
                .padding(
                    start = DockBarHorizontalMargin,
                    end = DockBarHorizontalMargin,
                    bottom = effectiveBottomPadding
                ),
            verticalAlignment = Alignment.Bottom
        ) {
            AnimatedVisibility(
                visible = miniPlayerVisible,
                modifier = Modifier
                    .weight(1f)
                    .zIndex(MINI_PLAYER_OVERLAY_Z_INDEX),
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 2 }
            ) + fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                targetOffsetY = { it / 2 }
            ) + fadeOut(animationSpec = tween(durationMillis = 120))
        ) {
            NeriMiniPlayer(
                title = miniPlayer.title(resources),
                artist = miniPlayer.artist(),
                coverUrl = miniPlayer.coverUrl,
                visualCoverUrl = miniPlayer.visualCoverUrl,
                coverIdentityKey = miniPlayer.songVisualKey,
                visualCoverIdentityKey = miniPlayer.visualCoverSongKey,
                hasCurrentSong = miniPlayer.hasSong,
                clearCover = miniPlayer.song.hasKnownEmptyLocalCover(),
                isPlaying = controls.playbackRequested,
                playPauseEnabled = !controls.usbPreparing,
                modifier = Modifier.testTag("appMiniPlayer")
                    .onGloballyPositioned { coordinates ->
                        onBoundsChanged(coordinates.boundsInRoot())
                    },
                onPlayPause = { PlayerManager.togglePlayPause() },
                onPrevious = { PlayerManager.previous() },
                onNext = { PlayerManager.next() },
                onExpand = onExpandNowPlaying,
                enableBlur = miniPlayer.enableBlur,
                offlineMode = offlineMode,
                isPlaybackWaiting = resolvePlaybackWaiting(
                    playbackRequested = controls.playbackRequested,
                    isPlaying = controls.playing,
                    usbPlaybackPreparing = controls.usbPreparing
                ),
                isAudioRouteMuted = controls.routeMuted,
                tabletControls = tabletControls
            )
            }

            // 右侧圆钮占位：播放栏收窄这么多，正好停在圆钮左边。
            // 圆钮本身在底栏那一层（Scaffold 的 bottomBar 槽位），
            // 层级在播放栏之上，点它不会被下面的标签截走。
            Spacer(modifier = Modifier.width(reservedForDockButton))
        }
    }
}
