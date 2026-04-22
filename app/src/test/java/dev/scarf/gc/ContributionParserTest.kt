package dev.scarf.gc

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ContributionParserTest {
    @Test
    fun parsesDailyCells() {
        val html = """
            <div>
              <table class="ContributionCalendar-grid js-calendar-graph-table">
                <td class="ContributionCalendar-day" data-date="2026-04-05" data-level="2"></td>
                <td class="ContributionCalendar-day" data-date="2026-04-06" data-level="0"></td>
                <td class="ContributionCalendar-day" data-date="2026-04-07" data-level="1"></td>
              </table>
            </div>
        """.trimIndent()

        val stats = parseContributionStats(html)

        assertNotNull(stats)
        assertEquals(3, stats?.days?.size)
        assertEquals(LocalDate.parse("2026-04-05"), stats?.days?.first()?.date)
        assertEquals(0, stats?.days?.get(1)?.level)
        assertEquals(1, stats?.days?.get(2)?.level)
    }

    @Test
    fun sortsDays() {
        val html = """
            <div>
              <table class="ContributionCalendar-grid js-calendar-graph-table">
                <td class="ContributionCalendar-day" data-date="2026-04-07" data-level="1"></td>
                <td class="ContributionCalendar-day" data-date="2026-04-05" data-level="2"></td>
                <td class="ContributionCalendar-day" data-date="2026-04-06" data-level="0"></td>
              </table>
            </div>
        """.trimIndent()

        val stats = parseContributionStats(html)

        assertNotNull(stats)
        assertEquals(
            listOf("2026-04-05", "2026-04-06", "2026-04-07"),
            stats?.days?.map { it.date.toString() },
        )
    }

    @Test
    fun normalizesHandlesAndBuildsEmptyStats() {
        val stats = emptyContributionStats(LocalDate.parse("2026-04-11"))

        assertEquals("foo", normalizeHandle(" @foo "))
        assertEquals(371, stats.days.size)
        assertEquals(LocalDate.parse("2025-04-06"), stats.days.first().date)
        assertEquals(LocalDate.parse("2026-04-11"), stats.days.last().date)
    }

    @Test
    fun encodesAndDecodesContributionCache() {
        val stats = ContributionStats(
            listOf(
                ContributionDay(LocalDate.parse("2026-04-05"), 2),
                ContributionDay(LocalDate.parse("2026-04-06"), 0),
                ContributionDay(LocalDate.parse("2026-04-07"), 1),
            ),
        )

        assertEquals(stats, decodeContributionStats(encodeContributionStats(stats)))
        assertNull(decodeContributionStats("broken"))
    }

    @Test
    fun keepsLastEntryWhenMarkupContainsDuplicateDates() {
        val html = """
            <table class="ContributionCalendar-grid js-calendar-graph-table">
              <td class="ContributionCalendar-day" data-date="2026-04-05" data-level="0"></td>
              <td class="ContributionCalendar-day" data-date="2026-04-05" data-level="3"></td>
              <td class="ContributionCalendar-day" data-date="2026-04-06" data-level="1"></td>
            </table>
        """.trimIndent()

        val stats = parseContributionStats(html)

        assertEquals(listOf(3, 1), stats?.days?.map(ContributionDay::level))
    }

    @Test
    fun rejectsIncompleteCoverage() {
        val truncated = ContributionStats(
            listOf(
                ContributionDay(LocalDate.parse("2026-04-13"), 4),
                ContributionDay(LocalDate.parse("2026-04-14"), 2),
            ),
        )

        assertEquals(false, truncated.hasExpectedCoverage(LocalDate.parse("2026-04-17")))
        assertEquals(true, emptyContributionStats(LocalDate.parse("2026-04-17")).hasExpectedCoverage(LocalDate.parse("2026-04-17")))
    }

    @Test
    fun keepsCachedStatsWhenRefreshFails() {
        val cached = ContributionStats(listOf(ContributionDay(LocalDate.parse("2026-04-07"), 3)))

        assertEquals(cached, displayedContributionStats(Result.failure(IllegalStateException("offline")), cached))
        assertEquals(cached, displayedContributionStats(Result.success(cached), null))
        assertNull(displayedContributionStats(Result.failure(IllegalStateException("offline")), null))
    }

    @Test
    fun keepsCachedStatsWhenFetchedGraphIsSuspiciouslyEmpty() {
        val cached = ContributionStats(
            listOf(
                ContributionDay(LocalDate.parse("2025-04-13"), 0),
                ContributionDay(LocalDate.parse("2026-04-13"), 4),
                ContributionDay(LocalDate.parse("2026-04-14"), 2),
            ),
        )
        val fetched = ContributionStats(
            listOf(
                ContributionDay(LocalDate.parse("2026-04-13"), 0),
                ContributionDay(LocalDate.parse("2026-04-14"), 0),
            ),
        )

        assertEquals(cached, displayedContributionStats(Result.success(fetched), cached))
    }

    @Test
    fun acceptsEmptyGraphWhenCachedActivityHasAgedOut() {
        val cached = ContributionStats(
            listOf(
                ContributionDay(LocalDate.parse("2025-04-09"), 3),
                ContributionDay(LocalDate.parse("2026-04-13"), 0),
                ContributionDay(LocalDate.parse("2026-04-14"), 0),
            ),
        )
        val fetched = ContributionStats(
            listOf(
                ContributionDay(LocalDate.parse("2026-04-13"), 0),
                ContributionDay(LocalDate.parse("2026-04-14"), 0),
            ),
        )

        assertEquals(fetched, displayedContributionStats(Result.success(fetched), cached))
    }
}
