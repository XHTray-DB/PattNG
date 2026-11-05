package com.v2ray.ang.v2get

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

/**
 * The switches of the V2GET settings screen, each stored where the rest of the app already reads it, so a switch
 * here changes the same setting the full settings screen shows.
 */
object V2GetSettings {

    /** Zero-Drop: when a connection fails to start, the next server is tried. On until switched off. */
    const val KEY_ZERO_DROP = "V2GET_ZERO_DROP"

    fun zeroDrop(): Boolean = MmkvManager.decodeSettingsBool(KEY_ZERO_DROP, true)

    fun setZeroDrop(on: Boolean) {
        MmkvManager.encodeSettings(KEY_ZERO_DROP, on)
    }

    /** Auto-select: a connection started with the button measures the servers and goes to the best. On until switched off. */
    const val KEY_AUTO_BEST = "V2GET_AUTO_BEST"

    fun autoBest(): Boolean = MmkvManager.decodeSettingsBool(KEY_AUTO_BEST, true)

    fun setAutoBest(on: Boolean) {
        MmkvManager.encodeSettings(KEY_AUTO_BEST, on)
    }

    /** The server of the last connection that came up: the next start tries it first, so a good server costs no search. */
    const val KEY_LAST_GOOD = "V2GET_LAST_GOOD_SERVER"

    fun lastGood(): String? = MmkvManager.decodeSettingsString(KEY_LAST_GOOD)?.takeIf { it.isNotBlank() }

    fun setLastGood(guid: String) {
        MmkvManager.encodeSettings(KEY_LAST_GOOD, guid)
    }

    /** Split tunneling is the per-app proxy of the app: only the chosen apps (or all but them) use the tunnel. */
    fun perAppProxy(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)

    /** The DNS protection switch of the settings tab: on until switched off. */
    const val KEY_DNS_PROTECTED = "V2GET_DNS_PROTECTED"

    /**
     * DNS leak protection: the remote DNS the core asks through the tunnel is an encrypted (https) address. It is a
     * switch of its own, so it can be turned off and on; the local DNS and the fake DNS stay off either way.
     */
    fun dnsProtected(): Boolean =
        MmkvManager.decodeSettingsBool(KEY_DNS_PROTECTED, true) &&
            isEncryptedDns(MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_DNS))

    /** Blank is the app's default, an encrypted address. */
    internal fun isEncryptedDns(remote: String?): Boolean =
        remote.isNullOrBlank() || remote.split(",").all { it.trim().startsWith("https") }

    /**
     * Takes effect at the next connection. On sets the encrypted remote DNS; off sets a plain one (Google's address,
     * through the tunnel), which the core answers without DNS over HTTPS.
     */
    fun setDnsProtected(on: Boolean) {
        MmkvManager.encodeSettings(KEY_DNS_PROTECTED, on)
        MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, if (on) AppConfig.DNS_PROXY else AppConfig.DNS_VPN)
    }
}
