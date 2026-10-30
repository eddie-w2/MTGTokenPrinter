package com.example.tokenprinter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Self-updating for installs that don't come from Google Play.
 *
 * Every build the project publishes also goes to a public GitHub repo as a Release: the APK, a .sha256 file and
 * the "What's new" text as the release notes. The APK name carries the version code (TokenPrinter-0.8.00-code11.apk)
 * so the app can tell if it is newer without downloading anything.
 *
 * Android never lets an app install silently. The user allows "Install unknown apps" for Token Printer once, and
 * Android shows its own confirmation (on newer Android versions it is skipped for updates when it can be).
 */
object Updater {
    private const val RELEASES_API = "https://api.github.com/repos/eddie-w2/MTGTokenPrinter-releases/releases/latest"
    private const val RESULT_ACTION = "com.example.tokenprinter.INSTALL_RESULT"
    private val CODE_IN_NAME = Regex("-code(\\d+)\\.apk$")

    /** Set by the install receiver when Android refuses or fails an install. */
    val installMessage = MutableStateFlow<String?>(null)

    data class Info(
        val versionName: String,
        val versionCode: Long,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val shaUrl: String?,
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun installedCode(ctx: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (e: Exception) {
        0L
    }

    fun installedName(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun get(url: String, accept: String): okhttp3.Response {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "TokenPrinter-Updater")
            .header("Accept", accept)
            .build()
        return client.newCall(req).execute()
    }

    /** Asks GitHub for the newest release. Blocking: call it off the main thread. */
    fun fetchLatest(): Info {
        get(RELEASES_API, "application/vnd.github+json").use { r ->
            if (r.code == 404) throw IllegalStateException("No update has been published yet.")
            if (r.code == 403 || r.code == 429) throw IllegalStateException("GitHub is limiting requests right now. Try again in a while.")
            if (!r.isSuccessful) throw IllegalStateException("GitHub answered ${r.code}.")
            val json = JSONObject(r.body?.string() ?: throw IllegalStateException("Empty answer from GitHub."))
            val assets = json.optJSONArray("assets") ?: throw IllegalStateException("The release has no files.")
            var apk: JSONObject? = null
            var sha: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk") && CODE_IN_NAME.containsMatchIn(name)) apk = a
                if (name.endsWith(".apk.sha256")) sha = a.optString("browser_download_url")
            }
            val file = apk ?: throw IllegalStateException("The release has no app file.")
            val code = CODE_IN_NAME.find(file.getString("name"))!!.groupValues[1].toLong()
            return Info(
                versionName = json.optString("tag_name").removePrefix("v").ifBlank { "?" },
                versionCode = code,
                notes = json.optString("body", "").trim(),
                apkUrl = file.getString("browser_download_url"),
                apkSize = file.optLong("size", 0L),
                shaUrl = sha?.takeIf { it.isNotBlank() },
            )
        }
    }

    fun toJson(i: Info): String = JSONObject()
        .put("n", i.versionName).put("c", i.versionCode).put("notes", i.notes)
        .put("url", i.apkUrl).put("size", i.apkSize).put("sha", i.shaUrl ?: "")
        .toString()

    fun fromJson(s: String): Info? = try {
        val j = JSONObject(s)
        Info(j.getString("n"), j.getLong("c"), j.optString("notes"), j.getString("url"), j.optLong("size"), j.optString("sha").ifBlank { null })
    } catch (e: Exception) {
        null
    }

    private fun apkFile(ctx: Context) = File(File(ctx.cacheDir, "updates"), "TokenPrinter-update.apk")

    fun downloadedFile(ctx: Context): File? = apkFile(ctx).takeIf { it.exists() && it.length() > 0 }

    /** Downloads and checks the APK. Blocking. [onProgress] gets 0..1. */
    fun download(ctx: Context, info: Info, onProgress: (Float) -> Unit): File {
        val target = apkFile(ctx)
        target.parentFile?.mkdirs()
        target.delete()
        val expected = info.shaUrl?.let { u ->
            get(u, "*/*").use { r -> if (r.isSuccessful) r.body?.string()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase() else null }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        get(info.apkUrl, "application/octet-stream").use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Download failed (${r.code}).")
            val body = r.body ?: throw IllegalStateException("Download failed.")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
            var done = 0L
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && expected != actual) {
            target.delete()
            throw IllegalStateException("The download didn't match its checksum, so it was thrown away. Try again.")
        }
        return target
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" page for Token Printer. */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the APK to Android's installer. The result arrives in [UpdateReceiver]. */
    fun install(ctx: Context, file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(file.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(ctx, UpdateReceiver::class.java).setAction(RESULT_ACTION).setPackage(ctx.packageName)
            val pi = PendingIntent.getBroadcast(ctx, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }
}

/** Receives the installer's answer: shows Android's confirmation screen when needed, reports failures. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.installMessage.value = "The install was cancelled."
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Updater.installMessage.value = "Android couldn't install the update. $why".trim()
            }
        }
    }
}
