package com.v2ray.ang.v2get

import com.v2ray.ang.ui.main.MainServiceEvent
import com.v2ray.ang.v2get.ui.V2GetConnection
import com.v2ray.ang.v2get.ui.V2GetNotice
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
}
