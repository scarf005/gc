package dev.scarf.gc

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import kotlin.math.max

class WidgetConfigurationActivity : Activity() {
    private data class PreviewSize(
        val widthPx: Int,
        val heightPx: Int,
        val columns: Int,
        val weekBlocks: Int,
    )

    private lateinit var handleInput: EditText
    private lateinit var previewGraph: ImageView
    private lateinit var startOnSundaySwitch: Switch
    private lateinit var saveButton: Button

    private val refreshHandler = AppRuntime.main
    private var refreshToken = 0
    private val refreshRunnable = Runnable {
        refreshPreview(handleInput.text.toString(), ++refreshToken)
    }

    private val appWidgetId: Int by lazy {
        intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_configuration)

        handleInput = findViewById(R.id.handleInput)
        previewGraph = findViewById(R.id.previewGraph)
        startOnSundaySwitch = findViewById(R.id.startOnSundaySwitch)
        saveButton = findViewById(R.id.saveButton)

        val savedHandle = WidgetPreferences.readHandle(this, appWidgetId)
        val savedStartOnSunday = WidgetPreferences.readStartOnSunday(this, appWidgetId)
        handleInput.setText(savedHandle)
        handleInput.setSelection(savedHandle.length)
        startOnSundaySwitch.isChecked = savedStartOnSunday
        handleInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                queueRefresh()
            }
        })
        startOnSundaySwitch.setOnCheckedChangeListener { _, _ ->
            queueRefresh()
        }

        saveButton.setOnClickListener {
            val handle = normalizeHandle(handleInput.text.toString())
            WidgetPreferences.writeHandle(this, appWidgetId, handle)
            WidgetPreferences.writeStartOnSunday(this, appWidgetId, startOnSundaySwitch.isChecked)
            ContributionWidgetProvider.requestRefresh(this, appWidgetId)
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            finish()
        }

        previewGraph.post {
            queueRefresh()
        }
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onDestroy()
    }

    private fun queueRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 450)
    }

    private fun refreshPreview(rawHandle: String, token: Int) {
        val handle = normalizeHandle(rawHandle)
        val previewSize = previewSize()
        if (handle.isBlank()) {
            previewGraph.setImageBitmap(
                ContributionBitmapRenderer.placeholder(
                    options = ContributionBitmapRenderer.RenderOptions(
                        widthPx = previewSize.widthPx,
                        heightPx = previewSize.heightPx,
                        columns = previewSize.columns,
                        weekBlocks = previewSize.weekBlocks,
                        startOnSunday = startOnSundaySwitch.isChecked,
                    ),
                ),
            )
            ContributionWidgetUpdater.showPlaceholder(this, appWidgetId)
            return
        }

        AppRuntime.io.execute {
            val result = ContributionRepository.fetch(handle)
            refreshHandler.post {
                if (isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed)) {
                    return@post
                }
                if (token != refreshToken || handle != normalizeHandle(handleInput.text.toString())) {
                    return@post
                }

                result.onSuccess { stats ->
                    previewGraph.setImageBitmap(
                        ContributionBitmapRenderer.render(
                            stats = stats,
                            options = ContributionBitmapRenderer.RenderOptions(
                                widthPx = previewSize.widthPx,
                                heightPx = previewSize.heightPx,
                                columns = previewSize.columns,
                                weekBlocks = previewSize.weekBlocks,
                                startOnSunday = startOnSundaySwitch.isChecked,
                            ),
                        ),
                    )
                    ContributionWidgetUpdater.showContent(this, appWidgetId, stats)
                }.onFailure {
                    previewGraph.setImageBitmap(
                        ContributionBitmapRenderer.placeholder(
                            options = ContributionBitmapRenderer.RenderOptions(
                                widthPx = previewSize.widthPx,
                                heightPx = previewSize.heightPx,
                                columns = previewSize.columns,
                                weekBlocks = previewSize.weekBlocks,
                                startOnSunday = startOnSundaySwitch.isChecked,
                            ),
                        ),
                    )
                    ContributionWidgetUpdater.showError(this, appWidgetId)
                }
            }
        }
    }

    private fun previewSize(): PreviewSize {
        val widthPx = (previewGraph.width.takeIf { it > 0 } ?: dp(this, 320)) - dp(this, 12)
        val heightPx = (previewGraph.height.takeIf { it > 0 } ?: dp(this, 160)) - dp(this, 12)
        return PreviewSize(
            widthPx = max(13, widthPx),
            heightPx = max(13, heightPx),
            columns = 20,
            weekBlocks = 1,
        )
    }
}
