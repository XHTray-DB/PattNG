package com.v2ray.ang.v2get

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

/** One server of the account: the profile it is stored under and what the screens show of it. */
data class V2GetServer(val guid: String, val name: String, val protocol: String)

/**
 * Where the Aether servers come from: the public `conf.json` of the Pattconf repository, read raw.
 * Every `aether://` link of the file is one server. The panel (worker) hands out no core settings; the
 * file is the only source, and the app has no built-in command.
 */
object V2GetConfigSource {

    const val CONFIG_URL = "https://raw.githubusercontent.com/XHTray-DB/Pattconf/main/conf.json"

    /** Every profile of V2GET starts with this, the one single-profile build of the app (see [V2GetAuthManager.PROFILE_GUID]) included. */
    const val GUID_PREFIX = "v2get-aether-"

    /** Written by the single-profile build: the one link of the last good download. Read as a fallback only. */
    private const val KEY_CACHE = "V2GET_AETHER_URI_CACHE"
    private const val KEY_CACHE_LIST = "V2GET_AETHER_URI_LIST_CACHE"
    private const val KEY_SERVERS = "V2GET_SERVERS"
    private const val KEY_SELECTED = "V2GET_SELECTED_GUID"

    private val AETHER_URI = Regex("""aether://[^\s"'<>,]+""", RegexOption.IGNORE_CASE)

    /** The first `aether://` link in [text], or null. */
    internal fun extractAetherUri(text: String): String? = extractAetherUris(text).firstOrNull()

    /**
     * Every `aether://` link in [text], in file order, each once. The file may be plain lines or JSON; a JSON
     * writer's escapes of `&` and `/` are undone first. Other schemes of the file are not used by this app.
     */
    internal fun extractAetherUris(text: String): List<String> =
        AETHER_URI.findAll(text.replace("\\u0026", "&").replace("\\/", "/")).map { it.value }.distinct().toList()

    /** The profile id of a link: the same link is always the same profile, so a chosen server stays chosen when the file is reordered. */
    internal fun guidOf(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        return GUID_PREFIX + digest.take(6).joinToString("") { "%02x".format(it) }
    }

    /** What the screens show of the link at [index]: its name (the fragment) and its protocol line. */
    internal fun describe(uri: String, index: Int): V2GetServer {
        val fragment = uri.substringAfter('#', "")
        val name = runCatching { URLDecoder.decode(fragment.replace("+", "%2B"), "UTF-8") }.getOrDefault(fragment).trim()
        val params = uri.substringBefore('#').substringAfter('?', "").split('&').mapNotNull {
            val key = it.substringBefore('=')
            if (key.isEmpty() || !it.contains('=')) null else key.lowercase() to it.substringAfter('=')
        }.toMap()
        val protocol = listOf(params["protocol"], params["transport"])
            .filter { !it.isNullOrBlank() }
            .joinToString(" / ") { it!!.uppercase() }
        return V2GetServer(guidOf(uri), name.ifEmpty { "V2GET ${index + 1}" }, protocol)
    }

    fun isV2GetGuid(guid: String?): Boolean = guid != null && guid.startsWith(GUID_PREFIX)

    // Written by hand, not by reflection, so a release that renames fields in the minified app still reads the list.
    private fun encodeServers(servers: Collection<V2GetServer>): String = JsonArray().apply {
        servers.forEach {
            add(JsonObject().apply {
                addProperty("guid", it.guid)
                addProperty("name", it.name)
                addProperty("protocol", it.protocol)
            })
        }
    }.toString()

    fun loadServers(): List<V2GetServer> {
        val json = MmkvManager.decodeSettingsString(KEY_SERVERS).orEmpty()
        if (json.isEmpty()) return emptyList()
        return try {
            JsonParser.parseString(json).asJsonArray.mapNotNull { element ->
                val o = element.asJsonObject
                val guid = o.get("guid")?.asString.orEmpty()
                if (guid.isEmpty()) null else V2GetServer(guid, o.get("name")?.asString.orEmpty(), o.get("protocol")?.asString.orEmpty())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "V2GetConfigSource: stored server list not readable", e)
            emptyList()
        }
    }

    /** The server the next connection uses: the chosen one while it still exists, else the first. */
    fun selectedGuid(): String? {
        val servers = loadServers()
        val chosen = MmkvManager.decodeSettingsString(KEY_SELECTED)
        return servers.firstOrNull { it.guid == chosen }?.guid ?: servers.firstOrNull()?.guid
    }

    /** Makes [guid] the server of the next connection. False when no such server is stored. */
    fun selectServer(guid: String): Boolean {
        if (loadServers().none { it.guid == guid }) return false
        MmkvManager.encodeSettings(KEY_SELECTED, guid)
        MmkvManager.setSelectServer(guid)
        return true
    }

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

    private fun cachedUris(): List<String> {
        val list = MmkvManager.decodeSettingsString(KEY_CACHE_LIST).orEmpty()
        if (list.isNotEmpty()) return extractAetherUris(list)
        return MmkvManager.decodeSettingsString(KEY_CACHE).orEmpty().takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
    }

    /**
     * Reads the file, builds one Aether profile per link and keeps the chosen server selected (the first one
     * when none is chosen yet or it left the file). A link that does not parse is skipped; the call fails only
     * when none does. When the download fails, the links of the last good download are used. Blocking; call it
     * off the main thread.
     *
     * @return null on success, otherwise the exact reason, in text fit to show.
     */
    fun refreshProfile(): String? {
        var uris: List<String> = emptyList()
        var downloadError: String? = null
        try {
            uris = extractAetherUris(download())
            if (uris.isEmpty()) return "در فایل conf.json هیچ کانفیگ aether:// پیدا نشد."
        } catch (e: Exception) {
            downloadError = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            LogUtil.e(AppConfig.TAG, "V2GetConfigSource: download failed", e)
        }
        if (uris.isEmpty()) {
            uris = cachedUris()
            if (uris.isEmpty()) return "دریافت کانفیگ از گیت‌هاب ناموفق بود (و نسخهٔ ذخیره‌شده‌ای وجود ندارد): $downloadError"
        }

        val built = LinkedHashMap<String, Pair<V2GetServer, ProfileItem>>()
        var parseError: String? = null
        uris.forEachIndexed { index, uri ->
            try {
                val profile = AetherFmt.parse(uri)
                if (profile == null) {
                    parseError = parseError ?: "کانفیگ aether معتبر نیست."
                } else {
                    val server = describe(uri, index)
                    built.putIfAbsent(server.guid, server to profile)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "V2GetConfigSource: link ${index + 1} not readable", e)
                parseError = parseError ?: "کانفیگ aether خوانده نشد: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            }
        }
        if (built.isEmpty()) return parseError ?: "کانفیگ aether معتبر نیست."

        return try {
            val stale = (loadServers().map { it.guid } + V2GetAuthManager.PROFILE_GUID).filter { it !in built }
            built.values.forEach { (server, profile) -> MmkvManager.encodeServerConfig(server.guid, profile) }
            stale.forEach { MmkvManager.tryRemoveServer(it) }
            MmkvManager.encodeSettings(KEY_SERVERS, encodeServers(built.values.map { it.first }))
            val chosen = MmkvManager.decodeSettingsString(KEY_SELECTED)
            val selected = if (chosen != null && chosen in built) chosen else built.keys.first()
            MmkvManager.encodeSettings(KEY_SELECTED, selected)
            MmkvManager.setSelectServer(selected)
            if (downloadError == null) MmkvManager.encodeSettings(KEY_CACHE_LIST, uris.joinToString("\n"))
            null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "V2GetConfigSource: storing the profiles failed", e)
            "ذخیرهٔ کانفیگ ناموفق بود: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        }
    }

    /** Signing out: every profile of the account and what was kept about them goes. */
    fun clear() {
        (loadServers().map { it.guid } + V2GetAuthManager.PROFILE_GUID).forEach { MmkvManager.tryRemoveServer(it) }
        MmkvManager.encodeSettings(KEY_SERVERS, "")
        MmkvManager.encodeSettings(KEY_SELECTED, "")
        MmkvManager.encodeSettings(KEY_CACHE_LIST, "")
        MmkvManager.encodeSettings(KEY_CACHE, "")
    }
}
