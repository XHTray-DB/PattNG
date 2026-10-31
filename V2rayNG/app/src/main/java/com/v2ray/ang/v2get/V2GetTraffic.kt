package com.v2ray.ang.v2get

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.handler.MmkvManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The traffic of one V2GET connection, counted in the process that runs the core and reported to the panel
 * while the connection runs and when it ends. The core's counters reset on every query, so each query is added up here.
 *
 * The figures the screens show (this connection's totals and its speed) are written to the settings storage, which
 * both processes share: the screens run in another process than the core and cannot read this object.
 */
object V2GetTraffic {
    /** What the core counted since the last report to the panel. */
    private val up = AtomicLong()
    private val down = AtomicLong()

    /** What the connection has used in all, for the screens. */
    private val totalUp = AtomicLong()
    private val totalDown = AtomicLong()

    private val seq = AtomicInteger()

    @Volatile private var sessionId: String? = null
    @Volatile private var lastAddNanos = 0L
    @Volatile private var lastReportNanos = 0L

    private const val KEY_LIVE = "V2GET_LIVE_TRAFFIC"

    /** What the core counted and has not yet been written into a report; the screens add it to the panel's figure. */
    private const val KEY_UNREPORTED = "V2GET_UNREPORTED_BYTES"

    /** A connection's usage is sent to the panel at least this often, so a long connection, or one the system kills, is not lost. */
    private const val REPORT_INTERVAL_NANOS = 15_000_000_000L

    /** The figures of the running connection: bytes used so far and the speed in bytes per second. */
    data class Live(val up: Long = 0, val down: Long = 0, val upSpeed: Long = 0, val downSpeed: Long = 0)

    /** The core counts traffic while an account is signed in; the usage the panel is told comes from these counters. */
    fun statsNeeded(): Boolean = V2GetAuthManager.isLoggedIn()

    /** A connection of the V2GET account starts: counting begins from zero. */
    @Synchronized
    fun begin(selectedGuid: String?) {
        up.set(0)
        down.set(0)
        totalUp.set(0)
        totalDown.set(0)
        seq.set(0)
        lastAddNanos = System.nanoTime()
        lastReportNanos = lastAddNanos
        writeLive(Live())
        writeUnreported(0)
        sessionId = if (V2GetConfigSource.isV2GetGuid(selectedGuid) && V2GetAuthManager.isLoggedIn()) {
            UUID.randomUUID().toString().replace("-", "")
        } else {
            null
        }
    }

    @Synchronized
    fun add(stats: List<OutboundTrafficStat>) {
        if (sessionId == null) return
        var dUp = 0L
        var dDown = 0L
        stats.forEach { stat ->
            // The same outbounds the speed display leaves out: the core's second dial, direct and blocked traffic.
            if (stat.tag == AppConfig.TAG_EXIT_NODE || stat.tag == AppConfig.TAG_DIRECT || stat.tag == AppConfig.TAG_BLOCKED) return@forEach
            when (stat.direction) {
                AppConfig.UPLINK -> dUp += stat.value
                AppConfig.DOWNLINK -> dDown += stat.value
            }
        }
        val now = System.nanoTime()
        val seconds = ((now - lastAddNanos) / 1e9).coerceAtLeast(0.5)
        lastAddNanos = now
        up.addAndGet(dUp)
        down.addAndGet(dDown)
        writeLive(Live(totalUp.addAndGet(dUp), totalDown.addAndGet(dDown), (dUp / seconds).toLong(), (dDown / seconds).toLong()))
        // Counted here, in the app, and shown at once: the screens do not wait for the panel to answer.
        writeUnreported(up.get() + down.get())
        if (now - lastReportNanos >= REPORT_INTERVAL_NANOS) {
            lastReportNanos = now
            sendDelta(sessionId ?: return)
        }
    }

    /** The connection ends: the rest of its usage goes to the panel, off the calling thread. */
    @Synchronized
    fun finish() {
        val id = sessionId ?: return
        sessionId = null
        writeLive(Live())
        sendDelta(id)
    }

    /** What the running connection has used, read in any process. */
    fun live(): Live {
        val parts = MmkvManager.decodeSettingsString(KEY_LIVE).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        return if (parts.size == 4) Live(parts[0], parts[1], parts[2], parts[3]) else Live()
    }

    /**
     * The bytes this phone counted that the panel's figure does not hold yet: the ones not yet written into a report,
     * and the ones in reports still waiting. Read in any process.
     */
    fun unconfirmedBytes(): Long =
        (MmkvManager.decodeSettingsLong(KEY_UNREPORTED, 0L) + V2GetAuthManager.pendingBytes()).coerceAtLeast(0L)

    private fun writeUnreported(bytes: Long) {
        MmkvManager.encodeSettings(KEY_UNREPORTED, bytes)
    }

    private fun writeLive(live: Live) {
        MmkvManager.encodeSettings(KEY_LIVE, "${live.up},${live.down},${live.upSpeed},${live.downSpeed}")
    }

    /** Takes what was counted since the last report and sends it, with the next number of the session's reports. */
    private fun sendDelta(id: String) {
        val u = up.getAndSet(0)
        val d = down.getAndSet(0)
        val number = seq.getAndIncrement()
        // The report is written down first and only then taken off the live counters, so the usage shown never dips.
        V2GetAuthManager.queueUsage(id, number, u, d)
        writeUnreported(0)
        Thread({ V2GetAuthManager.flushPending() }, "v2get-usage").start()
    }
}
