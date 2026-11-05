// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 eddie-w2 and Token Printer contributors

package com.example.tokenprinter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Sharing the project: the link for other apps, or a printed QR code sticker. */
object ShareApp {
    const val TITLE = "MTG Token Printer"
    /** The releases page, where people download the app. */
    const val url = "https://github.com/eddie-w2/MTGTokenPrinter-releases"

    /** Copies the link and opens Android's share sheet. */
    fun shareLink(ctx: Context) {
        val text = "$TITLE: print Magic: The Gathering tokens on cheap Bluetooth sticker printers. Free and open source.\n$url"
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(TITLE, url))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, TITLE)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(send, "Share Token Printer").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * The QR sticker, styled like a Magic card so it fits in at a game store: a framed card with a black title bar
     * ("MTG Token Printer" with a {0} mana cost, because it's free), the QR code as the art, a type line, rules and
     * flavor text, the link spelled out, and a power/toughness box. Pure black and white for thermal paper.
     */
    fun qrPage(width: Int): Bitmap {
        val w = width.toFloat()
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE }
        val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        val serifItalic = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        fun text(size: Float, face: Typeface, c: Int = Color.BLACK, align: Paint.Align = Paint.Align.LEFT) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = c; typeface = face; textSize = size; textAlign = align }
        fun fit(p: Paint, s: String, maxW: Float) { while (p.measureText(s) > maxW && p.textSize > 6f) p.textSize *= 0.96f }

        val outer = w * 0.025f          // white margin round the card
        val frame = w * 0.035f          // thick black card border
        val inL = outer + frame
        val inR = w - outer - frame
        val inW = inR - inL
        val gap = w * 0.025f
        val barH = w * 0.11f
        val radius = w * 0.035f

        // QR code (the "art").
        val matrix = QRCodeWriter().encode(
            url, BarcodeFormat.QR_CODE, 1, 1,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        val n = matrix.width
        val module = ((inW * 0.82f) / n).toInt().coerceAtLeast(1) // whole dots per module keeps it scannable
        val qr = n * module
        val artPad = (inW - qr) / 2f
        val artH = qr + 2 * artPad.coerceAtMost(w * 0.05f)

        // Rules and flavor text box.
        val textPad = w * 0.035f
        val boxW = (inW - 2 * textPad).toInt()
        val rules = text(w * 0.048f, Typeface.create(Typeface.SERIF, Typeface.NORMAL))
        val rulesText = "Scan this code: Download Token Printer. It's free and open source.\n" +
            "Tap: Create any token, as a sticker, on a pocket printer."
        val rulesLayout = StaticLayout.Builder.obtain(rulesText, 0, rulesText.length, rules, boxW)
            .setLineSpacing(0f, 1.05f).build()
        val flavor = text(w * 0.043f, serifItalic)
        val flavorText = "“Why count dice when you can print the whole army?”"
        val flavorLayout = StaticLayout.Builder.obtain(flavorText, 0, flavorText.length, flavor, boxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val link = text(w * 0.05f, serifBold, align = Paint.Align.CENTER)
        val shown = url.removePrefix("https://")
        val linkLines = if (link.measureText(shown) <= boxW) listOf(shown)
        else listOf(shown.substringBeforeLast('/') + "/", shown.substringAfterLast('/'))
        linkLines.forEach { fit(link, it, boxW.toFloat()) }
        val textH = textPad + rulesLayout.height + gap + flavorLayout.height + gap * 1.4f +
            link.fontSpacing * linkLines.size + textPad

        val ptH = w * 0.09f
        val top = outer + frame
        val titleY = top + gap
        val artY = titleY + barH + gap
        val typeY = artY + artH + gap
        val textY = typeY + barH + gap
        val bottom = textY + textH + ptH * 0.6f + gap // room for the P/T box that overlaps the text box
        val height = (bottom + frame + outer + w * 0.05f).toInt()

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        // Card frame: thick black border with rounded corners, a thin white line inside it.
        c.drawRoundRect(RectF(outer, outer, w - outer, bottom + frame), radius * 1.6f, radius * 1.6f, black)
        c.drawRoundRect(RectF(inL, top, inR, bottom), radius, radius, white)
        line.strokeWidth = (w * 0.006f).coerceAtLeast(1.5f)
        c.drawRoundRect(RectF(inL + gap * 0.5f, top + gap * 0.5f, inR - gap * 0.5f, bottom - gap * 0.5f), radius, radius, line)

        // Title bar: black with white title and a {0} mana cost.
        val barL = inL + gap
        val barR = inR - gap
        c.drawRoundRect(RectF(barL, titleY, barR, titleY + barH), barH / 2, barH / 2, black)
        val pipR = barH * 0.34f
        val pipX = barR - barH * 0.5f
        val pipY = titleY + barH / 2
        c.drawCircle(pipX, pipY, pipR, white)
        val pipText = text(pipR * 1.45f, serifBold, align = Paint.Align.CENTER)
        c.drawText("0", pipX, pipY - (pipText.ascent() + pipText.descent()) / 2, pipText)
        val title = text(barH * 0.56f, serifBold, Color.WHITE)
        val titleMax = pipX - pipR - barL - barH * 0.7f
        fit(title, TITLE, titleMax)
        c.drawText(TITLE, barL + barH * 0.4f, pipY - (title.ascent() + title.descent()) / 2, title)

        // Art box: the QR code inside a thin frame.
        line.strokeWidth = (w * 0.008f).coerceAtLeast(2f)
        c.drawRect(barL, artY, barR, artY + artH, line)
        val qrLeft = ((w - qr) / 2f).toInt()
        val qrTop = (artY + (artH - qr) / 2f).toInt()
        for (yy in 0 until n) for (xx in 0 until n) {
            if (matrix[xx, yy]) {
                val x0 = (qrLeft + xx * module).toFloat()
                val y0 = (qrTop + yy * module).toFloat()
                c.drawRect(x0, y0, x0 + module, y0 + module, black)
            }
        }

        // Type line with a small diamond "set symbol".
        c.drawRoundRect(RectF(barL, typeY, barR, typeY + barH), barH / 2, barH / 2, black)
        val typeText = text(barH * 0.5f, serifBold, Color.WHITE)
        val typeLine = "Artifact — Printer"
        fit(typeText, typeLine, barR - barL - barH * 1.6f)
        c.drawText(typeLine, barL + barH * 0.4f, typeY + barH / 2 - (typeText.ascent() + typeText.descent()) / 2, typeText)
        val dx = barR - barH * 0.55f
        val dy = typeY + barH / 2
        val ds = barH * 0.28f
        c.drawPath(Path().apply {
            moveTo(dx, dy - ds); lineTo(dx + ds, dy); lineTo(dx, dy + ds); lineTo(dx - ds, dy); close()
        }, white)

        // Text box: rules, a divider, flavor, then the link.
        c.drawRect(barL, textY, barR, textY + textH, line)
        var y = textY + textPad
        c.save(); c.translate(barL + textPad, y); rulesLayout.draw(c); c.restore()
        y += rulesLayout.height + gap / 2
        line.strokeWidth = (w * 0.004f).coerceAtLeast(1f)
        c.drawLine(barL + inW * 0.2f, y, barR - inW * 0.2f, y, line)
        y += gap / 2
        c.save(); c.translate(barL + textPad, y); flavorLayout.draw(c); c.restore()
        y += flavorLayout.height + gap * 1.4f - link.ascent()
        linkLines.forEach { l ->
            c.drawText(l, w / 2f, y, link)
            y += link.fontSpacing
        }

        // Power/toughness box overlapping the bottom right of the text box.
        val ptW = w * 0.2f
        val ptTop = textY + textH - ptH * 0.4f
        val pt = RectF(barR - ptW, ptTop, barR + gap * 0.3f, ptTop + ptH)
        c.drawRoundRect(pt, ptH / 3, ptH / 3, white)
        line.strokeWidth = (w * 0.008f).coerceAtLeast(2f)
        c.drawRoundRect(pt, ptH / 3, ptH / 3, line)
        val ptText = text(ptH * 0.62f, serifBold, align = Paint.Align.CENTER)
        c.drawText("1/1", pt.centerX(), pt.centerY() - (ptText.ascent() + ptText.descent()) / 2, ptText)

        // Small print under the card.
        val small = text(w * 0.032f, Typeface.SANS_SERIF, align = Paint.Align.CENTER)
        val note = "Unofficial fan-made app. Not affiliated with Wizards of the Coast."
        fit(small, note, w - 2 * outer)
        c.drawText(note, w / 2f, bottom + frame + outer + w * 0.035f, small)
        return bmp
    }
}

@Composable
fun ShareDialog(vm: MainViewModel, withBle: (() -> Unit) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Filled.Share, contentDescription = null) },
        title = { Text("Share Token Printer", textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Sharing is the best way to help other players find this project. Token Printer is free and made by one " +
                        "person, so every share really counts. Thank you!",
                )
                Text(
                    "Send the link to a friend or your playgroup, or print a QR code sticker for your deck box, binder or the " +
                        "table at your game store.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { ShareApp.shareLink(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share the link")
                }
                OutlinedButton(onClick = { withBle { vm.printQrCode() }; onClose() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.QrCode2, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Print a QR code")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Print, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "The QR code prints with the title and the link written under it, so people know where it goes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
