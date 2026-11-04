package com.v2ray.ang.v2get

import android.content.Context
import android.content.Intent
import com.v2ray.ang.AngApplication
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.v2get.ui.V2GetBlockedActivity

/** Why the panel will not let this account use the service. */
enum class V2GetBlockReason { REMOVED, DISABLED, EXPIRED, EXHAUSTED }

/**
 * An account the panel refuses (deleted, blocked, ended, out of quota). The reason is kept in the settings storage,
 * which the screens and the core's process share, so a refusal found by a usage report in the background is shown
 * the next time the app is in front, and the tunnel is stopped at once wherever it was found.
 */
object V2GetBlock {
    private const val KEY = "V2GET_BLOCK_REASON"

    /** The reason from the `status` the panel sends with the account; null when the account is active. */
    fun fromStatus(status: String?): V2GetBlockReason? = when (status) {
        "disabled" -> V2GetBlockReason.DISABLED
        "expired" -> V2GetBlockReason.EXPIRED
        "exhausted" -> V2GetBlockReason.EXHAUSTED
        else -> null
    }

    /** The reason from the `error` of a 403 answer; null when the 403 is something else. */
    fun fromError(error: String?): V2GetBlockReason? = when (error) {
        "account_removed" -> V2GetBlockReason.REMOVED
        "account_disabled" -> V2GetBlockReason.DISABLED
        else -> null
    }

    fun pending(): V2GetBlockReason? =
        runCatching { V2GetBlockReason.valueOf(MmkvManager.decodeSettingsString(KEY).orEmpty()) }.getOrNull()

    fun clear() {
        MmkvManager.encodeSettings(KEY, "")
    }

    /** Remembers the reason and stops the tunnel, from any process, without showing anything. */
    fun enforce(reason: V2GetBlockReason) {
        MmkvManager.encodeSettings(KEY, reason.name)
        runCatching { LauncherManager.stopService(AngApplication.application) }
    }

    /** Stops the tunnel, closes the other screens of the app and shows the blocked screen. */
    fun show(context: Context, reason: V2GetBlockReason) {
        enforce(reason)
        context.startActivity(
            Intent(context, V2GetBlockedActivity::class.java)
                .putExtra(V2GetBlockedActivity.EXTRA_REASON, reason.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }
}
