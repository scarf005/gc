package dev.scarf.gc

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val pinButton: MaterialButton = findViewById(R.id.pinWidgetButton)
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
