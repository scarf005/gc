package dev.scarf.gc

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.Button

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val manager = AppWidgetManager.getInstance(this)
        findViewById<Button>(R.id.pinWidgetButton).apply {
            isEnabled = manager.isRequestPinAppWidgetSupported
            setOnClickListener { manager.requestPinAppWidget(ComponentName(this@MainActivity, ContributionWidgetProvider::class.java), null, null) }
        }
    }
}
