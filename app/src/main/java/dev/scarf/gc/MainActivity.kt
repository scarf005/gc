package dev.scarf.gc

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val pinButton: android.widget.Button = findViewById(R.id.pinWidgetButton)
        val appWidgetManager = AppWidgetManager.getInstance(this)

        pinButton.isEnabled = appWidgetManager.isRequestPinAppWidgetSupported
        pinButton.setOnClickListener {
            appWidgetManager.requestPinAppWidget(
                ComponentName(this, ContributionWidgetProvider::class.java),
                null,
                null,
            )
        }
    }
}
