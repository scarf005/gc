package dev.scarf.gc

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class ContributionWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
            ACTION_MANUAL_REFRESH -> {
                val pendingResult = goAsync()
                val appWidgetIds = resolveWidgetIds(context, intent)
                AppRuntime.io.execute {
                    runCatching {
                        appWidgetIds.forEach { appWidgetId ->
                            ContributionWidgetUpdater.refreshStored(context, appWidgetId)
                        }
                    }
                    pendingResult.finish()
                }
            }

            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = Unit

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            WidgetPreferences.clear(context, appWidgetId)
        }
    }

    private fun resolveWidgetIds(context: Context, intent: Intent): IntArray = when {
        intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) -> {
            intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: intArrayOf()
        }

        intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID) -> {
            intArrayOf(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
                .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
                .toIntArray()
        }

        else -> ContributionWidgetUpdater.allWidgetIds(context)
    }

    companion object {
        private const val ACTION_MANUAL_REFRESH = "dev.scarf.gc.MANUAL_REFRESH"

        fun requestRefresh(context: Context, appWidgetId: Int) {
            val intent = Intent(context, ContributionWidgetProvider::class.java)
                .setAction(ACTION_MANUAL_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            context.sendBroadcast(intent)
        }
    }
}
