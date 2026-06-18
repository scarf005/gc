package dev.scarf.gc

import android.content.Context
import android.content.res.TypedArray

internal data class WidgetTheme(val key: String, val titleResId: Int, val backgroundResId: Int, val paletteResId: Int, val borderColor: Int?, val aliases: List<String>) {
    fun levelColors(context: Context): IntArray = context.resources.obtainTypedArray(paletteResId).read { colors ->
        IntArray(colors.length()) { colors.getColor(it, 0) }.takeIf { it.isNotEmpty() }
            ?: fromKey(context, null).also { require(it.key != key) { "Default widget palette is empty" } }.levelColors(context)
    }

    companion object {
        fun all(context: Context): List<WidgetTheme> = context.resources.obtainTypedArray(R.array.widget_themes).read { themes ->
            List(themes.length()) { index ->
                context.obtainStyledAttributes(themes.getResourceId(index, 0), R.styleable.WidgetTheme).read { attributes ->
                    WidgetTheme(
                        requireNotNull(attributes.getString(R.styleable.WidgetTheme_widgetThemeKey)),
                        attributes.getResourceId(R.styleable.WidgetTheme_widgetThemeTitle, 0),
                        attributes.getResourceId(R.styleable.WidgetTheme_widgetThemeBackground, 0),
                        attributes.getResourceId(R.styleable.WidgetTheme_widgetThemePalette, 0),
                        if (attributes.hasValue(R.styleable.WidgetTheme_widgetThemeBorder)) attributes.getColor(R.styleable.WidgetTheme_widgetThemeBorder, 0) else null,
                        attributes.getResourceId(R.styleable.WidgetTheme_widgetThemeAliases, 0).takeIf { it != 0 }?.let { context.resources.getStringArray(it).toList() }.orEmpty(),
                    )
                }
            }
        }

        fun fromKey(context: Context, key: String?) = all(context).let { themes ->
            themes.firstOrNull { it.key == key || key in it.aliases } ?: themes.single { it.key == context.getString(R.string.widget_theme_default_key) }
        }
    }
}

private inline fun <T> TypedArray.read(block: (TypedArray) -> T): T = try { block(this) } finally { recycle() }
