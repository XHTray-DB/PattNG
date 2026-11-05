package com.v2ray.ang.v2get

import com.v2ray.ang.ui.main.MainServiceEvent
import com.v2ray.ang.v2get.ui.V2GetConnection
import com.v2ray.ang.v2get.ui.V2GetNotice
import com.v2ray.ang.v2get.ui.V2GetPhase
import com.v2ray.ang.v2get.ui.V2GetUiState
import com.v2ray.ang.v2get.ui.V2GetViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class V2GetViewModelTest {

    private fun reduce(state: V2GetUiState, event: MainServiceEvent) = V2GetViewModel.reduce(state, event)

    @Test
    fun theConnectionFollowsWhatTheDaemonReports() {
        val idle = V2GetUiState()
        assertEquals(V2GetConnection.Connected, reduce(idle, MainServiceEvent.StateRunning).connection)
        assertEquals(V2GetConnection.Connected, reduce(idle, MainServiceEvent.StateStartSuccess).connection)
        assertEquals(V2GetConnection.Connecting, reduce(idle, MainServiceEvent.StateConnecting("scanning")).connection)

        val up = idle.copy(connection = V2GetConnection.Connected)
        assertEquals(V2GetConnection.Disconnected, reduce(up, MainServiceEvent.StateNotRunning).connection)
        assertEquals(V2GetConnection.Disconnected, reduce(up, MainServiceEvent.StateStopSuccess).connection)
    }

    @Test
    fun aFailedStartEndsConnectingAndSaysWhy() {
        val connecting = V2GetUiState(connection = V2GetConnection.Connecting)
        val failed = reduce(connecting, MainServiceEvent.StateStartFailure("no route"))
        assertEquals(V2GetConnection.Disconnected, failed.connection)
        assertEquals(V2GetNotice.StartFailure("no route"), failed.notice)
        assertEquals(V2GetNotice.StartFailure(""), reduce(connecting, MainServiceEvent.StateStartFailure(null)).notice)
    }

    @Test
    fun anEventNotAboutTheServiceChangesNothing() {
        val up = V2GetUiState(connection = V2GetConnection.Connected)
        assertEquals(up, reduce(up, MainServiceEvent.ServersChanged))
    }

    @Test
    fun aStateQueryDoesNotEndAStartThatIsStillMeasuringServers() {
        val measuring = V2GetUiState(connection = V2GetConnection.Connecting, phase = V2GetPhase.Tcping)
        assertEquals(measuring, reduce(measuring, MainServiceEvent.StateNotRunning))
    }

    private fun server(guid: String, family: String = "VLESS") = V2GetServer(guid, guid, "", family = family)

    @Test
    fun theTcpStepKeepsTheServersThatAnsweredAndThoseItCannotJudge() {
        val servers = listOf(server("a"), server("b"), server("c", "HYSTERIA2"), server("d"))
        val tcp = mapOf("a" to 40L, "b" to -1L, "c" to -1L, "d" to 0L)
        assertEquals(listOf("a", "c"), V2GetViewModel.tcpSurvivors(servers, tcp))
    }

    @Test
    fun whenEveryTcpTestFailedEveryServerStays() {
        val servers = listOf(server("a"), server("b"))
        assertEquals(listOf("a", "b"), V2GetViewModel.tcpSurvivors(servers, mapOf("a" to -1L, "b" to -1L)))
    }

    @Test
    fun theBestServerHasTheLowestRealDelayAmongTheSurvivors() {
        val servers = listOf(server("a"), server("b"), server("c"))
        val tcp = mapOf("a" to 10L, "b" to 50L, "c" to -1L)
        // c failed the TCP step, so its good real delay is never looked at.
        val real = mapOf("a" to 900L, "b" to 300L, "c" to 5L)
        assertEquals("b", V2GetViewModel.bestServer(servers, tcp, real))
    }

    @Test
    fun withoutRealDelaysTheLowestTcpTimeWins() {
        val servers = listOf(server("a"), server("b"))
        assertEquals("b", V2GetViewModel.bestServer(servers, mapOf("a" to 80L, "b" to 20L), mapOf("a" to -1L, "b" to -1L)))
    }

    @Test
    fun withNothingMeasuredTheFirstServerIsKept() {
        val servers = listOf(server("a"), server("b"))
        assertEquals("a", V2GetViewModel.bestServer(servers, emptyMap(), emptyMap()))
        assertEquals(null, V2GetViewModel.bestServer(emptyList(), emptyMap(), emptyMap()))
    }

    @Test
    fun theRealTestGoesFastestTcpFirstAndUdpServersLast() {
        val servers = listOf(server("a"), server("b"), server("h", "HYSTERIA2"), server("c"), server("dead"))
        val tcp = mapOf("a" to 90L, "b" to 20L, "c" to 55L, "dead" to -1L)
        assertEquals(listOf("b", "c", "a", "h"), V2GetViewModel.realTestOrder(servers, tcp))
    }

    @Test
    fun whenEveryTcpTestFailedEveryServerStillGetsTheRealTestInListOrder() {
        val servers = listOf(server("a"), server("b"))
        assertEquals(listOf("a", "b"), V2GetViewModel.realTestOrder(servers, mapOf("a" to -1L, "b" to -1L)))
    }

    @Test
    fun theReplacementIsTheFastestServerThatWorkedAndNeverOneThatFailed() {
        val servers = listOf(server("a"), server("b"), server("c"))
        val delays = mapOf("a" to -1L, "b" to 300L, "c" to 120L)
        assertEquals("c", V2GetViewModel.nextAlternative(servers, setOf("a"), delays))
        assertEquals("b", V2GetViewModel.nextAlternative(servers, setOf("a", "c"), delays))
    }

    @Test
    fun withoutAnyTestResultTheReplacementIsTheFirstServerNotFailed() {
        val servers = listOf(server("a"), server("b"))
        assertEquals("b", V2GetViewModel.nextAlternative(servers, setOf("a"), emptyMap()))
        assertEquals(null, V2GetViewModel.nextAlternative(servers, setOf("a", "b"), emptyMap()))
    }

    @Test
    fun anAetherServerHasNoRealTestAndOthersDo() {
        assertEquals(false, V2GetViewModel.needsRealTest(server("a", "AETHER")))
        assertEquals(true, V2GetViewModel.needsRealTest(server("b", "VLESS")))
        assertEquals(true, V2GetViewModel.needsRealTest(server("c", "HYSTERIA2")))
    }

    @Test
    fun aNotRunningAnswerWhileAStartIsInFlightDoesNotEndIt() {
        val connecting = V2GetUiState(connection = V2GetConnection.Connecting)
        assertEquals(connecting, V2GetViewModel.reduce(connecting, MainServiceEvent.StateNotRunning, startInFlight = true))
    }

    @Test
    fun aNotRunningAnswerWithNoStartInFlightStillEndsTheConnection() {
        val connecting = V2GetUiState(connection = V2GetConnection.Connecting)
        assertEquals(
            V2GetConnection.Disconnected,
            V2GetViewModel.reduce(connecting, MainServiceEvent.StateNotRunning, startInFlight = false).connection,
        )
    }

    @Test
    fun aStartInFlightDoesNotHideARunningConnectionStopping() {
        val up = V2GetUiState(connection = V2GetConnection.Connected)
        assertEquals(
            V2GetConnection.Disconnected,
            V2GetViewModel.reduce(up, MainServiceEvent.StateNotRunning, startInFlight = true).connection,
        )
    }

    @Test
    fun theFastestAnsweringAetherServerWinsWithoutAnyOtherTest() {
        val servers = listOf(server("v"), server("a1", "AETHER"), server("a2", "AETHER"), server("a3", "AETHER"))
        val tcp = mapOf("v" to 5L, "a1" to 90L, "a2" to 30L, "a3" to -1L)
        assertEquals("a2", V2GetViewModel.pickAetherWinner(servers, tcp))
    }

    @Test
    fun withoutAnAnsweringAetherServerThereIsNoAetherWinner() {
        val servers = listOf(server("v"), server("a1", "AETHER"))
        assertEquals(null, V2GetViewModel.pickAetherWinner(servers, mapOf("v" to 5L, "a1" to -1L)))
        assertEquals(null, V2GetViewModel.pickAetherWinner(listOf(server("v")), mapOf("v" to 5L)))
    }
}
