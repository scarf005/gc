package dev.scarf.gc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import org.junit.Assume.assumeTrue
import org.junit.Test

class ContributionWidgetPreviewTest {
    @Test
    fun writesSvgPreviews() {
        assumeTrue(System.getenv("GC_WIDGET_PREVIEWS") == "1")
        val out = File(System.getenv("GC_WIDGET_PREVIEW_OUT") ?: "build/widget-previews").apply { mkdirs() }
        WidgetTheme.values().forEach { theme -> defaultPreviewSizes.forEach { size ->
            val suffix = if (theme == WidgetTheme.Light) "" else "-${theme.key}"
            val file = File(out, "gc-widget-${size.widthDp}x${size.heightDp}dp$suffix.svg")
            file.writeText(widgetPreviewSvg(size, theme))
            println(file.absolutePath)
        } }
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
private fun widgetPreviewSvg(size: PreviewSize, theme: WidgetTheme): String {
    val levelColors = theme.levelColors.map { "#%06x".format(it and 0xffffff) }
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
            appendLine("  <rect x=\"${left.svg()}\" y=\"${top.svg()}\" width=\"${draw.svg()}\" height=\"${draw.svg()}\" rx=\"${radius.svg()}\" ry=\"${radius.svg()}\" fill=\"${levelColors[previewLevel(x, y)]}\" />")
            theme.borderColor?.let { border ->
                appendLine("  <rect x=\"${(left + 0.5f).svg()}\" y=\"${(top + 0.5f).svg()}\" width=\"${(draw - 1f).svg()}\" height=\"${(draw - 1f).svg()}\" rx=\"${radius.svg()}\" ry=\"${radius.svg()}\" fill=\"none\" stroke=\"#%06x\" stroke-width=\"1\" />".format(border and 0xffffff))
            }
        } }
    }
    return """
        |<svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
        |  <title>gc widget preview ${size.widthDp}x${size.heightDp}dp, columns=${layout.columns}, cell=${layout.cellDp}dp, gap=${layout.gapDp}dp, padding=${paddingDp}dp</title>
        |  <rect x="0" y="0" width="$widthPx" height="$heightPx" rx="${(8 * previewDensity).svg()}" ry="${(8 * previewDensity).svg()}" fill="${previewBackground(theme)}" />
        |$rects</svg>
        |
    """.trimMargin()
}

private fun previewBackground(theme: WidgetTheme): String {
    if (theme.backgroundResId == android.R.color.transparent) return "none"
    val name = R.drawable::class.java.fields.single { it.getInt(null) == theme.backgroundResId }.name
    val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val drawable = parser.parse(File("src/main/res/drawable/$name.xml"))
    val colorName = drawable.getElementsByTagName("solid").item(0).attributes.getNamedItem("android:color").nodeValue.removePrefix("@color/")
    val colors = parser.parse(File("src/main/res/values/colors.xml")).getElementsByTagName("color")
    return (0 until colors.length).map { colors.item(it) }.single { it.attributes.getNamedItem("name").nodeValue == colorName }.textContent
}

private fun previewLevel(column: Int, row: Int) = (column * 37 + row * 17 + column / 3) % 5

private fun Float.svg() = "%.2f".format(this)
