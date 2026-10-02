package dev.scarf.gc

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetConfigurationActivityTest {
    private lateinit var context: Application
    private var widgetId = 0
    private val cached = emptyContributionStats().let { stats ->
        ContributionStats(stats.days.map { it.copy(level = 3) })
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        mockkObject(ContributionRepository)
        every { ContributionRepository.fetch(any()) } returns Result.success(cached)
        widgetId = shadowOf(AppWidgetManager.getInstance(context)).createWidget(ContributionWidgetProvider::class.java, R.layout.widget_contribution)
        settle()
        WidgetPreferences.writeHandle(context, widgetId, "existing")
        WidgetPreferences.writeStats(context, widgetId, cached)
    }

    @After
    fun tearDown() {
        settle()
        unmockkAll()
    }

    @Test
    fun selectingThemeImmediatelyRepaintsCachedDataWithoutSavingOrFetching() {
        val activity = openSettings()
        selectTheme(activity, WidgetTheme.WinterLight)

        assertEquals(WidgetTheme.WinterLight, WidgetPreferences.readTheme(context, widgetId))
        assertEquals(cached, WidgetPreferences.readStats(context, widgetId))
        assertFalse(activity.isFinishing)
        val graph = shadowOf(AppWidgetManager.getInstance(context)).getViewFor(widgetId).findViewById<ImageView>(R.id.widgetGraph)
        val bitmap = (graph.drawable as BitmapDrawable).bitmap
        assertTrue((0 until bitmap.width).any { x -> (0 until bitmap.height).any { y -> bitmap.getPixel(x, y) == WidgetTheme.WinterLight.levelColors[3] } })
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun openingSettingsKeepsTheStoredThemeWithoutFetching() {
        WidgetPreferences.writeTheme(context, widgetId, WidgetTheme.Dark)
        val activity = openSettings()

        assertEquals(WidgetTheme.Dark.ordinal, activity.findViewById<Spinner>(R.id.themeInput).selectedItemPosition)
        assertEquals(WidgetTheme.Dark, WidgetPreferences.readTheme(context, widgetId))
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun themeSelectionDoesNotSaveAnEditedHandle() {
        val activity = openSettings()
        activity.findViewById<EditText>(R.id.handleInput).setText("unsaved")
        selectTheme(activity, WidgetTheme.HalloweenDark)

        assertEquals("existing", WidgetPreferences.readHandle(context, widgetId))
        assertEquals(WidgetTheme.HalloweenDark, WidgetPreferences.readTheme(context, widgetId))
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun selectingThemeWithoutCacheStillDoesNotFetch() {
        WidgetPreferences.clear(context, widgetId)
        val activity = openSettings()
        selectTheme(activity, WidgetTheme.Dark)

        assertEquals(WidgetTheme.Dark, WidgetPreferences.readTheme(context, widgetId))
        assertEquals(null, WidgetPreferences.readStats(context, widgetId))
        assertFalse(activity.isFinishing)
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun savingAnUnchangedNormalizedHandleDoesNotFetch() {
        val activity = openSettings()
        activity.findViewById<EditText>(R.id.handleInput).setText(" @existing ")
        activity.findViewById<Button>(R.id.saveButton).performClick()
        settle()

        assertTrue(activity.isFinishing)
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun changingHandleFetchesOnceWithoutARefreshBroadcast() {
        val activity = openSettings()
        activity.findViewById<EditText>(R.id.handleInput).setText(" @different ")
        activity.findViewById<Button>(R.id.saveButton).performClick()
        settle()

        assertEquals("different", WidgetPreferences.readHandle(context, widgetId))
        assertTrue(activity.isFinishing)
        verify(exactly = 1) { ContributionRepository.fetch("different") }
        verify(exactly = 1) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun unchangedHandleWithoutCacheCanFetchToCompleteSetup() {
        WidgetPreferences.clear(context, widgetId)
        WidgetPreferences.writeHandle(context, widgetId, "existing")
        val activity = openSettings()
        activity.findViewById<Button>(R.id.saveButton).performClick()
        settle()

        assertEquals(cached, WidgetPreferences.readStats(context, widgetId))
        assertTrue(activity.isFinishing)
        verify(exactly = 1) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun failedHandleChangePreservesCachedDataAndDoesNotFetchAgain() {
        every { ContributionRepository.fetch("missing") } returns Result.failure(IllegalStateException("Handle not found"))
        val activity = openSettings()
        selectTheme(activity, WidgetTheme.Dark)
        activity.findViewById<EditText>(R.id.handleInput).setText("missing")
        activity.findViewById<Button>(R.id.saveButton).performClick()
        settle()

        assertEquals("existing", WidgetPreferences.readHandle(context, widgetId))
        assertEquals(cached, WidgetPreferences.readStats(context, widgetId))
        assertEquals(WidgetTheme.Dark, WidgetPreferences.readTheme(context, widgetId))
        assertEquals(activity.getString(R.string.widget_handle_missing), activity.findViewById<TextView>(R.id.statusView).text.toString())
        assertFalse(activity.isFinishing)
        assertTrue(activity.findViewById<Button>(R.id.saveButton).isEnabled)
        verify(exactly = 1) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun blankHandleShowsValidationWithoutFetchingStoredHandle() {
        val activity = openSettings()
        activity.findViewById<EditText>(R.id.handleInput).setText(" ")
        activity.findViewById<Button>(R.id.saveButton).performClick()
        settle()

        assertEquals("existing", WidgetPreferences.readHandle(context, widgetId))
        assertEquals(activity.getString(R.string.widget_empty_status), activity.findViewById<TextView>(R.id.statusView).text.toString())
        assertFalse(activity.isFinishing)
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun selectingThemeDuringAHandleFetchKeepsTheLatestSelection() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ContributionRepository.fetch("different") } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Result.success(cached)
        }
        val activity = openSettings()
        activity.findViewById<EditText>(R.id.handleInput).setText("different")
        activity.findViewById<Button>(R.id.saveButton).performClick()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            activity.findViewById<Spinner>(R.id.themeInput).setSelection(WidgetTheme.WinterDark.ordinal, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WidgetTheme.WinterDark, WidgetPreferences.readTheme(context, widgetId))
        } finally {
            release.countDown()
        }
        settle()

        assertTrue(activity.isFinishing)
        assertEquals(WidgetTheme.WinterDark, WidgetPreferences.readTheme(context, widgetId))
        verify(exactly = 1) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun explicitRefreshStillFetchesStoredHandle() {
        ContributionWidgetProvider.requestRefresh(context, widgetId)
        settle()

        assertEquals(cached, WidgetPreferences.readStats(context, widgetId))
        verify(exactly = 1) { ContributionRepository.fetch("existing") }
    }

    private fun openSettings(): WidgetConfigurationActivity {
        val intent = Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        return Robolectric.buildActivity(WidgetConfigurationActivity::class.java, intent).setup().visible().get().also { settle() }
    }

    private fun selectTheme(activity: WidgetConfigurationActivity, theme: WidgetTheme) {
        activity.findViewById<Spinner>(R.id.themeInput).setSelection(theme.ordinal, true)
        settle()
    }

    private fun settle() = repeat(2) {
        io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
