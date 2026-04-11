package dev.scarf.gc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.RemoteViews
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object ContributionWidgetUpdater {
    fun refreshStored(context: Context, appWidgetId: Int): Result<ContributionStats> {
        val handle = WidgetPreferences.readHandle(context, appWidgetId)
        if (handle.isBlank()) {
            showPlaceholder(context, appWidgetId)
            return Result.success(emptyContributionStats())
        }

        return ContributionRepository.fetch(handle)
            .onSuccess { stats ->
                showContent(context, appWidgetId, stats)
            }
            .onFailure {
                showError(context, appWidgetId)
            }
    }

    fun showPlaceholder(context: Context, appWidgetId: Int) {
        val renderOptions = widgetRenderOptions(context, appWidgetId)
        val bitmap = ContributionBitmapRenderer.placeholder(options = renderOptions)
        pushState(context = context, appWidgetId = appWidgetId, bitmap = bitmap)
    }

    fun showContent(context: Context, appWidgetId: Int, stats: ContributionStats) {
        val renderOptions = widgetRenderOptions(context, appWidgetId)
        val bitmap = ContributionBitmapRenderer.render(stats = stats, options = renderOptions)
        pushState(context = context, appWidgetId = appWidgetId, bitmap = bitmap)
    }

    fun showError(context: Context, appWidgetId: Int) {
        val renderOptions = widgetRenderOptions(context, appWidgetId)
        val bitmap = ContributionBitmapRenderer.placeholder(options = renderOptions)
        pushState(context = context, appWidgetId = appWidgetId, bitmap = bitmap)
    }

    fun allWidgetIds(context: Context): IntArray = AppWidgetManager
        .getInstance(context)
        .getAppWidgetIds(ComponentName(context, ContributionWidgetProvider::class.java))

    private fun pushState(
        context: Context,
        appWidgetId: Int,
        bitmap: Bitmap,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_contribution).apply {
            setImageViewBitmap(R.id.widgetGraph, bitmap)
            setOnClickPendingIntent(R.id.widgetRoot, settingsPendingIntent(context, appWidgetId))
        }
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
    }

    private fun settingsPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetConfigurationActivity::class.java).putExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            appWidgetId,
        )
        return PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private data class WidgetGraphSize(
        val widthPx: Int,
        val heightPx: Int,
        val columns: Int,
        val weekBlocks: Int,
    )

    private const val graphPaddingDp = 6

    private fun widgetRenderOptions(context: Context, appWidgetId: Int): ContributionBitmapRenderer.RenderOptions {
        val size = widgetGraphSize(context, appWidgetId)
        return ContributionBitmapRenderer.RenderOptions(
            widthPx = size.widthPx,
            heightPx = size.heightPx,
            columns = size.columns,
            weekBlocks = size.weekBlocks,
            startOnSunday = WidgetPreferences.readStartOnSunday(context, appWidgetId),
        )
    }

    private fun widgetGraphSize(context: Context, appWidgetId: Int): WidgetGraphSize {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        return widgetGraphSize(context, options)
    }

    private fun widgetGraphSize(context: Context, options: Bundle): WidgetGraphSize {
        val density = context.resources.displayMetrics.density
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            .takeIf { it > 0 }
            ?: 250
        val heightDp = max(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 40),
        ).takeIf { it > 0 } ?: 40
        val widthPx = max((widthDp * density).roundToInt() - dp(context, graphPaddingDp * 2), 13)
        val heightPx = max((heightDp * density).roundToInt() - dp(context, graphPaddingDp * 2), 13)
        val spanX = max(
            1,
            min(
                ((widthDp + 30f) / 70f).toInt(),
                (widthDp / 62.5f).toInt(),
            ),
        )
        val spanY = max(
            1,
            min(
                ((heightDp + 30f) / 70f).toInt(),
                (heightDp / 62.5f).toInt(),
            ),
        )
        return WidgetGraphSize(
            widthPx = widthPx,
            heightPx = heightPx,
            columns = spanX * 5,
            weekBlocks = spanY,
        )
    }
}

internal fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
