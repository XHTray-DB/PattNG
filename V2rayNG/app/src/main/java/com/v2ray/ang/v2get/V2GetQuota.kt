package com.v2ray.ang.v2get

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** What an account's quota and expiry allow, as plain functions so that they can be tested without Android. */
object V2GetQuota {

    const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

    enum class Block { NONE, QUOTA_EXHAUSTED, EXPIRED }

    fun remainingBytes(session: V2GetSession): Long = (session.quotaBytes - session.usedBytes).coerceAtLeast(0L)

    /** The share of the quota used, 0..1; 0 when the account has no quota figure. */
    fun usedFraction(session: V2GetSession): Float =
        if (session.quotaBytes > 0) (session.usedBytes.toDouble() / session.quotaBytes).toFloat().coerceIn(0f, 1f) else 0f

    fun toGigabytes(bytes: Long): Double = bytes / BYTES_PER_GB

    /**
     * A byte count in the unit that reads best, in the same binary units the quota is counted in (1 GB is 1024^3 bytes),
     * with enough digits that a small use shows: 48.9 MB, 1.23 GB. Never rounds a use up to a whole gigabyte.
     */
    fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        return when {
            value >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", value / BYTES_PER_GB)
            value >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
            value >= 1024L -> String.format(java.util.Locale.US, "%d KB", value / 1024L)
            else -> String.format(java.util.Locale.US, "%d B", value)
        }
    }

    /**
     * Why a connection should not start. A quota of 0 means the panel set no limit; an expiry that is not
     * an ISO date (yyyy-MM-dd, optionally followed by a time) is not enforced here, the panel decides then.
     */
    fun blockReason(session: V2GetSession, today: LocalDate = LocalDate.now()): Block {
        if (session.quotaBytes > 0 && session.usedBytes >= session.quotaBytes) return Block.QUOTA_EXHAUSTED
        val expires = parseDate(session.expiresAt)
        if (expires != null && today.isAfter(expires)) return Block.EXPIRED
        return Block.NONE
    }

    internal fun parseDate(text: String): LocalDate? {
        val day = text.trim().take(10)
        return try {
            LocalDate.parse(day)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
