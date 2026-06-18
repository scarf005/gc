package dev.scarf.gc

import android.app.Application
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.appwidget.AppWidgetManager
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContributionWidgetRefreshJobServiceTest {
    private lateinit var context: Application
    private var widgetId = 0
    private val cached = emptyContributionStats().let { stats ->
        ContributionStats(stats.days.map { it.copy(level = 2) })
    }
    private val fresh = emptyContributionStats().let { stats ->
        ContributionStats(stats.days.map { it.copy(level = 4) })
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        mockkObject(ContributionRepository)
        every { ContributionRepository.fetch(any()) } returns Result.success(fresh)
        widgetId = shadowOf(AppWidgetManager.getInstance(context)).createWidget(ContributionWidgetProvider::class.java, R.layout.widget_contribution)
        WidgetPreferences.writeHandle(context, widgetId, "existing")
        WidgetPreferences.writeStats(context, widgetId, cached)
        context.getSystemService(JobScheduler::class.java).cancelAll()
        settle()
    }

    @After
    fun tearDown() {
        context.getSystemService(JobScheduler::class.java).cancelAll()
        WidgetRefreshScheduler.resetForTests(context)
        settle()
        unmockkAll()
    }

    @Test
    fun stoppedJobPreservesTheUnfinishedWorkForRetry() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ContributionRepository.fetch("existing") } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Result.success(fresh)
        } andThen Result.success(fresh)
        WidgetRefreshScheduler.enqueue(context, intArrayOf(widgetId))
        val service = Robolectric.buildService(ContributionWidgetRefreshJobService::class.java).create().get()
        val work = workItem(WidgetRefreshScheduler.pendingTokenForTests(context, widgetId).orEmpty())
        val first = paramsFor(work)
        assertTrue(service.onStartJob(first))
        assertTrue(started.await(5, TimeUnit.SECONDS))

        assertTrue(service.onStopJob(first))
        release.countDown()
        settle()
        assertEquals(cached, WidgetPreferences.readStats(context, widgetId))
        assertFalse(WidgetRefreshScheduler.hasPending(context))

        val retry = paramsFor(work)
        assertTrue(service.onStartJob(retry))
        settle()

        assertEquals(fresh, WidgetPreferences.readStats(context, widgetId))
        assertFalse(WidgetRefreshScheduler.hasPending(context))
    }

    @Test
    fun enqueueDuringActiveFetchKeepsTheCurrentFetchAndProcessesTheNewerRequest() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ContributionRepository.fetch("existing") } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Result.success(fresh)
        } andThen Result.success(fresh)
        WidgetRefreshScheduler.enqueue(context, intArrayOf(widgetId))
        val service = Robolectric.buildService(ContributionWidgetRefreshJobService::class.java).create().get()
        val firstWork = workItem(WidgetRefreshScheduler.pendingTokenForTests(context, widgetId).orEmpty())
        val params = paramsFor(firstWork)
        assertTrue(service.onStartJob(params))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        WidgetRefreshScheduler.enqueue(context, intArrayOf(widgetId))
        val newerWork = workItem(WidgetRefreshScheduler.pendingTokenForTests(context, widgetId).orEmpty())
        every { params.dequeueWork() } returnsMany listOf(newerWork, null)
        release.countDown()
        settle()

        verify(exactly = 2) { ContributionRepository.fetch("existing") }
        assertFalse(WidgetRefreshScheduler.hasPending(context))
    }

    @Test
    fun redeliveredOldWorkDoesNotEraseNewerSameWidgetRequest() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ContributionRepository.fetch("existing") } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Result.success(fresh)
        } andThen Result.success(fresh) andThen Result.success(fresh)
        WidgetRefreshScheduler.enqueue(context, intArrayOf(widgetId))
        val tokenA = WidgetRefreshScheduler.pendingTokenForTests(context, widgetId).orEmpty()
        val first = Robolectric.buildService(ContributionWidgetRefreshJobService::class.java).create().get()
        val firstParams = paramsFor(workItem(tokenA))
        assertTrue(first.onStartJob(firstParams))
        assertTrue(started.await(5, TimeUnit.SECONDS))

        WidgetRefreshScheduler.enqueue(context, intArrayOf(widgetId))
        val tokenB = WidgetRefreshScheduler.pendingTokenForTests(context, widgetId)
        assertTrue(first.onStopJob(firstParams))
        release.countDown()
        settle()

        val redelivery = Robolectric.buildService(ContributionWidgetRefreshJobService::class.java).create().get()
        assertTrue(redelivery.onStartJob(paramsFor(workItem(tokenA))))
        settle()
        assertEquals(tokenB, WidgetRefreshScheduler.pendingTokenForTests(context, widgetId))
        WidgetRefreshScheduler.enqueue(context, intArrayOf(widgetId))

        assertEquals(tokenB, WidgetRefreshScheduler.pendingTokenForTests(context, widgetId))
        assertEquals(1, WidgetRefreshScheduler.pendingCount(context))
    }

    @Test
    fun newStartBeforePreviousWorkerCleanupStillGetsAWorker() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ContributionRepository.fetch("existing") } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Result.success(fresh)
        } andThen Result.success(fresh)
        val service = Robolectric.buildService(ContributionWidgetRefreshJobService::class.java).create().get()
        assertTrue(service.onStartJob(paramsFor(workItem("first"))))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(service.onStartJob(paramsFor(workItem("second"))))
        release.countDown()
        settle()

        verify(exactly = 2) { ContributionRepository.fetch("existing") }
    }

    @Test
    fun startingAJobKeepsItsRequestRecoverableUntilCompletion() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ContributionRepository.fetch("existing") } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Result.success(fresh)
        }
        WidgetRefreshScheduler.enqueue(context, intArrayOf(widgetId))
        val service = Robolectric.buildService(ContributionWidgetRefreshJobService::class.java).create().get()
        val params = paramsFor(workItem(WidgetRefreshScheduler.pendingTokenForTests(context, widgetId).orEmpty()))
        assertTrue(service.onStartJob(params))
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(WidgetRefreshScheduler.hasPending(context))
        } finally {
            release.countDown()
        }
        settle()
    }

    @Test
    fun freshServiceCanRecoverAJobThatDidNotReachStopCallback() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ContributionRepository.fetch("existing") } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Result.success(fresh)
        }
        WidgetRefreshScheduler.enqueue(context, intArrayOf(widgetId))
        val work = workItem(WidgetRefreshScheduler.pendingTokenForTests(context, widgetId).orEmpty())
        val first = Robolectric.buildService(ContributionWidgetRefreshJobService::class.java).create().get()
        assertTrue(first.onStartJob(paramsFor(work)))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val replacement = Robolectric.buildService(ContributionWidgetRefreshJobService::class.java).create().get()

        try {
            assertTrue(replacement.onStartJob(paramsFor(work)))
        } finally {
            release.countDown()
        }
        settle()
    }

    @Test
    fun deletingWidgetRemovesQueuedRefreshAndPreventsFetch() {
        WidgetRefreshScheduler.enqueue(context, intArrayOf(widgetId))
        ContributionWidgetProvider().onDeleted(context, intArrayOf(widgetId))

        assertFalse(WidgetRefreshScheduler.hasPending(context))
        assertEquals("", WidgetPreferences.readHandle(context, widgetId))
        assertEquals(null, WidgetPreferences.readStats(context, widgetId))

        val work = workItem("deleted")
        val service = Robolectric.buildService(ContributionWidgetRefreshJobService::class.java).create().get()
        assertTrue(service.onStartJob(paramsFor(work)))
        settle()
        verify(exactly = 0) { ContributionRepository.fetch(any()) }
        assertFalse(WidgetRefreshScheduler.hasPending(context))
    }

    private fun workItem(token: String = "token") = JobWorkItem(Intent(context, ContributionWidgetRefreshJobService::class.java).putExtra("refresh_widget_id", widgetId).putExtra("refresh_widget_token", token))

    private fun paramsFor(vararg works: JobWorkItem) = mockk<JobParameters> {
        every { dequeueWork() } returnsMany works.toList() + null
        works.forEach { work -> every { completeWork(work) } returns Unit }
    }

    private fun settle() = repeat(2) {
        io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
