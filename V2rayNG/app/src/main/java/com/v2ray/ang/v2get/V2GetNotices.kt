package com.v2ray.ang.v2get

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A message the panel shows to every account (set with one curl or on the admin page). [id] is what "dismissed" remembers. */
data class V2GetAnnouncement(val id: Long, val text: String, val url: String)

enum class V2GetAlertKind { QUOTA_LOW, QUOTA_ALMOST, EXPIRY_SOON }

/** What the account's figures call for now: [percentLeft] for the quota kinds, [daysLeft] for [V2GetAlertKind.EXPIRY_SOON]. */
data class V2GetAlert(val kind: V2GetAlertKind, val percentLeft: Int = 0, val daysLeft: Int = 0)

/**
 * The notices of the V2GET screens: the panel's announcement, and the warnings that the quota is nearly used or the
 * subscription nearly over. The warnings show as a card on the main screen and, once for each level, as a
 * notification that leads to the support chat, so the account is renewed before it is cut.
 */
object V2GetNotices {
    private const val KEY_ANNOUNCEMENT = "V2GET_ANNOUNCEMENT"
    private const val KEY_DISMISSED = "V2GET_ANNOUNCEMENT_DISMISSED"
    private const val KEY_NOTIFIED = "V2GET_ALERT_NOTIFIED"
    private const val CHANNEL_ID = "v2get_alerts"
    private const val NOTIFICATION_ID = 4410

    /** Keeps the announcement the panel sent with the account, or forgets it when it sent none. */
    fun saveAnnouncement(element: JsonElement?) {
        val obj: JsonObject? = element?.takeIf { it.isJsonObject }?.asJsonObject
        val text = obj?.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim()
        if (obj == null || text.isEmpty()) {
            MmkvManager.encodeSettings(KEY_ANNOUNCEMENT, "")
            return
        }
        val saved = JsonObject().apply {
            addProperty("id", obj.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L)
            addProperty("text", text)
            addProperty("url", obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
        }
        MmkvManager.encodeSettings(KEY_ANNOUNCEMENT, saved.toString())
    }

    /** The announcement that has not been dismissed on this phone, or null. */
    fun announcement(): V2GetAnnouncement? {
        val raw = MmkvManager.decodeSettingsString(KEY_ANNOUNCEMENT).orEmpty()
        if (raw.isEmpty()) return null
        val found = try {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            V2GetAnnouncement(obj.get("id").asLong, obj.get("text").asString, obj.get("url").asString)
        } catch (_: Exception) {
            return null
        }
        return found.takeIf { it.id != MmkvManager.decodeSettingsLong(KEY_DISMISSED, -1L) }
    }

    fun dismiss(id: Long) {
        MmkvManager.encodeSettings(KEY_DISMISSED, id)
    }

    /**
     * What the figures of [session] call for on [today], or null. The order is the urgency: the quota nearly gone,
     * the subscription ending in three days, then the quota past four fifths. An account already out of quota or
     * time is not here: it has the blocked screen.
     */
    fun alertFor(session: V2GetSession?, today: LocalDate): V2GetAlert? {
        if (session == null) return null
        val daysLeft = V2GetQuota.parseDate(session.expiresAt)?.let { ChronoUnit.DAYS.between(today, it) }
        if (daysLeft != null && daysLeft < 0) return null
        val used = if (session.quotaBytes > 0) session.usedBytes.toDouble() / session.quotaBytes else 0.0
        if (session.quotaBytes > 0 && used >= 1.0) return null
        val percentLeft = ((1.0 - used) * 100).toInt().coerceIn(0, 100)
        if (session.quotaBytes > 0 && used >= 0.9) return V2GetAlert(V2GetAlertKind.QUOTA_ALMOST, percentLeft = percentLeft)
        if (daysLeft != null && daysLeft <= 3) return V2GetAlert(V2GetAlertKind.EXPIRY_SOON, daysLeft = daysLeft.toInt())
        if (session.quotaBytes > 0 && used >= 0.8) return V2GetAlert(V2GetAlertKind.QUOTA_LOW, percentLeft = percentLeft)
        return null
    }

    fun alertText(context: Context, alert: V2GetAlert): String = when (alert.kind) {
        V2GetAlertKind.QUOTA_ALMOST -> context.getString(R.string.v2get_alert_quota_almost, alert.percentLeft)
        V2GetAlertKind.QUOTA_LOW -> context.getString(R.string.v2get_alert_quota_low, 100 - alert.percentLeft)
        V2GetAlertKind.EXPIRY_SOON ->
            if (alert.daysLeft <= 0) context.getString(R.string.v2get_alert_expiry_today)
            else context.getString(R.string.v2get_alert_expiry_days, alert.daysLeft)
    }

    /** True once for each kind of alert and each subscription period: the same warning is not posted again and again. */
    private fun firstTimeFor(alert: V2GetAlert, session: V2GetSession): Boolean {
        val stamp = "${alert.kind}|${session.expiresAt}|${session.quotaBytes}"
        val seen = MmkvManager.decodeSettingsString(KEY_NOTIFIED).orEmpty().split(';').filter { it.isNotEmpty() }
        if (stamp in seen) return false
        // Only the kinds of this period are kept; an older period's stamps would only pile up.
        val keep = seen.filter { it.endsWith("|${session.expiresAt}|${session.quotaBytes}") } + stamp
        MmkvManager.encodeSettings(KEY_NOTIFIED, keep.joinToString(";"))
        return true
    }

    /** Posts the notification of [alert] the first time it holds for this period; a tap opens the support chat. */
    @SuppressLint("MissingPermission")
    fun notifyOnce(context: Context, session: V2GetSession, alert: V2GetAlert) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || !firstTimeFor(alert, session)) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.v2get_alert_channel), NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/axis_developer")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = alertText(context, alert)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(context.getString(R.string.v2get_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
