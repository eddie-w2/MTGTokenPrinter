package com.example.tokenprinter

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Everything worth keeping, as one JSON file: favorites, decks, custom tokens (with their pictures) and settings. */
object Backup {
    private const val VERSION = 1

    class Restored(
        val favorites: Set<String>,
        val favArts: Set<String>,
        val decks: List<Deck>,
        val customs: List<CustomToken>,
        val opts: Opts?,
        val profileId: String?,
    )

    private fun strings(arr: JSONArray?): Set<String> =
        if (arr == null) emptySet() else (0 until arr.length()).map { arr.getString(it) }.toSet()

    fun export(
        favorites: Set<String>,
        favArts: Set<String>,
        decks: List<Deck>,
        deckStore: DeckStore,
        customs: List<CustomToken>,
        customStore: CustomStore,
        opts: Opts,
        profileId: String,
    ): String {
        val o = JSONObject()
        o.put("backupVersion", VERSION)
        o.put("favorites", JSONArray(favorites.toList()))
        o.put("favArts", JSONArray(favArts.toList()))
        o.put("decks", JSONArray().also { a -> decks.forEach { a.put(deckStore.toJson(it)) } })
        o.put("custom", JSONArray().also { a ->
            for (c in customs) {
                val f = customStore.fileFor(c)
                if (!f.exists()) continue
                a.put(customStore.toJson(c).put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)))
            }
        })
        o.put("profile", profileId)
        o.put("settings", JSONObject().apply {
            put("caption", opts.caption)
            put("fullCard", opts.fullCard)
            put("contrast", opts.contrast.toDouble())
            put("invert", opts.invert)
            put("lsbFirst", opts.lsbFirst)
            put("density", opts.density)
            put("handshake", opts.handshake)
            put("capSize", opts.capSize)
            put("capFont", opts.capFont)
            put("capBold", opts.capBold)
            put("capPt", opts.capPt)
            put("capKeywords", opts.capKeywords)
            put("capRules", opts.capRules)
            put("trimSpace", opts.trimSpace)
            put("autoContrast", opts.autoContrast)
        })
        return o.toString()
    }

    /** Parses a backup. Custom token pictures are written into [customStore] as a side effect. */
    fun parse(text: String, deckStore: DeckStore, customStore: CustomStore, current: Opts): Restored {
        val o = JSONObject(text)
        if (!o.has("backupVersion")) throw IllegalArgumentException("That file isn't a Token Printer backup.")
        val decks = o.optJSONArray("decks")?.let { a -> (0 until a.length()).map { deckStore.fromJson(a.getJSONObject(it)) } }
            ?: emptyList()
        val customs = ArrayList<CustomToken>()
        o.optJSONArray("custom")?.let { a ->
            for (i in 0 until a.length()) {
                val item = a.getJSONObject(i)
                val c = customStore.fromJson(item)
                val data = item.optString("data")
                if (data.isEmpty()) continue
                customStore.writeBytes(c.id, c.ext, Base64.decode(data, Base64.NO_WRAP))
                customs.add(c)
            }
        }
        val s = o.optJSONObject("settings")
        val opts = s?.let {
            Opts(
                caption = it.optBoolean("caption", current.caption),
                fullCard = it.optBoolean("fullCard", current.fullCard),
                contrast = it.optDouble("contrast", current.contrast.toDouble()).toFloat(),
                invert = it.optBoolean("invert", current.invert),
                lsbFirst = it.optBoolean("lsbFirst", current.lsbFirst),
                density = it.optInt("density", current.density),
                handshake = it.optBoolean("handshake", current.handshake),
                capSize = it.optInt("capSize", current.capSize),
                capFont = it.optString("capFont", current.capFont),
                capBold = it.optBoolean("capBold", current.capBold),
                capPt = it.optBoolean("capPt", current.capPt),
                capKeywords = it.optBoolean("capKeywords", current.capKeywords),
                capRules = it.optBoolean("capRules", current.capRules),
                trimSpace = it.optBoolean("trimSpace", current.trimSpace),
                autoContrast = it.optBoolean("autoContrast", current.autoContrast),
            )
        }
        return Restored(
            favorites = strings(o.optJSONArray("favorites")),
            favArts = strings(o.optJSONArray("favArts")),
            decks = decks,
            customs = customs,
            opts = opts,
            profileId = o.optString("profile").ifEmpty { null },
        )
    }
}
