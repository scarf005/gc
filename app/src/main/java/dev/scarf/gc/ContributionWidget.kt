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
import kotlin.math.min
import kotlin.math.roundToInt

private const val prefsName = "contribution_widget"
private const val actionRefresh = "dev.scarf.gc.MANUAL_REFRESH"
private const val graphPaddingDp = 6
private const val logTag = "ContributionWidget"
internal val io = Executors.newSingleThreadExecutor()
private fun prefs(context: Context) = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

internal object WidgetPreferences {
    fun readHandle(context: Context, id: Int) = prefs(context).getString("handle_$id", "").orEmpty()
    fun writeHandle(context: Context, id: Int, handle: String) = prefs(context).edit().putString("handle_$id", normalizeHandle(handle)).apply()
    fun readStats(context: Context, id: Int) = prefs(context).getString("stats_$id", "")?.let(::decodeContributionStats)
    fun writeStats(context: Context, id: Int, stats: ContributionStats) = prefs(context).edit().putString("stats_$id", encodeContributionStats(stats)).apply()
    fun clear(context: Context, id: Int) = prefs(context).edit().remove("handle_$id").remove("stats_$id").apply()
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
        val options = renderOptions(context, appWidgetId)
        val bitmap = stats?.let { ContributionBitmapRenderer.render(it, options) } ?: ContributionBitmapRenderer.placeholder(options)
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, RemoteViews(context.packageName, R.layout.widget_contribution).apply {
            setImageViewBitmap(R.id.widgetGraph, bitmap)
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

private fun renderOptions(context: Context, appWidgetId: Int): ContributionBitmapRenderer.RenderOptions {
    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    val density = context.resources.displayMetrics.density
    val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).takeIf { it > 0 } ?: 250
    val heightDp = max(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 40)).takeIf { it > 0 } ?: 40
    val span = { dp: Int -> max(1, min(((dp + 30f) / 70f).toInt(), (dp / 62.5f).toInt())) }
    return ContributionBitmapRenderer.RenderOptions(
        max((widthDp * density).roundToInt() - dp(context, graphPaddingDp * 2), 13),
        max((heightDp * density).roundToInt() - dp(context, graphPaddingDp * 2), 13),
        span(widthDp) * 5,
        span(heightDp),
    )
}

private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
