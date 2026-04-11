package dev.scarf.gc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

private val levelColors = intArrayOf(Color.parseColor("#ebedf0"), Color.parseColor("#8ee8a4"), Color.parseColor("#39ce5b"), Color.parseColor("#2eb24c"), Color.parseColor("#278d3b"))
private const val rowCount = 7
private const val githubCell = 10f
private const val githubGap = 2f

internal object ContributionBitmapRenderer {
    data class RenderOptions(val widthPx: Int, val heightPx: Int, val columns: Int, val weekBlocks: Int)

    fun render(stats: ContributionStats, options: RenderOptions): Bitmap {
        val width = max(1, options.widthPx); val height = max(1, options.heightPx); val columns = max(1, options.columns); val blocks = max(1, options.weekBlocks); val rows = rowCount * blocks
        val (cell, gap) = cellAndGap(width, height, columns, rows)
        val offsetX = max(0, (width - (columns * cell + (columns - 1) * gap)) / 2)
        val offsetY = max(0, (height - (rows * cell + (rows - 1) * gap)) / 2)
        val draw = max(1f, cell * 0.95f); val inset = (cell - draw) / 2f; val radius = max(1f, draw * 0.12f)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap); val paint = Paint().apply { isAntiAlias = true }
        val days = stats.days.associateBy(ContributionDay::date); val first = weekStart(stats.endDate).minusWeeks((columns * blocks - 1L))
        repeat(columns) { x -> repeat(rows) { y ->
            val date = first.plusWeeks((x * blocks + y / rowCount).toLong()).plusDays((y % rowCount).toLong())
            if (date <= stats.endDate) {
                paint.color = levelColors[(days[date]?.level ?: 0).coerceIn(0, levelColors.lastIndex)]
                val left = offsetX + x * (cell + gap) + inset; val top = offsetY + y * (cell + gap) + inset
                canvas.drawRoundRect(RectF(left, top, left + draw, top + draw), radius, radius, paint)
            }
        } }
        return bitmap
    }

    fun placeholder(options: RenderOptions, today: LocalDate = LocalDate.now()) = render(emptyContributionStats(today), options)

    private fun cellAndGap(widthPx: Int, heightPx: Int, columns: Int, rows: Int): Pair<Int, Int> {
        for (cell in minOf(max(1, heightPx / rows), max(1, widthPx / max(1, columns))) downTo 1) {
            val gap = max(1, (cell * githubGap / githubCell).roundToInt())
            if (rows * cell + (rows - 1) * gap <= heightPx && columns * cell + (columns - 1) * gap <= widthPx) return cell to gap
        }
        return 1 to 0
    }

    private fun weekStart(date: LocalDate) = date.minusDays(((date.dayOfWeek.value - DayOfWeek.MONDAY.value + rowCount) % rowCount).toLong())
}
