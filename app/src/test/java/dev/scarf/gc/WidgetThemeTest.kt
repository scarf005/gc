package dev.scarf.gc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetThemeTest {
    @Test
    fun fallsBackToLightTheme() {
        assertEquals(WidgetTheme.Light, WidgetTheme.fromKey(null))
        assertEquals(WidgetTheme.Light, WidgetTheme.fromKey("missing"))
    }

    @Test
    fun exposesFiveContributionLevelsPerTheme() {
        WidgetTheme.values().forEach { theme ->
            assertEquals(5, theme.levelColors.size)
            assertTrue(theme.key.isNotBlank())
        }
    }
}
