package dev.scarf.gc

import org.junit.Assert.assertArrayEquals
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
    fun keepsExistingThemeKeys() {
        assertEquals(WidgetTheme.Light, WidgetTheme.fromKey("light"))
        assertEquals(WidgetTheme.Dark, WidgetTheme.fromKey("dark"))
        assertEquals(WidgetTheme.Transparent, WidgetTheme.fromKey("transparent"))
    }

    @Test
    fun migratesBlueToWinterWithoutADuplicateChoice() {
        assertEquals(WidgetTheme.WinterLight, WidgetTheme.fromKey("blue"))
        assertTrue(WidgetTheme.values().none { it.key == "blue" })
    }

    @Test
    fun matchesPrimerContributionPalettes() {
        // https://unpkg.com/@primer/primitives@11.10.0/dist/css/functional/themes/
        val palettes = mapOf(
            "light" to "eff2f5 aceebb 4ac26b 2da44e 116329",
            "dark" to "151b23 033a16 196c2e 2ea043 56d364",
            "light-high-contrast" to "ffffff 82e596 26a148 117f32 024c1a",
            "dark-high-contrast" to "010409 007728 02a232 0ac740 4ae168",
            "dark-dimmed" to "2a313c 1b4721 2b6a30 46954a 6bc46d",
            "dark-dimmed-high-contrast" to "151b23 1b4721 2b6a30 46954a 6bc46d",
            "halloween-light" to "eff2f5 f0db3d ffd642 f68c41 1f2328",
            "halloween-dark" to "151b23 fac68f c46212 984b10 e3d04f",
            "winter-light" to "eff2f5 b6e3ff 54aeff 0969da 0a3069",
            "winter-dark" to "151b23 0c2d6b 1158c7 58a6ff cae8ff",
        )
        assertEquals(palettes.keys + "transparent", WidgetTheme.values().map { it.key }.toSet())
        palettes.forEach { (key, colors) ->
            val theme = WidgetTheme.fromKey(key)
            assertEquals(key, theme.key)
            assertArrayEquals(key, colors.split(" ").map { (0xff000000L or it.toLong(16)).toInt() }.toIntArray(), theme.levelColors)
        }
    }

    @Test
    fun usesMatchingCardBackgrounds() {
        listOf(WidgetTheme.Light, WidgetTheme.LightHighContrast, WidgetTheme.HalloweenLight, WidgetTheme.WinterLight).forEach {
            assertEquals(R.drawable.widget_background, it.backgroundResId)
        }
        listOf(WidgetTheme.Dark, WidgetTheme.HalloweenDark, WidgetTheme.WinterDark).forEach {
            assertEquals(R.drawable.widget_background_dark, it.backgroundResId)
        }
        assertEquals(R.drawable.widget_background_dark_high_contrast, WidgetTheme.DarkHighContrast.backgroundResId)
        listOf(WidgetTheme.DarkDimmed, WidgetTheme.DarkDimmedHighContrast).forEach {
            assertEquals(R.drawable.widget_background_dark_dimmed, it.backgroundResId)
        }
    }

    @Test
    fun highContrastBordersKeepEmptyCellsVisible() {
        assertEquals(0xff010409.toInt(), WidgetTheme.LightHighContrast.borderColor)
        assertEquals(0xffffffff.toInt(), WidgetTheme.DarkHighContrast.borderColor)
        WidgetTheme.values().filter { it !in listOf(WidgetTheme.LightHighContrast, WidgetTheme.DarkHighContrast) }.forEach {
            assertEquals(null, it.borderColor)
        }
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
        assertEquals(WidgetTheme.values().size, WidgetTheme.values().map { it.key }.toSet().size)
        WidgetTheme.values().forEach { theme ->
            assertEquals(5, theme.levelColors.size)
            assertTrue(theme.levelColors.all { it ushr 24 == 0xff })
            assertTrue(theme.key.isNotBlank())
        }
    }
}
