package com.example.tokenprinter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Turns a deck list into the tokens it needs.
 *
 * Scryfall links every card to the tokens it makes (`all_parts` with component "token"),
 * so: look the cards up in batches of 75, collect the linked token ids, fetch those token cards,
 * and match them to the locally synced token list by name/type/P-T/colors.
 */
object DeckImport {
    private const val COLLECTION = "https://api.scryfall.com/cards/collection"

    class Parsed(val name: String?, val cards: List<String>)

    class Result(
        val entries: List<DeckEntry>,
        val unmatchedCards: List<String>,
        val missingTokens: List<String>,
        val cardCount: Int,
    )

    // ------------------------------------------------------------------ parsing

    private val QTY_LINE = Regex("""^(\d+)\s*[xX]?\s+(.+)$""")
    private val FOIL = Regex("""\s*\*[^*]*\*\s*$""")
    private val SET_SUFFIX = Regex("""\s+\([A-Za-z0-9]{2,6}\)(\s+[A-Za-z0-9★-]+)?\s*$""")
    private val BRACKETS = Regex("""\s+\[[^\]]*]""")
    private val CARETS = Regex("""\s+\^[^^]*\^""")
    private val MOX_ID = Regex("""moxfield\.com/decks/([A-Za-z0-9_-]+)""")

    private fun clean(raw: String): String {
        var s = raw
        s = CARETS.replace(s, "")
        s = BRACKETS.replace(s, "")
        s = FOIL.replace(s, "")
        s = SET_SUFFIX.replace(s, "")
        return s.trim()
    }

    /** Understands "1 Sol Ring", "1x Sol Ring (CMM) 400 *F*", Arena exports, and plain names. */
    fun parseList(text: String): List<String> {
        val names = LinkedHashSet<String>()
        var skipping = false
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue
            when (line.trimEnd(':').trim().lowercase()) {
                "deck", "main", "mainboard", "commander", "commanders", "sideboard", "companion", "companions" -> {
                    skipping = false
                    continue
                }
                "maybeboard", "considering", "tokens" -> {
                    skipping = true
                    continue
                }
            }
            if (skipping) continue
            if (line.startsWith("SB:", ignoreCase = true)) line = line.substring(3).trim()
            val m = QTY_LINE.matchEntire(line)
            if (m == null && line.any { it.isDigit() }) continue // headings such as "Lands (36)"
            val name = clean(if (m != null) m.groupValues[2] else line)
            if (name.length >= 2) names.add(name)
        }
        return names.toList()
    }

    // ----------------------------------------------------------------- Moxfield

    /**
     * Best effort: Moxfield only lets approved apps use its API, so this often fails with HTTP 403.
     * The error message tells the user to export the list instead.
     */
    suspend fun fromMoxfield(input: String): Parsed = withContext(Dispatchers.IO) {
        val id = MOX_ID.find(input)?.groupValues?.get(1)
            ?: throw IOException("That doesn't look like a Moxfield deck link.")
        val bytes = try {
            Scryfall.httpGet("https://api2.moxfield.com/v2/decks/all/$id", "application/json")
        } catch (e: IOException) {
            throw IOException(
                "Moxfield didn't allow this app to read the deck (${e.message}). " +
                    "In Moxfield, use the deck's Export option to copy the text list, then paste it here."
            )
        }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val names = LinkedHashSet<String>()
        collectCards(json, names)
        Parsed(json.optString("name").ifBlank { null }, names.toList())
    }

    /** Shape-agnostic: any object with "quantity" and a "card" holding a "name" is a card line. */
    private fun collectCards(node: Any?, out: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                val card = node.optJSONObject("card")
                if (card != null && node.has("quantity")) {
                    card.optString("name").takeIf { it.isNotBlank() }?.let { out.add(it) }
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "maybeboard" || k == "tokens") continue
                    collectCards(node.opt(k), out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectCards(node.opt(i), out)
        }
    }

    // ---------------------------------------------------------------- resolving

    private fun postCollection(identifiers: JSONArray): JSONObject {
        val body = JSONObject().put("identifiers", identifiers).toString()
        return JSONObject(String(Scryfall.httpPostJson(COLLECTION, body), Charsets.UTF_8))
    }

    suspend fun resolve(
        names: List<String>,
        local: List<Token>,
        favArts: Set<String>,
        onProgress: (String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val byKey = local.associateBy { it.key }

        // 1. cards -> linked token printing ids (second pass retries front-face names)
        val tokenSources = LinkedHashMap<String, MutableSet<String>>()
        var pending: List<String> = names
        val unmatched = ArrayList<String>()
        for (pass in 0..1) {
            val retry = ArrayList<String>()
            var done = 0
            for (chunk in pending.chunked(75)) {
                onProgress("Looking up cards ${minOf(done + chunk.size, pending.size)} / ${pending.size}")
                val ids = JSONArray()
                chunk.forEach { ids.put(JSONObject().put("name", it)) }
                val resp = postCollection(ids)
                val data = resp.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val card = data.getJSONObject(i)
                    val cardName = card.getString("name")
                    val parts = card.optJSONArray("all_parts") ?: continue
                    for (j in 0 until parts.length()) {
                        val p = parts.getJSONObject(j)
                        if (p.optString("component") == "token") {
                            tokenSources.getOrPut(p.getString("id")) { LinkedHashSet() }.add(cardName)
                        }
                    }
                }
                val nf = resp.optJSONArray("not_found") ?: JSONArray()
                for (i in 0 until nf.length()) {
                    val n = nf.getJSONObject(i).optString("name")
                    if (n.isEmpty()) continue
                    val front = n.substringBefore(" // ").trim()
                    if (pass == 0 && front != n) retry.add(front) else unmatched.add(n)
                }
                done += chunk.size
                delay(110)
            }
            pending = retry
            if (pending.isEmpty()) break
        }

        // 2. token ids -> token cards -> local tokens
        val merged = LinkedHashMap<String, Pair<MutableSet<String>, String?>>()
        val missing = LinkedHashSet<String>()
        var fetched = 0
        val allIds = tokenSources.keys.toList()
        for (chunk in allIds.chunked(75)) {
            onProgress("Matching tokens ${minOf(fetched + chunk.size, allIds.size)} / ${allIds.size}")
            val ids = JSONArray()
            chunk.forEach { ids.put(JSONObject().put("id", it)) }
            val data = postCollection(ids).optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val card = data.getJSONObject(i)
                val key = Scryfall.keyOf(card)
                val tokenId = card.getString("id")
                val sources = tokenSources[tokenId] ?: emptySet<String>()
                val token = key?.let { byKey[it] }
                if (token == null) {
                    missing.add(card.optString("name"))
                    continue
                }
                val existing = merged[token.key]
                val linkedArt = if (token.arts.any { it.id == tokenId }) tokenId else null
                if (existing == null) {
                    merged[token.key] = LinkedHashSet(sources) to linkedArt
                } else {
                    existing.first.addAll(sources)
                    if (existing.second == null && linkedArt != null) {
                        merged[token.key] = existing.first to linkedArt
                    }
                }
            }
            fetched += chunk.size
            delay(110)
        }

        val entries = merged.map { (key, v) ->
            val token = byKey.getValue(key)
            val art = token.arts.firstOrNull { it.id in favArts }?.id ?: v.second
            DeckEntry(key, art, 1, v.first.toList())
        }.sortedBy { byKey.getValue(it.tokenKey).name.lowercase() }

        Result(entries, unmatched, missing.toList(), names.size)
    }
}
