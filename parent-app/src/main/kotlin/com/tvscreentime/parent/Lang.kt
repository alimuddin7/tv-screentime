package com.tvscreentime.parent

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** App language, "in" or "en". Defaults to Indonesian when the phone is Indonesian, else English. Also pushed to every TV. */
object Lang {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(c: Context): String = prefs(c).getString("lang", null)
        ?: if (Locale.getDefault().language in setOf("in", "id")) "in" else "en"

    fun set(c: Context, tag: String) = prefs(c).edit().putString("lang", tag).apply()

    fun wrap(base: Context): Context =
        base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(get(base))) })
}
