package dev.scarf.gc

import android.app.Application
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
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
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
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
        context.getSystemService(JobScheduler::class.java).cancelAll()
        WidgetRefreshScheduler.resetForTests(context)
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
        selectTheme(activity, "winter-light")

        assertEquals("winter-light", WidgetPreferences.readTheme(context, widgetId).key)
        assertEquals(cached, WidgetPreferences.readStats(context, widgetId))
        assertFalse(activity.isFinishing)
        val graph = shadowOf(AppWidgetManager.getInstance(context)).getViewFor(widgetId).findViewById<ImageView>(R.id.widgetGraph)
        val bitmap = (graph.drawable as BitmapDrawable).bitmap
        val color = theme("winter-light").levelColors(context)[3]
        assertTrue((0 until bitmap.width).any { x -> (0 until bitmap.height).any { y -> bitmap.getPixel(x, y) == color } })
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun selectingTransparentThemePreservesCellAlphaInTheRenderedBitmap() {
        val activity = openSettings()
        selectTheme(activity, "transparent")

        assertEquals("transparent", WidgetPreferences.readTheme(context, widgetId).key)
        val graph = shadowOf(AppWidgetManager.getInstance(context)).getViewFor(widgetId).findViewById<ImageView>(R.id.widgetGraph)
        val bitmap = (graph.drawable as BitmapDrawable).bitmap
        val alphas = (0 until bitmap.width).flatMap { x -> (0 until bitmap.height).map { y -> bitmap.getPixel(x, y) ushr 24 } }
        assertTrue(alphas.contains(0xc4))
        assertTrue(alphas.all { it <= 0xc4 })
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun openingSettingsKeepsTheStoredThemeWithoutFetching() {
        WidgetPreferences.writeTheme(context, widgetId, theme("dark"))
        val activity = openSettings()

        assertEquals(WidgetTheme.all(context).indexOf(theme("dark")), activity.findViewById<Spinner>(R.id.themeInput).selectedItemPosition)
        assertEquals("dark", WidgetPreferences.readTheme(context, widgetId).key)
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun themeSelectionDoesNotSaveAnEditedHandle() {
        val activity = openSettings()
        activity.findViewById<EditText>(R.id.handleInput).setText("unsaved")
        selectTheme(activity, "halloween-dark")

        assertEquals("existing", WidgetPreferences.readHandle(context, widgetId))
        assertEquals("halloween-dark", WidgetPreferences.readTheme(context, widgetId).key)
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun selectingThemeWithoutCacheStillDoesNotFetch() {
        WidgetPreferences.clear(context, widgetId)
        val activity = openSettings()
        selectTheme(activity, "dark")

        assertEquals("dark", WidgetPreferences.readTheme(context, widgetId).key)
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
        selectTheme(activity, "dark")
        activity.findViewById<EditText>(R.id.handleInput).setText("missing")
        activity.findViewById<Button>(R.id.saveButton).performClick()
        settle()

        assertEquals("existing", WidgetPreferences.readHandle(context, widgetId))
        assertEquals(cached, WidgetPreferences.readStats(context, widgetId))
        assertEquals("dark", WidgetPreferences.readTheme(context, widgetId).key)
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
    fun successfulFetchCannotPublishAfterActivityIsDestroyed() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ContributionRepository.fetch("different") } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Result.success(ContributionStats(cached.days.map { it.copy(level = 1) }))
        }
        val controller = Robolectric.buildActivity(
            WidgetConfigurationActivity::class.java,
            Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
        ).setup().visible()
        val activity = controller.get()
        activity.findViewById<EditText>(R.id.handleInput).setText("different")
        activity.findViewById<Button>(R.id.saveButton).performClick()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        controller.destroy()
        release.countDown()
        settle()

        assertEquals("existing", WidgetPreferences.readHandle(context, widgetId))
        assertEquals(cached, WidgetPreferences.readStats(context, widgetId))
        verify(exactly = 1) { ContributionRepository.fetch("different") }
    }

    @Test
    fun failedFetchCannotWriteViewsAfterActivityIsDestroyed() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ContributionRepository.fetch("missing") } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Result.failure(IllegalStateException("Handle not found"))
        }
        val controller = Robolectric.buildActivity(
            WidgetConfigurationActivity::class.java,
            Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
        ).setup().visible()
        val activity = controller.get()
        activity.findViewById<EditText>(R.id.handleInput).setText("missing")
        activity.findViewById<Button>(R.id.saveButton).performClick()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        controller.destroy()
        release.countDown()
        settle()

        assertEquals(activity.getString(R.string.widget_loading_status), activity.findViewById<TextView>(R.id.statusView).text.toString())
        assertFalse(activity.findViewById<Button>(R.id.saveButton).isEnabled)
        assertEquals("existing", WidgetPreferences.readHandle(context, widgetId))
    }

    @Test
    fun replacementCanRetryWithoutAStaleDestroyedCallbackPublishing() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ContributionRepository.fetch("different") } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Result.success(ContributionStats(cached.days.map { it.copy(level = 1) }))
        }
        val controller = Robolectric.buildActivity(
            WidgetConfigurationActivity::class.java,
            Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
        ).setup().visible()
        val old = controller.get()
        old.findViewById<EditText>(R.id.handleInput).setText("different")
        old.findViewById<Button>(R.id.saveButton).performClick()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        controller.destroy()
        release.countDown()
        settle()

        every { ContributionRepository.fetch("missing") } returns Result.failure(IllegalStateException("Handle not found"))
        val replacement = openSettings()
        replacement.findViewById<EditText>(R.id.handleInput).setText("missing")
        replacement.findViewById<Button>(R.id.saveButton).performClick()
        settle()

        assertEquals("existing", WidgetPreferences.readHandle(context, widgetId))
        assertEquals(cached, WidgetPreferences.readStats(context, widgetId))
        assertFalse(replacement.isFinishing)
        assertEquals(replacement.getString(R.string.widget_handle_missing), replacement.findViewById<TextView>(R.id.statusView).text.toString())
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
            activity.findViewById<Spinner>(R.id.themeInput).setSelection(WidgetTheme.all(context).indexOf(theme("winter-dark")), true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("winter-dark", WidgetPreferences.readTheme(context, widgetId).key)
        } finally {
            release.countDown()
        }
        settle()

        assertTrue(activity.isFinishing)
        assertEquals("winter-dark", WidgetPreferences.readTheme(context, widgetId).key)
        verify(exactly = 1) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun staleBackgroundRenderCannotOverwriteANewerThemeRedraw() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val oldTheme = theme("dark")
        val newTheme = theme("winter-light")
        WidgetPreferences.writeTheme(context, widgetId, oldTheme)
        mockkObject(ContributionBitmapRenderer)
        every { ContributionBitmapRenderer.render(any(), any()) } answers {
            if (secondArg<ContributionBitmapRenderer.RenderOptions>().levelColors.contentEquals(oldTheme.levelColors(context))) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            callOriginal()
        }
        try {
            val background = executor.submit { ContributionWidgetUpdater.redrawStored(context, widgetId) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            WidgetPreferences.writeTheme(context, widgetId, newTheme)
            val selection = executor.submit { ContributionWidgetUpdater.redrawStored(context, widgetId) }
            try {
                selection.get(1, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                // A serialized redraw waits for the background render to finish.
            } finally {
                release.countDown()
            }
            background.get(5, TimeUnit.SECONDS)
            selection.get(5, TimeUnit.SECONDS)

            val graph = shadowOf(AppWidgetManager.getInstance(context)).getViewFor(widgetId).findViewById<ImageView>(R.id.widgetGraph)
            val bitmap = (graph.drawable as BitmapDrawable).bitmap
            val color = newTheme.levelColors(context)[3]
            assertTrue((0 until bitmap.width).any { x -> (0 until bitmap.height).any { y -> bitmap.getPixel(x, y) == color } })
            assertEquals(newTheme, WidgetPreferences.readTheme(context, widgetId))
            verify(exactly = 0) { ContributionRepository.fetch(any()) }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun delayedCachedRedrawCannotOverwriteFreshStats() {
        val fresh = ContributionStats(cached.days.map { it.copy(level = 1) })
        every { ContributionRepository.fetch("existing") } returns Result.success(fresh)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val redrawThread = AtomicReference<Thread>()
        val executor = Executors.newFixedThreadPool(2)
        mockkObject(WidgetPreferences)
        every { WidgetPreferences.readStats(context, widgetId) } answers {
            val snapshot = callOriginal()
            if (Thread.currentThread() == redrawThread.get()) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            snapshot
        }
        try {
            val redraw = executor.submit {
                redrawThread.set(Thread.currentThread())
                ContributionWidgetUpdater.redrawStored(context, widgetId)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val refresh = executor.submit { ContributionWidgetUpdater.refreshStored(context, widgetId) }
            try {
                refresh.get(1, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                // An atomic cached redraw holds the monitor while reading its snapshot.
            } finally {
                release.countDown()
            }
            redraw.get(5, TimeUnit.SECONDS)
            refresh.get(5, TimeUnit.SECONDS)

            assertEquals(fresh, WidgetPreferences.readStats(context, widgetId))
            assertGraphLevel(1)
            verify(exactly = 1) { ContributionRepository.fetch("existing") }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun refreshForPreviousHandleCannotOverwriteSavedAccount() = assertPreviousHandleRefreshIsIgnored(Result.success(cached))

    @Test
    fun failedRefreshForPreviousHandleKeepsSavedAccount() = assertPreviousHandleRefreshIsIgnored(Result.failure(IllegalStateException("Unable to load")))

    @Test
    fun emptyRefreshForPreviousHandleKeepsSavedAccount() = assertPreviousHandleRefreshIsIgnored(Result.success(emptyContributionStats()))

    private fun assertPreviousHandleRefreshIsIgnored(result: Result<ContributionStats>) {
        val fresh = ContributionStats(cached.days.map { it.copy(level = 1) })
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ContributionRepository.fetch("different") } returns Result.success(fresh)
        every { ContributionRepository.fetch("existing") } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            result
        }
        val activity = openSettings()
        activity.findViewById<EditText>(R.id.handleInput).setText("different")
        activity.findViewById<Button>(R.id.saveButton).performClick()
        val refresh = io.submit { ContributionWidgetUpdater.refreshStored(context, widgetId) }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.isFinishing)
            assertEquals("different", WidgetPreferences.readHandle(context, widgetId))
            assertEquals(fresh, WidgetPreferences.readStats(context, widgetId))
        } finally {
            release.countDown()
        }
        refresh.get(5, TimeUnit.SECONDS)
        settle()

        assertEquals("different", WidgetPreferences.readHandle(context, widgetId))
        assertEquals(fresh, WidgetPreferences.readStats(context, widgetId))
        assertGraphLevel(1)
        verify(exactly = 1) { ContributionRepository.fetch("different") }
        verify(exactly = 1) { ContributionRepository.fetch("existing") }
    }

    private fun assertGraphLevel(level: Int) {
        val graph = shadowOf(AppWidgetManager.getInstance(context)).getViewFor(widgetId).findViewById<ImageView>(R.id.widgetGraph)
        val bitmap = (graph.drawable as BitmapDrawable).bitmap
        val color = WidgetPreferences.readTheme(context, widgetId).levelColors(context)[level]
        assertTrue((0 until bitmap.width).any { x -> (0 until bitmap.height).any { y -> bitmap.getPixel(x, y) == color } })
    }

    @Test
    fun explicitRefreshStillFetchesStoredHandle() {
        ContributionWidgetProvider.requestRefresh(context, widgetId)
        settle()
        val service = Robolectric.buildService(ContributionWidgetRefreshJobService::class.java).create().get()
        val work = JobWorkItem(Intent(context, ContributionWidgetRefreshJobService::class.java).putExtra("refresh_widget_id", widgetId))
        val params = mockk<JobParameters> {
            every { dequeueWork() } returns work andThen null
            every { completeWork(work) } returns Unit
        }
        assertTrue(service.onStartJob(params))
        settle()

        assertEquals(cached, WidgetPreferences.readStats(context, widgetId))
        verify(exactly = 1) { ContributionRepository.fetch("existing") }
    }

    private fun openSettings(): WidgetConfigurationActivity {
        val intent = Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        return Robolectric.buildActivity(WidgetConfigurationActivity::class.java, intent).setup().visible().get().also { settle() }
    }

    private fun theme(key: String) = WidgetTheme.fromKey(context, key)

    private fun selectTheme(activity: WidgetConfigurationActivity, key: String) {
        activity.findViewById<Spinner>(R.id.themeInput).setSelection(WidgetTheme.all(context).indexOf(theme(key)), true)
        settle()
    }

    private fun settle() = repeat(2) {
        io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
