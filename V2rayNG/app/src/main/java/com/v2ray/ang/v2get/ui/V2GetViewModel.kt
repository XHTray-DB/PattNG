package com.v2ray.ang.v2get.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.ui.main.MainDataSource
import com.v2ray.ang.v2get.V2GetAuthManager
import com.v2ray.ang.v2get.V2GetConfigSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.v2ray.ang.ui.main.MainServiceEvent
import com.v2ray.ang.v2get.V2GetQuota
import com.v2ray.ang.v2get.V2GetSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class V2GetConnection { Disconnected, Connecting, Connected }

/** A one-off message for the screen to show once, then [V2GetViewModel.noticeShown]. */
sealed class V2GetNotice {
    data object QuotaExhausted : V2GetNotice()
    data object Expired : V2GetNotice()
    data class StartFailure(val reason: String) : V2GetNotice()
    /** A failure before the core starts (account check, configuration download); shown in a dialog like a start failure. */
    data class Failure(val reason: String) : V2GetNotice()
}

data class V2GetUiState(
    val session: V2GetSession? = null,
    val connection: V2GetConnection = V2GetConnection.Disconnected,
    val notice: V2GetNotice? = null,
)

/**
 * The state of the V2GET screens. The daemon process alone knows whether the core runs, so the connection
 * follows what it reports through [dataSource], never what a tap on the button asked for.
 */
class V2GetViewModel(private val dataSource: MainDataSource) : ViewModel() {

    private val _uiState = MutableStateFlow(V2GetUiState(session = V2GetSession.load()))
    val uiState: StateFlow<V2GetUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event -> _uiState.update { reduce(it, event) } }
        }
        dataSource.queryServiceState()
    }

    fun refresh() {
        _uiState.update { it.copy(session = V2GetSession.load()) }
        dataSource.queryServiceState()
    }

    /**
     * Whether a connection may start; when it may not, the notice says why. A start that is allowed shows
     * as connecting until the daemon reports otherwise.
     */
    fun beginStart(): Boolean {
        val session = V2GetSession.load()
        _uiState.update { it.copy(session = session) }
        val notice = when (session?.let { V2GetQuota.blockReason(it) }) {
            V2GetQuota.Block.QUOTA_EXHAUSTED -> V2GetNotice.QuotaExhausted
            V2GetQuota.Block.EXPIRED -> V2GetNotice.Expired
            else -> null
        }
        if (session == null || notice != null) {
            _uiState.update { it.copy(notice = notice) }
            return false
        }
        _uiState.update { it.copy(connection = V2GetConnection.Connecting) }
        return true
    }

    /**
     * What a connection asks before it starts: the account figures are checked against the panel, the usage
     * reports still waiting go out, and the configuration is read again from the repository. False, with the
     * reason in a notice, when the connection must not start. A panel that cannot be reached does not block:
     * the figures stored at the last sync decide then, as [beginStart] checks.
     */
    suspend fun prepareStart(): Boolean {
        _uiState.update { it.copy(connection = V2GetConnection.Connecting) }
        val failure: String? = withContext(Dispatchers.IO) {
            V2GetAuthManager.flushPending()
            when (V2GetAuthManager.syncAccount()) {
                V2GetAuthManager.SyncResult.Unauthorized -> "نشست حساب منقضی شده است؛ دوباره وارد شوید."
                else -> V2GetConfigSource.refreshProfile()
            }
        }
        if (failure != null) {
            _uiState.update { it.copy(connection = V2GetConnection.Disconnected, notice = V2GetNotice.Failure(failure)) }
            return false
        }
        return beginStart()
    }

    /** A start that did not go ahead after [beginStart], such as a refused VPN permission. */
    fun startCancelled() {
        _uiState.update { it.copy(connection = V2GetConnection.Disconnected) }
    }

    fun noticeShown() {
        _uiState.update { it.copy(notice = null) }
    }

    override fun onCleared() {
        dataSource.close()
    }

    class Factory(private val dataSource: MainDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = V2GetViewModel(dataSource) as T
    }

    companion object {
        internal fun reduce(state: V2GetUiState, event: MainServiceEvent): V2GetUiState = when (event) {
            MainServiceEvent.StateRunning,
            MainServiceEvent.StateStartSuccess -> state.copy(connection = V2GetConnection.Connected)

            MainServiceEvent.StateNotRunning,
            MainServiceEvent.StateStopSuccess -> state.copy(connection = V2GetConnection.Disconnected)

            is MainServiceEvent.StateConnecting -> state.copy(connection = V2GetConnection.Connecting)

            is MainServiceEvent.StateStartFailure -> state.copy(
                connection = V2GetConnection.Disconnected,
                notice = V2GetNotice.StartFailure(event.message.orEmpty()),
            )

            else -> state
        }
    }
}
