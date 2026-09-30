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
    fun roundTripsEveryThemeKey() {
        WidgetTheme.values().forEach { theme ->
            assertEquals(theme, WidgetTheme.fromKey(theme.key))
        }
    }

    @Test
    fun transparentThemeKeepsContributionColorsWithoutACardBackground() {
        val theme = WidgetTheme.fromKey("transparent")
        assertEquals(WidgetTheme.Transparent, theme)
        assertEquals(android.R.color.transparent, theme.backgroundResId)
        assertTrue(defaultLevelColors.contentEquals(theme.levelColors))
        assertTrue(theme.levelColors.all { it ushr 24 == 0xff })
    }

    @Test
    fun exposesFiveContributionLevelsPerTheme() {
        WidgetTheme.values().forEach { theme ->
            assertEquals(5, theme.levelColors.size)
            assertTrue(theme.key.isNotBlank())
        }
    }
}
