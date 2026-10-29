package com.example.tokenprinter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A token the user made: drawn with a finger, or from a photo.
 * [linkedKey] set: it is one more art option on that existing token. Null: it lives in the Custom folder
 * (custom tokens with the same name share one entry).
 */
data class CustomToken(
    val id: String,
    val name: String,
    val linkedKey: String?,
    val created: Long,
    val ext: String,
    val text: String = "",
)

class CustomStore(private val dir: File, private val meta: File) {
    init {
        dir.mkdirs()
    }

    fun fileFor(c: CustomToken) = File(dir, "${c.id}.${c.ext}")

    fun load(): List<CustomToken> = try {
        if (!meta.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(meta.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<CustomToken>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(toJson(it)) }
            meta.writeText(arr.toString())
        } catch (e: Exception) {
            // best effort
        }
    }

    /** Writes the picture and returns its file extension. Photos are JPEG, drawings PNG. */
    fun writeBitmap(id: String, bmp: Bitmap, photo: Boolean): String {
        val ext = if (photo) "jpg" else "png"
        FileOutputStream(File(dir, "$id.$ext")).use {
            bmp.compress(if (photo) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, it)
        }
        return ext
    }

    fun writeBytes(id: String, ext: String, bytes: ByteArray) {
        File(dir, "$id.$ext").writeBytes(bytes)
    }

    fun delete(c: CustomToken) {
        try {
            fileFor(c).delete()
        } catch (e: Exception) {
            // already gone
        }
    }

    fun toJson(c: CustomToken) = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("link", c.linkedKey ?: "")
        put("created", c.created)
        put("ext", c.ext)
        put("text", c.text)
    }

    fun fromJson(o: JSONObject) = CustomToken(
        id = o.getString("id"),
        name = o.getString("name"),
        linkedKey = o.optString("link").ifEmpty { null },
        created = o.optLong("created", 0L),
        ext = o.optString("ext", "png"),
        text = o.optString("text", ""),
    )

    fun art(c: CustomToken): TokenArt {
        val url = Uri.fromFile(fileFor(c)).toString()
        return TokenArt(
            id = "custom:${c.id}",
            artUrl = url,
            cardUrl = url,
            setName = "Custom",
            setCode = "custom",
            artist = "You",
            number = "",
            released = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(c.created)),
            note = c.text,
        )
    }
}

/**
 * Adds the custom tokens to the Scryfall list. A linked one becomes another printing of its token
 * (placed after the Scryfall arts); the rest are grouped by name into tokens of type "Custom".
 */
fun mergeCustom(base: List<Token>, customs: List<CustomToken>, store: CustomStore): List<Token> {
    if (customs.isEmpty()) return base
    val byKey = base.associateBy { it.key }
    val extra = HashMap<String, MutableList<TokenArt>>()
    val loose = LinkedHashMap<String, MutableList<CustomToken>>()
    for (c in customs.sortedByDescending { it.created }) {
        if (!store.fileFor(c).exists()) continue
        val target = c.linkedKey?.let { byKey[it] }
        if (target != null) extra.getOrPut(target.key) { ArrayList() }.add(store.art(c))
        else loose.getOrPut(c.name.trim().lowercase()) { ArrayList() }.add(c)
    }
    val out = ArrayList<Token>(base.size + loose.size)
    for (t in base) {
        val more = extra[t.key]
        out.add(if (more == null) t else t.copy(arts = t.arts + more))
    }
    for ((lower, list) in loose) {
        out.add(
            Token(
                key = "custom|$lower",
                name = list.first().name.trim(),
                typeLine = "Custom",
                pt = "",
                colors = "",
                oracle = "",
                arts = list.map { store.art(it) },
                keywords = emptyList(),
            )
        )
    }
    return out
}

object CustomImages {
    /** Loads a picked photo, applies its EXIF rotation, shrinks it to [maxSide] and flattens transparency onto white. */
    fun loadPicked(ctx: Context, uri: Uri, maxSide: Int = 1400): Bitmap {
        val cr = ctx.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Couldn't read that image")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Couldn't read that image")

        val degrees = try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }

        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val m = Matrix()
        m.postScale(scale, scale)
        m.postRotate(degrees)
        val moved = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)

        val flat = Bitmap.createBitmap(moved.width, moved.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(flat)
        c.drawColor(Color.WHITE)
        c.drawBitmap(moved, 0f, 0f, null)
        return flat
    }
}
