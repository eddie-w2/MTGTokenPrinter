// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 eddie-w2 and Token Printer contributors
// Portions ported from TiMini-Print (https://github.com/Dejniel/TiMini-Print),
// Copyright 2026 Daniel Banecki, licensed under the Apache License 2.0. See THIRD_PARTY_NOTICES.md.

package com.example.tokenprinter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The Bluetooth command languages Token Printer can speak. */
enum class PrinterFamily(val label: String) {
    FUNNY_LX("Funny Print (LX-D)"),
    TINY("Cat printer (classic)"),
    V5G("Cat printer (MX/YT series)"),
    V5X("Cat printer (MXW01 series)"),
    PHOMEMO("Phomemo"),
    ESCPOS("Receipt printer (ESC/POS)"),
    ;

    companion object {
        fun fromCatalog(s: String): PrinterFamily? = when (s) {
            "funny_lx" -> FUNNY_LX
            "tiny" -> TINY
            "v5g" -> V5G
            "v5x" -> V5X
            "phomemo_esc" -> PHOMEMO
            "escpos" -> ESCPOS
            else -> null
        }
    }
}

/**
 * One printer model: how to talk to it and how wide its paper is.
 * Most come from the TiMini-Print catalog (assets/printers/catalog.json, Apache-2.0); a few are Token Printer's own.
 */
data class PrinterType(
    val key: String,
    val name: String,
    val family: PrinterFamily,
    val variant: String?,
    val widthDots: Int,
    val dpi: Int,
    /** Prefers Classic Bluetooth (SPP) when the printer offers both. */
    val spp: Boolean,
    val mtu: Int?,
    /** Cat printers: send run-length compressed lines when that is shorter. */
    val rle: Boolean,
    /** Paper-feed steps after the image (cat printers), in the printer's own units. */
    val postFeed: Int,
    val speed: Int?,
    /** Heat / density for light, normal and dark prints. */
    val energy: IntArray?,
    val density: IntArray?,
    /** The vendor apps this model is sold with, e.g. "Tiny Print". */
    val apps: List<String>,
) {
    /** "Darkness" 1..3 in the app maps to the low / middle / high levels. */
    fun energyFor(darkness: Int): Int = energy?.getOrNull((darkness - 1).coerceIn(0, 2)) ?: 0
    fun densityFor(darkness: Int): Int? = density?.getOrNull((darkness - 1).coerceIn(0, 2))

    /** TiMini-style blackening 1..5 for darkness 1..3. */
    fun blackeningFor(darkness: Int): Int = when (darkness) {
        1 -> 1
        3 -> 5
        else -> 3
    }

    /** The vendor app(s) this printer is sold with, for grouping the printer list. */
    val appGroup: String
        get() = when {
            family == PrinterFamily.ESCPOS -> "Receipt printers"
            family == PrinterFamily.FUNNY_LX -> "Funny Print"
            apps == listOf("Tiny Print") -> "Tiny Print / iPrint"
            apps.isNotEmpty() -> apps.joinToString(" / ")
            else -> family.label
        }

    val description: String
        get() = buildString {
            append(family.label)
            append(" · ").append(widthDots).append(" dots")
            if (apps.isNotEmpty()) append(" · ").append(apps.joinToString(" / ")).append(" app")
        }

    override fun equals(other: Any?): Boolean = other is PrinterType && other.key == key
    override fun hashCode(): Int = key.hashCode()
}

/** A printer type picked for a specific device, with how sure the app is about it. */
data class Detected(val type: PrinterType, val sure: Boolean)

/**
 * Recognises printers from their Bluetooth name (and, for a few, their advertised manufacturer data).
 * The matching rules are a port of TiMini-Print's detection (devices/profiles.py ModelDetection.matched_specificity
 * and devices/catalog.py detect_model): each vendor app's best match wins, and models with longer, more exact
 * name triggers beat shorter ones.
 */
class PrinterCatalog private constructor(
    val types: List<PrinterType>,
    private val rules: List<Rule>,
) {
    private class Rule(
        val type: PrinterType,
        val origins: List<String>,
        val whitespace: String,
        val exact: List<String>,
        val prefixes: List<String>,
        val substrings: List<String>,
        val suffixes: List<String>,
        val excludedPrefixes: List<String>,
        val mfrSuffixes: List<String>,
        val mfrLengths: List<Int>,
        val macPrefixes: List<String>,
        val macSuffixes: List<String>,
        val excludedMacSuffixes: List<String>,
        val pattern: Regex?,
        val patternCi: Regex?,
        val allOf: Boolean,
    )

    fun byKey(key: String?): PrinterType? = types.firstOrNull { it.key == key } ?: extras.firstOrNull { it.key == key }

    /** A choice under "Try as…": named after the app the printer is sold with, since that's what people know. */
    class TryAs(val title: String, val detail: String, val type: PrinterType)

    /** The types offered under "Try as…" for a printer the app doesn't recognise, grouped by vendor app. */
    val tryAs: List<TryAs>
        get() = listOfNotNull(
            byKey("funny_lx_d")?.let { TryAs("Funny Print", "LX-D, XenGro AH-M2 and similar · 384 dots", it) },
            byKey("gb01")?.let { TryAs("Tiny Print / iPrint", "Classic cat printers (GB01, GT01) · 384 dots", it) },
            byKey("x6")?.let { TryAs("Tiny Print", "X5, X6, X7 pocket printers · 384 dots", it) },
            byKey("mx05")?.let { TryAs("Fun Print / iBleem", "MX, YT and BQ printers · 384 dots", it) },
            byKey("v5x")?.let { TryAs("Fun Print / iBleem", "MXW01 and newer models · 384 dots", it) },
            byKey("phomemo_m02")?.let { TryAs("Phomemo", "M02, T02 and similar · 384 dots", it) },
            byKey("phomemo_m02s")?.let { TryAs("Phomemo", "M02S, M02 Pro (300 dpi) · 576 dots", it) },
            byKey(ESCPOS_58)?.let { TryAs("Receipt printer", "58 mm ESC/POS · 384 dots", it) },
            byKey(ESCPOS_80)?.let { TryAs("Receipt printer", "80 mm ESC/POS · 576 dots", it) },
        )

    /** All models that match [name], best first. Several can match when vendor apps disagree. */
    fun detect(name: String, address: String?, manufacturerData: List<ByteArray>): List<PrinterType> {
        if (name.isBlank()) return emptyList()
        val found = LinkedHashMap<String, PrinterType>()
        val byOrigin = LinkedHashMap<String, MutableList<Rule>>()
        for (r in rules) for (o in r.origins.ifEmpty { listOf("") }) byOrigin.getOrPut(o) { mutableListOf() }.add(r)
        for ((_, entries) in byOrigin) {
            for (caseSensitive in listOf(true, false)) {
                var best: IntArray? = null
                val picks = mutableListOf<PrinterType>()
                for (r in entries) {
                    val s = specificity(r, name, address, caseSensitive, manufacturerData) ?: continue
                    val c = best?.let { compare(s, it) } ?: 1
                    if (c > 0) {
                        best = s
                        picks.clear()
                        picks.add(r.type)
                    } else if (c == 0 && picks.none { it.key == r.type.key }) {
                        picks.add(r.type)
                    }
                }
                if (picks.isNotEmpty()) {
                    picks.forEach { found.putIfAbsent(it.key, it) }
                    break
                }
            }
        }
        // Token Printer's own additions (LX-D lookalikes the catalog doesn't list yet).
        val upper = name.uppercase()
        if (found.isEmpty()) {
            EXTRA_LX_NAMES.firstOrNull { upper.startsWith(it) }?.let { byKey("funny_lx_d")?.let { t -> found[t.key] = t } }
        }
        return found.values.toList()
    }

    private fun compare(a: IntArray, b: IntArray): Int {
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return a.size.compareTo(b.size)
    }

    private fun normalize(value: String, mode: String): String = when (mode) {
        "preserve" -> value
        "trim" -> value.trim()
        else -> value.replace(Regex("\\s+"), "")
    }

    private fun triggerScore(trigger: String, rank: Int, mac: Boolean): IntArray {
        val t = trigger.replace(Regex("\\s+"), "")
        val len = if (t.endsWith("-") || t.endsWith("_")) t.length - 1 else t.length
        return intArrayOf(len, if (mac) 1 else 0, rank, t.length, t.count { it.isUpperCase() })
    }

    private fun specificity(r: Rule, deviceName: String, address: String?, caseSensitive: Boolean, mfr: List<ByteArray>): IntArray? {
        val hasMac = r.macPrefixes.isNotEmpty() || r.macSuffixes.isNotEmpty() || r.excludedMacSuffixes.isNotEmpty()
        val adRank = (if (r.mfrSuffixes.isNotEmpty()) 1 else 0) + (if (r.mfrLengths.isNotEmpty()) 1 else 0)
        if (r.mfrSuffixes.isNotEmpty()) {
            val ok = mfr.any { data ->
                (r.mfrLengths.isEmpty() || data.size in r.mfrLengths) &&
                    r.mfrSuffixes.any { suf -> hex(data).endsWith(suf.uppercase()) }
            }
            if (!ok) return null
        }
        if (hasMac) {
            if (address == null || !MAC.matches(address.trim())) return null
            val a = address.uppercase().replace(Regex("[^0-9A-F]"), "")
            if (r.excludedMacSuffixes.any { a.endsWith(it) }) return null
            if (r.macPrefixes.isNotEmpty() && r.macPrefixes.none { a.startsWith(it) }) return null
            if (r.macSuffixes.isNotEmpty() && r.macSuffixes.none { a.endsWith(it) }) return null
        }
        val name = normalize(deviceName, r.whitespace)
        val pattern = if (caseSensitive) r.pattern else r.patternCi
        if (pattern != null && !pattern.matches(name)) return null
        fun norm(list: List<String>) = list.map { normalize(it, r.whitespace) }
        val target = if (caseSensitive) name else name.uppercase()
        fun cand(s: String) = if (caseSensitive) s else s.uppercase()
        if (norm(r.excludedPrefixes).any { target.startsWith(cand(it)) }) return null
        if (pattern != null && r.exact.isEmpty() && r.prefixes.isEmpty() && r.substrings.isEmpty() && r.suffixes.isEmpty()) {
            return intArrayOf(name.length, (if (hasMac) 1 else 0) + adRank, 2, name.length, 0)
        }
        val ex = norm(r.exact).filter { target == cand(it) }.map { triggerScore(it, 2, hasMac) }
        val pre = norm(r.prefixes).filter { target.startsWith(cand(it)) }.map { triggerScore(it, 1, hasMac) }
        val sub = norm(r.substrings).filter { target.contains(cand(it)) }.map { triggerScore(it, 0, hasMac) }
        val suf = norm(r.suffixes).filter { it.isNotEmpty() && target.endsWith(cand(it)) }.map { triggerScore(it, 1, hasMac) }
        if (r.allOf) {
            val groups = listOf(r.exact to ex, r.prefixes to pre, r.substrings to sub, r.suffixes to suf)
            if (groups.any { (configured, matched) -> configured.isNotEmpty() && matched.isEmpty() }) return null
            val bests = groups.filter { it.first.isNotEmpty() }.map { (_, m) -> m.reduce { a, b -> if (compare(a, b) >= 0) a else b } }
            return intArrayOf(
                bests.sumOf { it[0] }, (if (hasMac) 1 else 0) + adRank,
                bests.sumOf { it[2] }, bests.sumOf { it[3] }, bests.sumOf { it[4] },
            )
        }
        val all = ex + pre + sub + suf
        if (all.isEmpty()) return null
        val best = all.reduce { a, b -> if (compare(a, b) >= 0) a else b }.copyOf()
        if (r.mfrSuffixes.isNotEmpty()) best[1] += adRank
        return best
    }

    companion object {
        const val ESCPOS_58 = "escpos_58"
        const val ESCPOS_80 = "escpos_80"

        /** Advertised names of printers that have actually been tested with Token Printer. */
        val TESTED_NAMES = setOf("LX-D02")

        /** Printers that use the Funny Print app but aren't in the catalog (the XenGro AH-M2 is confirmed to use Funny Print). */
        private val EXTRA_LX_NAMES = listOf("AH-M2", "AHM2", "ITW14302", "SWS-PT1", "BH-01")

        private val MAC = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

        private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

        /** Generic receipt printers: not in TiMini's catalog, picked by hand from "Try as…". */
        val extras = listOf(
            PrinterType(ESCPOS_58, "Receipt printer, 58 mm", PrinterFamily.ESCPOS, null, 384, 203, true, null, false, 0, null, null, null, emptyList()),
            PrinterType(ESCPOS_80, "Receipt printer, 80 mm", PrinterFamily.ESCPOS, null, 576, 203, true, null, false, 0, null, null, null, emptyList()),
        )

        @Volatile private var instance: PrinterCatalog? = null

        fun get(ctx: Context): PrinterCatalog = instance ?: synchronized(this) {
            instance ?: load(ctx).also { instance = it }
        }

        private fun strings(o: JSONObject, k: String): List<String> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return List(a.length()) { a.getString(it) }
        }

        private fun ints(a: JSONArray?): IntArray? = a?.let { arr -> IntArray(arr.length()) { arr.getInt(it) } }

        private fun load(ctx: Context): PrinterCatalog {
            val text = ctx.assets.open("printers/catalog.json").bufferedReader().use { it.readText() }
            val models = JSONObject(text).getJSONArray("models")
            val types = mutableListOf<PrinterType>()
            val rules = mutableListOf<Rule>()
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                val family = PrinterFamily.fromCatalog(m.getString("family")) ?: continue
                val type = PrinterType(
                    key = m.getString("key"),
                    name = m.optString("name", m.getString("key")),
                    family = family,
                    variant = if (m.isNull("variant")) null else m.optString("variant"),
                    widthDots = m.getInt("width"),
                    dpi = m.optInt("dpi", 203),
                    spp = m.optBoolean("spp"),
                    mtu = if (m.isNull("mtu")) null else m.optInt("mtu"),
                    rle = m.optBoolean("rle"),
                    postFeed = m.optInt("postFeed", 2),
                    speed = if (m.isNull("speed")) null else m.optInt("speed"),
                    energy = ints(m.optJSONArray("energy")),
                    density = ints(m.optJSONArray("density")),
                    apps = strings(m, "apps"),
                )
                types += type
                val origins = strings(m, "origins")
                val ws = m.optString("whitespace", "remove")
                val det = m.getJSONArray("detect")
                for (j in 0 until det.length()) {
                    val d = det.getJSONObject(j)
                    val p = d.optString("name_pattern").ifEmpty { null }
                    rules += Rule(
                        type, origins, ws,
                        strings(d, "exact_names"), strings(d, "prefixes"), strings(d, "substrings"), strings(d, "suffixes"),
                        strings(d, "excluded_prefixes"),
                        strings(d, "manufacturer_data_suffixes"),
                        ints(d.optJSONArray("manufacturer_data_lengths"))?.toList() ?: emptyList(),
                        strings(d, "mac_prefixes").map { it.uppercase() },
                        strings(d, "mac_suffixes").map { it.uppercase() },
                        strings(d, "excluded_mac_suffixes").map { it.uppercase() },
                        p?.let { runCatching { Regex(it) }.getOrNull() },
                        p?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() },
                        d.optBoolean("all_of"),
                    )
                }
            }
            return PrinterCatalog(types, rules)
        }
    }
}
