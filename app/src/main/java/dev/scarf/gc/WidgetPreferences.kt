package dev.scarf.gc

import android.content.Context

private const val prefsName = "contribution_widget"

internal object WidgetPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun readHandle(context: Context, appWidgetId: Int): String = prefs(context)
        .getString("handle_$appWidgetId", "")
        .orEmpty()

    fun writeHandle(context: Context, appWidgetId: Int, handle: String) {
        prefs(context).edit().putString("handle_$appWidgetId", normalizeHandle(handle)).apply()
    }

    fun readStartOnSunday(context: Context, appWidgetId: Int): Boolean = prefs(context)
        .getBoolean("week_start_sunday_$appWidgetId", false)

    fun writeStartOnSunday(context: Context, appWidgetId: Int, startOnSunday: Boolean) {
        prefs(context).edit().putBoolean("week_start_sunday_$appWidgetId", startOnSunday).apply()
    }

    fun clear(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove("handle_$appWidgetId")
            .remove("week_start_sunday_$appWidgetId")
            .apply()
    }
}
