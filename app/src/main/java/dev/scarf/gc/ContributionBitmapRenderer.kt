package dev.scarf.gc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.time.LocalDate
import java.time.DayOfWeek
import kotlin.math.max
import kotlin.math.roundToInt

private val levelColors = intArrayOf(
    Color.parseColor("#ebedf0"),
    Color.parseColor("#8ee8a4"),
    Color.parseColor("#39ce5b"),
    Color.parseColor("#2eb24c"),
    Color.parseColor("#278d3b"),
)

private const val rowCount = 7
private const val githubCell = 10f
private const val githubGap = 2f

internal object ContributionBitmapRenderer {
    data class RenderOptions(
        val widthPx: Int,
        val heightPx: Int,
        val columns: Int,
        val weekBlocks: Int,
        val startOnSunday: Boolean,
    )

    fun render(
        stats: ContributionStats,
        options: RenderOptions,
    ): Bitmap {
        val safeWidth = max(options.widthPx, 1)
        val safeHeight = max(options.heightPx, 1)
        val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val daysByDate = stats.days.associateBy(ContributionDay::date)
        val geometry = graphGeometry(options)
        val latestWeekStart = weekStart(stats.endDate, options.startOnSunday)
        val firstWeekStart = latestWeekStart.minusWeeks((geometry.columns * geometry.weekBlocks - 1L))
        val paint = Paint().apply { isAntiAlias = true }
        val drawSize = max(1f, geometry.cell * 0.95f)
        val inset = (geometry.cell - drawSize) / 2f
        val radius = max(1f, drawSize * 0.12f)

        repeat(geometry.columns) { column ->
            repeat(geometry.rows) { row ->
                val weekBlock = row / rowCount
                val dayIndex = row % rowCount
                val weekIndex = (column * geometry.weekBlocks) + weekBlock
                val date = firstWeekStart.plusWeeks(weekIndex.toLong()).plusDays(dayIndex.toLong())
                if (date > stats.endDate) {
                    return@repeat
                }
                val level = daysByDate[date]?.level ?: 0
                paint.color = levelColors[level.coerceIn(levelColors.indices)]
                val left = geometry.offsetX + column * (geometry.cell + geometry.gap)
                val top = geometry.offsetY + row * (geometry.cell + geometry.gap)
                canvas.drawRoundRect(
                    RectF(
                        left + inset,
                        top + inset,
                        left + inset + drawSize,
                        top + inset + drawSize,
                    ),
                    radius,
                    radius,
                    paint,
                )
            }
        }

        return bitmap
    }

    fun placeholder(
        options: RenderOptions,
        today: LocalDate = LocalDate.now(),
    ): Bitmap = render(
        stats = emptyContributionStats(today),
        options = options,
    )

    private data class Geometry(
        val cell: Int,
        val gap: Int,
        val columns: Int,
        val weekBlocks: Int,
        val rows: Int,
        val offsetX: Int,
        val offsetY: Int,
    )

    private fun graphGeometry(options: RenderOptions): Geometry {
        val columns = max(1, options.columns)
        val weekBlocks = max(1, options.weekBlocks)
        val rows = rowCount * weekBlocks
        val (cell, gap) = cellAndGap(
            widthPx = options.widthPx,
            heightPx = options.heightPx,
            columns = columns,
            rows = rows,
        )
        val graphWidth = (columns * cell) + ((columns - 1) * gap)
        val graphHeight = (rows * cell) + ((rows - 1) * gap)
        return Geometry(
            cell = cell,
            gap = gap,
            columns = columns,
            weekBlocks = weekBlocks,
            rows = rows,
            offsetX = max(0, (options.widthPx - graphWidth) / 2),
            offsetY = max(0, (options.heightPx - graphHeight) / 2),
        )
    }

    private fun cellAndGap(widthPx: Int, heightPx: Int, columns: Int, rows: Int): Pair<Int, Int> {
        val maxCellByHeight = max(1, heightPx / rows)
        val maxCellByWidth = max(1, widthPx / max(1, columns))
        for (cell in minOf(maxCellByHeight, maxCellByWidth) downTo 1) {
            val gap = max(1, (cell * githubGap / githubCell).roundToInt())
            val totalHeight = (rows * cell) + ((rows - 1) * gap)
            val totalWidth = (columns * cell) + ((columns - 1) * gap)
            if (totalHeight <= heightPx && totalWidth <= widthPx) {
                return cell to gap
            }
        }
        return 1 to 0
    }

    private fun weekStart(date: LocalDate, startOnSunday: Boolean): LocalDate {
        val offset = if (startOnSunday) {
            date.dayOfWeek.value % rowCount
        } else {
            (date.dayOfWeek.value - DayOfWeek.MONDAY.value + rowCount) % rowCount
        }
        return date.minusDays(offset.toLong())
    }
}

private fun Int.coerceIn(indices: IntRange): Int = coerceIn(indices.first, indices.last)
