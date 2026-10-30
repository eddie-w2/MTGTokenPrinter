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

    /** A bug report: the subject carries the app version, today's date and the phone, so reports are easy to sort. */
    fun bugReport(ctx: Context) {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
        send(ctx, "Token Printer bug report: v${Updater.installedName(ctx)}, $date, $model, Android ${Build.VERSION.RELEASE}")
    }

    private fun send(ctx: Context, subject: String?) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "No email app found. You can write to $EMAIL.", Toast.LENGTH_LONG).show()
        }
    }
}
