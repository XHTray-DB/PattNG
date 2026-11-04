package com.v2ray.ang.v2get

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.SocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * One server of the account: the profile it is stored under and what the screens show of it. [countryCode] is
 * the exit country the app measured through the tunnel (blank until the server has been connected once).
 */
data class V2GetServer(
    val guid: String,
    val name: String,
    val protocol: String,
    val countryCode: String = "",
    /** The kind of the profile (`AETHER`, `VLESS`, `VMESS`...), what the server list filters by. */
    val family: String = "",
)

/**
 * Where the servers come from: the public `conf.json` of the Pattconf repository, read raw. Every link of the
 * file that the app can use (`aether://`, `vless://`, `vmess://`, `ss://`, `trojan://`, `hysteria2://` and the
 * like) is one server. The panel (worker) hands out no core settings; the file is the only source, and the app
 * has no built-in command.
 */
object V2GetConfigSource {

    const val CONFIG_URL = "https://raw.githubusercontent.com/XHTray-DB/Pattconf/main/conf.json"

    /** The second file, rebuilt by the repository's action from the subscriptions, with only the servers that answered a TCP test. */
    const val CONFIG2_URL = "https://raw.githubusercontent.com/XHTray-DB/Pattconf/main/conf2.json"

    /** Every profile of V2GET starts with this, the one single-profile build of the app (see [V2GetAuthManager.PROFILE_GUID]) included. */
    const val GUID_PREFIX = "v2get-aether-"

    /** Written by the single-profile build: the one link of the last good download. Read as a fallback only. */
    private const val KEY_CACHE = "V2GET_AETHER_URI_CACHE"
    private const val KEY_CACHE_LIST = "V2GET_AETHER_URI_LIST_CACHE"
    private const val KEY_CACHE_LIST2 = "V2GET_CONFIG2_URI_LIST_CACHE"
    private const val KEY_SERVERS = "V2GET_SERVERS"
    private const val KEY_SELECTED = "V2GET_SELECTED_GUID"

    private const val KEY_COUNTRIES = "V2GET_COUNTRIES"

    /** The schemes of the links a server can be made of, and what reads each of them. */
    private val PARSERS: Map<String, (String) -> ProfileItem?> = linkedMapOf(
        EConfigType.AETHER.protocolScheme to AetherFmt::parse,
        EConfigType.VLESS.protocolScheme to VlessFmt::parse,
        EConfigType.VMESS.protocolScheme to VmessFmt::parse,
        EConfigType.SHADOWSOCKS.protocolScheme to ShadowsocksFmt::parse,
        EConfigType.TROJAN.protocolScheme to TrojanFmt::parse,
        EConfigType.HYSTERIA2.protocolScheme to Hysteria2Fmt::parse,
        AppConfig.HY2 to Hysteria2Fmt::parse,
        EConfigType.WIREGUARD.protocolScheme to WireguardFmt::parse,
        EConfigType.SOCKS.protocolScheme to SocksFmt::parse,
        AppConfig.SOCKS5 to SocksFmt::parse,
    )

    /**
     * A link starts at its scheme, and a scheme is not found inside a longer word: `ss://` must not be read out
     * of `vless://`.
     */
    private val LINK_URI = Regex(
        "(?<![A-Za-z0-9+.-])(?:" + PARSERS.keys.joinToString("|") { Regex.escape(it.removeSuffix("://")) } + """)://[^\s"'<>,]+""",
        RegexOption.IGNORE_CASE,
    )

    /** The first `aether://` link in [text], or null. */
    internal fun extractAetherUri(text: String): String? = extractAetherUris(text).firstOrNull()

    /** Every `aether://` link in [text], in file order, each once. */
    internal fun extractAetherUris(text: String): List<String> =
        extractLinks(text).filter { it.startsWith(EConfigType.AETHER.protocolScheme, ignoreCase = true) }

    /**
     * Every link of a kind the app can use in [text], in file order, each once. The file may be plain lines or
     * JSON; a JSON writer's escapes of `&` and `/` are undone first.
     */
    internal fun extractLinks(text: String): List<String> =
        LINK_URI.findAll(text.replace("\\u0026", "&").replace("\\/", "/")).map { it.value }.distinct().toList()

    /** The profile a link stands for, or null when it is none the app can read. */
    internal fun parseLink(uri: String): ProfileItem? =
        PARSERS.entries.firstOrNull { uri.startsWith(it.key, ignoreCase = true) }?.value?.invoke(uri)

    /** The profile id of a link: the same link is always the same profile, so a chosen server stays chosen when the file is reordered. */
    internal fun guidOf(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        return GUID_PREFIX + digest.take(6).joinToString("") { "%02x".format(it) }
    }

    /**
     * What the screens show of a link that is not an Aether link: its name and a protocol line made of the
     * profile's own fields, such as `VLESS / WS / TLS`.
     */
    internal fun describe(uri: String, index: Int, profile: ProfileItem?): V2GetServer {
        if (profile == null || profile.configType == EConfigType.AETHER) return describe(uri, index)
        val line = listOf(profile.configType.name, profile.network, profile.security)
            .filter { !it.isNullOrBlank() && !it.equals("none", ignoreCase = true) && !it.equals("tcp", ignoreCase = true) }
            .joinToString(" / ") { it!!.uppercase() }
        val name = profile.remarks.trim().ifEmpty { "V2GET ${index + 1}" }
        return V2GetServer(guidOf(uri), name, line, family = profile.configType.name)
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
        return V2GetServer(guidOf(uri), name.ifEmpty { "V2GET ${index + 1}" }, protocol, family = EConfigType.AETHER.name)
    }

    fun isV2GetGuid(guid: String?): Boolean = guid != null && guid.startsWith(GUID_PREFIX)

    // Written by hand, not by reflection, so a release that renames fields in the minified app still reads the list.
    private fun encodeServers(servers: Collection<V2GetServer>): String = JsonArray().apply {
        servers.forEach {
            add(JsonObject().apply {
                addProperty("guid", it.guid)
                addProperty("name", it.name)
                addProperty("protocol", it.protocol)
                addProperty("family", it.family)
            })
        }
    }.toString()

    /** The exit countries measured so far, by server id. */
    private fun loadCountries(): Map<String, String> {
        val json = MmkvManager.decodeSettingsString(KEY_COUNTRIES).orEmpty()
        if (json.isEmpty()) return emptyMap()
        return try {
            JsonParser.parseString(json).asJsonObject.entrySet()
                .mapNotNull { (guid, code) -> V2GetCountry.normalize(code.asString)?.let { guid to it } }
                .toMap()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "V2GetConfigSource: stored countries not readable", e)
            emptyMap()
        }
    }

    private fun saveCountries(countries: Map<String, String>) {
        val json = JsonObject().apply { countries.forEach { (guid, code) -> addProperty(guid, code) } }
        MmkvManager.encodeSettings(KEY_COUNTRIES, json.toString())
    }

    /** The exit country measured for the server [guid], as a code; null until it was measured. Read in any process. */
    fun countryOf(guid: String?): String? = guid?.let { loadCountries()[it] }

    /** Keeps the exit country measured for the server [guid]; the screens show it from then on. */
    fun saveCountry(guid: String, code: String) {
        val normalized = V2GetCountry.normalize(code) ?: return
        if (loadServers().none { it.guid == guid }) return
        saveCountries(loadCountries() + (guid to normalized))
    }

    /**
     * The country the running tunnel exits in, asked of an address service through the tunnel itself, so it is the
     * country a site sees and not where a configuration says its server is. Blocking; call it off the main thread.
     */
    fun detectExitCountry(): String? = try {
        V2GetCountry.normalize(SpeedtestManager.getRemoteIPInfo()?.country)
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "V2GetConfigSource: exit country lookup failed", e)
        null
    }

    fun loadServers(): List<V2GetServer> {
        val json = MmkvManager.decodeSettingsString(KEY_SERVERS).orEmpty()
        if (json.isEmpty()) return emptyList()
        val countries = loadCountries()
        return try {
            JsonParser.parseString(json).asJsonArray.mapNotNull { element ->
                val o = element.asJsonObject
                val guid = o.get("guid")?.asString.orEmpty()
                if (guid.isEmpty()) null else V2GetServer(
                    guid,
                    o.get("name")?.asString.orEmpty(),
                    o.get("protocol")?.asString.orEmpty(),
                    countries[guid].orEmpty(),
                    o.get("family")?.asString?.takeIf { it.isNotBlank() } ?: EConfigType.AETHER.name,
                )
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

    /** The same file through GitHub's API, which answers with the file as it is now and not from the raw host's cache. */
    private const val API_URL = "https://api.github.com/repos/XHTray-DB/Pattconf/contents/conf.json?ref=main"
    private const val API_URL2 = "https://api.github.com/repos/XHTray-DB/Pattconf/contents/conf2.json?ref=main"

    /**
     * Downloads the file. The raw host keeps an answer for minutes, and a filtered network may refuse it, so the
     * sources are tried one after the other and the first answer that holds a usable link wins: the raw file with a
     * query that never repeats (the cache cannot answer it), the same through the running tunnel's local proxy when
     * one is up, then the GitHub API. Blocking.
     */
    private fun download(configUrl: String = CONFIG_URL, apiUrl: String = API_URL): String {
        val stamp = System.currentTimeMillis()
        val headers = """{"Cache-Control":"no-cache, no-store","Pragma":"no-cache"}"""
        val port = SettingsManager.getHttpPort()
        val username = SettingsManager.getSocksUsername()
        val password = SettingsManager.getSocksPassword()
        val sources = listOf(
            UrlContentRequest("$configUrl?t=$stamp", timeout = 12_000, requestHeaders = headers),
            UrlContentRequest("$configUrl?t=$stamp", timeout = 12_000, httpPort = port, proxyUsername = username, proxyPassword = password, requestHeaders = headers),
            UrlContentRequest(apiUrl, timeout = 12_000, requestHeaders = """{"Accept":"application/vnd.github.raw+json","Cache-Control":"no-cache"}"""),
            UrlContentRequest(apiUrl, timeout = 12_000, httpPort = port, proxyUsername = username, proxyPassword = password, requestHeaders = """{"Accept":"application/vnd.github.raw+json","Cache-Control":"no-cache"}"""),
        )
        var failure = "no source answered"
        sources.forEachIndexed { index, request ->
            try {
                val text = HttpUtil.getUrlContentWithUserAgent(request)
                if (extractLinks(text).isNotEmpty()) return text
                failure = "source ${index + 1} held no usable link"
            } catch (e: Exception) {
                failure = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
                LogUtil.w(AppConfig.TAG, "V2GetConfigSource: source ${index + 1} failed: $failure")
            }
        }
        throw IllegalStateException(failure)
    }

    private fun cachedUris(): List<String> {
        val list = MmkvManager.decodeSettingsString(KEY_CACHE_LIST).orEmpty()
        if (list.isNotEmpty()) return extractLinks(list)
        return MmkvManager.decodeSettingsString(KEY_CACHE).orEmpty().takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
    }

    /**
     * The links of `conf2.json`, the file the repository's action builds. It is a help and never a requirement: when
     * it cannot be downloaded the links of its last good download are used, and when there are none, there are none.
     */
    private fun secondaryUris(): List<String> {
        return try {
            extractLinks(download(CONFIG2_URL, API_URL2)).also {
                MmkvManager.encodeSettings(KEY_CACHE_LIST2, it.joinToString("\n"))
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "V2GetConfigSource: conf2.json not downloaded: ${e.message.orEmpty()}")
            extractLinks(MmkvManager.decodeSettingsString(KEY_CACHE_LIST2).orEmpty())
        }
    }

    /**
     * Reads the files (`conf.json`, then `conf2.json`), builds one profile per link and keeps the chosen server selected (the first one
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
            uris = extractLinks(download())
            if (uris.isEmpty()) return "در فایل conf.json هیچ کانفیگ قابل استفاده‌ای پیدا نشد."
        } catch (e: Exception) {
            downloadError = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            LogUtil.e(AppConfig.TAG, "V2GetConfigSource: download failed", e)
        }
        if (uris.isEmpty()) {
            uris = cachedUris()
            if (uris.isEmpty()) return "دریافت کانفیگ از گیت‌هاب ناموفق بود (و نسخهٔ ذخیره‌شده‌ای وجود ندارد): $downloadError"
        }
        val primaryUris = uris
        // The links of conf.json come first; those of conf2.json follow, each server once.
        uris = (primaryUris + secondaryUris()).distinct()

        val built = LinkedHashMap<String, Pair<V2GetServer, ProfileItem>>()
        var parseError: String? = null
        uris.forEachIndexed { index, uri ->
            try {
                val profile = parseLink(uri)
                if (profile == null) {
                    parseError = parseError ?: "یکی از کانفیگ‌های فایل معتبر نیست."
                } else {
                    val server = describe(uri, index, profile)
                    built.putIfAbsent(server.guid, server to profile)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "V2GetConfigSource: link ${index + 1} not readable", e)
                parseError = parseError ?: "کانفیگ خوانده نشد: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            }
        }
        if (built.isEmpty()) return parseError ?: "هیچ کانفیگ معتبری در فایل نبود."

        return try {
            val stale = (loadServers().map { it.guid } + V2GetAuthManager.PROFILE_GUID).filter { it !in built }
            built.values.forEach { (server, profile) -> MmkvManager.encodeServerConfig(server.guid, profile) }
            stale.forEach { MmkvManager.tryRemoveServer(it) }
            MmkvManager.encodeSettings(KEY_SERVERS, encodeServers(built.values.map { it.first }))
            // A server that left the file takes its measured country with it.
            saveCountries(loadCountries().filterKeys { it in built })
            val chosen = MmkvManager.decodeSettingsString(KEY_SELECTED)
            val selected = if (chosen != null && chosen in built) chosen else built.keys.first()
            MmkvManager.encodeSettings(KEY_SELECTED, selected)
            MmkvManager.setSelectServer(selected)
            if (downloadError == null) MmkvManager.encodeSettings(KEY_CACHE_LIST, primaryUris.joinToString("\n"))
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
        MmkvManager.encodeSettings(KEY_CACHE_LIST2, "")
        MmkvManager.encodeSettings(KEY_CACHE, "")
        MmkvManager.encodeSettings(KEY_COUNTRIES, "")
    }
}
