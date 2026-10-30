package com.example.tokenprinter

import android.content.ContentValues
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/** How an exported token image looks. */
enum class ExportStyle(val label: String, val hint: String, val suffix: String) {
    Color(
        "Full color",
        "The original art in color, with your caption. For color printers or sharing.",
        "color",
    ),
    Gray(
        "Grayscale",
        "Grey tones with your contrast settings. Best for other thermal printers: their app turns it into dots.",
        "gray",
    ),
    BlackWhite(
        "Black & white",
        "Exactly the dots this app sends to your printer. Use it if another app's own conversion looks muddy.",
        "bw",
    ),
}

/** Turns the open token into a JPEG for printers the app can't talk to yet. */
object TokenExport {
    /** Width of color and grayscale exports. Large enough that text stays sharp when another app scales it down. */
    private const val EXPORT_WIDTH = 1200

    /** Black and white is drawn at the printer's own width, then each dot is blown up to a crisp square block. */
    private const val BW_SCALE = 3

    private const val QUALITY = 95
    private const val FOLDER = "Token Printer"

    fun draw(
        src: Bitmap,
        caption: CharSequence?,
        captionStyle: CaptionStyle,
        badge: String?,
        o: Opts,
        profile: PrinterProfile,
        style: ExportStyle,
    ): Bitmap {
        val design = profile.widthDots
        return when (style) {
            ExportStyle.Color -> {
                val k = EXPORT_WIDTH / design
                Raster.layoutPage(src, caption, captionStyle, EXPORT_WIDTH, design, false, 8 * k, 1, badge).bitmap
            }
            ExportStyle.Gray -> {
                val k = EXPORT_WIDTH / design
                val page = Raster.layoutPage(src, caption, captionStyle, EXPORT_WIDTH, design, false, 8 * k, 1, badge)
                Raster.grayscale(page, o.contrast, o.autoContrast)
            }
            ExportStyle.BlackWhite -> {
                val page = Raster.layoutPage(src, caption, captionStyle, design, design, false, 8, 1, badge)
                val bw = Raster.blackWhite(page, o.contrast, o.autoContrast)
                // filter = false keeps hard pixel edges instead of blurring the dots into grey.
                Bitmap.createScaledBitmap(bw, bw.width * BW_SCALE, bw.height * BW_SCALE, false)
            }
        }
    }

    fun writeJpeg(bmp: Bitmap, out: OutputStream) {
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) throw IllegalStateException("Couldn't encode JPEG")
    }

    fun fileName(tokenName: String, style: ExportStyle): String {
        val base = tokenName.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase().ifEmpty { "token" }.take(60)
        return "$base-${style.suffix}.jpg"
    }

    /** True when the phone can save straight into the shared Pictures folder without asking for a location. */
    val canSaveToPictures: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertPicture(ctx: Context, f: File): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Couldn't create the picture")
        try {
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Couldn't open the picture")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    fun saveToPictures(ctx: Context, f: File): Uri {
        if (!canSaveToPictures) throw IllegalStateException("Needs Android 10 or newer")
        return insertPicture(ctx, f)
    }

    fun shareUri(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    /** The share sheet for an exported image. */
    fun shareIntent(uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Send token image")
    }
}
