package com.v2ray.ang.v2get

import com.v2ray.ang.handler.MmkvManager

/**
 * The V2GET account the app is signed in to. It lives in the settings storage `MmkvManager` owns, under
 * keys of its own; an empty token means signed out.
 */
data class V2GetSession(
    val token: String,
    val username: String,
    val quotaBytes: Long,
    val usedBytes: Long,
    val expiresAt: String,
) {
    companion object {
        private const val KEY_TOKEN = "V2GET_USER_TOKEN"
        private const val KEY_USERNAME = "V2GET_USERNAME"
        private const val KEY_QUOTA = "V2GET_QUOTA"
        private const val KEY_USED = "V2GET_USED"
        private const val KEY_EXPIRES = "V2GET_EXPIRES"

        fun load(): V2GetSession? {
            val token = MmkvManager.decodeSettingsString(KEY_TOKEN).orEmpty()
            if (token.isEmpty()) return null
            return V2GetSession(
                token = token,
                username = MmkvManager.decodeSettingsString(KEY_USERNAME).orEmpty(),
                quotaBytes = MmkvManager.decodeSettingsLong(KEY_QUOTA, 0L),
                usedBytes = MmkvManager.decodeSettingsLong(KEY_USED, 0L),
                expiresAt = MmkvManager.decodeSettingsString(KEY_EXPIRES).orEmpty(),
            )
        }

        /**
         * The account as the screens show it: the panel's figure plus what this phone counted since, so that the
         * usage is the app's own count, up to date at once, and the panel only corrects its base. Read in any process.
         */
        fun loadLive(): V2GetSession? {
            val session = load() ?: return null
            return session.copy(usedBytes = session.usedBytes + V2GetTraffic.unconfirmedBytes())
        }

        fun save(session: V2GetSession) {
            MmkvManager.encodeSettings(KEY_USERNAME, session.username)
            MmkvManager.encodeSettings(KEY_QUOTA, session.quotaBytes)
            MmkvManager.encodeSettings(KEY_USED, session.usedBytes)
            MmkvManager.encodeSettings(KEY_EXPIRES, session.expiresAt)
            // The token goes last: a session without one counts as signed out, so a refused write never
            // leaves a half-saved account looking signed in.
            MmkvManager.encodeSettings(KEY_TOKEN, session.token)
        }

        fun clear() {
            MmkvManager.encodeSettings(KEY_TOKEN, "")
            MmkvManager.encodeSettings(KEY_USERNAME, "")
            MmkvManager.encodeSettings(KEY_QUOTA, 0L)
            MmkvManager.encodeSettings(KEY_USED, 0L)
            MmkvManager.encodeSettings(KEY_EXPIRES, "")
        }
    }
}
