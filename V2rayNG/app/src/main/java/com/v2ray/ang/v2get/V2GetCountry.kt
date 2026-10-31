package com.v2ray.ang.v2get

import java.util.Locale

/**
 * The country of a server as the app finds it out itself: the exit country measured through the tunnel once a
 * connection is up (see [V2GetConfigSource.detectExitCountry]), never read out of a configuration's name. Codes
 * are ISO 3166 alpha-2 in capitals; the flag and the Persian name are made from the code.
 */
object V2GetCountry {

    private val isoCodes: Set<String> by lazy { Locale.getISOCountries().toSet() }

    /** English country name (lower case) to code, for an address service that answers with a name and no code. */
    private val byEnglishName: Map<String, String> by lazy {
        Locale.getISOCountries().associateBy({ regionLocale(it).getDisplayCountry(Locale.ENGLISH).lowercase() }, { it })
    }

    private fun regionLocale(code: String): Locale = Locale.Builder().setRegion(code).build()

    /** The code of what an address service answered ("DE", "de" or "Germany"), or null when it is none. */
    fun normalize(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.length == 2) return value.uppercase().takeIf { it in isoCodes }
        return byEnglishName[value.lowercase()]
    }

    /** The flag emoji of [code]: its two letters as regional indicator symbols. */
    fun flag(code: String): String {
        val upper = code.uppercase()
        if (upper.length != 2 || upper.any { it !in 'A'..'Z' }) return ""
        return upper.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    /** The name of the country in Persian; the code itself when the device knows no name. */
    fun name(code: String): String =
        regionLocale(code.uppercase()).getDisplayCountry(Locale.forLanguageTag("fa")).ifBlank { code.uppercase() }

    /** What a server is called on screen: flag and country once the country is known, else [fallback]. */
    fun title(code: String, fallback: String): String =
        if (code.isBlank()) fallback else "${flag(code)} ${name(code)}".trim()
}
