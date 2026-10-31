package com.v2ray.ang.v2get.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.ui.main.MainDataSource
import com.v2ray.ang.v2get.V2GetAuthManager
import com.v2ray.ang.v2get.V2GetConfigSource
import com.v2ray.ang.v2get.V2GetGeoFiles
import com.v2ray.ang.v2get.V2GetServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val servers: List<V2GetServer> = emptyList(),
    val selectedGuid: String? = null,
)

/**
 * The state of the V2GET screens. The daemon process alone knows whether the core runs, so the connection
 * follows what it reports through [dataSource], never what a tap on the button asked for.
 */
class V2GetViewModel(private val dataSource: MainDataSource) : ViewModel() {

    private val _uiState = MutableStateFlow(
        V2GetUiState(
            session = V2GetSession.load(),
            servers = V2GetConfigSource.loadServers(),
            selectedGuid = V2GetConfigSource.selectedGuid(),
        )
    )
    val uiState: StateFlow<V2GetUiState> = _uiState.asStateFlow()

    /** The measuring of the exit country of the running connection; ended with the connection. */
    private var countryJob: Job? = null

    init {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                _uiState.update { reduce(it, event) }
                when (event) {
                    MainServiceEvent.StateRunning, MainServiceEvent.StateStartSuccess -> detectCountry()
                    MainServiceEvent.StateNotRunning, MainServiceEvent.StateStopSuccess, is MainServiceEvent.StateStartFailure -> countryJob?.cancel()
                    else -> Unit
                }
            }
        }
        dataSource.queryServiceState()
    }

    /**
     * Finds out which country the connection exits in, a few seconds after it came up (the tunnel needs a moment
     * before a request through it is answered), and keeps it for the server so the lists show its flag and name.
     */
    private fun detectCountry() {
        val guid = _uiState.value.selectedGuid ?: return
        countryJob?.cancel()
        countryJob = viewModelScope.launch {
            repeat(COUNTRY_ATTEMPTS) { attempt ->
                delay(if (attempt == 0) 2_000L else 4_000L)
                val code = withContext(Dispatchers.IO) { V2GetConfigSource.detectExitCountry() }
                if (code != null) {
                    withContext(Dispatchers.IO) { V2GetConfigSource.saveCountry(guid, code) }
                    _uiState.update { it.copy(servers = V2GetConfigSource.loadServers()) }
                    return@launch
                }
            }
        }
    }

    fun refresh() {
        _uiState.update {
            it.copy(
                session = V2GetSession.load(),
                servers = V2GetConfigSource.loadServers(),
                selectedGuid = V2GetConfigSource.selectedGuid(),
            )
        }
        dataSource.queryServiceState()
    }

    /** The server the next connection uses. */
    fun selectServer(guid: String) {
        if (V2GetConfigSource.selectServer(guid)) _uiState.update { it.copy(selectedGuid = guid) }
    }

    /**
     * Reads the server list again when the screen opens, so a server added to the file shows without a
     * connection. Only while disconnected: the profiles of a running core are not touched. A failure keeps the
     * list as it was.
     */
    suspend fun reloadServers() {
        if (_uiState.value.connection != V2GetConnection.Disconnected) return
        withContext(Dispatchers.IO) { V2GetConfigSource.refreshProfile() }
        refresh()
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
                // The geo files first, and the connection stops here without them: a routing rule naming a missing
                // list makes the core refuse the whole configuration, with a message that says little.
                else -> V2GetGeoFiles.ensure() ?: V2GetConfigSource.refreshProfile()
            }
        }
        if (failure != null) {
            _uiState.update { it.copy(connection = V2GetConnection.Disconnected, notice = V2GetNotice.Failure(failure)) }
            return false
        }
        _uiState.update { it.copy(servers = V2GetConfigSource.loadServers(), selectedGuid = V2GetConfigSource.selectedGuid()) }
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
        private const val COUNTRY_ATTEMPTS = 4

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
