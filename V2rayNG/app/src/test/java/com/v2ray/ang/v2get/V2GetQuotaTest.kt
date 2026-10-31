package com.v2ray.ang.v2get

import com.v2ray.ang.v2get.V2GetQuota.Block
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class V2GetQuotaTest {

    private val today = LocalDate.of(2026, 10, 31)

    private fun session(quota: Long, used: Long, expires: String) =
        V2GetSession("t", "u", quota, used, expires)

    @Test
    fun anAccountWithRoomAndTimeMayConnect() {
        assertEquals(Block.NONE, V2GetQuota.blockReason(session(100, 10, "2026-12-31"), today))
        assertEquals(Block.NONE, V2GetQuota.blockReason(session(100, 10, "2026-10-31"), today))
    }

    @Test
    fun aSpentQuotaBlocks() {
        assertEquals(Block.QUOTA_EXHAUSTED, V2GetQuota.blockReason(session(100, 100, ""), today))
        assertEquals(Block.QUOTA_EXHAUSTED, V2GetQuota.blockReason(session(100, 250, "2026-12-31"), today))
    }

    @Test
    fun noQuotaFigureMeansNoLimit() {
        assertEquals(Block.NONE, V2GetQuota.blockReason(session(0, 999, ""), today))
        assertEquals(0f, V2GetQuota.usedFraction(session(0, 999, "")))
    }

    @Test
    fun anExpiredAccountBlocksAndAnUnreadableDateDoesNot() {
        assertEquals(Block.EXPIRED, V2GetQuota.blockReason(session(100, 1, "2026-10-30"), today))
        assertEquals(Block.EXPIRED, V2GetQuota.blockReason(session(100, 1, "2026-10-30T23:59:00Z"), today))
        assertEquals(Block.NONE, V2GetQuota.blockReason(session(100, 1, "soon"), today))
    }

    @Test
    fun remainingAndFractionStayInRange() {
        assertEquals(90L, V2GetQuota.remainingBytes(session(100, 10, "")))
        assertEquals(0L, V2GetQuota.remainingBytes(session(100, 250, "")))
        assertEquals(1f, V2GetQuota.usedFraction(session(100, 250, "")))
        assertEquals(0.1f, V2GetQuota.usedFraction(session(100, 10, "")))
    }

    @Test
    fun aSmallUseShowsInMegabytesNotAsAWholeGigabyte() {
        // 40 MB down and 10 MB up: the screen must read about 50 MB, never 0.0 or 0.1 GB.
        val fiftyMb = 50L * 1024 * 1024
        assertEquals("50.0 MB", V2GetQuota.formatBytes(fiftyMb))
        assertEquals("1.50 GB", V2GetQuota.formatBytes(1536L * 1024 * 1024))
        assertEquals("900 KB", V2GetQuota.formatBytes(900L * 1024))
        assertEquals("12 B", V2GetQuota.formatBytes(12))
        assertEquals("0 B", V2GetQuota.formatBytes(-5))
    }

    @Test
    fun theRemainingVolumeShrinksByTheBytesUsed() {
        val gb = 1024L * 1024 * 1024
        val fiftyMb = 50L * 1024 * 1024
        assertEquals(50 * gb - fiftyMb, V2GetQuota.remainingBytes(session(50 * gb, fiftyMb, "")))
    }
}
