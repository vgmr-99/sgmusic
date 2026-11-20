package moe.ouom.neriplayer.ui.screen.about

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.activity.DisclaimerDetails
import moe.ouom.neriplayer.ui.component.navigation.ReportBottomDockScroll
import moe.ouom.neriplayer.ui.component.overlay.CleanAlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.platform.tryStartActivity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.BuildConfig
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.theme.NeriMotion
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.component.navigation.firstVisibleItemTopPx
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
 * File: moe.ouom.neriplayer.ui.screen.about/AboutScreen
 * Created: 2026/11/20
 *
 * 关于页：大 LOGO 折叠头。
 *
 * 交互参照 MeowPlayer：顶部一张大标志 + 应用名 + 版本号，背景是品牌渐变；
 * 页面向下滚动时标志逐渐缩小淡出，标题栏的"关于"随之浮现。
 * 这里没用标准 LargeTopAppBar，因为它的折叠行为只有标题淡入，
 * 撑不起"大标志缩成小标题"的视觉，所以自己按滚动偏移驱动。
 */

/**
 * 打开关于页的回调。由应用根节点提供，设置页据此跳到独立路由，
 * 而不是渲染设置页内嵌的子页。
 */
internal val LocalAboutPageNavigator = staticCompositionLocalOf<(() -> Unit)?> { null }

/** 折叠头完全收起所需的滚动距离。 */
private val AboutHeaderCollapseDistance = 140.dp

/** 大标志的原始尺寸。 */
private val AboutLogoSize = 132.dp

/** 折叠时大标志向上移动的行程占折叠距离的比例。 */
private const val AboutHeroTravelRatio = 0.55f

/** 折叠时大标志的最小缩放（保留一点存在感，不完全消失）。 */
private const val AboutHeroMinScale = 0.72f

/** 头图区顶部留白：避开状态栏和顶栏返回键。 */
private val AboutHeaderTopPadding = 52.dp

/** 头图区占位高度：列表用它在顶部撑出空间。 */
private val AboutHeaderReservedHeight = 300.dp

/** 顶栏遮罩高度。 */
private val AboutTopBarHeight = 108.dp

/** 列表底部留白，保证内容总高超过视口、可滚动。 */
private val AboutBottomSpacerHeight = 220.dp

/** 折叠时大标志淡出的速度系数：大于 1 表示提前淡尽。 */
private const val AboutHeroFadeRatio = 1.35f

/**
 * 把滚动偏移换算成折叠进度：0 = 完全展开，1 = 完全折叠。
 *
 * @param scrollOffset 列表向下滚动的距离
 * @param collapseDistance 折叠所需的滚动距离
 */
internal fun resolveAboutHeaderCollapseProgress(
    scrollOffset: Int,
    collapseDistance: Float
): Float {
    if (collapseDistance <= 0f) return 0f
    return (scrollOffset / collapseDistance).coerceIn(0f, 1f)
}

/**
 * 折叠进度映射到大标志的透明度。
 *
 * 系数大于 1，让标志在折叠完成前就淡尽，避免和浮现中的顶栏标题打架。
 */
internal fun resolveAboutLogoAlpha(progress: Float): Float {
    val normalized = progress.coerceIn(0f, 1f)
    return (1f - normalized * AboutHeroFadeRatio).coerceIn(0f, 1f)
}

/**
 * 折叠进度映射到大标志的缩放：越往下越小。
 */
internal fun resolveAboutLogoScale(progress: Float): Float {
    val normalized = progress.coerceIn(0f, 1f)
    return 1f - (1f - AboutHeroMinScale) * normalized
}

/**
 * 折叠进度映射到大标志的上移行程（像素）。
 *
 * @param heroTravelPx 折叠总行程
 */
internal fun resolveAboutHeroTranslationY(progress: Float, heroTravelPx: Float): Float {
    val normalized = progress.coerceIn(0f, 1f)
    return -heroTravelPx * AboutHeroTravelRatio * normalized
}

/**
 * 顶栏标题的浮现进度：直接跟随折叠进度。
 */
internal fun resolveAboutTitleRevealProgress(progress: Float): Float =
    progress.coerceIn(0f, 1f)

/**
 * 关于页。
 *
 * @param content 页面主体（版本、仓库、鸣谢等卡片）
 */
@Composable
internal fun AboutScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val listState: LazyListState = rememberLazyListState()
    val density = LocalDensity.current
    val heroTravelPx = with(density) { AboutHeaderCollapseDistance.toPx() }
    // 头图区不在列表里，而是固定在顶部由列表的 contentPadding 撑出空间。
    // 这样列表的滚动偏移就是纯粹的"内容滚了多远"，不会因为首个 item 变化而重置，
    // 折叠进度也就不会中途弹回去。
    val collapseProgress by remember(listState, heroTravelPx) {
        derivedStateOf {
            // 三重判定，任一成立即视为完全折叠：
            // 1) 已经滚过首个 item（列表被拆成多个 item 后会命中）
            // 2) 已经滚到底（内容不够长时 offset 到不了阈值）
            // 3) 偏移量本身超过折叠距离
            val atBottom = !listState.canScrollForward
            if (listState.firstVisibleItemIndex > 0 || atBottom) {
                1f
            } else {
                resolveAboutHeaderCollapseProgress(
                    listState.firstVisibleItemScrollOffset,
                    heroTravelPx
                )
            }
        }
    }
    val titleReveal by animateFloatAsState(
        targetValue = resolveAboutTitleRevealProgress(collapseProgress),
        animationSpec = NeriMotion.smooth(),
        label = "about_title_reveal"
    )

    // 卡片清单要读 stringResource，属于 Composable 调用，
    // 必须在 LazyListScope 之外算好再传进去
    var showDisclaimer by remember { mutableStateOf(false) }
    val aboutScope = rememberCoroutineScope()
    val context = LocalContext.current
    val cardEntries = aboutContentCardEntries(
        onOpenRepo = {
            val intent = Intent(
                Intent.ACTION_VIEW,
                "https://github.com/vgmr-99/sgmusic".toUri()
            )
            context.tryStartActivity(intent)
            Unit
        },
        onShowDisclaimer = { showDisclaimer = true }
    )

    if (showDisclaimer) {
        DisclaimerDialog(onDismiss = { showDisclaimer = false })
    }

    Box(modifier = modifier.fillMaxSize()) {
        AboutHeaderBackground()

        // 关于页也是可滚动的独立路由，接上底部两栏的形态上报，
        // 保持和主 Tab 一致的手感
        ReportBottomDockScroll(
            { aboutScope.launch { listState.scrollToItem(0) } },
            { listState.firstVisibleItemTopPx() }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = AboutHeaderReservedHeight,
                bottom = 40.dp
            )
        ) {
            // 每张卡片一个 item：这样列表滚动时 firstVisibleItemIndex 会变化，
            // 折叠进度能可靠地走到 1（全部卡片塞进单个 item 时索引永远为 0）
            cardEntries.forEach { entry ->
                item(key = entry.key) {
                    AboutInfoCard(
                        title = entry.title,
                        subtitle = entry.subtitle,
                        onClick = entry.onClick,
                        modifier = Modifier.padding(
                            horizontal = 20.dp,
                            vertical = 5.dp
                        )
                    )
                }
            }
            // 底部留白：卡片数量减少后内容可能装得下屏幕，那样就滚不动，
            // 折叠头永远触发不了。这段空白保证内容总高超过视口。
            item(key = "about_bottom_spacer") {
                Spacer(modifier = Modifier.height(AboutBottomSpacerHeight))
            }
        }

        // 头图区：固定在顶部，随滚动缩小上移并淡出。
        // 必须声明在列表之后——Box 按声明顺序绘制，列表在前才不会盖住头图。
        AboutHeader(
            collapseProgress = collapseProgress,
            heroTravelPx = heroTravelPx,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 顶栏：返回键常驻，"关于"标题随折叠浮现
        AboutTopBar(
            titleReveal = titleReveal,
            onBack = onBack
        )
    }
}

/**
 * 关于页正文：版本、构建时间、仓库与来源说明。
 *
 * 卡片样式跟设置页保持一致：白底圆角、标题加副标题。
 */
/** 一张信息卡片的内容。 */
private data class AboutCardEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val onClick: (() -> Unit)? = null
)

/**
 * 关于页的卡片清单。
 *
 * 每张卡片单独成一个列表 item，这样滚动时首个可见 item 的下标会变化，
 * 折叠进度才能可靠地走到完全折叠（全部塞进单个 item 时下标永远是 0）。
 */
@Composable
private fun aboutContentCardEntries(
    onOpenRepo: () -> Unit,
    onShowDisclaimer: () -> Unit
): List<AboutCardEntry> {
    return listOf(
        AboutCardEntry(
            key = "about",
            title = stringResource(CoreCommonR.string.settings_about),
            subtitle = stringResource(R.string.about_app_footer)
        ),
        AboutCardEntry(
            key = "version",
            title = stringResource(CoreCommonR.string.settings_version),
            subtitle = BuildConfig.VERSION_NAME
        ),
        AboutCardEntry(
            key = "repo",
            title = stringResource(CoreCommonR.string.settings_about_repo_address),
            subtitle = stringResource(CoreCommonR.string.settings_github_fork_url),
            onClick = onOpenRepo
        ),
        // 免责声明放在最底下
        AboutCardEntry(
            key = "disclaimer",
            title = stringResource(R.string.disclaimer_button_title),
            subtitle = stringResource(R.string.disclaimer_intro),
            onClick = onShowDisclaimer
        )
    )
}

/**
 * 信息卡片：一行标题 + 一行说明。
 */
@Composable
private fun AboutInfoCard(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            // 描边：卡片底色与页面底色接近时，靠描边保持层次
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = shape
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 背景：品牌渐变。上半部是柔和的紫粉过渡，往下淡出到页面底色。
 */
@Composable
private fun AboutHeaderBackground() {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(AboutHeaderReservedHeight)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        colorScheme.primary.copy(alpha = 0.22f),
                        colorScheme.tertiary.copy(alpha = 0.12f),
                        Color.Transparent
                    )
                )
            )
    )
}

/**
 * 折叠头：大标志 + 应用名 + 版本号。
 *
 * 标志随滚动缩小、上移并淡出，名字跟着一起退场。
 * 整块用同一个 graphicsLayer 驱动，避免逐个子元素做动画导致多次重组。
 */
@Composable
private fun AboutHeader(
    collapseProgress: Float,
    heroTravelPx: Float,
    modifier: Modifier = Modifier
) {
    val logoAlpha = resolveAboutLogoAlpha(collapseProgress)
    val logoScale = resolveAboutLogoScale(collapseProgress)
    val translationY = resolveAboutHeroTranslationY(collapseProgress, heroTravelPx)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = logoAlpha
                this.translationY = translationY
                scaleX = logoScale
                scaleY = logoScale
                // 以顶部中点为原点缩放，标志才会朝顶栏方向收拢而不是原地缩小
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .padding(top = AboutHeaderTopPadding, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(AboutLogoSize)) {
            Image(
                painter = painterResource(id = R.drawable.splash_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = stringResource(R.string.about_app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_UUID.take(8)})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 顶栏：返回键加"关于"标题。
 *
 * 标题在折叠过半后淡入，折叠前完全透明，避免和大标题重叠。
 */
@Composable
private fun AboutTopBar(
    titleReveal: Float,
    onBack: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        // 背景遮罩随折叠进度渐显，否则标题会与滚动上来的内容叠字
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(AboutTopBarHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f * titleReveal),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f * titleReveal),
                            Color.Transparent
                        )
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 36.dp, start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(
                        CoreCommonR.string.action_back
                    )
                )
            }
            Text(
                text = stringResource(CoreCommonR.string.settings_about),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.graphicsLayerAlpha(titleReveal)
            )
        }
    }
}

/** 便捷扩展：graphicsLayer 里设 alpha。 */
private fun Modifier.graphicsLayerAlpha(alpha: Float): Modifier =
    this.then(
        Modifier.graphicsLayer {
            this.alpha = alpha
        }
    )


/**
 * 免责协议弹窗。
 *
 * 内容沿用首次启动时的免责协议（[moe.ouom.neriplayer.activity.DisclaimerDetails]），
 * 保证两处说法一致。协议较长，所以正文区可滚动并限制最大高度。
 */
@Composable
private fun DisclaimerDialog(onDismiss: () -> Unit) {
    CleanAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null
            )
        },
        title = {
            Text(
                text = stringResource(R.string.disclaimer_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            // 用 LazyColumn 而不是 Column + verticalScroll：
            // Material3 的 AlertDialog 会把焦点移到末尾的可聚焦元素，
            // 普通滚动容器会因此被带到内容末尾；LazyColumn 初始位置恒为顶部。
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                state = rememberLazyListState(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "disclaimer_intro") {
                    Text(
                        text = stringResource(R.string.disclaimer_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item(key = "disclaimer_details") {
                    DisclaimerDetails()
                }
                item(key = "disclaimer_updated") {
                    Text(
                        text = stringResource(R.string.disclaimer_last_updated),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        },
        dismissButton = null,
        properties = DialogProperties(),
        modifier = Modifier.padding(horizontal = 24.dp)
    )
}
