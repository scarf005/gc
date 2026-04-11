package dev.scarf.gc

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

private val dayPattern = Regex("""data-date=\"(\d{4}-\d{2}-\d{2})\"[^>]*data-level=\"(\d)\"""")

internal fun normalizeHandle(raw: String) = raw.trim().removePrefix("@")
internal data class ContributionDay(val date: LocalDate, val level: Int)
internal data class ContributionStats(val days: List<ContributionDay>) { val endDate = days.maxOfOrNull(ContributionDay::date) ?: LocalDate.now() }

internal fun emptyContributionStats(clock: LocalDate = LocalDate.now()): ContributionStats {
    val start = clock.minusDays(370).let { it.minusDays((it.dayOfWeek.value % 7).toLong()) }
    return ContributionStats((0..370).map { ContributionDay(start.plusDays(it.toLong()), 0) })
}

internal fun parseContributionStats(html: String): ContributionStats? {
    val days = dayPattern.findAll(html).map { ContributionDay(LocalDate.parse(it.groupValues[1]), it.groupValues[2].toInt()) }.sortedBy(ContributionDay::date).toList()
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
                if (responseCode !in 200..299) error(if (responseCode == 404) "Handle not found" else "Unable to load")
                parseContributionStats(body) ?: error("Unable to parse contributions")
            } finally {
                disconnect()
            }
        }
    }
}
