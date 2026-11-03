package com.v2ray.ang.v2get

import android.content.Context
import android.content.Intent
import com.google.gson.JsonParser
import com.v2ray.ang.v2get.ui.V2GetUpdateActivity

/** What the panel says about the newest app: sent with a 426 "update_required" answer. */
data class V2GetUpdateInfo(val version: String, val url: String, val notes: String) {
    companion object {
        /** Reads the 426 body; an unreadable body still stops the app, only with no link to offer. */
        fun parse(body: String?): V2GetUpdateInfo {
            val root = try {
                JsonParser.parseString(body.orEmpty()).takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: Exception) {
                null
            }
            fun text(name: String) = root?.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            return V2GetUpdateInfo(text("latest_version"), text("update_url"), text("notes"))
        }
    }

    /** Opens the update screen over everything else; it cannot be left except by updating. */
    fun show(context: Context) {
        context.startActivity(
            Intent(context, V2GetUpdateActivity::class.java)
                .putExtra(V2GetUpdateActivity.EXTRA_VERSION, version)
                .putExtra(V2GetUpdateActivity.EXTRA_URL, url)
                .putExtra(V2GetUpdateActivity.EXTRA_NOTES, notes)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}
