package dev.scarf.gc

internal enum class WidgetTheme(val key: String, val titleResId: Int, val backgroundResId: Int, val levelColors: IntArray, val borderColor: Int? = null) {
    Light("light", R.string.widget_theme_light, R.drawable.widget_background, defaultLevelColors),
    Dark(
        "dark",
        R.string.widget_theme_dark,
        R.drawable.widget_background_dark,
        intArrayOf(0xff151b23.toInt(), 0xff033a16.toInt(), 0xff196c2e.toInt(), 0xff2ea043.toInt(), 0xff56d364.toInt()),
    ),
    LightHighContrast(
        "light-high-contrast",
        R.string.widget_theme_light_high_contrast,
        R.drawable.widget_background,
        intArrayOf(0xffffffff.toInt(), 0xff82e596.toInt(), 0xff26a148.toInt(), 0xff117f32.toInt(), 0xff024c1a.toInt()),
        borderColor = 0xff010409.toInt(),
    ),
    DarkHighContrast(
        "dark-high-contrast",
        R.string.widget_theme_dark_high_contrast,
        R.drawable.widget_background_dark_high_contrast,
        intArrayOf(0xff010409.toInt(), 0xff007728.toInt(), 0xff02a232.toInt(), 0xff0ac740.toInt(), 0xff4ae168.toInt()),
        borderColor = 0xffffffff.toInt(),
    ),
    DarkDimmed(
        "dark-dimmed",
        R.string.widget_theme_dark_dimmed,
        R.drawable.widget_background_dark_dimmed,
        intArrayOf(0xff2a313c.toInt(), 0xff1b4721.toInt(), 0xff2b6a30.toInt(), 0xff46954a.toInt(), 0xff6bc46d.toInt()),
    ),
    DarkDimmedHighContrast(
        "dark-dimmed-high-contrast",
        R.string.widget_theme_dark_dimmed_high_contrast,
        R.drawable.widget_background_dark_dimmed,
        intArrayOf(0xff151b23.toInt(), 0xff1b4721.toInt(), 0xff2b6a30.toInt(), 0xff46954a.toInt(), 0xff6bc46d.toInt()),
    ),
    HalloweenLight(
        "halloween-light",
        R.string.widget_theme_halloween_light,
        R.drawable.widget_background,
        intArrayOf(0xffeff2f5.toInt(), 0xfff0db3d.toInt(), 0xffffd642.toInt(), 0xfff68c41.toInt(), 0xff1f2328.toInt()),
    ),
    HalloweenDark(
        "halloween-dark",
        R.string.widget_theme_halloween_dark,
        R.drawable.widget_background_dark,
        intArrayOf(0xff151b23.toInt(), 0xfffac68f.toInt(), 0xffc46212.toInt(), 0xff984b10.toInt(), 0xffe3d04f.toInt()),
    ),
    WinterLight(
        "winter-light",
        R.string.widget_theme_winter_light,
        R.drawable.widget_background,
        intArrayOf(0xffeff2f5.toInt(), 0xffb6e3ff.toInt(), 0xff54aeff.toInt(), 0xff0969da.toInt(), 0xff0a3069.toInt()),
    ),
    WinterDark(
        "winter-dark",
        R.string.widget_theme_winter_dark,
        R.drawable.widget_background_dark,
        intArrayOf(0xff151b23.toInt(), 0xff0c2d6b.toInt(), 0xff1158c7.toInt(), 0xff58a6ff.toInt(), 0xffcae8ff.toInt()),
    ),
    Transparent("transparent", R.string.widget_theme_transparent, android.R.color.transparent, defaultLevelColors);

    companion object {
        val default = Light
        fun fromKey(key: String?) = if (key == "blue") WinterLight else values().firstOrNull { it.key == key } ?: default
    }
}
