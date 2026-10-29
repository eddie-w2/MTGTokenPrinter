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

data class CaptionStyle(val sizePx: Int, val family: String, val bold: Boolean)

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
    ): Rendered {
        val width = profile.widthDots
        val padY = if (trimSpace) PAD_Y_TRIMMED else PAD_Y
        val feed = if (trimSpace) profile.feedRowsTrimmed else profile.feedRows
        val cap = caption?.takeIf { it.isNotBlank() }
        val imgH = (src.height * width.toFloat() / src.width).roundToInt().coerceAtLeast(1)

        val layout: StaticLayout? = if (cap != null) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Fonts.typeface(style.family, style.bold)
                textSize = style.sizePx.toFloat()
            }
            StaticLayout.Builder.obtain(cap, 0, cap.length, tp, width - 2 * PAD_X)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(14)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else null

        val capH = if (layout != null) layout.height + 2 * padY else 0
        var total = imgH + capH + feed
        val rpp = profile.rowsPerPacket.coerceAtLeast(1)
        if (total % rpp != 0) total += rpp - total % rpp

        val page = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, null, Rect(0, 0, width, imgH), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val badgeBox = if (!badge.isNullOrEmpty()) drawBadge(canvas, badge, width) else null
        if (layout != null) {
            if (style.family == "scratch") {
                val capBmp = Bitmap.createBitmap(width, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val cc = Canvas(capBmp)
                cc.drawColor(Color.WHITE)
                cc.translate(PAD_X.toFloat(), 0f)
                layout.draw(cc)
                canvas.drawBitmap(roughen(capBmp), 0f, (imgH + padY).toFloat(), null)
            } else {
                canvas.save()
                canvas.translate(PAD_X.toFloat(), (imgH + padY).toFloat())
                layout.draw(canvas)
                canvas.restore()
            }
        }
        return dither(
            page, contrast, invert, lsbFirst,
            plainFrom = if (layout != null) imgH else total,
            autoContrast = autoContrast,
            skip = badgeBox,
        )
    }

    private fun hash(x: Int, y: Int, s: Int): Int {
        var h = x * 374761393 + y * 668265263 + s * 1274126177.toInt()
        h = (h xor (h ushr 13)) * 1274126177.toInt()
        return h xor (h ushr 16)
    }

    /** Gives text a jagged, scratched look: ragged edges from a blocky displacement, plus a few white scratches. */
    private fun roughen(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val inPx = IntArray(w * h)
        src.getPixels(inPx, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = Math.floorMod(hash(x / 3, y / 3, 1), 5) - 2
            val dy = Math.floorMod(hash(x / 3, y / 3, 2), 5) - 2
            out[y * w + x] = inPx[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
        }
        // createBitmap(pixels, ...) gives a read-only bitmap, so make an editable one and copy the pixels in.
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        val c = Canvas(bmp)
        val rnd = java.util.Random(7)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND }
        repeat((w * h / 900).coerceAtLeast(6)) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val len = 8f + rnd.nextFloat() * 26f
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
     * Stretches the picture's tones to the full range and lifts dark images, so murky art keeps its detail.
     * [n] is the number of pixels (from the top) that belong to the picture rather than the caption.
     */
    private fun autoLevels(g: FloatArray, w: Int, n: Int, skip: RectF?) {
        if (n <= 0) return
        val hist = IntArray(256)
        var cnt = 0
        for (i in 0 until n) {
            // The Goblin Mode label is drawn over the art; it must not skew the tone measurement.
            if (skip != null && (i % w).toFloat() in skip.left..skip.right && (i / w).toFloat() in skip.top..skip.bottom) continue
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
        skip: RectF? = null,
    ): Rendered {
        val w = bmp.width
        val h = bmp.height
        val rowBytes = w / 8
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
