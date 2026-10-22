package com.techgv.cineverse.data.tmdb

// Intl.DisplayNames throws a RangeError for a malformed code.
private fun intlLanguageName(code: String): String? =
    js("{ try { return new Intl.DisplayNames(['en'], { type: 'language' }).of(code) ?? null; } catch (e) { return null; } }")

internal actual fun englishLanguageName(code: String): String? = intlLanguageName(code)
