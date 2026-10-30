package com.example.tokenprinter

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class Opts(
    val caption: Boolean,
    val fullCard: Boolean,
    val contrast: Float,
    val invert: Boolean,
    val lsbFirst: Boolean,
    val density: Int,
    val handshake: Boolean,
    val capSize: Int,
    val capFont: String,
    val capBold: Boolean,
    val capPt: Boolean,
    val capKeywords: Boolean,
    val capRules: Boolean,
    val trimSpace: Boolean,
    val autoContrast: Boolean,
    /** Caption line height multiplier; 1.0 is the normal spacing. */
    val capLineSpacing: Float,
    /** Put the name, keywords/rules and power/toughness on one flowing line instead of separate lines. */
    val capSingleLine: Boolean,
    /** Write the token's colors (e.g. B/R/G) in the bottom left corner of the picture. */
    val colorId: Boolean,
)

enum class SortMode(val label: String) {
    NameAsc("Alphabetical (A–Z)"),
    NameDesc("Alphabetical (Z–A)"),
    ReleaseDesc("Release date (newest first)"),
    ReleaseAsc("Release date (oldest first)"),
}

data class SetInfo(val code: String, val name: String, val released: String)

data class Filters(
    val query: String = "",
    val colors: Set<Char> = emptySet(),
    val favOnly: Boolean = false,
    val customOnly: Boolean = false,
    val types: Set<String> = emptySet(),
    val setCode: String? = null,
    val sort: SortMode = SortMode.NameAsc,
)

/** The print dialog for one printing. */
data class PrintUi(
    val token: Token,
    val art: TokenArt,
    val preview: Bitmap? = null,
    val message: String = "",
    /** Goblin Mode: 0 = off, otherwise the 10 / 25 / 50 / 100 written on the card. */
    val goblin: Int = 0,
)

data class PrintJob(val token: Token, val art: TokenArt, val copies: Int, val badge: String? = null)

/** Opens the custom token maker. [linkKey] ties the new art to that token; [name] pre-fills the name. */
data class CustomEditorRequest(val linkKey: String? = null, val name: String = "")

/** Progress of a running (possibly multi-token) print, shown with the printing animation. */
data class PrintRun(
    val title: String,
    val step: Int,
    val steps: Int,
    val progress: Float = 0f,
    val preview: Bitmap? = null,
    val finished: Boolean = false,
    val error: String? = null,
)

data class ImportState(
    val busy: Boolean = false,
    val message: String = "",
    val error: String? = null,
    val doneDeckId: String? = null,
    val summary: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val cacheFile = File(app.filesDir, "tokens.json")
    private val deckStore = DeckStore(File(app.filesDir, "decks.json"))
    private val customStore = CustomStore(File(app.filesDir, "custom"), File(app.filesDir, "custom.json"))
    private val ctx: Application get() = getApplication()

    val printer = PrinterClient(app, viewModelScope)

    // ------------------------------------------------------------------ state

    /** The Scryfall tokens and emblems as downloaded. */
    private val scryTokens = MutableStateFlow<List<Token>>(emptyList())
    val customs = MutableStateFlow(customStore.load())

    /** Scryfall tokens plus the user's custom ones. */
    val tokens: StateFlow<List<Token>> = combine(scryTokens, customs) { s, c -> mergeCustom(s, c, customStore) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tokenMap: StateFlow<Map<String, Token>> = tokens
        .map { list -> list.associateBy { it.key } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val loading = MutableStateFlow(false)
    val progress = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val lastSync = MutableStateFlow(prefs.lastSync.takeIf { it > 0 } ?: cacheFile.lastModified())

    val filters = MutableStateFlow(Filters(sort = runCatching { SortMode.valueOf(prefs.sort) }.getOrDefault(SortMode.NameAsc)))
    val favorites = MutableStateFlow(prefs.favorites())
    val favArts = MutableStateFlow(prefs.favArts())
    val gridView = MutableStateFlow(prefs.gridView)
    val selected = MutableStateFlow<Token?>(null)

    val decks = MutableStateFlow(deckStore.load())
    val selectedDeckId = MutableStateFlow<String?>(null)
    val importState = MutableStateFlow(ImportState())

    val profile = MutableStateFlow(Profiles.byId(prefs.profileId))
    val editor = MutableStateFlow<CustomEditorRequest?>(null)
    val notice = MutableStateFlow<String?>(null)
    val pendingShare = MutableStateFlow<String?>(null)

    val printUi = MutableStateFlow<PrintUi?>(null)
    val copies = MutableStateFlow(1)
    val printRun = MutableStateFlow<PrintRun?>(null)

    val opts = MutableStateFlow(
        Opts(
            caption = prefs.caption,
            fullCard = prefs.fullCard,
            contrast = prefs.contrast,
            invert = prefs.invert,
            lsbFirst = prefs.lsbFirst,
            density = prefs.densityFor(prefs.profileId),
            handshake = prefs.handshake,
            capSize = prefs.capSize,
            capFont = prefs.capFont,
            capBold = prefs.capBold,
            capPt = prefs.capPt,
            capKeywords = prefs.capKeywords,
            capRules = prefs.capRules,
            trimSpace = prefs.trimSpace,
            autoContrast = prefs.autoContrast,
            capLineSpacing = prefs.capLineSpacing,
            capSingleLine = prefs.capSingleLine,
            colorId = prefs.colorId,
        )
    )

    /** Every set that has a token or emblem, newest release first. */
    val sets: StateFlow<List<SetInfo>> = tokens.map { list ->
        val m = HashMap<String, SetInfo>()
        for (t in list) for (a in t.arts) {
            if (a.setCode.isEmpty()) continue
            val cur = m[a.setCode]
            if (cur == null || (a.released.isNotEmpty() && a.released < cur.released)) {
                m[a.setCode] = SetInfo(a.setCode, a.setName, a.released)
            }
        }
        m.values.sortedWith(compareByDescending<SetInfo> { it.released }.thenBy { it.name })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Everything that matches the filters, in the chosen order. The UI pins favorites to the top. */
    val visible: StateFlow<List<Token>> = combine(tokens, filters, favorites, favArts) { all, f, favs, fa ->
        val s = f.query.trim().lowercase()
        val filtered = all.filter { t ->
            val textOk = s.isEmpty() ||
                t.name.lowercase().contains(s) ||
                t.typeLine.lowercase().contains(s) ||
                t.keywords.any { it.lowercase().contains(s) } ||
                t.arts.any { it.setName.lowercase().contains(s) }
            val colorOk = f.colors.isEmpty() ||
                f.colors.any { c -> if (c == 'C') t.colors.isEmpty() else t.colors.contains(c) }
            val favOk = !f.favOnly || t.key in favs || t.arts.any { it.id in fa }
            val typeOk = f.types.isEmpty() || t.cats.any { it in f.types }
            val setOk = f.setCode == null || t.arts.any { it.setCode == f.setCode }
            val customOk = !f.customOnly || t.arts.any { it.setCode == "custom" }
            textOk && colorOk && favOk && typeOk && setOk && customOk
        }
        val byName = compareBy<Token>({ it.name.lowercase() }, { it.pt }, { it.colors })
        when (f.sort) {
            SortMode.NameAsc -> filtered.sortedWith(byName)
            SortMode.NameDesc -> filtered.sortedWith(byName.reversed())
            SortMode.ReleaseDesc -> filtered.sortedWith(compareByDescending<Token> { it.newest }.then(byName))
            SortMode.ReleaseAsc -> filtered.sortedWith(compareBy<Token> { it.oldest }.then(byName))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        load(false)
    }

    // ------------------------------------------------------------ token sync

    fun load(force: Boolean) {
        if (loading.value) return
        viewModelScope.launch {
            loading.value = true
            error.value = null
            progress.value = ""
            try {
                val cached = if (force) null else Scryfall.loadCache(cacheFile)
                if (cached != null) {
                    scryTokens.value = cached
                } else {
                    scryTokens.value = Scryfall.fetchAll(cacheFile) { n, total ->
                        progress.value = "Downloading tokens… $n / $total"
                    }
                    val now = System.currentTimeMillis()
                    prefs.lastSync = now
                    lastSync.value = now
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: e.toString()
            }
            loading.value = false
        }
    }

    // --------------------------------------------------------- list controls

    fun setQuery(q: String) = filters.update { it.copy(query = q) }
    fun toggleFavOnly() = filters.update { it.copy(favOnly = !it.favOnly) }
    fun toggleCustomOnly() = filters.update { it.copy(customOnly = !it.customOnly) }
    fun setSort(m: SortMode) {
        filters.update { it.copy(sort = m) }
        prefs.sort = m.name
    }
    fun toggleType(t: String) = filters.update {
        it.copy(types = if (t in it.types) it.types - t else it.types + t)
    }
    fun setSet(code: String?) = filters.update { it.copy(setCode = code) }
    fun clearFilters() = filters.update { it.copy(types = emptySet(), setCode = null, colors = emptySet(), favOnly = false, customOnly = false) }
    fun toggleColor(c: Char) = filters.update {
        it.copy(colors = if (c in it.colors) it.colors - c else it.colors + c)
    }

    fun setGridView(on: Boolean) {
        gridView.value = on
        prefs.gridView = on
    }

    fun toggleFavorite(key: String) {
        val next = favorites.value.toMutableSet().also { if (!it.add(key)) it.remove(key) }
        favorites.value = next
        prefs.saveFavorites(next)
    }

    fun toggleFavArt(artId: String) {
        val next = favArts.value.toMutableSet().also { if (!it.add(artId)) it.remove(artId) }
        favArts.value = next
        prefs.saveFavArts(next)
    }

    // ---------------------------------------------------------------- decks

    private fun persistDecks() = deckStore.save(decks.value)

    private fun updateDeck(id: String, f: (Deck) -> Deck) {
        decks.update { list -> list.map { if (it.id == id) f(it) else it } }
        persistDecks()
    }

    fun newDeck(name: String): Deck {
        val deck = Deck(UUID.randomUUID().toString(), name.trim().ifEmpty { "New deck" }, System.currentTimeMillis(), "Manual", emptyList())
        decks.value = listOf(deck) + decks.value
        persistDecks()
        return deck
    }

    fun deleteDeck(id: String) {
        decks.value = decks.value.filter { it.id != id }
        if (selectedDeckId.value == id) selectedDeckId.value = null
        persistDecks()
    }

    fun renameDeck(id: String, name: String) {
        if (name.isBlank()) return
        updateDeck(id) { it.copy(name = name.trim()) }
    }

    fun setEntryQty(deckId: String, key: String, qty: Int) =
        updateDeck(deckId) { d -> d.copy(entries = d.entries.map { if (it.tokenKey == key) it.copy(qty = qty.coerceIn(1, 99)) else it }) }

    fun setEntryArt(deckId: String, key: String, artId: String) =
        updateDeck(deckId) { d -> d.copy(entries = d.entries.map { if (it.tokenKey == key) it.copy(artId = artId) else it }) }

    fun removeEntry(deckId: String, key: String) =
        updateDeck(deckId) { d -> d.copy(entries = d.entries.filter { it.tokenKey != key }) }

    fun addToDeck(deckId: String, token: Token, art: TokenArt) = updateDeck(deckId) { d ->
        val existing = d.entries.firstOrNull { it.tokenKey == token.key }
        if (existing == null) {
            d.copy(entries = d.entries + DeckEntry(token.key, art.id, 1, emptyList()))
        } else {
            d.copy(entries = d.entries.map { if (it.tokenKey == token.key) it.copy(qty = it.qty + 1, artId = art.id) else it })
        }
    }

    fun clearImportState() {
        importState.value = ImportState()
    }

    /** [text] is a pasted/opened deck list or a Moxfield link. */
    fun importDeck(nameInput: String, text: String) {
        if (importState.value.busy) return
        if (tokens.value.isEmpty()) {
            importState.value = ImportState(error = "The token list isn't loaded yet. Sync with Scryfall in Settings first.")
            return
        }
        importState.value = ImportState(busy = true, message = "Reading deck list…")
        viewModelScope.launch {
            try {
                val trimmed = text.trim()
                var deckName = nameInput.trim()
                val names: List<String>
                val origin: String
                if (trimmed.contains("moxfield.com/decks/", ignoreCase = true)) {
                    importState.update { it.copy(message = "Contacting Moxfield…") }
                    val p = DeckImport.fromMoxfield(trimmed)
                    names = p.cards
                    if (deckName.isEmpty()) deckName = p.name ?: ""
                    origin = "Moxfield"
                } else {
                    names = DeckImport.parseList(trimmed)
                    origin = "Deck list"
                }
                if (names.isEmpty()) throw IllegalStateException("No cards found in that text.")

                val res = DeckImport.resolve(names, tokens.value, favArts.value) { msg ->
                    importState.update { it.copy(message = msg) }
                }
                if (res.entries.isEmpty()) {
                    throw IllegalStateException("None of the ${names.size} cards make tokens that are in your token list.")
                }
                val deck = Deck(
                    id = UUID.randomUUID().toString(),
                    name = deckName.ifEmpty { "Imported deck" },
                    created = System.currentTimeMillis(),
                    origin = origin,
                    entries = res.entries,
                )
                decks.value = listOf(deck) + decks.value
                persistDecks()
                val notes = buildList {
                    add("${res.entries.size} tokens for ${res.cardCount} cards.")
                    if (res.unmatchedCards.isNotEmpty()) add("${res.unmatchedCards.size} card names weren't recognised.")
                    if (res.missingTokens.isNotEmpty()) add("${res.missingTokens.size} tokens aren't in your list yet; try Sync now.")
                }
                importState.value = ImportState(doneDeckId = deck.id, summary = notes.joinToString(" "))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                importState.value = ImportState(error = e.message ?: e.toString())
            }
        }
    }

    // -------------------------------------------------------------- printer

    fun scan() = printer.scan()
    fun disconnect() = printer.disconnect()

    fun connect(f: FoundPrinter) {
        viewModelScope.launch {
            try {
                printer.connect(f.address)
                prefs.lastPrinter = f.address
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                printer.status.value = "Connect failed: ${e.message}"
            }
        }
    }

    private suspend fun ensureConnected() {
        if (printer.link.value == Link.Connected) return
        val addr = prefs.lastPrinter
            ?: throw IllegalStateException("No printer chosen yet. Tap the Printer button and pick one.")
        printer.connect(addr)
    }

    private fun captionStyle(o: Opts) = CaptionStyle(o.capSize, o.capFont, o.capBold, o.capLineSpacing)

    private fun renderImage(bmp: Bitmap, caption: CharSequence?, o: Opts, badge: String? = null, idMark: String? = null) =
        Raster.render(bmp, caption, captionStyle(o), o.contrast, o.invert, o.lsbFirst, o.trimSpace, profile.value, o.autoContrast, badge, idMark)

    /** The color letters for the corner of the picture, when that option is on. */
    private fun markFor(token: Token, o: Opts): String? = if (o.colorId) token.colorMark else null

    fun setProfile(p: PrinterProfile) {
        profile.value = p
        prefs.profileId = p.id
        opts.update { it.copy(density = prefs.densityFor(p.id)) }
        render()
    }

    /**
     * Name, then (for custom art) the user's own text, then rules text or keyword abilities in smaller type,
     * then power/toughness. Emblems always show their rules; other tokens only when "full rules text" is on.
     */
    private fun captionFor(token: Token, art: TokenArt, o: Opts): CharSequence? {
        if (!o.caption) return null
        val isEmblem = token.typeLine.contains("Emblem", ignoreCase = true)
        val rules = if ((isEmblem || o.capRules) && token.oracle.isNotBlank()) token.oracle.trim() else ""
        val kw = if (o.capKeywords && rules.isEmpty()) token.keywordText else ""
        val pt = if (o.capPt) token.pt else ""
        val sb = SpannableStringBuilder(token.name)
        val single = o.capSingleLine
        fun small(text: String, scale: Float) {
            sb.append(if (single) "  " else "\n")
            val start = sb.length
            sb.append(if (single) text.replace(Regex("\\s*\\n\\s*"), " ") else text)
            sb.setSpan(RelativeSizeSpan(scale), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (art.note.isNotBlank()) small(art.note.trim(), 0.8f)
        if (rules.isNotEmpty()) small(rules, 0.62f)
        else if (kw.isNotEmpty()) small(kw, 0.72f)
        if (pt.isNotEmpty()) {
            if (!single && sb.contains('\n')) sb.append("\n").append(pt) else sb.append("  ").append(pt)
        }
        return sb
    }

    private var testRunJob: Job? = null

    /** Prints a pattern that makes polarity / bit-order / margin problems obvious. */
    fun printTest() {
        if (printRun.value?.finished == false) return
        testRunJob?.cancel()
        testRunJob = viewModelScope.launch {
            try {
                printRun.value = PrintRun("Test page", 1, 1)
                ensureConnected()
                val o = opts.value
                val w = profile.value.widthDots
                val step = w / 8f
                val r = withContext(Dispatchers.Default) {
                    val bmp = Bitmap.createBitmap(w, 220, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    c.drawColor(Color.WHITE)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
                    c.drawRect(0f, 0f, 56f, 56f, p) // solid block, LEFT edge
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 4f
                    c.drawRect(w - 56f, 2f, w - 2f, 56f, p) // outline, RIGHT edge
                    p.style = Paint.Style.FILL
                    p.textSize = 44f
                    c.drawText("TEST  →", 80f, 46f, p)
                    for (i in 0 until 8) { // grey ramp, left to right
                        p.color = Color.rgb(i * 36, i * 36, i * 36)
                        c.drawRect(i * step, 80f, i * step + step, 150f, p)
                    }
                    p.color = Color.BLACK
                    p.textSize = 30f
                    c.drawText("Token Printer", 8f, 195f, p)
                    Raster.render(bmp, null, captionStyle(o), 1.0f, o.invert, o.lsbFirst, o.trimSpace, profile.value)
                }
                printRun.update { it?.copy(preview = r.preview) }
                printer.print(r.bits, o.density, o.handshake) { s, t ->
                    printRun.update { it?.copy(progress = s.toFloat() / t) }
                }
                printRun.update { it?.copy(progress = 1f, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                printRun.update { (it ?: PrintRun("Test page", 1, 1)).copy(finished = true, error = e.message ?: e.toString()) }
            }
        }
    }

    // --------------------------------------------------------- print dialog

    private var src: Pair<String, Bitmap>? = null
    private var rendered: Raster.Rendered? = null
    private var renderJob: Job? = null

    fun openPrint(token: Token, art: TokenArt) {
        copies.value = 1
        printUi.value = PrintUi(token, art)
        render()
    }

    /** Goblin Mode: cycles Off, 10x, 25x, 50x, 100x, Off... and writes the quantity on the card. */
    fun cycleGoblin() {
        val cur = printUi.value ?: return
        val next = when (cur.goblin) {
            0 -> 10
            10 -> 25
            25 -> 50
            50 -> 100
            else -> 0
        }
        printUi.value = cur.copy(goblin = next)
        render()
    }

    /** Goblin Mode quantity: 0 turns it off. */
    fun setGoblin(n: Int) {
        val cur = printUi.value ?: return
        if (cur.goblin == n) return
        printUi.value = cur.copy(goblin = n)
        render()
    }

    fun closePrint() {
        renderJob?.cancel()
        printUi.value = null
    }

    fun setOpts(o: Opts) {
        opts.value = o
        prefs.caption = o.caption
        prefs.fullCard = o.fullCard
        prefs.contrast = o.contrast
        prefs.invert = o.invert
        prefs.lsbFirst = o.lsbFirst
        prefs.setDensityFor(profile.value.id, o.density)
        prefs.handshake = o.handshake
        prefs.capSize = o.capSize
        prefs.capFont = o.capFont
        prefs.capBold = o.capBold
        prefs.capPt = o.capPt
        prefs.capKeywords = o.capKeywords
        prefs.capRules = o.capRules
        prefs.trimSpace = o.trimSpace
        prefs.autoContrast = o.autoContrast
        prefs.capLineSpacing = o.capLineSpacing
        prefs.capSingleLine = o.capSingleLine
        prefs.colorId = o.colorId
        render()
    }

    /** The art (or whole card) for the open print dialog, downloaded once and kept while the dialog is open. */
    private suspend fun bitmapFor(art: TokenArt, o: Opts): Bitmap {
        val url = if (o.fullCard) art.cardUrl else art.artUrl
        src?.takeIf { it.first == url }?.let { return it.second }
        return withContext(Dispatchers.IO) { Raster.fetchBitmap(url) }.also { src = url to it }
    }

    private suspend fun sourceFor(cur: PrintUi, o: Opts): Bitmap = bitmapFor(cur.art, o)

    private fun render() {
        val cur = printUi.value ?: return
        val o = opts.value
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            delay(60) // debounce slider drags
            try {
                val bmp = sourceFor(cur, o)
                val r = renderImage(bmp, captionFor(cur.token, cur.art, o), o, if (cur.goblin > 0) "${cur.goblin}x" else null, markFor(cur.token, o))
                rendered = r
                printUi.update { it?.copy(preview = r.preview, message = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                printUi.update { it?.copy(message = "Preview error: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    // ---------------------------------------------------------- image export

    val exportBusy = MutableStateFlow(false)

    /** Draws a token (same caption, font, badge and contrast as the print) and writes it as a JPEG in the cache. */
    private suspend fun exportFile(token: Token, art: TokenArt, goblin: Int, style: ExportStyle): File =
        withContext(Dispatchers.Default) {
            val o = opts.value
            val bmp = bitmapFor(art, o)
            val out = TokenExport.draw(
                src = bmp,
                caption = captionFor(token, art, o),
                captionStyle = captionStyle(o),
                badge = if (goblin > 0) "${goblin}x" else null,
                idMark = markFor(token, o),
                o = o,
                profile = profile.value,
                style = style,
            )
            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // only the latest export is kept
            val f = File(dir, TokenExport.fileName(token.name, style))
            f.outputStream().use { TokenExport.writeJpeg(out, it) }
            f
        }

    /** The token open in the print screen. */
    private suspend fun exportCurrent(style: ExportStyle): File {
        val cur = printUi.value ?: throw IllegalStateException("Nothing to export")
        return exportFile(cur.token, cur.art, cur.goblin, style)
    }

    private fun runExport(block: suspend () -> Unit) {
        if (exportBusy.value) return
        viewModelScope.launch {
            exportBusy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = "Export failed: ${e.message ?: e.javaClass.simpleName}"
            }
            exportBusy.value = false
        }
    }

    /** Saves to the phone's Pictures/Token Printer folder (Android 10 and newer). */
    fun saveExport(style: ExportStyle) = runExport {
        val f = exportCurrent(style)
        withContext(Dispatchers.IO) { TokenExport.saveToPictures(ctx, f) }
        notice.value = "Saved to Pictures/Token Printer"
    }

    /** Writes the export to a file the user picked (used on Android 8 and 9, which have no shared Pictures API). */
    fun writeExport(style: ExportStyle, uri: Uri) = runExport {
        val f = exportCurrent(style)
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Couldn't open the file")
        }
        notice.value = "Image saved"
    }

    /** Builds the export and hands back a shareable link to it, for sending straight to another printer's app. */
    fun shareExport(style: ExportStyle, onReady: (Uri) -> Unit) = runExport {
        val f = exportCurrent(style)
        onReady(TokenExport.shareUri(ctx, f))
    }

    fun lastExportStyle(): ExportStyle =
        runCatching { ExportStyle.valueOf(prefs.exportStyle) }.getOrDefault(ExportStyle.Gray)

    /** Remembers what the Export dialog was last used for, so the long-press quick export can repeat it. */
    fun rememberExport(style: ExportStyle, share: Boolean) {
        prefs.exportStyle = style.name
        prefs.exportShare = share
    }

    /**
     * Long-press export: the last style and action used in the Export dialog (save to Pictures to begin with).
     * Android 8 and 9 can't save without asking where, so they open the share sheet instead.
     */
    fun quickExport(token: Token, art: TokenArt, onShare: (Uri) -> Unit) = runExport {
        val style = lastExportStyle()
        val f = exportFile(token, art, 0, style)
        if (prefs.exportShare || !TokenExport.canSaveToPictures) {
            onShare(TokenExport.shareUri(ctx, f))
        } else {
            withContext(Dispatchers.IO) { TokenExport.saveToPictures(ctx, f) }
            notice.value = "Saved to Pictures/Token Printer"
        }
    }

    fun exportName(style: ExportStyle): String = TokenExport.fileName(printUi.value?.token?.name ?: "token", style)

    // ------------------------------------------------------------ printing

    private var runJob: Job? = null

    fun printCurrent() {
        val ui = printUi.value ?: return
        printJobs(listOf(PrintJob(ui.token, ui.art, copies.value, if (ui.goblin > 0) "${ui.goblin}x" else null)))
    }

    /** Long-press print: one print with the current settings ([qty] copies, which is the deck's count in a deck). */
    fun quickPrint(token: Token, art: TokenArt, qty: Int) {
        if (printRun.value?.finished == false) {
            notice.value = "Wait for the current print to finish"
            return
        }
        printJobs(listOf(PrintJob(token, art, qty.coerceIn(1, 20))))
    }

    fun printDeck(deckId: String) {
        val deck = decks.value.firstOrNull { it.id == deckId } ?: return
        val map = tokenMap.value
        val fa = favArts.value
        val jobs = deck.entries.mapNotNull { e ->
            val token = map[e.tokenKey] ?: return@mapNotNull null
            val art = token.arts.firstOrNull { it.id == e.artId } ?: token.thumbArt(fa)
            PrintJob(token, art, e.qty)
        }
        if (jobs.isNotEmpty()) printJobs(jobs)
    }

    fun printJobs(jobs: List<PrintJob>) {
        if (printRun.value?.finished == false) return
        val o = opts.value
        val steps = jobs.sumOf { it.copies }
        runJob?.cancel()
        runJob = viewModelScope.launch {
            var step = 0
            var title = jobs.first().token.name
            try {
                printRun.value = PrintRun(title, 1, steps)
                ensureConnected()
                for (job in jobs) {
                    title = job.token.name
                    val url = if (o.fullCard) job.art.cardUrl else job.art.artUrl
                    val bmp = withContext(Dispatchers.IO) { Raster.fetchBitmap(url) }
                    val r = withContext(Dispatchers.Default) {
                        renderImage(bmp, captionFor(job.token, job.art, o), o, job.badge, markFor(job.token, o))
                    }
                    repeat(job.copies) {
                        step++
                        printRun.value = PrintRun(title, step, steps, 0f, r.preview)
                        printer.print(r.bits, o.density, o.handshake) { sent, total ->
                            printRun.update { it?.copy(progress = sent.toFloat() / total) }
                        }
                        printRun.update { it?.copy(progress = 1f) }
                        delay(700)
                    }
                }
                printRun.update { it?.copy(finished = true, progress = 1f) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                printRun.update {
                    (it ?: PrintRun(title, maxOf(step, 1), steps)).copy(finished = true, error = e.message ?: e.toString())
                }
            }
        }
    }

    fun cancelRun() {
        runJob?.cancel()
        testRunJob?.cancel()
        printRun.value = null
    }

    fun dismissRun() {
        printRun.value = null
    }
    // ---------------------------------------------------------- custom tokens

    fun openEditor(linkKey: String? = null, name: String = "") {
        editor.value = CustomEditorRequest(linkKey, name)
    }

    fun closeEditor() {
        editor.value = null
    }

    /**
     * Saves a drawing or photo. With [linkedKey] it becomes another art for that token. Without one, a custom token
     * whose name matches exactly one Scryfall token is added to that token instead; anything else goes to Custom.
     */
    fun saveCustom(name: String, linkedKey: String?, bmp: Bitmap, photo: Boolean, text: String = "") {
        val clean = name.trim()
        if (clean.isEmpty()) {
            notice.value = "Give the token a name first."
            return
        }
        viewModelScope.launch {
            try {
                val link = linkedKey ?: scryTokens.value.filter { it.name.equals(clean, ignoreCase = true) }
                    .map { it.key }.distinct().singleOrNull()
                val id = UUID.randomUUID().toString()
                val ext = withContext(Dispatchers.IO) { customStore.writeBitmap(id, bmp, photo) }
                val updated = customs.value + CustomToken(id, clean, link, System.currentTimeMillis(), ext, text.trim())
                customs.value = updated
                withContext(Dispatchers.IO) { customStore.save(updated) }
                editor.value = null
                notice.value = if (link != null) "Added as another art for $clean." else "Saved to Custom."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = "Couldn't save: ${e.message ?: e.toString()}"
            }
        }
    }

    /** Deletes a custom picture (a printing whose id starts with "custom:"). */
    fun deleteCustomArt(artId: String) {
        val id = artId.removePrefix("custom:")
        val target = customs.value.firstOrNull { it.id == id } ?: return
        customStore.delete(target)
        val updated = customs.value.filter { it.id != id }
        customs.value = updated
        customStore.save(updated)
        if (artId in favArts.value) {
            val next = favArts.value - artId
            favArts.value = next
            prefs.saveFavArts(next)
        }
    }

    // ------------------------------------------------------- backup and share

    fun clearNotice() {
        notice.value = null
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = Backup.export(
                        favorites.value, favArts.value, decks.value, deckStore,
                        customs.value, customStore, opts.value, profile.value.id,
                    )
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: throw IllegalStateException("Couldn't open the file for writing")
                }
                notice.value = "Backup saved."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = "Backup failed: ${e.message ?: e.toString()}"
            }
        }
    }

    /** Merges a backup into what's on the phone: nothing is deleted, and decks/customs already here are kept as they are. */
    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    val text = ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                        ?: throw IllegalStateException("Couldn't open the file")
                    Backup.parse(text, deckStore, customStore, opts.value)
                }
                val favs = favorites.value + r.favorites
                favorites.value = favs
                prefs.saveFavorites(favs)
                val arts = favArts.value + r.favArts
                favArts.value = arts
                prefs.saveFavArts(arts)

                val haveDecks = decks.value.map { it.id }.toSet()
                val newDecks = r.decks.filter { it.id !in haveDecks }
                decks.value = decks.value + newDecks
                persistDecks()

                val haveCustoms = customs.value.map { it.id }.toSet()
                val newCustoms = r.customs.filter { it.id !in haveCustoms }
                customs.value = customs.value + newCustoms
                customStore.save(customs.value)

                r.profileId?.let { setProfile(Profiles.byId(it)) }
                r.opts?.let { setOpts(it) }
                notice.value = "Backup restored: ${newDecks.size} decks, ${newCustoms.size} custom tokens added."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = "Couldn't restore: ${e.message ?: e.toString()}"
            }
        }
    }

    /** Something was shared to the app: a deck list, a Moxfield link, or a text file. */
    fun receiveShared(text: String?, uri: Uri?) {
        viewModelScope.launch {
            val body = if (!text.isNullOrBlank()) {
                text
            } else if (uri != null) {
                try {
                    withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                    }
                } catch (e: Exception) {
                    null
                }
            } else null
            if (body.isNullOrBlank()) notice.value = "Nothing readable was shared."
            else pendingShare.value = body
        }
    }
}
