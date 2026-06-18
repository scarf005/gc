package dev.scarf.gc

import android.content.Context
import android.content.res.Resources
import android.content.res.TypedArray
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class WidgetThemeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val resources get() = context.resources
    private fun theme(key: String?) = WidgetTheme.fromKey(context, key)

    @Test
    fun fallsBackToLightTheme() {
        assertEquals("light", theme(null).key)
        assertEquals("light", theme("missing").key)
    }

    @Test
    fun keepsExistingThemeKeys() {
        listOf("light", "dark", "transparent").forEach { assertEquals(it, theme(it).key) }
    }

    @Test
    fun migratesBlueToWinterWithoutADuplicateChoice() {
        assertEquals(theme("winter-light"), theme("blue"))
        assertTrue(WidgetTheme.all(context).none { it.key == "blue" })
    }

    @Test
    fun matchesPrimerContributionPalettesAndOrder() {
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
        assertEquals(palettes.keys.toList() + "transparent", WidgetTheme.all(context).map { it.key })
        palettes.forEach { (key, colors) ->
            assertArrayEquals(key, colors.split(" ").map { (0xff000000L or it.toLong(16)).toInt() }.toIntArray(), theme(key).levelColors(context))
        }
    }

    @Test
    fun usesMatchingTitlesAndCardBackgrounds() {
        WidgetTheme.all(context).forEach {
            assertEquals("widget_theme_${it.key.replace('-', '_')}", resources.getResourceEntryName(it.titleResId))
            assertTrue(context.getString(it.titleResId).isNotBlank())
        }
        listOf("light", "light-high-contrast", "halloween-light", "winter-light").forEach {
            assertEquals(R.drawable.widget_background, theme(it).backgroundResId)
        }
        listOf("dark", "halloween-dark", "winter-dark").forEach {
            assertEquals(R.drawable.widget_background_dark, theme(it).backgroundResId)
        }
        assertEquals(R.drawable.widget_background_dark_high_contrast, theme("dark-high-contrast").backgroundResId)
        listOf("dark-dimmed", "dark-dimmed-high-contrast").forEach {
            assertEquals(R.drawable.widget_background_dark_dimmed, theme(it).backgroundResId)
        }
    }

    @Test
    fun highContrastBordersKeepEmptyCellsVisible() {
        assertEquals(0xff010409.toInt(), theme("light-high-contrast").borderColor)
        assertEquals(0xffffffff.toInt(), theme("dark-high-contrast").borderColor)
        WidgetTheme.all(context).filter { it.key !in listOf("light-high-contrast", "dark-high-contrast") }.forEach {
            assertEquals(null, it.borderColor)
        }
    }

    @Test
    fun emptyPaletteFallsBackToDefaultResourceColorsAndRecyclesTheArray() {
        val emptyArray = mockk<TypedArray>(relaxed = true)
        val modifiedContext = withPalette(theme("dark").paletteResId, emptyArray)

        assertArrayEquals(theme(null).levelColors(context), theme("dark").levelColors(modifiedContext))
        verify(exactly = 1) { emptyArray.recycle() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyDefaultPaletteFailsWithoutRecursiveLoading() {
        val modifiedContext = withPalette(theme(null).paletteResId, mockk<TypedArray>(relaxed = true))
        theme(null).levelColors(modifiedContext)
    }

    @Test
    fun roundTripsEveryThemeKeyAliasAndResourceDefault() {
        val themes = WidgetTheme.all(context)
        themes.forEach { theme ->
            assertEquals(theme, WidgetTheme.fromKey(context, theme.key))
            theme.aliases.forEach { assertEquals(theme, WidgetTheme.fromKey(context, it)) }
        }
        assertEquals(context.getString(R.string.widget_theme_default_key), theme(null).key)
    }

    @Test
    fun transparentThemeUsesGreenWithIncreasingOpacityWithoutACardBackground() {
        val theme = theme("transparent")
        assertEquals(android.R.color.transparent, theme.backgroundResId)
        val colors = theme.levelColors(context)
        assertArrayEquals(intArrayOf(0x20, 0x54, 0x88, 0xc4, 0xff), colors.map { it ushr 24 }.toIntArray())
        assertTrue(colors.all { it and 0xffffff == 0x39d353 })
    }

    @Test
    fun exposesFiveContributionLevelsPerTheme() {
        val themes = WidgetTheme.all(context)
        assertEquals(themes.size, themes.map { it.key }.toSet().size)
        themes.forEach { theme ->
            val colors = resources.obtainTypedArray(theme.paletteResId)
            try { assertEquals(5, colors.length()) } finally { colors.recycle() }
            if (theme.key != "transparent") assertTrue(theme.levelColors(context).all { it ushr 24 == 0xff })
            assertTrue(theme.key.isNotBlank())
        }
    }

    @Test
    @Config(qualifiers = "ko")
    fun resolvesLocalizedTitlesWithoutChangingStoredKeys() {
        assertEquals("GitHub 어두운 회색", context.getString(theme("dark-dimmed").titleResId))
        assertEquals("겨울 (밝게)", context.getString(theme("blue").titleResId))
        assertEquals("light", theme(null).key)
    }

    @Test
    fun resourceOnlyThemesCanChangeOrderDefaultAndAliases() {
        val modifiedContext = mockk<Context>()
        val modifiedResources = mockk<Resources>()
        val catalogue = mockk<TypedArray>(relaxed = true)
        val attributes = mockk<TypedArray>(relaxed = true)
        val styleId = 123
        val aliasesId = 456
        every { modifiedContext.resources } returns modifiedResources
        every { modifiedResources.obtainTypedArray(R.array.widget_themes) } returns catalogue
        every { catalogue.length() } returns 2
        every { catalogue.getResourceId(0, 0) } returns R.style.WidgetTheme_Dark
        every { catalogue.getResourceId(1, 0) } returns styleId
        every { modifiedContext.obtainStyledAttributes(R.style.WidgetTheme_Dark, R.styleable.WidgetTheme) } answers { context.obtainStyledAttributes(R.style.WidgetTheme_Dark, R.styleable.WidgetTheme) }
        every { modifiedContext.obtainStyledAttributes(styleId, R.styleable.WidgetTheme) } returns attributes
        every { attributes.getString(R.styleable.WidgetTheme_widgetThemeKey) } returns "resource-only"
        every { attributes.getResourceId(R.styleable.WidgetTheme_widgetThemeTitle, 0) } returns R.string.widget_theme_winter_light
        every { attributes.getResourceId(R.styleable.WidgetTheme_widgetThemeBackground, 0) } returns android.R.color.transparent
        every { attributes.getResourceId(R.styleable.WidgetTheme_widgetThemePalette, 0) } returns R.array.widget_palette_winter_light
        every { attributes.hasValue(R.styleable.WidgetTheme_widgetThemeBorder) } returns true
        every { attributes.getResourceId(R.styleable.WidgetTheme_widgetThemeAliases, 0) } returns aliasesId
        every { modifiedResources.getStringArray(aliasesId) } returns arrayOf("legacy-one", "legacy-two")
        every { modifiedContext.getString(R.string.widget_theme_default_key) } returns "resource-only"

        val themes = WidgetTheme.all(modifiedContext)
        assertEquals(listOf("dark", "resource-only"), themes.map { it.key })
        assertEquals(R.string.widget_theme_winter_light, themes[1].titleResId)
        assertEquals(android.R.color.transparent, themes[1].backgroundResId)
        assertEquals(R.array.widget_palette_winter_light, themes[1].paletteResId)
        assertEquals(0, themes[1].borderColor)
        listOf(null, "missing", "resource-only", "legacy-one", "legacy-two").forEach {
            assertEquals(themes[1], WidgetTheme.fromKey(modifiedContext, it))
        }
        verify(exactly = 6) { catalogue.recycle() }
        verify(exactly = 6) { attributes.recycle() }
    }

    private fun withPalette(id: Int, colors: TypedArray): Context {
        val modifiedContext = mockk<Context>()
        val modifiedResources = mockk<Resources>()
        every { modifiedContext.resources } returns modifiedResources
        every { modifiedResources.obtainTypedArray(any()) } answers { resources.obtainTypedArray(firstArg()) }
        every { modifiedResources.obtainTypedArray(id) } returns colors
        every { modifiedResources.getStringArray(any()) } answers { resources.getStringArray(firstArg()) }
        every { modifiedContext.getString(any()) } answers { context.getString(firstArg()) }
        every { modifiedContext.obtainStyledAttributes(any<Int>(), any<IntArray>()) } answers { context.obtainStyledAttributes(firstArg<Int>(), secondArg<IntArray>()) }
        return modifiedContext
    }
}
