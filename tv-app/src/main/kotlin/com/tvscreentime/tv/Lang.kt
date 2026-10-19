package com.tvscreentime.tv

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** UI language chosen on the parent phone ("in" / "en"); null follows the TV's system language. */
object Lang {
    fun get(c: Context): String? = c.getSharedPreferences("state", Context.MODE_PRIVATE).getString("lang", null)

    fun set(c: Context, tag: String?) =
        c.getSharedPreferences("state", Context.MODE_PRIVATE).edit().putString("lang", tag?.takeIf { it == "in" || it == "en" }).apply()

    /** Use from attachBaseContext. */
    fun wrap(base: Context): Context {
        val tag = get(base) ?: return base
        val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(cfg)
    }
}
