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

    /** Split tunneling is the per-app proxy of the app: only the chosen apps (or all but them) use the tunnel. */
    fun perAppProxy(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)

    /**
     * DNS leak protection holds when the core answers every name itself through the tunnel: the local DNS and the
     * fake DNS are on and the remote DNS is an encrypted (https) address. The app's defaults already are that.
     */
    fun dnsProtected(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED, true) &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED, true) &&
            isEncryptedDns(MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_DNS))

    /** Blank is the app's default, an encrypted address. */
    internal fun isEncryptedDns(remote: String?): Boolean =
        remote.isNullOrBlank() || remote.split(",").all { it.trim().startsWith("https") }

    /** Takes effect at the next connection. Turning it off only stops the fake DNS; the rest stays as it is. */
    fun setDnsProtected(on: Boolean) {
        if (on) {
            MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, true)
            MmkvManager.encodeSettings(AppConfig.PREF_FAKE_DNS_ENABLED, true)
            if (!isEncryptedDns(MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_DNS))) {
                MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, AppConfig.DNS_PROXY)
            }
        } else {
            MmkvManager.encodeSettings(AppConfig.PREF_FAKE_DNS_ENABLED, false)
        }
    }
}
