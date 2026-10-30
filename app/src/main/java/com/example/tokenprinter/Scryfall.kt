// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 eddie-w2 and Token Printer contributors

package com.example.tokenprinter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TokenArt(
    val id: String,
    val artUrl: String,
    val cardUrl: String,
    val setName: String,
    val setCode: String,
    val artist: String,
    val number: String,
    val released: String,
    /** Extra text the user typed for a custom art; printed under the name. */
    val note: String = "",
)

data class Token(
    val key: String,
    val name: String,
    val typeLine: String,
    val pt: String,
    val colors: String,
    val oracle: String,
    val arts: List<TokenArt>,
    val keywords: List<String> = emptyList(),
) {
    val keywordText: String get() = keywords.joinToString(", ")

    /** Colors in WUBRG order with slashes, e.g. "B/R/G". Colorless is "C", the same letter the color filter uses. */
    val colorMark: String
        get() {
            val present = "WUBRG".filter { it in colors }
            return if (present.isEmpty()) "C" else present.toList().joinToString("/")
        }
    val newest: String get() = arts.maxOfOrNull { it.released } ?: ""
    val oldest: String get() = arts.minOfOrNull { it.released } ?: ""

    /** Type groups used by the type filter: Creature, Artifact, Enchantment, Emblem, Other. */
    val cats: Set<String> = typeCats(typeLine)

    val subtitle: String
        get() = listOf(pt, typeLine.removePrefix("Token ").trim())
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    /** The printing to show as this token's thumbnail: a favorite printing if there is one. */
    fun thumbArt(favArts: Set<String>): TokenArt = arts.firstOrNull { it.id in favArts } ?: arts.first()

    fun favArtCount(favArts: Set<String>): Int = arts.count { it.id in favArts }
}

val TYPE_CATS = listOf("Creature", "Artifact", "Enchantment", "Emblem", "Custom", "Other")

private fun typeCats(typeLine: String): Set<String> {
    val t = typeLine.lowercase()
    val s = LinkedHashSet<String>()
    if ("creature" in t) s.add("Creature")
    if ("artifact" in t) s.add("Artifact")
    if ("enchantment" in t) s.add("Enchantment")
    if ("emblem" in t) s.add("Emblem")
    if (t == "custom") s.add("Custom")
    if (s.isEmpty()) s.add("Other")
    return s
}

private val KW_RE = Regex(
    """^(flying|vigilance|haste|trample|deathtouch|lifelink|reach|menace|first strike|double strike|defender|flash|hexproof|indestructible|infect|prowess|skulk|fear|intimidate|shadow|horsemanship|wither|persist|undying|changeling|devoid|decayed|ward( \{[^}]+\}| \d+)?|toxic \d+|afterlife \d+|annihilator \d+|bushido \d+|rampage \d+|protection from .+)$""",
    RegexOption.IGNORE_CASE,
)

/** Keyword abilities: Scryfall's `keywords` list, or failing that the keyword-only lines of the rules text. */
private fun keywordsOf(o: JSONObject, face0: JSONObject?, oracle: String): List<String> {
    val arr = o.optJSONArray("keywords") ?: face0?.optJSONArray("keywords")
    val fromApi = ArrayList<String>()
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val k = arr.optString(i).trim()
            if (k.isNotEmpty()) fromApi.add(k)
        }
    }
    if (fromApi.isNotEmpty()) return fromApi.distinct()
    val out = LinkedHashSet<String>()
    for (line in oracle.lines()) {
        val parts = line.split(",").map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
        if (parts.isNotEmpty() && parts.all { KW_RE.matches(it) }) {
            parts.forEach { out.add(it.replaceFirstChar { c -> c.uppercase() }) }
        }
    }
    return out.toList()
}

private val COLOR_NAMES = mapOf('W' to "White", 'U' to "Blue", 'B' to "Black", 'R' to "Red", 'G' to "Green")

fun colorLabel(code: String): String =
    if (code.isEmpty()) "Colorless" else code.map { COLOR_NAMES[it] ?: it.toString() }.joinToString("/")

private data class Flat(
    val id: String, val name: String, val typeLine: String, val pt: String, val colors: String,
    val oracle: String, val artUrl: String, val cardUrl: String, val setName: String,
    val setCode: String, val artist: String, val number: String, val released: String,
    val keywords: String,
) {
    val key: String get() = "$name|$typeLine|$pt|$colors"

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("type", typeLine); put("pt", pt); put("colors", colors)
        put("oracle", oracle); put("art", artUrl); put("card", cardUrl); put("set", setName)
        put("code", setCode); put("artist", artist); put("num", number); put("rel", released)
        put("kw", keywords)
    }

    companion object {
        fun fromJson(o: JSONObject) = Flat(
            o.getString("id"), o.getString("name"), o.getString("type"), o.getString("pt"),
            o.getString("colors"), o.getString("oracle"), o.getString("art"), o.getString("card"),
            o.getString("set"), o.getString("code"), o.getString("artist"), o.getString("num"),
            o.getString("rel"), o.getString("kw"), // an older cache has no "kw": it is ignored and re-downloaded
        )
    }
}

private fun JSONObject.s(k: String): String = if (has(k) && !isNull(k)) getString(k) else ""

object Scryfall {
    const val UA = "TokenPrinter/0.6 (personal Android app)"

    private fun readError(c: HttpURLConnection): String =
        try {
            c.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }?.take(300) ?: ""
        } catch (e: Exception) {
            ""
        }

    fun httpGet(url: String, accept: String = "*/*"): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    fun httpPostJson(url: String, body: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code ${readError(c)}".trim())
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(o: JSONObject): Flat? {
        val faces = o.optJSONArray("card_faces")
        val face0 = faces?.optJSONObject(0)
        val imgs = o.optJSONObject("image_uris") ?: face0?.optJSONObject("image_uris") ?: return null
        val art = imgs.s("art_crop")
        val card = imgs.s("normal")
        if (art.isEmpty() || card.isEmpty()) return null

        fun pick(k: String) = o.s(k).ifEmpty { face0?.s(k) ?: "" }
        val power = pick("power")
        val tough = pick("toughness")
        val colorsArr = o.optJSONArray("colors") ?: face0?.optJSONArray("colors")
        val colors = buildString {
            if (colorsArr != null) for (i in 0 until colorsArr.length()) append(colorsArr.getString(i))
        }
        return Flat(
            id = o.getString("id"),
            name = o.getString("name"),
            typeLine = pick("type_line"),
            pt = if (power.isNotEmpty() || tough.isNotEmpty()) "$power/$tough" else "",
            colors = colors,
            oracle = pick("oracle_text"),
            artUrl = art,
            cardUrl = card,
            setName = o.s("set_name"),
            setCode = o.s("set"),
            artist = pick("artist"),
            number = o.s("collector_number"),
            released = o.s("released_at"),
            keywords = keywordsOf(o, face0, pick("oracle_text")).joinToString("|"),
        )
    }

    /** The grouping key a token card JSON object would get, so decks can be matched to the local list. */
    fun keyOf(card: JSONObject): String? = parse(card)?.key

    private fun group(flat: List<Flat>): List<Token> =
        flat.groupBy { it.key }
            .map { (key, list) ->
                val first = list.first()
                Token(
                    key = key,
                    name = first.name,
                    typeLine = first.typeLine,
                    pt = first.pt,
                    colors = first.colors,
                    oracle = list.firstOrNull { it.oracle.isNotBlank() }?.oracle ?: "",
                    arts = list.sortedByDescending { it.released }.map {
                        TokenArt(it.id, it.artUrl, it.cardUrl, it.setName, it.setCode, it.artist, it.number, it.released)
                    },
                    keywords = list.firstOrNull { it.keywords.isNotEmpty() }?.keywords?.split("|") ?: emptyList(),
                )
            }

    suspend fun loadCache(file: File): List<Token>? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            val arr = JSONArray(file.readText())
            val flat = (0 until arr.length()).map { Flat.fromJson(arr.getJSONObject(it)) }
            if (flat.isEmpty()) null else group(flat)
        } catch (e: Exception) {
            null
        }
    }

    /** Downloads every token printing with unique art (all pages) and caches it on disk. */
    suspend fun fetchAll(cache: File, onProgress: (Int, Int) -> Unit): List<Token> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode("(t:token or t:emblem)", "UTF-8")
        var url: String? = "https://api.scryfall.com/cards/search?q=$q&unique=art&order=name&include_extras=true"
        val flat = ArrayList<Flat>()
        while (url != null) {
            val json = JSONObject(String(httpGet(url, "application/json;q=0.9,*/*;q=0.8"), Charsets.UTF_8))
            val data = json.getJSONArray("data")
            for (i in 0 until data.length()) parse(data.getJSONObject(i))?.let { flat.add(it) }
            onProgress(flat.size, json.optInt("total_cards", flat.size))
            url = if (json.optBoolean("has_more")) json.getString("next_page") else null
            if (url != null) delay(120) // Scryfall asks for 50-100 ms between requests
        }
        val arr = JSONArray()
        flat.forEach { arr.put(it.toJson()) }
        cache.writeText(arr.toString())
        group(flat)
    }
}
