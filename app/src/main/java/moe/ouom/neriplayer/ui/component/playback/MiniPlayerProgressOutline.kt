package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.core.player.PlayerManager
import kotlin.math.min

/**
 * 收紧态迷你播放条的边框进度。
 *
 * 为什么不用 drawArc：它画的是椭圆弧，贴不住胶囊的圆角；
 * 为什么不用 sweepGradient：它按中心角度取色，不是沿周长。
 * 这里把胶囊轮廓转成 Path，用 dashPathEffect 在周长上取一段，
 * 既能严丝合缝贴着圆角，又天然处理绕回，不需要手动算四段弧。
 */

/** 一圈巡游时长。播放时线段沿边框缓慢移动，形成跑马灯感。 */
private const val MarqueeLapDurationMs = 12_000

/** 进度切段的阈值：低于它就不画，避免 0dp 的 dash 触发绘制异常。 */
private const val MinVisibleProgress = 0.001f

/** 播放进度：无时长源（直播 / 未知时长）返回 0，绝不画假进度。 */
internal fun resolvePlaybackProgress(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.coerceAtLeast(0L).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/** 圆角矩形的周长：四条直线 + 四个四分之一圆。 */
internal fun resolveRoundedRectPerimeter(size: Size, radiusPx: Float): Float {
    val width = size.width
    val height = size.height
    val radius = radiusPx.coerceIn(0f, min(width, height) / 2f)
    return 2f * (width - 2f * radius) +
        2f * (height - 2f * radius) +
        2f * Math.PI.toFloat() * radius
}

/**
 * dash 的 [on, off] 区间。on = 进度占周长的长度，off 补足一圈；
 * phase 是巡游偏移，由调用方按周长换算。
 */
internal fun resolveProgressDashIntervals(perimeterPx: Float, progress: Float): FloatArray? {
    val fraction = progress.coerceIn(0f, 1f)
    if (fraction <= MinVisibleProgress || perimeterPx <= 0f) return null
    val onPx = perimeterPx * fraction
    val offPx = (perimeterPx - onPx).coerceAtLeast(1f)
    return floatArrayOf(onPx, offPx)
}

/** 绘制所需的三个动态值。它们以状态读取的方式参与绘制，只触发重绘不触发重组。 */
internal class MiniPlayerProgressVisual(
    val progress: () -> Float,
    val phase: () -> Float,
    val alpha: () -> Float
)

/**
 * 收集播放进度与跑马灯相位。
 *
 * 刻意不返回 Compose State 给调用方：这里只更新内部状态，
 * 边框绘制在 draw 阶段读取，重绘范围只有这一条描边，
 * 不会带动封面 / 文字 / 玻璃层重组。
 */
@Composable
internal fun rememberMiniPlayerProgressVisual(
    active: Boolean,
    isPlaying: Boolean
): MiniPlayerProgressVisual {
    val progress = remember { Animatable(0f) }
    val duration by PlayerManager.playbackDurationFlow.collectAsStateWithLifecycle()

    LaunchedEffect(duration) {
        PlayerManager.playbackPositionFlow.collect { position ->
            val next = resolvePlaybackProgress(position, duration)
            if (kotlin.math.abs(progress.value - next) > 0.0005f) {
                progress.snapTo(next)
            }
        }
    }

    val phase = remember { Animatable(0f) }
    LaunchedEffect(active, isPlaying) {
        if (!active || !isPlaying) {
            phase.snapTo(0f)
            return@LaunchedEffect
        }
        // 进度线沿边框巡游；切歌 / 暂停后相位归零，不留下一段错位的亮线
        while (true) {
            phase.animateTo(1f, animationSpec = tween(MarqueeLapDurationMs, easing = LinearEasing))
            phase.snapTo(0f)
        }
    }

    val alpha = remember { Animatable(0f) }
    LaunchedEffect(active) {
        alpha.animateTo(if (active) 1f else 0f, animationSpec = tween(220))
    }

    return remember {
        MiniPlayerProgressVisual(
            progress = { progress.value },
            phase = { phase.value },
            alpha = { alpha.value }
        )
    }
}

/** 从 Shape 的轮廓解析出路径与周长。 */
private data class ProgressOutlinePath(
    val path: Path,
    val perimeterPx: Float
)

/**
 * 从 Shape 取实际轮廓路径和周长。
 *
 * Clean 风格用的是连续圆角 Shape，createOutline 返回的是 [Outline.Generic]，
 * 早期这里只处理 Rounded/Rectangle，结果进度线一条都画不出来。
 * 三种轮廓都要支持，Generic 用 PathMeasure 量真实周长。
 */
private fun DrawScope.resolveProgressOutlinePath(shape: Shape): ProgressOutlinePath? {
    val outline = shape.createOutline(size, layoutDirection, this)
    return when (outline) {
        is Outline.Rounded -> {
            val radius = min(outline.roundRect.width / 2f, outline.roundRect.height / 2f)
            ProgressOutlinePath(
                path = Path().apply { addRoundRect(outline.roundRect) },
                perimeterPx = resolveRoundedRectPerimeter(size, radius)
            )
        }

        is Outline.Rectangle -> ProgressOutlinePath(
            path = Path().apply { addRect(outline.rect) },
            perimeterPx = 2f * (size.width + size.height)
        )

        is Outline.Generic -> {
            val path = outline.path
            val length = android.graphics.PathMeasure(path.asAndroidPath(), false).length
            if (length <= 0f) null else ProgressOutlinePath(path = path, perimeterPx = length)
        }

        else -> null
    }
}

/** 在胶囊边框上画进度线段。必须接在 [drawWithContent] 的内容之后，不能盖住内容。 */
internal fun Modifier.miniPlayerProgressOutline(
    shape: Shape,
    color: Color,
    visual: MiniPlayerProgressVisual,
    strokeWidth: Dp = 2.5.dp,
    /** 底衬色：主题色在浅色玻璃面上常常不够跳，铺一层深色让进度段始终看得清。 */
    backingColor: Color = Color.Black
): Modifier = drawWithContent {
    drawContent()
    val visibility = visual.alpha()
    if (visibility <= 0.01f) return@drawWithContent
    val progress = visual.progress()
    if (progress <= MinVisibleProgress) return@drawWithContent
    val geometry = resolveProgressOutlinePath(shape) ?: return@drawWithContent
    val intervals = resolveProgressDashIntervals(geometry.perimeterPx, progress)
        ?: return@drawWithContent
    val phaseOffset = visual.phase() * geometry.perimeterPx

    // 先用稍宽的低透明度深色描一段，主色再叠在上面。
    // 这样无论当前主题色是深是浅、玻璃面是透明还是实底，进度段都有清晰轮廓。
    drawPath(
        path = geometry.path,
        brush = SolidColor(backingColor.copy(alpha = 0.28f * visibility)),
        style = Stroke(
            width = (strokeWidth + 1.5.dp).toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(intervals, phaseOffset)
        )
    )
    drawPath(
        path = geometry.path,
        brush = SolidColor(color.copy(alpha = color.alpha * visibility)),
        style = Stroke(
            width = strokeWidth.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(intervals, phaseOffset)
        )
    )
}
