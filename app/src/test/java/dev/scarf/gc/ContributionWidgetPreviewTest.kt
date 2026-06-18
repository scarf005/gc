package dev.scarf.gc

import java.io.File
import kotlin.math.max
import org.junit.Assume.assumeTrue
import org.junit.Test

class ContributionWidgetPreviewTest {
    @Test
    fun writesSvgPreviews() {
        assumeTrue(System.getenv("GC_WIDGET_PREVIEWS") == "1")
        val out = File(System.getenv("GC_WIDGET_PREVIEW_OUT") ?: "build/widget-previews").apply { mkdirs() }
        defaultPreviewSizes.forEach { size ->
            val file = File(out, "gc-widget-${size.widthDp}x${size.heightDp}dp.svg")
            file.writeText(widgetPreviewSvg(size))
            println(file.absolutePath)
        }
    }
}

private data class PreviewSize(val widthDp: Int, val heightDp: Int)

private val defaultPreviewSizes = listOf(
    PreviewSize(widthDp = 250, heightDp = 110),
    PreviewSize(widthDp = 393, heightDp = 110),
    PreviewSize(widthDp = 443, heightDp = 110),
    PreviewSize(widthDp = 700, heightDp = 182),
)

private const val previewDensity = 3f
private const val previewRowCount = 7
private val previewLevelColors = listOf("#ebedf0", "#8ee8a4", "#39ce5b", "#2eb24c", "#278d3b")

private fun widgetPreviewSvg(size: PreviewSize): String {
    val paddingDp = graphPaddingDp(size.heightDp)
    val graphWidthDp = max(1, size.widthDp - paddingDp * 2)
    val graphHeightDp = max(1, size.heightDp - paddingDp * 2)
    val layout = contributionGraphLayout(graphWidthDp, graphHeightDp, targetCellDp = 10, targetGapDp = 2)
    val rows = previewRowCount * layout.weekBlocks
    val widthPx = (size.widthDp * previewDensity).toInt()
    val heightPx = (size.heightDp * previewDensity).toInt()
    val cell = layout.cellDp * previewDensity
    val gap = layout.gapDp * previewDensity
    val draw = max(1f, cell * 0.95f)
    val inset = (cell - draw) / 2f
    val radius = max(1f, draw * 0.12f)
    val gridWidth = layout.columns * cell + (layout.columns - 1) * gap
    val gridHeight = rows * cell + (rows - 1) * gap
    val offsetX = paddingDp * previewDensity + max(0f, (graphWidthDp * previewDensity - gridWidth) / 2f)
    val offsetY = paddingDp * previewDensity + max(0f, (graphHeightDp * previewDensity - gridHeight) / 2f)
    val rects = buildString {
        repeat(layout.columns) { x -> repeat(rows) { y ->
            val left = offsetX + x * (cell + gap) + inset
            val top = offsetY + y * (cell + gap) + inset
            appendLine("  <rect x=\"${left.svg()}\" y=\"${top.svg()}\" width=\"${draw.svg()}\" height=\"${draw.svg()}\" rx=\"${radius.svg()}\" ry=\"${radius.svg()}\" fill=\"${previewLevelColors[previewLevel(x, y)]}\" />")
        } }
    }
    return """
        |<svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
        |  <title>gc widget preview ${size.widthDp}x${size.heightDp}dp, columns=${layout.columns}, cell=${layout.cellDp}dp, gap=${layout.gapDp}dp, padding=${paddingDp}dp</title>
        |  <rect x="0" y="0" width="$widthPx" height="$heightPx" rx="${(8 * previewDensity).svg()}" ry="${(8 * previewDensity).svg()}" fill="#ffffff" />
        |$rects</svg>
        |
    """.trimMargin()
}

private fun previewLevel(column: Int, row: Int) = (column * 37 + row * 17 + column / 3) % previewLevelColors.size

private fun Float.svg() = "%.2f".format(this)
