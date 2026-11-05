package com.v2ray.ang.v2get

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The report a person sends to support when the connection gives trouble: the app's own log of the last minutes,
 * with everything private taken out, and a few lines about the phone and the account. It is written to a file and
 * handed to the share sheet, so it goes by Telegram or anything else the person picks.
 */
object V2GetReport {

    private val bearer = Regex("""(?i)bearer\s+\S+""")
    private val link = Regex("""(?i)\b[a-z][a-z0-9+.-]{1,15}://[^\s"'<>]+""")
    private val secretField = Regex("""(?i)\b(password|passwd|pass|token|secret|auth|authorization|key|uuid|id|sni|host|path)(["']?\s*[=:]\s*)("[^"]*"|'[^']*'|\S+)""")
    private val longToken = Regex("""[A-Za-z0-9_\-+/=]{32,}""")

    /** [text] with what must not leave the phone replaced: bearer tokens, server links, secret fields and long keys. */
    internal fun scrub(text: String): String = text.lineSequence().joinToString("\n") { line ->
        line
            .replace(bearer, "Bearer <hidden>")
            .replace(link, "<link>")
            .replace(secretField) { "${it.groupValues[1]}${it.groupValues[2]}<hidden>" }
            .replace(longToken, "<hidden>")
    }

    /** The last lines of the app's log, as the log screen reads it. Blocking. */
    private fun readLog(): String = try {
        val command = arrayOf("logcat", "-d", "-v", "time", "-t", "1500", "-s", "GoLog,${AppConfig.ANG_PACKAGE},AndroidRuntime,System.err")
        Runtime.getRuntime().exec(command).inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        ""
    }

    private fun header(): String {
        val session = V2GetSession.load()
        return buildString {
            appendLine("V2GET ${BuildConfig.VERSION_NAME}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Account: ${session?.username ?: "-"}")
            appendLine("Expires: ${session?.expiresAt ?: "-"}")
            appendLine("Blocked: ${V2GetBlock.pending() ?: "no"}")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine("----")
        }
    }

    /** Writes the report and returns the share intent for it, or null when it could not be written. Blocking. */
    fun build(context: Context): Intent? = try {
        val dir = File(context.cacheDir, "shared_logs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "V2GET_report_$stamp.txt")
        file.writeText(header() + scrub(readLog()), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.cache", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        }
    } catch (_: Exception) {
        null
    }
}
