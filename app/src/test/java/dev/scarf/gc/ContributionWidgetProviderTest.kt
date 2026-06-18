package dev.scarf.gc

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.pm.PackageManager
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
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
class ContributionWidgetProviderTest {
    private lateinit var context: Application
    private var widgetId = 0
    private val cached = emptyContributionStats().let { stats ->
        ContributionStats(stats.days.map { it.copy(level = 2) })
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSystemService(JobScheduler::class.java).cancelAll()
        WidgetRefreshScheduler.resetForTests(context)
        mockkObject(ContributionRepository)
        every { ContributionRepository.fetch(any()) } returns Result.success(cached)
        widgetId = shadowOf(AppWidgetManager.getInstance(context)).createWidget(ContributionWidgetProvider::class.java, R.layout.widget_contribution)
        WidgetPreferences.writeHandle(context, widgetId, "existing")
        WidgetPreferences.writeStats(context, widgetId, cached)
        context.getSystemService(JobScheduler::class.java).cancelAll()
        WidgetRefreshScheduler.resetForTests(context)
        settle()
    }

    @After
    fun tearDown() {
        context.getSystemService(JobScheduler::class.java).cancelAll()
        settle()
        unmockkAll()
    }

    @Test
    fun resizingRedrawsCachedDataWithoutFetching() {
        assertEquals(cached, WidgetPreferences.readStats(context, widgetId))
        ContributionWidgetProvider().onReceive(context, Intent(AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        settle()
        ContributionWidgetUpdater.redrawStored(context, widgetId)

        val graph = shadowOf(AppWidgetManager.getInstance(context)).getViewFor(widgetId).findViewById<ImageView>(R.id.widgetGraph)
        val bitmap = (graph.drawable as BitmapDrawable).bitmap
        val levelColor = WidgetPreferences.readTheme(context, widgetId).levelColors(context)[2]
        val levelPixels = (0 until bitmap.width).sumOf { x -> (0 until bitmap.height).count { y -> bitmap.getPixel(x, y) == levelColor } }
        assertTrue(levelPixels > 0)
        assertTrue(context.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
        assertTrue(!WidgetRefreshScheduler.hasPending(context))
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
    }

    @Test
    fun connectivityPermissionIsDeclaredForConstrainedRefreshJobs() {
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    @Test
    fun allDistinctWidgetRequestsRemainQueuedWithoutAnArbitraryCardinalityLimit() {
        val ids = (1..200).toList().toIntArray()
        WidgetRefreshScheduler.enqueue(context, ids)

        assertEquals(200, WidgetRefreshScheduler.pendingCount(context))
    }

    @Test
    fun missingOsJobIsRebuiltByBootRecoveryAndCanExecute() {
        val refresh = Intent(context, ContributionWidgetProvider::class.java)
            .setAction("dev.scarf.gc.MANUAL_REFRESH")
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        ContributionWidgetProvider().onReceive(context, refresh)
        val jobId = context.getSystemService(JobScheduler::class.java).allPendingJobs.single().id
        context.getSystemService(JobScheduler::class.java).cancel(jobId)
        assertTrue(WidgetRefreshScheduler.hasPending(context))

        ContributionWidgetProvider().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue(context.getSystemService(JobScheduler::class.java).allPendingJobs.isNotEmpty())
        val service = Robolectric.buildService(ContributionWidgetRefreshJobService::class.java).create().get()
        assertTrue(service.onStartJob(paramsFor(workItem(WidgetRefreshScheduler.pendingTokenForTests(context, widgetId).orEmpty()))))
        settle()
        verify(exactly = 1) { ContributionRepository.fetch("existing") }
    }

    @Test
    fun ordinaryUpdateRebuildsWorkAfterOsJobCancellation() {
        val refresh = Intent(context, ContributionWidgetProvider::class.java)
            .setAction("dev.scarf.gc.MANUAL_REFRESH")
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        ContributionWidgetProvider().onReceive(context, refresh)
        val jobId = context.getSystemService(JobScheduler::class.java).allPendingJobs.single().id
        context.getSystemService(JobScheduler::class.java).cancel(jobId)
        assertTrue(WidgetRefreshScheduler.hasPending(context))

        ContributionWidgetProvider().onReceive(context, Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))

        assertTrue(context.getSystemService(JobScheduler::class.java).allPendingJobs.isNotEmpty())
        val service = Robolectric.buildService(ContributionWidgetRefreshJobService::class.java).create().get()
        assertTrue(service.onStartJob(paramsFor(workItem(WidgetRefreshScheduler.pendingTokenForTests(context, widgetId).orEmpty()))))
        settle()
        verify(exactly = 1) { ContributionRepository.fetch("existing") }
    }

    @Test
    fun allEnqueueFailuresRemainRetryableWithoutThrowing() {
        val scheduler = mockk<JobScheduler>()
        every { scheduler.enqueue(any(), any()) } returns JobScheduler.RESULT_FAILURE
        every { scheduler.getPendingJob(any()) } returns mockk<JobInfo>()
        WidgetRefreshScheduler.schedulerOverride = scheduler
        WidgetRefreshScheduler.resetForTests(context)
        try {
            assertTrue(runCatching { WidgetRefreshScheduler.enqueue(context, intArrayOf(41)) }.isSuccess)
            assertEquals(0, WidgetRefreshScheduler.pendingCount(context))
            every { scheduler.enqueue(any(), any()) } returns JobScheduler.RESULT_SUCCESS
            WidgetRefreshScheduler.enqueue(context, intArrayOf(41))

            assertEquals(1, WidgetRefreshScheduler.pendingCount(context))
        } finally {
            WidgetRefreshScheduler.schedulerOverride = null
        }
    }

    @Test
    fun partialEnqueueFailureRecordsOnlyAcceptedItemsAndRetriesRejectedItems() {
        val scheduler = mockk<JobScheduler>()
        every { scheduler.enqueue(any(), any()) } returnsMany listOf(JobScheduler.RESULT_SUCCESS, JobScheduler.RESULT_FAILURE)
        every { scheduler.getPendingJob(any()) } returns mockk<JobInfo>()
        WidgetRefreshScheduler.schedulerOverride = scheduler
        WidgetRefreshScheduler.resetForTests(context)
        try {
            WidgetRefreshScheduler.enqueue(context, intArrayOf(41, 42))
            assertEquals(1, WidgetRefreshScheduler.pendingCount(context))
            every { scheduler.enqueue(any(), any()) } returns JobScheduler.RESULT_SUCCESS
            WidgetRefreshScheduler.enqueue(context, intArrayOf(41, 42))

            assertEquals(2, WidgetRefreshScheduler.pendingCount(context))
            verify(exactly = 3) { scheduler.enqueue(any(), any()) }
        } finally {
            WidgetRefreshScheduler.schedulerOverride = null
        }
    }

    @Test
    fun refreshBroadcastsCoalesceIntoOneBackgroundRefresh() {
        val refresh = Intent(context, ContributionWidgetProvider::class.java)
            .setAction("dev.scarf.gc.MANUAL_REFRESH")
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        ContributionWidgetProvider().onReceive(context, refresh)
        ContributionWidgetProvider().onReceive(context, refresh)

        val jobs = context.getSystemService(JobScheduler::class.java).allPendingJobs
        assertEquals(1, jobs.size)
        assertTrue(jobs.single().service.className.contains("ContributionWidget"))
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
    }

    private fun workItem(token: String = "token") = JobWorkItem(Intent(context, ContributionWidgetRefreshJobService::class.java).putExtra("refresh_widget_id", widgetId).putExtra("refresh_widget_token", token))

    private fun paramsFor(work: JobWorkItem) = mockk<JobParameters> {
        every { dequeueWork() } returns work andThen null
        every { completeWork(work) } returns Unit
    }

    private fun settle() = repeat(2) {
        io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
