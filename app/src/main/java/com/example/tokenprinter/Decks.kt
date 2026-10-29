package com.example.tokenprinter

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One token inside a deck folder. [artId] null means "use the default printing". */
data class DeckEntry(
    val tokenKey: String,
    val artId: String?,
    val qty: Int,
    val sources: List<String>,
)

data class Deck(
    val id: String,
    val name: String,
    val created: Long,
    val origin: String,
    val entries: List<DeckEntry>,
) {
    val copies: Int get() = entries.sumOf { it.qty }
}

class DeckStore(private val file: File) {

    fun load(): List<Deck> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(decks: List<Deck>) {
        try {
            val arr = JSONArray()
            decks.forEach { arr.put(toJson(it)) }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // best effort; decks stay in memory for this run
        }
    }

    fun toJson(d: Deck) = JSONObject().apply {
        put("id", d.id)
        put("name", d.name)
        put("created", d.created)
        put("origin", d.origin)
        put("entries", JSONArray().also { arr ->
            d.entries.forEach { e ->
                arr.put(JSONObject().apply {
                    put("key", e.tokenKey)
                    put("art", e.artId ?: "")
                    put("qty", e.qty)
                    put("src", JSONArray(e.sources))
                })
            }
        })
    }

    fun fromJson(o: JSONObject): Deck {
        val arr = o.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            val src = e.optJSONArray("src") ?: JSONArray()
            DeckEntry(
                tokenKey = e.getString("key"),
                artId = e.optString("art").ifEmpty { null },
                qty = e.optInt("qty", 1),
                sources = (0 until src.length()).map { src.getString(it) },
            )
        }
        return Deck(
            id = o.getString("id"),
            name = o.optString("name", "Deck"),
            created = o.optLong("created", 0L),
            origin = o.optString("origin", ""),
            entries = entries,
        )
    }
}
