package dev.scarf.gc

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.roundToInt

private const val prefsName = "contribution_widget"
private const val actionRefresh = "dev.scarf.gc.MANUAL_REFRESH"
private const val refreshJobId = 7341
private const val refreshQueueKey = "refresh_widget_ids"
private const val refreshSequenceKey = "refresh_sequence"
private const val refreshWidgetIdExtra = "refresh_widget_id"
private const val refreshWidgetTokenExtra = "refresh_widget_token"
private const val referenceGraphPaddingHeightRatio = 0.085f
private const val referenceTargetCellDp = 10
private const val referenceTargetGapDp = 2
private const val logTag = "ContributionWidget"
internal val io = Executors.newSingleThreadExecutor()
private fun prefs(context: Context) = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

internal object WidgetPreferences {
    fun readHandle(context: Context, id: Int) = prefs(context).getString("handle_$id", "").orEmpty()
    fun writeHandle(context: Context, id: Int, handle: String) = prefs(context).edit().putString("handle_$id", normalizeHandle(handle)).apply()
    fun readStats(context: Context, id: Int) = prefs(context).getString("stats_$id", "")?.let(::decodeContributionStats)
    fun writeStats(context: Context, id: Int, stats: ContributionStats) = prefs(context).edit().putString("stats_$id", encodeContributionStats(stats)).apply()
    fun readTheme(context: Context, id: Int) = WidgetTheme.fromKey(context, prefs(context).getString("theme_$id", null))
    fun writeTheme(context: Context, id: Int, theme: WidgetTheme) = prefs(context).edit().putString("theme_$id", theme.key).apply()
    fun clear(context: Context, id: Int) = prefs(context).edit().remove("handle_$id").remove("stats_$id").remove("theme_$id").apply()
}

internal object WidgetRefreshScheduler {
    private data class Request(val token: String, val widgetId: Int)

    private val lock = Any()
    internal var schedulerOverride: JobScheduler? = null

    fun enqueue(context: Context, widgetIds: IntArray) = synchronized(lock) {
        val ids = widgetIds.filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }.distinct()
        if (ids.isEmpty()) return@synchronized
        val scheduler = scheduler(context)
        if (scheduler.getPendingJob(refreshJobId) == null) prefs(context).edit().remove(refreshQueueKey).commit()
        val pending = readRequests(context, refreshQueueKey)
        val pendingWidgetIds = pending.map(Request::widgetId).toSet()
        var sequence = prefs(context).getLong(refreshSequenceKey, 0L)
        val candidates = ids.filterNot(pendingWidgetIds::contains).map { widgetId ->
            Request("r${++sequence}", widgetId)
        }
        val accepted = candidates.filter { request ->
            scheduler.enqueue(
                jobInfo(context),
                JobWorkItem(Intent(context, ContributionWidgetRefreshJobService::class.java)
                    .putExtra(refreshWidgetIdExtra, request.widgetId)
                    .putExtra(refreshWidgetTokenExtra, request.token)),
            ) == JobScheduler.RESULT_SUCCESS
        }
        if (accepted.isNotEmpty()) {
            prefs(context).edit()
                .putLong(refreshSequenceKey, sequence)
                .putRequests(refreshQueueKey, pending + accepted)
                .commit()
        }
    }

    internal fun resetForTests(context: Context) = synchronized(lock) {
        prefs(context).edit().remove(refreshQueueKey).commit()
    }

    fun markDequeued(context: Context, token: String) = synchronized(lock) {
        val pending = readRequests(context, refreshQueueKey)
        if (pending.none { it.token == token }) return@synchronized
        prefs(context).edit().putRequests(refreshQueueKey, pending.filterNot { it.token == token }).commit()
    }

    fun remove(context: Context, widgetId: Int) = synchronized(lock) {
        val pending = readRequests(context, refreshQueueKey).filterNot { it.widgetId == widgetId }
        prefs(context).edit().putRequests(refreshQueueKey, pending).commit()
    }

    fun hasPending(context: Context) = pendingCount(context) > 0

    fun pendingCount(context: Context) = synchronized(lock) {
        readRequests(context, refreshQueueKey).size
    }

    internal fun pendingTokenForTests(context: Context, widgetId: Int) = synchronized(lock) {
        readRequests(context, refreshQueueKey).firstOrNull { it.widgetId == widgetId }?.token
    }

    private fun scheduler(context: Context) = schedulerOverride ?: context.getSystemService(JobScheduler::class.java)

    private fun jobInfo(context: Context) = JobInfo.Builder(refreshJobId, ComponentName(context, ContributionWidgetRefreshJobService::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .build()

    private fun readRequests(context: Context, key: String) = prefs(context).getString(key, null).orEmpty()
        .split(';').mapNotNull { encoded ->
            val parts = encoded.split('|', limit = 2)
            if (parts.size == 2) parts[0].takeIf(String::isNotBlank)?.let { token -> parts[1].toIntOrNull()?.let { Request(token, it) } } else null
        }.distinctBy(Request::token)

    private fun android.content.SharedPreferences.Editor.putRequests(key: String, requests: List<Request>) =
        if (requests.isEmpty()) remove(key) else putString(key, requests.joinToString(";") { "${it.token}|${it.widgetId}" })
}

class ContributionWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in arrayOf(AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED, actionRefresh, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return super.onReceive(context, intent)
        val ids = widgetIds(context, intent)
        Log.d(logTag, "onReceive action=$action ids=${ids.joinToString()}")
        ids.forEach { ContributionWidgetUpdater.redrawStored(context, it) }
        if (action == AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED) return
        WidgetRefreshScheduler.enqueue(context, ids)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = Unit

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = appWidgetIds.forEach {
        WidgetPreferences.clear(context, it)
        WidgetRefreshScheduler.remove(context, it)
    }

    companion object {
        fun requestRefresh(context: Context, appWidgetId: Int) = context.sendBroadcast(Intent(context, ContributionWidgetProvider::class.java).setAction(actionRefresh).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
    }
}

class ContributionWidgetRefreshJobService : JobService() {
    private class Running(val params: JobParameters) {
        @Volatile
        var stopped = false
        @Volatile
        var task: Future<*>? = null
    }

    @Volatile
    private var running: Running? = null
    private val waiting = ArrayDeque<JobParameters>()

    override fun onStartJob(params: JobParameters): Boolean {
        val state = synchronized(this) {
            if (running != null) {
                waiting.addLast(params)
                null
            } else {
                Running(params).also { running = it }
            }
        }
        if (state != null) startWorker(state)
        return true
    }

    private fun startWorker(state: Running) {
        val task = io.submit {
            try {
                while (!state.stopped) {
                    val work = state.params.dequeueWork() ?: break
                    val widgetId = work.intent.getIntExtra(refreshWidgetIdExtra, AppWidgetManager.INVALID_APPWIDGET_ID)
                    val token = work.intent.getStringExtra(refreshWidgetTokenExtra).orEmpty()
                    WidgetRefreshScheduler.markDequeued(this, token)
                    val active = widgetId in ContributionWidgetUpdater.allWidgetIds(this).toSet()
                    if (active) ContributionWidgetUpdater.refreshStored(this, widgetId) { !state.stopped }
                    if (state.stopped) break
                    state.params.completeWork(work)
                }
            } finally {
                val next = synchronized(this) {
                    if (running !== state) null else waiting.removeFirstOrNull()?.let { nextParams ->
                        Running(nextParams).also { running = it }
                    } ?: run {
                        running = null
                        null
                    }
                }
                if (next != null) startWorker(next)
            }
        }
        synchronized(this) {
            if (running === state) state.task = task else task.cancel(true)
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val active = synchronized(this) { running?.takeIf { it.params === params } }
        if (active != null) {
            active.stopped = true
            synchronized(this) {
                if (running === active) running = null
                waiting.clear()
            }
            active.task?.cancel(true)
            return true
        }
        return synchronized(this) { waiting.removeIf { it === params } }
    }
}

internal object ContributionWidgetUpdater {
    @Synchronized
    fun redrawStored(context: Context, appWidgetId: Int) = show(context, appWidgetId)

    @Synchronized
    fun saveAccount(context: Context, appWidgetId: Int, handle: String, stats: ContributionStats) {
        WidgetPreferences.writeHandle(context, appWidgetId, handle)
        WidgetPreferences.writeStats(context, appWidgetId, stats)
        show(context, appWidgetId)
    }

    fun refreshStored(context: Context, appWidgetId: Int, shouldContinue: () -> Boolean = { true }): Result<ContributionStats> {
        val handle = synchronized(this) {
            if (!shouldContinue()) return Result.success(emptyContributionStats())
            WidgetPreferences.readHandle(context, appWidgetId).also { show(context, appWidgetId) }
        }
        if (handle.isBlank()) return Result.success(emptyContributionStats())
        val result = ContributionRepository.fetch(handle)
        synchronized(this) {
            if (!shouldContinue() || WidgetPreferences.readHandle(context, appWidgetId) != handle) return@synchronized
            val cached = WidgetPreferences.readStats(context, appWidgetId)
            result.onSuccess {
                if (shouldUseFetchedStats(it, cached)) {
                    WidgetPreferences.writeStats(context, appWidgetId, it)
                    Log.d(logTag, "refresh ok widget=$appWidgetId handle=$handle cached=true")
                } else {
                    Log.w(logTag, "refresh suspicious-empty widget=$appWidgetId handle=$handle keeping cached graph")
                }
            }.onFailure {
                Log.w(logTag, "refresh failed widget=$appWidgetId handle=$handle cached=${cached != null} message=${it.message}", it)
            }
            show(context, appWidgetId)
        }
        return result
    }

    fun allWidgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ContributionWidgetProvider::class.java))

    private fun show(context: Context, appWidgetId: Int) {
        val stats = WidgetPreferences.readStats(context, appWidgetId)
        val theme = WidgetPreferences.readTheme(context, appWidgetId)
        val options = renderOptions(context, appWidgetId, theme)
        val bitmap = stats?.let { ContributionBitmapRenderer.render(it, options.graph) } ?: ContributionBitmapRenderer.placeholder(options.graph)
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, RemoteViews(context.packageName, R.layout.widget_contribution).apply {
            setInt(R.id.widgetGraph, "setBackgroundResource", theme.backgroundResId)
            setImageViewBitmap(R.id.widgetGraph, bitmap)
            setViewPadding(R.id.widgetGraph, options.paddingPx, options.paddingPx, options.paddingPx, options.paddingPx)
            setOnClickPendingIntent(R.id.widgetRoot, settingsIntent(context, appWidgetId))
        })
    }
}

private fun widgetIds(context: Context, intent: Intent) = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
    ?: intent.takeIf { it.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID) }
        ?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        ?.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
        ?.let { intArrayOf(it) }
    ?: ContributionWidgetUpdater.allWidgetIds(context)

private fun settingsIntent(context: Context, appWidgetId: Int) = PendingIntent.getActivity(
    context,
    appWidgetId,
    Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

private data class WidgetRenderOptions(val graph: ContributionBitmapRenderer.RenderOptions, val paddingPx: Int)

private fun renderOptions(context: Context, appWidgetId: Int, theme: WidgetTheme): WidgetRenderOptions {
    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    val density = context.resources.displayMetrics.density
    val widgetHeightDp = widgetHeightDp(
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40),
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 40),
    )
    val paddingDp = graphPaddingDp(widgetHeightDp)
    val bounds = widgetGraphBoundsDp(
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250),
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250),
        widgetHeightDp,
        paddingDp,
    )
    val layout = contributionGraphLayout(bounds.widthDp, bounds.heightDp, referenceTargetCellDp, referenceTargetGapDp)
    return WidgetRenderOptions(
        ContributionBitmapRenderer.RenderOptions(
            max((bounds.widthDp * density).roundToInt(), 13),
            max((bounds.heightDp * density).roundToInt(), 13),
            layout.columns,
            layout.weekBlocks,
            dp(context, layout.cellDp),
            dp(context, layout.gapDp),
            theme.levelColors(context),
            theme.borderColor,
        ),
        dp(context, paddingDp),
    )
}

internal fun widgetHeightDp(minHeightDp: Int, maxHeightDp: Int) = max(minHeightDp, maxHeightDp).takeIf { it > 0 } ?: 40

internal fun graphPaddingDp(widgetHeightDp: Int) = max(1, (widgetHeightDp * referenceGraphPaddingHeightRatio).roundToInt())

internal data class WidgetGraphBoundsDp(val widthDp: Int, val heightDp: Int)

internal fun widgetGraphBoundsDp(minWidthDp: Int, maxWidthDp: Int, heightDp: Int, graphPaddingDp: Int): WidgetGraphBoundsDp {
    val widthDp = minWidthDp.takeIf { it > 0 } ?: maxWidthDp.takeIf { it > 0 } ?: 250
    return WidgetGraphBoundsDp(max(1, widthDp - graphPaddingDp * 2), max(1, heightDp - graphPaddingDp * 2))
}

internal data class ContributionGraphLayout(val columns: Int, val weekBlocks: Int, val cellDp: Int, val gapDp: Int)

internal fun contributionGraphLayout(widthDp: Int, heightDp: Int, targetCellDp: Int, targetGapDp: Int): ContributionGraphLayout {
    val weekBlocks = 1
    val rows = weekBlocks * 7
    for (cellDp in max(1, heightDp / rows) downTo 1) {
        val gapDp = scaledGap(cellDp, targetCellDp, targetGapDp)
        val heightUsedDp = gridLength(rows, cellDp, gapDp)
        if (heightUsedDp > heightDp) continue
        val columns = fitSlots(widthDp, cellDp, gapDp)
        val horizontalPaddingDp = widthDp - gridLength(columns, cellDp, gapDp)
        val verticalPaddingDp = heightDp - heightUsedDp
        if (horizontalPaddingDp <= verticalPaddingDp || cellDp == 1) return ContributionGraphLayout(columns, weekBlocks, cellDp, gapDp)
    }
    return ContributionGraphLayout(1, weekBlocks, 1, 0)
}

private fun scaledGap(cellDp: Int, targetCellDp: Int, targetGapDp: Int) = if (targetGapDp == 0 || cellDp == 1) 0 else max(1, (cellDp * targetGapDp.toFloat() / targetCellDp).roundToInt())

private fun fitSlots(lengthDp: Int, cellDp: Int, gapDp: Int) = max(1, (lengthDp + gapDp) / (cellDp + gapDp))

private fun gridLength(slots: Int, cellDp: Int, gapDp: Int) = slots * cellDp + max(0, slots - 1) * gapDp

private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
