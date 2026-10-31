package com.v2ray.ang.v2get

import com.v2ray.ang.AppConfig
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where the Aether configuration comes from: the public `conf.json` of the Pattconf repository, read raw.
 * The panel (worker) hands out no core settings; the file is the only source, and the app has no built-in command.
 */
object V2GetConfigSource {

    const val CONFIG_URL = "https://raw.githubusercontent.com/XHTray-DB/Pattconf/main/conf.json"

    private const val KEY_CACHE = "V2GET_AETHER_URI_CACHE"
    private val AETHER_URI = Regex("""aether://[^\s"'<>,]+""", RegexOption.IGNORE_CASE)

    /** The first `aether://` link in [text], or null. Other schemes of the file are not used by this app. */
    internal fun extractAetherUri(text: String): String? = AETHER_URI.find(text)?.value

    private fun download(): String {
        val conn = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Reads the file, builds the Aether profile from its link and selects it. When the download fails, the
     * link of the last good download is used. Blocking; call it off the main thread.
     *
     * @return null on success, otherwise the exact reason, in text fit to show.
     */
    fun refreshProfile(): String? {
        var uri: String? = null
        var downloadError: String? = null
        try {
            uri = extractAetherUri(download())
            if (uri == null) return "در فایل conf.json هیچ کانفیگ aether:// پیدا نشد."
        } catch (e: Exception) {
            downloadError = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            LogUtil.e(AppConfig.TAG, "V2GetConfigSource: download failed", e)
        }
        if (uri == null) {
            uri = MmkvManager.decodeSettingsString(KEY_CACHE).orEmpty().ifEmpty { null }
                ?: return "دریافت کانفیگ از گیت‌هاب ناموفق بود (و نسخهٔ ذخیره‌شده‌ای وجود ندارد): $downloadError"
        }
        val profile = try {
            AetherFmt.parse(uri)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "V2GetConfigSource: link not readable", e)
            return "کانفیگ aether خوانده نشد: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        } ?: return "کانفیگ aether معتبر نیست."
        return try {
            MmkvManager.encodeServerConfig(V2GetAuthManager.PROFILE_GUID, profile)
            MmkvManager.setSelectServer(V2GetAuthManager.PROFILE_GUID)
            if (downloadError == null) MmkvManager.encodeSettings(KEY_CACHE, uri)
            null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "V2GetConfigSource: storing the profile failed", e)
            "ذخیرهٔ کانفیگ ناموفق بود: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        }
    }
}
