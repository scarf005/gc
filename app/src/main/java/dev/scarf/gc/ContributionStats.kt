package dev.scarf.gc

import java.time.LocalDate

internal data class ContributionDay(
    val date: LocalDate,
    val count: Int,
    val level: Int,
)

internal data class ContributionStats(
    val totalContributions: Int,
    val days: List<ContributionDay>,
) {
    val startDate: LocalDate = days.minOfOrNull(ContributionDay::date) ?: LocalDate.now()
    val endDate: LocalDate = days.maxOfOrNull(ContributionDay::date) ?: LocalDate.now()
}

internal fun emptyContributionStats(clock: LocalDate = LocalDate.now()): ContributionStats {
    val endDate = clock
    val alignedStart = endDate.minusDays(370).minusDays((endDate.minusDays(370).dayOfWeek.value % 7).toLong())
    val days = (0..370).map { offset ->
        ContributionDay(
            date = alignedStart.plusDays(offset.toLong()),
            count = 0,
            level = 0,
        )
    }
    return ContributionStats(totalContributions = 0, days = days)
}
