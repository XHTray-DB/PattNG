package com.v2ray.ang.v2get

import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The routing of the account names `geosite:` and `geoip:` lists, and the core refuses a configuration whose
 * list file is missing. A build that carries no geo files (see fetch-geo.sh) would then never connect, so
 * before a connection starts the missing files are fetched once into the asset folder the core reads.
 * A file already there, the user's own included, is never replaced.
 */
object V2GetGeoFiles {

    private const val BASE = "https://github.com/Chocolate4U/Iran-v2ray-rules/releases/latest/download"
    private const val MIN_BYTES = 100_000L

    internal fun missing(dir: File): List<String> =
        listOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT).filter { name -> File(dir, name).let { !it.isFile || it.length() < 1 } }

    /** Fetches what is missing. Blocking; call it off the main thread. A failure is logged and left to the core to report. */
    fun ensure() {
        val dir = File(Utils.userAssetPath(AngApplication.application))
        if (dir.path.isEmpty()) return
        missing(dir).forEach { name ->
            val tmp = File(dir, "$name.part")
            try {
                val conn = (URL("$BASE/$name").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${conn.responseCode}")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                } finally {
                    conn.disconnect()
                }
                if (tmp.length() < MIN_BYTES) throw IllegalStateException("file too small: ${tmp.length()} bytes")
                if (!tmp.renameTo(File(dir, name))) throw IllegalStateException("rename failed")
                LogUtil.i(AppConfig.TAG, "V2GetGeoFiles: fetched $name")
            } catch (e: Exception) {
                tmp.delete()
                LogUtil.e(AppConfig.TAG, "V2GetGeoFiles: fetching $name failed", e)
            }
        }
    }
}
