package com.example.tokenprinter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.File
import java.net.URI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

data class FontChoice(val label: String, val family: String)

/**
 * Caption fonts. A family is either the name of a font built into Android ("sans-serif", "serif", ...),
 * a bundled font file ("asset:paint" = assets/fonts/paint.ttf), or "scratch" (bold serif with a scratched effect).
 */
object Fonts {
    val all = listOf(
        FontChoice("Sans", "sans-serif"),
        FontChoice("Serif", "serif"),
        FontChoice("Mono", "monospace"),
        FontChoice("Condensed", "sans-serif-condensed"),
        FontChoice("Handwriting", "cursive"),
        FontChoice("Casual", "casual"),
        FontChoice("Notebook", "asset:notebook"),
        FontChoice("Paint", "asset:paint"),
        FontChoice("Spooky", "asset:spooky"),
        FontChoice("Scratched", "scratch"),
    )

    private val bundled = HashMap<String, Typeface>()

    /** Loads the bundled font files once, at app start. A font that fails to load is simply skipped. */
    fun init(ctx: android.content.Context) {
        all.filter { it.family.startsWith("asset:") }.forEach { f ->
            val name = f.family.removePrefix("asset:")
            try {
                bundled[f.family] = Typeface.createFromAsset(ctx.assets, "fonts/$name.ttf")
            } catch (e: Exception) {
                // falls back to the default font
            }
        }
    }

    fun typeface(family: String, bold: Boolean): Typeface = try {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        when {
            family == "scratch" -> Typeface.create("serif", Typeface.BOLD)
            family.startsWith("asset:") -> bundled[family]?.let { Typeface.create(it, style) } ?: Typeface.DEFAULT
            else -> Typeface.create(family, style)
        }
    } catch (e: Exception) {
        Typeface.DEFAULT
    }
}

data class CaptionStyle(val sizePx: Int, val family: String, val bold: Boolean, val lineSpacing: Float = 1f)

/** Turns an image into the 1-bit raster the printer expects (width and packet rows come from the [PrinterProfile]). */
object Raster {
    private const val PAD_X = 12
    private const val PAD_Y = 8
    private const val PAD_Y_TRIMMED = 3

    class Rendered(val bits: ByteArray, val preview: Bitmap)

    /** Scryfall images come over HTTP; custom tokens are local file:// URLs. */
    fun fetchBitmap(url: String): Bitmap {
        val bytes = if (url.startsWith("file:")) File(URI(url)).readBytes() else Scryfall.httpGet(url)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Couldn't decode image")
    }

    fun render(
        src: Bitmap,
        caption: CharSequence?,
        style: CaptionStyle,
        contrast: Float,
        invert: Boolean,
        lsbFirst: Boolean,
        trimSpace: Boolean = false,
        profile: PrinterProfile = Profiles.LX_D02,
        autoContrast: Boolean = false,
        badge: String? = null,
        idMark: String? = null,
    ): Rendered {
        val feed = if (trimSpace) profile.feedRowsTrimmed else profile.feedRows
        val page = layoutPage(src, caption, style, profile.widthDots, profile.widthDots, trimSpace, feed, profile.rowsPerPacket, badge, idMark)
        return dither(
            page.bitmap, contrast, invert, lsbFirst,
            plainFrom = page.plainFrom,
            autoContrast = autoContrast,
            skip = page.marks,
        )
    }

    /** The art, badge and caption drawn in full color, before any dithering. */
    class Page(val bitmap: Bitmap, val plainFrom: Int, val marks: List<RectF>)

    /**
     * Lays the token out on a white page [width] pixels wide. Caption size and margins are given in printer dots
     * for a page [designWidth] dots wide and scaled up, so a larger export wraps its text the same way the print does.
     */
    fun layoutPage(
        src: Bitmap,
        caption: CharSequence?,
        style: CaptionStyle,
        width: Int,
        designWidth: Int,
        trimSpace: Boolean,
        feed: Int,
        rowsPerPacket: Int,
        badge: String?,
        idMark: String? = null,
    ): Page {
        val k = width.toFloat() / designWidth
        val padX = (PAD_X * k).roundToInt()
        val padY = ((if (trimSpace) PAD_Y_TRIMMED else PAD_Y) * k).roundToInt()
        val cap = caption?.takeIf { it.isNotBlank() }
        val imgH = (src.height * width.toFloat() / src.width).roundToInt().coerceAtLeast(1)

        val layout: StaticLayout? = if (cap != null) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Fonts.typeface(style.family, style.bold)
                textSize = style.sizePx * k
            }
            StaticLayout.Builder.obtain(cap, 0, cap.length, tp, width - 2 * padX)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(14)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else null

        // Line spacing is applied by moving each finished line (see lineShifts), not by Android's own multiplier,
        // which spaces lines unevenly when the text mixes sizes.
        val shifts = if (layout != null) lineShifts(layout, style.lineSpacing) else IntArray(0)
        val textH = if (layout != null) (layout.height + (shifts.lastOrNull() ?: 0)).coerceAtLeast(1) else 0
        val capH = if (layout != null) textH + 2 * padY else 0
        var total = imgH + capH + feed
        val rpp = rowsPerPacket.coerceAtLeast(1)
        if (total % rpp != 0) total += rpp - total % rpp

        val page = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, null, Rect(0, 0, width, imgH), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val marks = ArrayList<RectF>()
        if (!badge.isNullOrEmpty()) marks.add(drawBadge(canvas, badge, width))
        if (!idMark.isNullOrEmpty()) marks.add(drawIdMark(canvas, idMark, width, imgH))
        if (layout != null) {
            if (style.family == "scratch") {
                val capBmp = Bitmap.createBitmap(width, textH, Bitmap.Config.ARGB_8888)
                val cc = Canvas(capBmp)
                cc.drawColor(Color.WHITE)
                drawCaption(cc, layout, shifts, padX.toFloat(), 0f, width)
                canvas.drawBitmap(roughen(capBmp, k), 0f, (imgH + padY).toFloat(), null)
            } else {
                drawCaption(canvas, layout, shifts, padX.toFloat(), (imgH + padY).toFloat(), width)
            }
        }
        return Page(page, if (layout != null) imgH else total, marks)
    }

    /** The page as grey tones with the user's contrast settings applied, for printers that do their own dithering. */
    fun grayscale(page: Page, contrast: Float, autoContrast: Boolean): Bitmap {
        val w = page.bitmap.width
        val h = page.bitmap.height
        val g = tones(page.bitmap, contrast, autoContrast, page.plainFrom, page.marks)
        val px = IntArray(w * h) { i -> val v = g[i].roundToInt().coerceIn(0, 255); Color.rgb(v, v, v) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** The page exactly as this printer would print it (black and white dots), with no invert. */
    fun blackWhite(page: Page, contrast: Float, autoContrast: Boolean): Bitmap =
        dither(page.bitmap, contrast, false, false, page.plainFrom, autoContrast, page.marks).preview

    /**
     * How far each line moves (in pixels, cumulative) to give the caption [spacing] times its normal line gaps.
     * Every line's distance from the one above is its own normal distance times [spacing], so mixed text sizes
     * stay even, and 1.0 changes nothing. Below 1.0 the lines overlap a little, which is what tight text wants.
     */
    private fun lineShifts(layout: StaticLayout, spacing: Float): IntArray {
        val n = layout.lineCount
        val out = IntArray(n)
        if (spacing == 1f) return out
        var total = 0f
        for (i in 1 until n) {
            total += (layout.getLineBaseline(i) - layout.getLineBaseline(i - 1)) * (spacing - 1f)
            out[i] = total.roundToInt()
        }
        return out
    }

    /** Draws the caption at ([x], [y]); with shifts, one clipped line at a time so each can sit where it belongs. */
    private fun drawCaption(canvas: Canvas, layout: StaticLayout, shifts: IntArray, x: Float, y: Float, width: Int) {
        if (shifts.all { it == 0 }) {
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
            return
        }
        for (i in 0 until layout.lineCount) {
            val dy = y + shifts[i]
            canvas.save()
            canvas.clipRect(0f, dy + layout.getLineTop(i), width.toFloat(), dy + layout.getLineBottom(i))
            canvas.translate(x, dy)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun hash(x: Int, y: Int, s: Int): Int {
        var h = x * 374761393 + y * 668265263 + s * 1274126177.toInt()
        h = (h xor (h ushr 13)) * 1274126177.toInt()
        return h xor (h ushr 16)
    }

    /** Gives text a jagged, scratched look: ragged edges from a blocky displacement, plus a few white scratches. */
    private fun roughen(src: Bitmap, k: Float = 1f): Bitmap {
        val w = src.width
        val h = src.height
        val inPx = IntArray(w * h)
        src.getPixels(inPx, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val cx = (x / (3 * k)).toInt()
            val cy = (y / (3 * k)).toInt()
            val dx = ((Math.floorMod(hash(cx, cy, 1), 5) - 2) * k).roundToInt()
            val dy = ((Math.floorMod(hash(cx, cy, 2), 5) - 2) * k).roundToInt()
            out[y * w + x] = inPx[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
        }
        // createBitmap(pixels, ...) gives a read-only bitmap, so make an editable one and copy the pixels in.
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        val c = Canvas(bmp)
        val rnd = java.util.Random(7)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f * k; strokeCap = Paint.Cap.ROUND }
        repeat((w * h / (900 * k * k)).toInt().coerceAtLeast(6)) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val len = (8f + rnd.nextFloat() * 26f) * k
            val ang = (-0.9 + rnd.nextFloat() * 1.8).toFloat() // mostly diagonal strokes
            c.drawLine(x, y, x + len * kotlin.math.cos(ang), y + len * kotlin.math.sin(ang), paint)
        }
        return bmp
    }

    /** A white label with a heavy outline in the top-right corner, e.g. "25x" for Goblin Mode. */
    private fun drawBadge(canvas: Canvas, text: String, width: Int): RectF {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = width * 0.15f
        }
        val margin = width * 0.03f
        val padding = width * 0.025f
        val tw = tp.measureText(text)
        val fm = tp.fontMetrics
        val th = fm.descent - fm.ascent
        val box = RectF(
            width - margin - tw - 2 * padding,
            margin,
            width - margin,
            margin + th + 2 * padding,
        )
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = width * 0.012f
        }
        val r = width * 0.03f
        canvas.drawRoundRect(box, r, r, fill)
        canvas.drawRoundRect(box, r, r, line)
        canvas.drawText(text, box.left + padding, box.top + padding - fm.ascent, tp)
        return box
    }

    /**
     * The token's color letters (e.g. "B/R/G") in white with a heavy black outline, in the bottom left corner
     * of the picture: the opposite corner to the Goblin Mode label.
     */
    private fun drawIdMark(canvas: Canvas, text: String, width: Int, imgH: Int): RectF {
        val size = width * 0.09f
        val outline = width * 0.009f
        val margin = width * 0.035f
        val face = Typeface.create("sans-serif", Typeface.BOLD)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = face
            textSize = size
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = face
            textSize = size
            style = Paint.Style.STROKE
            strokeWidth = outline * 2
            strokeJoin = Paint.Join.ROUND
        }
        val fm = fill.fontMetrics
        val tw = fill.measureText(text)
        val x = margin + outline
        val baseline = imgH - margin - outline - fm.descent
        canvas.drawText(text, x, baseline, edge)
        canvas.drawText(text, x, baseline, fill)
        return RectF(x - outline, baseline + fm.ascent - outline, x + tw + outline, baseline + fm.descent + outline)
    }

    /**
     * Stretches the picture's tones to the full range and lifts dark images, so murky art keeps its detail.
     * [n] is the number of pixels (from the top) that belong to the picture rather than the caption.
     */
    private fun autoLevels(g: FloatArray, w: Int, n: Int, skip: List<RectF>) {
        if (n <= 0) return
        val hist = IntArray(256)
        var cnt = 0
        for (i in 0 until n) {
            // The Goblin Mode label is drawn over the art; it must not skew the tone measurement.
            val px = (i % w).toFloat()
            val py = (i / w).toFloat()
            if (skip.any { px in it.left..it.right && py in it.top..it.bottom }) continue
            hist[g[i].toInt().coerceIn(0, 255)]++
            cnt++
        }
        if (cnt <= 0) return
        fun percentile(p: Float): Int {
            val target = (cnt * p).toInt()
            var acc = 0
            for (v in 0..255) {
                acc += hist[v]
                if (acc > target) return v
            }
            return 255
        }
        val lo = percentile(0.01f).toFloat()
        val hi = percentile(0.99f).toFloat()
        if (hi - lo < 24f) return // flat image: stretching would only amplify noise
        val median = percentile(0.5f).toFloat()
        val medianS = ((median - lo) / (hi - lo)).coerceIn(0f, 1f)
        // Only brighten dark pictures; thermal paper already prints darker than it looks.
        val gamma = if (medianS in 0.03f..0.45f) (ln(0.5f) / ln(medianS)).coerceIn(0.4f, 1f) else 1f
        for (i in 0 until n) {
            val s = ((g[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
            g[i] = 255f * (if (gamma == 1f) s else s.pow(gamma))
        }
    }

    /** Grey level (0 black .. 255 white) of every pixel, after auto contrast and the contrast slider. */
    private fun tones(bmp: Bitmap, contrast: Float, autoContrast: Boolean, plainFrom: Int, skip: List<RectF>): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val g = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24) / 255f
            val lum = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
            g[i] = lum * a + 255f * (1f - a)
        }
        if (autoContrast) autoLevels(g, w, w * minOf(plainFrom, h), skip)
        for (i in g.indices) g[i] = ((g[i] - 128f) * contrast + 128f).coerceIn(0f, 255f)
        return g
    }

    /**
     * Floyd-Steinberg dithering for the picture, plain thresholding for the caption so text stays crisp.
     * A set bit means "burn this dot" unless [invert] is on.
     */
    private fun dither(
        bmp: Bitmap,
        contrast: Float,
        invert: Boolean,
        lsbFirst: Boolean,
        plainFrom: Int,
        autoContrast: Boolean,
        skip: List<RectF> = emptyList(),
    ): Rendered {
        val w = bmp.width
        val h = bmp.height
        val rowBytes = w / 8
        val g = tones(bmp, contrast, autoContrast, plainFrom, skip)

        val bits = ByteArray(h * rowBytes)
        val shown = IntArray(w * h)
        for (y in 0 until h) {
            val plain = y >= plainFrom
            for (x in 0 until w) {
                val i = y * w + x
                val old = g[i]
                val black = old < 128f
                if (!plain) {
                    val err = old - (if (black) 0f else 255f)
                    if (x + 1 < w) g[i + 1] += err * 7f / 16f
                    if (y + 1 < plainFrom) {
                        if (x > 0) g[i + w - 1] += err * 3f / 16f
                        g[i + w] += err * 5f / 16f
                        if (x + 1 < w) g[i + w + 1] += err * 1f / 16f
                    }
                }
                shown[i] = if (black) Color.BLACK else Color.WHITE
                if (black != invert) {
                    val idx = y * rowBytes + (x shr 3)
                    val mask = if (lsbFirst) 1 shl (x and 7) else 0x80 shr (x and 7)
                    bits[idx] = (bits[idx].toInt() or mask).toByte()
                }
            }
        }
        return Rendered(bits, Bitmap.createBitmap(shown, w, h, Bitmap.Config.ARGB_8888))
    }
}
