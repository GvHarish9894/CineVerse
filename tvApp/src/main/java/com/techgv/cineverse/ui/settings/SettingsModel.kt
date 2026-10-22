package com.techgv.cineverse.ui.settings

import com.techgv.cineverse.data.auth.AuthSession
import com.techgv.cineverse.data.settings.AccountDefaults
import com.techgv.cineverse.data.settings.Settings
import com.techgv.cineverse.data.tmdb.AccountDto

/** §7.9 Content language values with their display names (prototype `LANGS`). */
val LANGUAGE_NAMES = mapOf(
    "en-US" to "English",
    "hi-IN" to "हिन्दी · Hindi",
    "ta-IN" to "தமிழ் · Tamil",
    "ml-IN" to "മലയാളം · Malayalam",
    "es-ES" to "Español",
)

val REGION_NAMES = mapOf("IN" to "India", "US" to "United States", "GB" to "United Kingdom")

/** ←→ cycling: the value [delta] steps from [current] in [values], wrapping at both ends. */
fun <T> List<T>.cycle(current: T, delta: Int): T = this[Math.floorMod(indexOf(current) + delta, size)]

/**
 * The account's TMDB defaults mapped onto supported values (`en` → `en-US`; unsupported language / region
 * keep the current one), or null when they would change nothing.
 */
fun accountDefaultsOffer(session: AuthSession, account: AccountDto, current: Settings): AccountDefaults? {
    val language = Settings.LANGUAGES.firstOrNull { it.substringBefore('-') == account.language } ?: current.language
    val region = account.region?.takeIf { it in Settings.REGIONS } ?: current.region
    if (language == current.language && region == current.region && account.includeAdult == current.includeAdult) return null
    return AccountDefaults(session.accountObjectId.value, language, region, account.includeAdult)
}
