// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 eddie-w2 and Token Printer contributors

package com.example.tokenprinter

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Opens the user's email app with a message to the developer. */
object Contact {
    const val EMAIL = "mtgtokenprinter@gmail.com"

    /** A plain "contact me" email with no subject. */
    fun email(ctx: Context) = send(ctx, null)

    /**
     * A bug report: the subject carries the app version, today's date and the phone, so reports are easy to sort,
     * and the body is a short form asking for what's needed to track a bug down, with the details filled in.
     */
    fun bugReport(ctx: Context, printerLine: String) {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
        val version = Updater.installedName(ctx)
        val body = """
            |What happened?
            |
            |
            |What did you expect to happen?
            |
            |
            |Steps to make it happen (1, 2, 3...):
            |
            |
            |Where in the app? (Tokens, Decks, print screen, Settings...):
            |
            |Does it happen every time?
            |
            |Printer, if it's about printing (name in the scan list, and the app it came with):
            |
            |
            |Screenshots or a photo of the print help a lot. Please attach them.
            |
            |---- Filled in by the app (please leave) ----
            |App version: $version
            |Phone: $model
            |Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            |Printer: $printerLine
            |Date: $date
        """.trimMargin()
        send(ctx, "Token Printer bug report: v$version, $date, $model, Android ${Build.VERSION.RELEASE}", body)
    }

    private fun send(ctx: Context, subject: String?, body: String? = null) {
        // Subject and body go both in the mailto link and as extras: different email apps read one or the other.
        val query = listOfNotNull(subject?.let { "subject=" + Uri.encode(it) }, body?.let { "body=" + Uri.encode(it) })
            .joinToString("&")
        val uri = Uri.parse("mailto:$EMAIL" + if (query.isEmpty()) "" else "?$query")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body != null) putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "No email app found. You can write to $EMAIL.", Toast.LENGTH_LONG).show()
        }
    }
}
