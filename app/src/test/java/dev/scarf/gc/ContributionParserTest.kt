package dev.scarf.gc

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ContributionParserTest {
    @Test
    fun parsesTotalAndDailyCells() {
        val html = """
            <div>
              <h2>12 contributions in the last year</h2>
              <td data-date="2026-04-05" data-level="2"></td>
              <tool-tip>5 contributions on April 5th.</tool-tip>
              <td data-date="2026-04-06" data-level="0"></td>
              <tool-tip>No contributions on April 6th.</tool-tip>
              <td data-date="2026-04-07" data-level="1"></td>
              <tool-tip>7 contributions on April 7th.</tool-tip>
            </div>
        """.trimIndent()

        val stats = parseContributionStats(html)

        assertNotNull(stats)
        assertEquals(12, stats?.totalContributions)
        assertEquals(3, stats?.days?.size)
        assertEquals(LocalDate.parse("2026-04-05"), stats?.days?.first()?.date)
        assertEquals(0, stats?.days?.get(1)?.count)
        assertEquals(1, stats?.days?.get(2)?.level)
    }
}
