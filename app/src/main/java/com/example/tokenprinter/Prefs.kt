package com.example.tokenprinter

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tokenprinter", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = (sp.getStringSet("fav", emptySet()) ?: emptySet()).toSet()
    fun saveFavorites(s: Set<String>) = sp.edit().putStringSet("fav", HashSet(s)).apply()

    /** Favorite individual printings (Scryfall card ids). */
    fun favArts(): Set<String> = (sp.getStringSet("favArts", emptySet()) ?: emptySet()).toSet()
    fun saveFavArts(s: Set<String>) = sp.edit().putStringSet("favArts", HashSet(s)).apply()

    var lastSync: Long
        get() = sp.getLong("lastSync", 0L)
        set(v) = sp.edit().putLong("lastSync", v).apply()

    var density: Int
        get() = sp.getInt("density", 2)
        set(v) = sp.edit().putInt("density", v).apply()
    var contrast: Float
        get() = sp.getFloat("contrast", 1.2f)
        set(v) = sp.edit().putFloat("contrast", v).apply()
    var invert: Boolean
        get() = sp.getBoolean("invert", false)
        set(v) = sp.edit().putBoolean("invert", v).apply()
    var lsbFirst: Boolean
        get() = sp.getBoolean("lsb", false)
        set(v) = sp.edit().putBoolean("lsb", v).apply()
    var caption: Boolean
        get() = sp.getBoolean("caption", true)
        set(v) = sp.edit().putBoolean("caption", v).apply()
    var fullCard: Boolean
        get() = sp.getBoolean("fullCard", false)
        set(v) = sp.edit().putBoolean("fullCard", v).apply()
    var handshake: Boolean
        get() = sp.getBoolean("handshake", true)
        set(v) = sp.edit().putBoolean("handshake", v).apply()
    var capSize: Int
        get() = sp.getInt("capSize", 32)
        set(v) = sp.edit().putInt("capSize", v).apply()
    var capFont: String
        get() = sp.getString("capFont", "sans-serif") ?: "sans-serif"
        set(v) = sp.edit().putString("capFont", v).apply()
    var capBold: Boolean
        get() = sp.getBoolean("capBold", true)
        set(v) = sp.edit().putBoolean("capBold", v).apply()
    var capPt: Boolean
        get() = sp.getBoolean("capPt", true)
        set(v) = sp.edit().putBoolean("capPt", v).apply()
    var trimSpace: Boolean
        get() = sp.getBoolean("trimSpace", false)
        set(v) = sp.edit().putBoolean("trimSpace", v).apply()
    fun densityFor(profileId: String): Int = sp.getInt("density_$profileId", density)
    fun setDensityFor(profileId: String, v: Int) = sp.edit().putInt("density_$profileId", v).apply()
    var capRules: Boolean
        get() = sp.getBoolean("capRules", false)
        set(v) = sp.edit().putBoolean("capRules", v).apply()
    var capKeywords: Boolean
        get() = sp.getBoolean("capKeywords", true)
        set(v) = sp.edit().putBoolean("capKeywords", v).apply()
    var sort: String
        get() = sp.getString("sort", "NameAsc") ?: "NameAsc"
        set(v) = sp.edit().putString("sort", v).apply()
    var autoContrast: Boolean
        get() = sp.getBoolean("autoContrast", false)
        set(v) = sp.edit().putBoolean("autoContrast", v).apply()
    var profileId: String
        get() = sp.getString("profile", "lx-d02") ?: "lx-d02"
        set(v) = sp.edit().putString("profile", v).apply()
    var gridView: Boolean
        get() = sp.getBoolean("gridView", false)
        set(v) = sp.edit().putBoolean("gridView", v).apply()
    var lastPrinter: String?
        get() = sp.getString("lastPrinter", null)
        set(v) = sp.edit().putString("lastPrinter", v).apply()
}
