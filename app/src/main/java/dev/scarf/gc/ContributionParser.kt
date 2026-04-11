package dev.scarf.gc

import java.time.LocalDate

private val totalPattern = Regex(
    pattern = """<h2[^>]*>\s*([\d,]+)\s*contributions\s*in the last year""",
    options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)

private val dayPattern = Regex(
    pattern = """data-date=\"(\d{4}-\d{2}-\d{2})\"[^>]*data-level=\"(\d)\".*?<tool-tip[^>]*>(No|\d+) contribution(?:s)? on .*?</tool-tip>""",
    options = setOf(RegexOption.DOT_MATCHES_ALL),
)

internal fun parseContributionStats(html: String): ContributionStats? {
    val days = dayPattern.findAll(html)
        .map { match ->
            val rawCount = match.groupValues[3]
            ContributionDay(
                date = LocalDate.parse(match.groupValues[1]),
                count = rawCount.toIntOrNull() ?: 0,
                level = match.groupValues[2].toInt(),
            )
        }
        .sortedBy(ContributionDay::date)
        .toList()

    if (days.isEmpty()) {
        return null
    }

    val totalContributions = totalPattern.find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace(",", "")
        ?.toIntOrNull()
        ?: days.sumOf(ContributionDay::count)

    return ContributionStats(
        totalContributions = totalContributions,
        days = days,
    )
}
