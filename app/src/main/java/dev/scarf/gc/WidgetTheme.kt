package dev.scarf.gc

internal enum class WidgetTheme(val key: String, val titleResId: Int, val backgroundResId: Int, val levelColors: IntArray) {
    Light("light", R.string.widget_theme_light, R.drawable.widget_background, defaultLevelColors),
    Dark(
        "dark",
        R.string.widget_theme_dark,
        R.drawable.widget_background_dark,
        intArrayOf(0xff161b22.toInt(), 0xff0e4429.toInt(), 0xff006d32.toInt(), 0xff26a641.toInt(), 0xff39d353.toInt()),
    ),
    Blue(
        "blue",
        R.string.widget_theme_blue,
        R.drawable.widget_background_blue,
        intArrayOf(0xffeaf2ff.toInt(), 0xffb6e3ff.toInt(), 0xff54aeff.toInt(), 0xff0969da.toInt(), 0xff0550ae.toInt()),
    ),
    Transparent("transparent", R.string.widget_theme_transparent, android.R.color.transparent, defaultLevelColors);

    companion object {
        val default = Light
        fun fromKey(key: String?) = values().firstOrNull { it.key == key } ?: default
    }
}
