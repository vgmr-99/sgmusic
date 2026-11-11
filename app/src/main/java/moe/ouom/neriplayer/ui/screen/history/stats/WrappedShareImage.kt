package moe.ouom.neriplayer.ui.screen.history.stats

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.stats.TrackStat
import java.io.File

/**
 * 听歌总结分享卡片。
 *
 * 参照主流音乐 App 的"听歌收据"：把统计数字渲染成一张可分享的竖版图片。
 */
internal object WrappedShareImage {

    private const val CardWidth = 1080
    private const val CardHeight = 1620
    private const val Padding = 72f
    private const val CacheDirName = "wrapped_share_cards"

    /**
     * 生成分享卡片并写入缓存目录，返回文件路径。
     */
    suspend fun create(
        context: Context,
        periodLabel: String,
        summary: WrappedShareSummary
    ): File = withContext(Dispatchers.IO) {
        val bitmap = createBitmap(CardWidth, CardHeight)
        try {
            val canvas = Canvas(bitmap)
            val cardRect = RectF(0f, 0f, CardWidth.toFloat(), CardHeight.toFloat())
            val cardPath = Path().apply {
                addRoundRect(cardRect, 64f, 64f, Path.Direction.CW)
            }
            canvas.clipPath(cardPath)

            drawBackground(canvas, cardRect)
            drawHeader(canvas, periodLabel)
            drawSummary(canvas, summary)
            drawTopTracks(canvas, summary.topTracks)
            drawTopArtists(canvas, summary.topArtists)

            val file = writeCardFile(context, bitmap)
            bitmap.recycle()
            file
        } catch (error: Throwable) {
            if (!bitmap.isRecycled) bitmap.recycle()
            throw error
        }
    }

    private fun drawBackground(canvas: Canvas, rect: RectF) {
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                intArrayOf(
                    AndroidColor.rgb(28, 24, 46),
                    AndroidColor.rgb(72, 44, 110),
                    AndroidColor.rgb(18, 16, 30)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, backgroundPaint)

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.top + rect.height() * 0.45f,
                intArrayOf(AndroidColor.argb(90, 255, 255, 255), AndroidColor.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, glowPaint)
    }

    private fun drawHeader(canvas: Canvas, periodLabel: String) {
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(160, 255, 255, 255)
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("拾光音乐", Padding, 130f, brandPaint)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 82f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("$periodLabel 听歌总结", Padding, 236f, titlePaint)
    }

    private fun drawSummary(canvas: Canvas, summary: WrappedShareSummary) {
        val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 68f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(190, 255, 255, 255)
            textSize = 34f
        }

        val columnWidth = (CardWidth - Padding * 2) / 3f
        canvas.drawText(summary.totalPlayCount.toString(), Padding, 360f, valuePaint)
        canvas.drawText("次播放", Padding, 408f, labelPaint)

        canvas.drawText(summary.totalListenLabel, Padding + columnWidth, 360f, valuePaint)
        canvas.drawText("累计收听", Padding + columnWidth, 408f, labelPaint)

        canvas.drawText(summary.trackCount.toString(), Padding + columnWidth * 2, 360f, valuePaint)
        canvas.drawText("首歌曲", Padding + columnWidth * 2, 408f, labelPaint)
    }

    private fun drawTopTracks(canvas: Canvas, tracks: List<TrackStat>) {
        val sectionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val itemPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(225, 255, 255, 255)
            textSize = 34f
        }
        val rankPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(150, 255, 255, 255)
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        canvas.drawText("最常播放", Padding, 520f, sectionPaint)
        var y = 590f
        tracks.take(WrappedShareTopLimit).forEachIndexed { index, track ->
            canvas.drawText("${index + 1}", Padding, y, rankPaint)
            canvas.drawText(
                buildTrackLine(track),
                Padding + 60f,
                y,
                itemPaint
            )
            y += 56f
        }
    }

    private fun drawTopArtists(canvas: Canvas, artists: List<WrappedArtist>) {
        val sectionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val itemPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(225, 255, 255, 255)
            textSize = 34f
        }

        val sectionTop = CardHeight - Padding - 320f
        canvas.drawText("最爱艺人", Padding, sectionTop, sectionPaint)
        var y = sectionTop + 70f
        artists.take(WrappedShareTopLimit).forEachIndexed { index, artist ->
            canvas.drawText("${index + 1}. ${artist.name}", Padding, y, itemPaint)
            y += 50f
        }
    }

    private fun buildTrackLine(track: TrackStat): String {
        val artist = track.artist.trim().ifEmpty { UnknownArtistName }
        return "${track.name} - $artist"
    }

    private fun writeCardFile(context: Context, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, CacheDirName).apply {
            if (!exists()) mkdirs()
        }
        val file = File(dir, "wrapped-${System.currentTimeMillis()}.png")
        file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, output)
        }
        return file
    }
}

/** 分享卡片需要的数据快照。 */
internal data class WrappedShareSummary(
    val totalPlayCount: Long,
    val totalListenLabel: String,
    val trackCount: Long,
    val topTracks: List<TrackStat>,
    val topArtists: List<WrappedArtist>
)

internal const val WrappedShareTopLimit = 5
