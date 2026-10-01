package dev.scarf.gc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

private const val prefsName = "contribution_widget"
private const val actionRefresh = "dev.scarf.gc.MANUAL_REFRESH"
private const val referenceGraphPaddingHeightRatio = 0.085f
private const val referenceTargetCellDp = 10
private const val referenceTargetGapDp = 2
private const val logTag = "ContributionWidget"
internal val io = Executors.newSingleThreadExecutor()
private fun prefs(context: Context) = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

internal object WidgetPreferences {
    fun readHandle(context: Context, id: Int) = prefs(context).getString("handle_$id", "").orEmpty()
    fun writeHandle(context: Context, id: Int, handle: String) = prefs(context).edit().putString("handle_$id", normalizeHandle(handle)).apply()
    fun readStats(context: Context, id: Int) = prefs(context).getString("stats_$id", "")?.let(::decodeContributionStats)
    fun writeStats(context: Context, id: Int, stats: ContributionStats) = prefs(context).edit().putString("stats_$id", encodeContributionStats(stats)).apply()
    fun readTheme(context: Context, id: Int) = WidgetTheme.fromKey(prefs(context).getString("theme_$id", null))
    fun writeTheme(context: Context, id: Int, theme: WidgetTheme) = prefs(context).edit().putString("theme_$id", theme.key).apply()
    fun clear(context: Context, id: Int) = prefs(context).edit().remove("handle_$id").remove("stats_$id").remove("theme_$id").apply()
}

class ContributionWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in arrayOf(AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED, actionRefresh, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return super.onReceive(context, intent)
        Log.d(logTag, "onReceive action=${intent.action} ids=${widgetIds(context, intent).joinToString()}")
        val pending = goAsync(); io.execute { widgetIds(context, intent).forEach { ContributionWidgetUpdater.refreshStored(context, it) }; pending.finish() }
    }
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = Unit
    override fun onDeleted(context: Context, appWidgetIds: IntArray) = appWidgetIds.forEach { WidgetPreferences.clear(context, it) }

    companion object {
        fun requestRefresh(context: Context, appWidgetId: Int) = context.sendBroadcast(Intent(context, ContributionWidgetProvider::class.java).setAction(actionRefresh).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
    }
}

internal object ContributionWidgetUpdater {
    fun refreshStored(context: Context, appWidgetId: Int): Result<ContributionStats> {
        val handle = WidgetPreferences.readHandle(context, appWidgetId)
        if (handle.isBlank()) return Result.success(emptyContributionStats()).also { show(context, appWidgetId) }
        val cached = WidgetPreferences.readStats(context, appWidgetId)
        cached?.also {
            Log.d(logTag, "restore cached widget=$appWidgetId handle=$handle end=${it.endDate}")
            show(context, appWidgetId, it)
        }
        val result = ContributionRepository.fetch(handle)
        return result.onSuccess {
            if (shouldUseFetchedStats(it, cached)) {
                WidgetPreferences.writeStats(context, appWidgetId, it)
                Log.d(logTag, "refresh ok widget=$appWidgetId handle=$handle cached=true")
                show(context, appWidgetId, it)
            } else {
                Log.w(logTag, "refresh suspicious-empty widget=$appWidgetId handle=$handle keeping cached graph")
                cached?.let { stats -> show(context, appWidgetId, stats) }
            }
        }.onFailure {
            val displayed = displayedContributionStats(result, cached)
            Log.w(logTag, "refresh failed widget=$appWidgetId handle=$handle cached=${cached != null} message=${it.message}", it)
            displayed?.let { stats -> show(context, appWidgetId, stats) }
        }
    }

    fun allWidgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ContributionWidgetProvider::class.java))

    private fun show(context: Context, appWidgetId: Int, stats: ContributionStats? = null) {
        val theme = WidgetPreferences.readTheme(context, appWidgetId)
        val options = renderOptions(context, appWidgetId, theme)
        val bitmap = stats?.let { ContributionBitmapRenderer.render(it, options.graph) } ?: ContributionBitmapRenderer.placeholder(options.graph)
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, RemoteViews(context.packageName, R.layout.widget_contribution).apply {
            setInt(R.id.widgetGraph, "setBackgroundResource", theme.backgroundResId)
            setImageViewBitmap(R.id.widgetGraph, bitmap)
            setViewPadding(R.id.widgetGraph, options.paddingPx, options.paddingPx, options.paddingPx, options.paddingPx)
            setOnClickPendingIntent(R.id.widgetRoot, settingsIntent(context, appWidgetId))
        })
    }
}

private fun widgetIds(context: Context, intent: Intent) = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
    ?: intent.takeIf { it.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID) }
        ?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        ?.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
        ?.let { intArrayOf(it) }
    ?: ContributionWidgetUpdater.allWidgetIds(context)

private fun settingsIntent(context: Context, appWidgetId: Int) = PendingIntent.getActivity(
    context,
    appWidgetId,
    Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

private data class WidgetRenderOptions(val graph: ContributionBitmapRenderer.RenderOptions, val paddingPx: Int)

private fun renderOptions(context: Context, appWidgetId: Int, theme: WidgetTheme): WidgetRenderOptions {
    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    val density = context.resources.displayMetrics.density
    val widgetHeightDp = widgetHeightDp(
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40),
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 40),
    )
    val paddingDp = graphPaddingDp(widgetHeightDp)
    val bounds = widgetGraphBoundsDp(
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250),
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250),
        widgetHeightDp,
        paddingDp,
    )
    val layout = contributionGraphLayout(bounds.widthDp, bounds.heightDp, referenceTargetCellDp, referenceTargetGapDp)
    return WidgetRenderOptions(
        ContributionBitmapRenderer.RenderOptions(
            max((bounds.widthDp * density).roundToInt(), 13),
            max((bounds.heightDp * density).roundToInt(), 13),
            layout.columns,
            layout.weekBlocks,
            dp(context, layout.cellDp),
            dp(context, layout.gapDp),
            theme.levelColors,
            theme.borderColor,
        ),
        dp(context, paddingDp),
    )
}

internal fun widgetHeightDp(minHeightDp: Int, maxHeightDp: Int) = max(minHeightDp, maxHeightDp).takeIf { it > 0 } ?: 40

internal fun graphPaddingDp(widgetHeightDp: Int) = max(1, (widgetHeightDp * referenceGraphPaddingHeightRatio).roundToInt())

internal data class WidgetGraphBoundsDp(val widthDp: Int, val heightDp: Int)

internal fun widgetGraphBoundsDp(minWidthDp: Int, maxWidthDp: Int, heightDp: Int, graphPaddingDp: Int): WidgetGraphBoundsDp {
    val widthDp = minWidthDp.takeIf { it > 0 } ?: maxWidthDp.takeIf { it > 0 } ?: 250
    return WidgetGraphBoundsDp(max(1, widthDp - graphPaddingDp * 2), max(1, heightDp - graphPaddingDp * 2))
}

internal data class ContributionGraphLayout(val columns: Int, val weekBlocks: Int, val cellDp: Int, val gapDp: Int)

internal fun contributionGraphLayout(widthDp: Int, heightDp: Int, targetCellDp: Int, targetGapDp: Int): ContributionGraphLayout {
    val weekBlocks = 1
    val rows = weekBlocks * 7
    for (cellDp in max(1, heightDp / rows) downTo 1) {
        val gapDp = scaledGap(cellDp, targetCellDp, targetGapDp)
        val heightUsedDp = gridLength(rows, cellDp, gapDp)
        if (heightUsedDp > heightDp) continue
        val columns = fitSlots(widthDp, cellDp, gapDp)
        val horizontalPaddingDp = widthDp - gridLength(columns, cellDp, gapDp)
        val verticalPaddingDp = heightDp - heightUsedDp
        if (horizontalPaddingDp <= verticalPaddingDp || cellDp == 1) return ContributionGraphLayout(columns, weekBlocks, cellDp, gapDp)
    }
    return ContributionGraphLayout(1, weekBlocks, 1, 0)
}

private fun scaledGap(cellDp: Int, targetCellDp: Int, targetGapDp: Int) = if (targetGapDp == 0 || cellDp == 1) 0 else max(1, (cellDp * targetGapDp.toFloat() / targetCellDp).roundToInt())

private fun fitSlots(lengthDp: Int, cellDp: Int, gapDp: Int) = max(1, (lengthDp + gapDp) / (cellDp + gapDp))

private fun gridLength(slots: Int, cellDp: Int, gapDp: Int) = slots * cellDp + max(0, slots - 1) * gapDp

private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
