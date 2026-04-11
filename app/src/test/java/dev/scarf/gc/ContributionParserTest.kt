package dev.scarf.gc

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ContributionParserTest {
    @Test
    fun parsesDailyCells() {
        val html = """
            <div>
              <td data-date="2026-04-05" data-level="2"></td>
              <td data-date="2026-04-06" data-level="0"></td>
              <td data-date="2026-04-07" data-level="1"></td>
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
              <td data-date="2026-04-07" data-level="1"></td>
              <td data-date="2026-04-05" data-level="2"></td>
              <td data-date="2026-04-06" data-level="0"></td>
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
}
