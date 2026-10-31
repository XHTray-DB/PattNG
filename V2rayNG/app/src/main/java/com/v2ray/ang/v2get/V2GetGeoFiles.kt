package com.v2ray.ang.v2get

import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The routing names `geosite:` and `geoip:` lists, and the core refuses a configuration whose list file is
 * missing: the three files of [SOURCES] are what the routing rules, `geoip:private` and `geoip:cn` among them,
 * read. A build that carries no geo files (see fetch-geo.sh) would then never connect, so before a connection
 * starts the missing files are fetched into the asset folder the core reads. A file already there, the user's
 * own included, is never replaced.
 *
 * The files come with the app: a build ships them in its assets (see fetch-geo.sh), and the first thing [ensure]
 * does is copy them into the asset folder. That copy used to be done only by the classic main screen, which a
 * V2GET user never opens, so a fresh install had no files and went to the network for them.
 *
 * One call spends at most [BUDGET_SECONDS]. A download that did not finish keeps its part file and the next
 * call goes on from where it stopped, so a slow line gets there over a few connection attempts.
 */
object V2GetGeoFiles {

    internal class Source(val name: String, val url: String, val minBytes: Long)

    private const val IRAN_RULES = "https://github.com/Chocolate4U/Iran-v2ray-rules/releases/latest/download"
    internal val SOURCES = listOf(
        Source(AppConfig.GEOSITE_DAT, "$IRAN_RULES/${AppConfig.GEOSITE_DAT}", 100_000L),
        Source(AppConfig.GEOIP_DAT, "$IRAN_RULES/${AppConfig.GEOIP_DAT}", 100_000L),
        Source(AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT, AppConfig.GEOIP_ONLY_CN_PRIVATE_URL, 1_000L),
    )

    private const val BUDGET_SECONDS = 40L

    internal fun missing(dir: File): List<String> =
        SOURCES.map { it.name }.filter { name -> File(dir, name).let { !it.isFile || it.length() < 1 } }

    /**
     * Fetches what is missing. Blocking; call it off the main thread.
     *
     * @return null when every file is there, otherwise the reason, in text fit to show.
     */
    fun ensure(): String? {
        val app = AngApplication.application
        val dir = File(Utils.userAssetPath(app))
        if (dir.path.isEmpty()) return null
        if (missing(dir).isEmpty()) return null

        // The copy of the bundled files skips a file that exists, so an empty leftover is cleared first.
        SOURCES.map { File(dir, it.name) }.filter { it.isFile && it.length() < 1 }.forEach { it.delete() }
        SettingsManager.initAssets(app, app.assets)
        val absent = missing(dir)
        if (absent.isEmpty()) return null

        val deadline = System.nanoTime() + BUDGET_SECONDS * 1_000_000_000L
        SOURCES.filter { it.name in absent }.forEach { source ->
            try {
                fetch(source, dir, deadline)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "V2GetGeoFiles: fetching ${source.name} stopped", e)
            }
        }
        val still = missing(dir)
        return if (still.isEmpty()) null else
            "فایل‌های ${still.joinToString("، ")} هنوز کامل دانلود نشده‌اند. اتصال اینترنت را بررسی کنید و دوباره وصل شوید؛ دانلود از همان‌جا ادامه پیدا می‌کند."
    }

    private fun fetch(source: Source, dir: File, deadline: Long) {
        val part = File(dir, "${source.name}.part")
        val have = if (part.isFile) part.length() else 0L
        val conn = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 416) {
                // The part no longer fits the file on the server: start over next time.
                part.delete()
                throw IOException("HTTP 416")
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP $code")
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            val expected = if (resumed) {
                conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
            } else {
                conn.contentLengthLong.takeIf { it > 0 }
            }
            conn.inputStream.use { input ->
                FileOutputStream(part, resumed).use { out ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (System.nanoTime() > deadline) throw IOException("time limit, ${part.length()} bytes kept")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
            if (expected != null && part.length() != expected) throw IOException("incomplete: ${part.length()} of $expected bytes")
            if (part.length() < source.minBytes) {
                part.delete()
                throw IOException("file too small")
            }
            if (!part.renameTo(File(dir, source.name))) throw IOException("rename failed")
            LogUtil.i(AppConfig.TAG, "V2GetGeoFiles: fetched ${source.name}")
        } finally {
            conn.disconnect()
        }
    }
}
