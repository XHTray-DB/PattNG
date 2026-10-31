package com.v2ray.ang.v2get

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.OutboundTrafficStat
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The traffic of one V2GET connection, counted in the process that runs the core and reported to the panel
 * when the connection ends. The core's counters reset on every query, so each query is added up here.
 */
object V2GetTraffic {
    private val up = AtomicLong()
    private val down = AtomicLong()

    @Volatile private var sessionId: String? = null

    /** A connection of the V2GET account starts: counting begins from zero. */
    fun begin(selectedGuid: String?) {
        up.set(0)
        down.set(0)
        sessionId = if (V2GetConfigSource.isV2GetGuid(selectedGuid) && V2GetAuthManager.isLoggedIn()) {
            UUID.randomUUID().toString().replace("-", "")
        } else {
            null
        }
    }

    fun add(stats: List<OutboundTrafficStat>) {
        if (sessionId == null) return
        stats.forEach { stat ->
            // The same outbounds the speed display leaves out: the core's second dial, direct and blocked traffic.
            if (stat.tag == AppConfig.TAG_EXIT_NODE || stat.tag == AppConfig.TAG_DIRECT || stat.tag == AppConfig.TAG_BLOCKED) return@forEach
            when (stat.direction) {
                AppConfig.UPLINK -> up.addAndGet(stat.value)
                AppConfig.DOWNLINK -> down.addAndGet(stat.value)
            }
        }
    }

    /** The connection ends: its total goes to the panel, off the calling thread. */
    fun finish() {
        val id = sessionId ?: return
        sessionId = null
        val u = up.getAndSet(0)
        val d = down.getAndSet(0)
        Thread({ V2GetAuthManager.reportUsage(id, u, d) }, "v2get-usage").start()
    }
}
