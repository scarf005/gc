package dev.scarf.gc

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val dayPattern = Regex("""<td(?=[^>]*ContributionCalendar-day)(?=[^>]*data-date=\"(\d{4}-\d{2}-\d{2})\")(?=[^>]*data-level=\"(\d)\")[^>]*>""")
private val calendarPattern = Regex("""<table[^>]*ContributionCalendar-grid[^>]*>(.*?)</table>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private const val logTag = "ContributionWidget"

internal fun normalizeHandle(raw: String) = raw.trim().removePrefix("@")
internal data class ContributionDay(val date: LocalDate, val level: Int)
internal data class ContributionStats(val days: List<ContributionDay>) { val endDate = days.maxOfOrNull(ContributionDay::date) ?: LocalDate.now() }

internal fun emptyContributionStats(clock: LocalDate = LocalDate.now()): ContributionStats {
    val start = clock.minusDays(370).let { it.minusDays((it.dayOfWeek.value % 7).toLong()) }
    return ContributionStats((0..370).map { ContributionDay(start.plusDays(it.toLong()), 0) })
}

internal fun encodeContributionStats(stats: ContributionStats) = stats.days.joinToString(",") { "${it.date}:${it.level}" }

internal fun decodeContributionStats(raw: String) = raw.takeIf(String::isNotBlank)?.runCatching {
    split(',').map {
        val (date, level) = it.split(':', limit = 2).takeIf { parts -> parts.size == 2 } ?: error("Invalid contribution cache")
        ContributionDay(LocalDate.parse(date), level.toInt())
    }.sortedBy(ContributionDay::date).let(::ContributionStats)
}?.getOrNull()

internal fun ContributionStats.hasVisibleActivity() = days.any { it.level > 0 }

internal fun ContributionStats.hasExpectedCoverage(clock: LocalDate = LocalDate.now()): Boolean {
    val first = days.firstOrNull()?.date ?: return false
    val coveredDays = ChronoUnit.DAYS.between(first, endDate) + 1
    return days.size >= 360 && coveredDays >= 360 && !endDate.isBefore(clock.minusDays(7))
}

internal fun shouldUseFetchedStats(fetched: ContributionStats, cached: ContributionStats?): Boolean {
    if (fetched.hasVisibleActivity()) return true
    if (cached == null) return true
    val suspiciousSince = fetched.endDate.minusDays(364)
    return cached.days.none { it.level > 0 && !it.date.isBefore(suspiciousSince) }
}

internal fun displayedContributionStats(result: Result<ContributionStats>, cached: ContributionStats?) = result.getOrNull()?.takeIf { shouldUseFetchedStats(it, cached) } ?: cached

internal fun parseContributionStats(html: String): ContributionStats? {
    val calendar = calendarPattern.find(html)?.groupValues?.get(1) ?: return null
    val days = dayPattern.findAll(calendar)
        .map { ContributionDay(LocalDate.parse(it.groupValues[1]), it.groupValues[2].toInt()) }
        .associateBy(ContributionDay::date)
        .values
        .sortedBy(ContributionDay::date)
        .toList()
    return days.takeIf(List<ContributionDay>::isNotEmpty)?.let(::ContributionStats)
}

internal object ContributionRepository {
    fun fetch(handle: String): Result<ContributionStats> = runCatching {
        val name = normalizeHandle(handle).also { require(it.isNotBlank()) { "Type a handle" } }
        (URL("https://github.com/users/${URLEncoder.encode(name, StandardCharsets.UTF_8.toString())}/contributions").openConnection() as HttpURLConnection).run {
            connectTimeout = 15000; readTimeout = 15000; requestMethod = "GET"; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ContributionWidget/1.0"); setRequestProperty("Accept", "text/html")
            try {
                val body = runCatching { inputStream.bufferedReader().use { it.readText() } }.getOrElse { errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() }
                if (responseCode !in 200..299) {
                    Log.w(logTag, "fetch failed handle=$name code=$responseCode remaining=${getHeaderField("X-RateLimit-Remaining") ?: "?"} body=${body.previewForLog()}")
                    error(if (responseCode == 404) "Handle not found" else "Unable to load")
                }
                parseContributionStats(body)?.takeIf { stats ->
                    stats.hasExpectedCoverage().also { complete ->
                        if (!complete) Log.w(logTag, "parse incomplete handle=$name days=${stats.days.size} start=${stats.days.firstOrNull()?.date} end=${stats.endDate} body=${body.previewForLog()}")
                    }
                }?.also {
                    Log.d(logTag, "fetch ok handle=$name days=${it.days.size} end=${it.endDate}")
                } ?: run {
                    Log.w(logTag, "parse failed handle=$name body=${body.previewForLog()}")
                    error("Unable to parse contributions")
                }
            } finally {
                disconnect()
            }
        }
    }
}

private fun String.previewForLog() = replace(Regex("\\s+"), " ").take(160)
