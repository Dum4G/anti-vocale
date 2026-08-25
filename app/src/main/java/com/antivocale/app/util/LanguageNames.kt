package com.antivocale.app.util

import java.util.Locale

/**
 * Native language display names resolved from the platform's ICU/CLDR locale
 * data, replacing ~60 hand-maintained per-locale string resources.
 *
 * java.util.Locale delegates to ICU on Android; using it instead of
 * android.icu.util.ULocale keeps the helper runnable on the plain JVM unit
 * test stack (no android.jar stubs involved).
 *
 * Names are always shown natively (each language in its own script,
 * independent of the app UI language). This is a deliberate product policy,
 * mirroring the Android system language picker: users recognize their own
 * language by its endonym regardless of the UI locale.
 *
 * Note this is a behavior change, not a restoration of translator intent:
 * most non-English locales had carried endonyms in the old lang_* resources
 * (de, hi, pt, ...), but values-it/es/fr had localized names instead
 * ("Russo"/"Rus"/"Russe" for ru). Users of those locales now see endonyms
 * where they previously saw localized names (language filter, model-info
 * chips, notification "Detected:" line).
 */
object LanguageNames {

    /**
     * Display name for [code] (ISO 639-1, optionally with a region tag like
     * "pt-BR"), written in that language itself with the first character
     * capitalized ("русский" -> "Русский"; CLDR canonical forms are often
     * lowercase). Region-qualified tags keep their region suffix ("pt-BR" ->
     * "Português (Brasil)"). Falls back to [code] verbatim when ICU cannot
     * resolve it.
     */
    fun nativeLanguageName(code: String): String {
        if (code.isBlank()) return code
        val locale = Locale.forLanguageTag(code)
        val name = if (locale.country.isEmpty()) {
            locale.getDisplayLanguage(locale)
        } else {
            locale.getDisplayName(locale)
        }
        if (name.isNullOrBlank() || name.equals(code, ignoreCase = true)) return code
        return capitalizeFirst(name, locale)
    }

    private fun capitalizeFirst(name: String, locale: Locale): String {
        if (name.isEmpty()) return name
        // Codepoint-safe: don't split surrogate pairs with substring(0, 1).
        val firstCodePoint = name.codePointAt(0)
        val charCount = Character.charCount(firstCodePoint)
        return StringBuilder(name.length)
            .append(name.substring(0, charCount).uppercase(locale))
            .append(name.substring(charCount))
            .toString()
    }
}
