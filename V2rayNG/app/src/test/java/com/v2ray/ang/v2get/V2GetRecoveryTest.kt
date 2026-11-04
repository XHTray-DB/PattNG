package com.v2ray.ang.v2get

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class V2GetRecoveryTest {

    @Before
    fun clean() = V2GetRecovery.reset()

    @Test
    fun aLostTunnelIsRecognised() {
        assertTrue(V2GetRecovery.looksLikeLoss("[2026-09-11T10:00:00.000Z WARN  aether] [-] tunnel ended"))
        assertTrue(V2GetRecovery.looksLikeLoss("[2026-09-11T10:00:00.000Z ERROR aether] [-] io: Connection reset by peer (os error 104)"))
        assertTrue(V2GetRecovery.looksLikeLoss("[2026-09-11T10:00:00.000Z ERROR aether] [-] handshake timed out"))
    }

    @Test
    fun otherLinesAreNot() {
        assertFalse(V2GetRecovery.looksLikeLoss("[2026-09-11T10:00:00.000Z WARN  aether] [-] scan deadline reached"))
        assertFalse(V2GetRecovery.looksLikeLoss("[2026-09-11T10:00:00.000Z INFO  aether] [+] tunnel ended"))
        assertFalse(V2GetRecovery.looksLikeLoss("[2026-09-11T10:00:00.000Z WARN  aether::psiphon] [-] psiphon lost its tunnel; it is reconnecting"))
        assertFalse(V2GetRecovery.looksLikeLoss("tunnel ended"))
    }

    @Test
    fun theWaitGrowsAndTheBudgetEnds() {
        val waits = (0 until V2GetRecovery.MAX_IN_WINDOW).map { V2GetRecovery.take(1_000L)!! }
        assertEquals(listOf(400L, 800L, 1_600L, 3_200L, 6_000L, 6_000L), waits)
        assertNull(V2GetRecovery.take(2_000L))
    }

    @Test
    fun theBudgetComesBackAfterTheWindow() {
        repeat(V2GetRecovery.MAX_IN_WINDOW) { V2GetRecovery.take(0L) }
        assertNull(V2GetRecovery.take(1L))
        assertNotNull(V2GetRecovery.take(V2GetRecovery.WINDOW_MS + 1))
    }

    @Test
    fun aRecentTryIsTold() {
        assertFalse(V2GetRecovery.recent(0L))
        V2GetRecovery.take(10_000L)
        assertTrue(V2GetRecovery.recent(20_000L))
        assertFalse(V2GetRecovery.recent(10_000L + V2GetRecovery.RECENT_MS + 1))
    }
}
