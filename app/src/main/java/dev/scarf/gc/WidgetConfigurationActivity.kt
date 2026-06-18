package dev.scarf.gc

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView

class WidgetConfigurationActivity : Activity() {
    private lateinit var handleInput: EditText
    private lateinit var statusView: TextView
    private lateinit var themeInput: Spinner
    private lateinit var saveButton: Button

    private val appWidgetId by lazy { intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_configuration)
        handleInput = findViewById(R.id.handleInput)
        statusView = findViewById(R.id.statusView)
        themeInput = findViewById(R.id.themeInput)
        saveButton = findViewById(R.id.saveButton)
        val themes = WidgetTheme.values()
        themeInput.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, themes.map { getString(it.titleResId) }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        themeInput.setSelection(themes.indexOf(WidgetPreferences.readTheme(this, appWidgetId)))
        WidgetPreferences.readHandle(this, appWidgetId).also { handleInput.setText(it); handleInput.setSelection(it.length) }
        saveButton.setOnClickListener {
            save(handleInput.text.toString(), themes[themeInput.selectedItemPosition])
        }
        statusView.setText(R.string.widget_idle_status)
    }

    private fun save(rawHandle: String, theme: WidgetTheme) {
        val handle = normalizeHandle(rawHandle)
        WidgetPreferences.writeTheme(this, appWidgetId, theme)
        if (handle.isBlank()) {
            ContributionWidgetProvider.requestRefresh(this, appWidgetId)
            return statusView.setText(R.string.widget_empty_status)
        }
        statusView.setText(R.string.widget_loading_status)
        saveButton.isEnabled = false
        io.execute {
            val result = ContributionRepository.fetch(handle)
            runOnUiThread {
                saveButton.isEnabled = true
                if (isFinishing) return@runOnUiThread
                result.onSuccess {
                    WidgetPreferences.writeHandle(this, appWidgetId, handle)
                    WidgetPreferences.writeStats(this, appWidgetId, it)
                    WidgetPreferences.writeTheme(this, appWidgetId, theme)
                    ContributionWidgetProvider.requestRefresh(this, appWidgetId)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                    finish()
                }.onFailure {
                    ContributionWidgetProvider.requestRefresh(this, appWidgetId)
                    statusView.setText(when (it.message) {
                        "Handle not found" -> R.string.widget_handle_missing
                        else -> R.string.widget_error_status
                    })
                }
            }
        }
    }
}
