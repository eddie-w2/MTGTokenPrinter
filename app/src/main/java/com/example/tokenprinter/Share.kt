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
import android.graphics.Typeface
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
    val url: String get() = SOURCE_URL

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

    /** A black-on-white page [width] dots wide: title, QR code, and the link spelled out underneath. */
    fun qrPage(width: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            url, BarcodeFormat.QR_CODE, 1, 1,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        val n = matrix.width
        val module = ((width * 0.78f) / n).toInt().coerceAtLeast(1) // whole dots per module keeps the code sharp
        val qr = n * module
        val pad = (width * 0.05f).toInt()

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = width * 0.105f
        }
        while (title.measureText(TITLE) > width - 2 * pad) title.textSize *= 0.95f
        val link = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = width * 0.06f
        }
        // The link on two lines if needed: "github.com/" then the rest.
        val shown = url.removePrefix("https://")
        val lines = if (link.measureText(shown) <= width - 2 * pad) listOf(shown)
        else listOf(shown.substringBefore('/') + "/", shown.substringAfter('/'))
        lines.forEach { l -> while (link.measureText(l) > width - 2 * pad) link.textSize *= 0.95f }

        val titleH = (title.fontSpacing).toInt()
        val linkH = link.fontSpacing
        val height = pad + titleH + pad / 2 + qr + pad / 2 + (linkH * lines.size).toInt() + pad
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        var y = pad - title.ascent()
        c.drawText(TITLE, width / 2f, y, title)
        val top = pad + titleH + pad / 2
        val left = (width - qr) / 2
        val dot = Paint().apply { color = Color.BLACK }
        for (yy in 0 until n) for (xx in 0 until n) {
            if (matrix[xx, yy]) {
                val x0 = (left + xx * module).toFloat()
                val y0 = (top + yy * module).toFloat()
                c.drawRect(x0, y0, x0 + module, y0 + module, dot)
            }
        }
        y = top + qr + pad / 2 - link.ascent()
        lines.forEach { l ->
            c.drawText(l, width / 2f, y, link)
            y += linkH
        }
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
