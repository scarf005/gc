package dev.scarf.gc

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.widget.SwitchCompat
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

class WidgetConfigurationActivity : AppCompatActivity() {
    private data class PreviewSize(
        val widthPx: Int,
        val heightPx: Int,
        val columns: Int,
        val weekBlocks: Int,
    )

    private lateinit var handleInput: EditText
    private lateinit var previewGraph: ImageView
    private lateinit var startOnSundaySwitch: SwitchCompat
    private lateinit var saveButton: MaterialButton

    private var refreshJob: Job? = null

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
        handleInput.doAfterTextChanged { text ->
            queueRefresh(text?.toString().orEmpty())
        }
        startOnSundaySwitch.setOnCheckedChangeListener { _, _ ->
            queueRefresh(handleInput.text.toString())
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
            queueRefresh(savedHandle)
        }
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        super.onDestroy()
    }

    private fun queueRefresh(rawHandle: String) {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            delay(450)
            refreshPreview(rawHandle)
        }
    }

    private suspend fun refreshPreview(rawHandle: String) {
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

        ContributionRepository.fetch(handle)
            .onSuccess { stats ->
                if (handle != normalizeHandle(handleInput.text.toString())) {
                    return@onSuccess
                }
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
            }
            .onFailure { throwable ->
                if (handle != normalizeHandle(handleInput.text.toString())) {
                    return@onFailure
                }
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
