package com.v2ray.ang.v2get.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.ui.main.MainDataSource
import com.v2ray.ang.v2get.V2GetAuthManager
import com.v2ray.ang.v2get.V2GetConfigSource
import com.v2ray.ang.v2get.V2GetGeoFiles
import com.v2ray.ang.v2get.V2GetServer
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.v2get.V2GetSettings
import com.v2ray.ang.v2get.V2GetTraffic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

/** What a connection start is doing before the core starts: the servers are measured to find the best one. */
enum class V2GetPhase { None, Tcping, RealPing }

data class V2GetUiState(
    val session: V2GetSession? = null,
    /** The traffic of the running connection: what it has used and its speed. */
    val live: V2GetTraffic.Live = V2GetTraffic.Live(),
    val phase: V2GetPhase = V2GetPhase.None,
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

    /** Reads the traffic figures of the running connection; ended with the connection. */
    private var liveJob: Job? = null

    /** The server tests waiting for the test service to finish, by request id: true when it finished, false when it was cancelled. */
    private val pendingTests = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    init {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                _uiState.update { reduce(it, event) }
                when (event) {
                    MainServiceEvent.StateRunning, MainServiceEvent.StateStartSuccess -> {
                        detectCountry()
                        startLivePolling()
                    }
                    MainServiceEvent.StateNotRunning, MainServiceEvent.StateStopSuccess, is MainServiceEvent.StateStartFailure -> {
                        countryJob?.cancel()
                        liveJob?.cancel()
                        _uiState.update { it.copy(live = V2GetTraffic.Live(), session = V2GetSession.load()) }
                    }
                    is MainServiceEvent.MeasureConfigFinish -> pendingTests.remove(event.requestId)?.complete(true)
                    is MainServiceEvent.MeasureConfigCancelled -> pendingTests.remove(event.requestId)?.complete(false)
                    else -> Unit
                }
            }
        }
        dataSource.queryServiceState()
    }

    /**
     * While the connection runs, the traffic it has used and its speed are read every second, and the account's
     * figures every few seconds: the core reports usage to the panel as it goes, and the screens show what came back.
     */
    private fun startLivePolling() {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            var tick = 0
            while (true) {
                val live = withContext(Dispatchers.IO) { V2GetTraffic.live() }
                val refreshSession = tick++ % SESSION_REFRESH_TICKS == 0
                _uiState.update { it.copy(live = live, session = if (refreshSession) V2GetSession.load() else it.session) }
                delay(1_000L)
            }
        }
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
    suspend fun prepareStart(auto: Boolean = false): Boolean {
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
        // A connection the person did not aim at one server picks the best: every server is measured first.
        if (auto && V2GetSettings.autoBest()) selectBestServer()
        _uiState.update { it.copy(servers = V2GetConfigSource.loadServers(), selectedGuid = V2GetConfigSource.selectedGuid()) }
        return beginStart()
    }

    /**
     * Measures the servers and selects the best: every server is tried with a TCP connection first, the ones that
     * answered get the real delay test (a request through the server itself), and the one with the lowest delay wins.
     * A server that cannot be tested by TCP (UDP based) is not dropped by the first step. When nothing could be
     * measured, the selection stays as it was.
     */
    private suspend fun selectBestServer() {
        val servers = V2GetConfigSource.loadServers()
        if (servers.size < 2) return
        try {
            val all = servers.map { it.guid }
            dataSource.clearAllTestDelayResults(all)
            _uiState.update { it.copy(phase = V2GetPhase.Tcping) }
            runServerTest(all, onlyTcp = true, budgetMs = TCP_BUDGET_MS)
            val tcp = readDelays(all)

            val alive = tcpSurvivors(servers, tcp)
            dataSource.clearAllTestDelayResults(alive)
            _uiState.update { it.copy(phase = V2GetPhase.RealPing) }
            runServerTest(alive, onlyTcp = false, budgetMs = REAL_BUDGET_MS)
            val real = readDelays(alive)

            bestServer(servers, tcp, real)?.let { selectServer(it) }
        } finally {
            _uiState.update { it.copy(phase = V2GetPhase.None) }
        }
    }

    private suspend fun readDelays(guids: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        guids.associateWith { dataSource.decodeAffiliationInfo(it)?.testDelayMillis ?: -1L }
    }

    /** Asks the test service to measure [guids] and waits for it, at most [budgetMs]; what did not finish by then is cancelled. */
    private suspend fun runServerTest(guids: List<String>, onlyTcp: Boolean, budgetMs: Long) {
        val requestId = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pendingTests[requestId] = done
        dataSource.sendMsg2TestService(
            TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_START, serverGuids = guids, onlyTcp = onlyTcp),
            requestId,
        )
        val finished = withTimeoutOrNull(budgetMs) { done.await() }
        pendingTests.remove(requestId)
        if (finished == null) dataSource.cancelAllPing()
    }

    /** The person ended a start that was still measuring servers: the tests stop and the screen is idle again. */
    fun cancelStart() {
        dataSource.cancelAllPing()
        pendingTests.values.forEach { it.complete(false) }
        _uiState.update { it.copy(connection = V2GetConnection.Disconnected, phase = V2GetPhase.None) }
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
        private const val SESSION_REFRESH_TICKS = 5
        private const val TCP_BUDGET_MS = 10_000L
        private const val REAL_BUDGET_MS = 30_000L

        /** The kinds of server a TCP connection says nothing about: they speak UDP. */
        private val TCP_UNTESTABLE = setOf("HYSTERIA2", "WIREGUARD")

        /**
         * The servers worth the real delay test: those whose TCP test answered, and those a TCP test cannot judge.
         * When every one failed, every one stays: a failed test is not proof when the phone's own network is the problem.
         */
        internal fun tcpSurvivors(servers: List<V2GetServer>, tcp: Map<String, Long>): List<String> {
            val alive = servers.filter { (tcp[it.guid] ?: -1L) > 0L || it.family in TCP_UNTESTABLE }.map { it.guid }
            return alive.ifEmpty { servers.map { it.guid } }
        }

        /**
         * The best server: the lowest real delay among the survivors; failing any, the lowest TCP time; failing any,
         * the first survivor. Null only without servers.
         */
        internal fun bestServer(servers: List<V2GetServer>, tcp: Map<String, Long>, real: Map<String, Long>): String? {
            if (servers.isEmpty()) return null
            val alive = tcpSurvivors(servers, tcp)
            alive.filter { (real[it] ?: -1L) > 0L }.minByOrNull { real.getValue(it) }?.let { return it }
            alive.filter { (tcp[it] ?: -1L) > 0L }.minByOrNull { tcp.getValue(it) }?.let { return it }
            return alive.first()
        }

        internal fun reduce(state: V2GetUiState, event: MainServiceEvent): V2GetUiState = when (event) {
            MainServiceEvent.StateRunning,
            MainServiceEvent.StateStartSuccess -> state.copy(connection = V2GetConnection.Connected)

            // "Not running" is also what a state query answers while the servers are still being measured for the
            // start: nothing runs yet, and the start under way is not over.
            MainServiceEvent.StateNotRunning,
            MainServiceEvent.StateStopSuccess ->
                if (state.phase != V2GetPhase.None) state else state.copy(connection = V2GetConnection.Disconnected)

            is MainServiceEvent.StateConnecting -> state.copy(connection = V2GetConnection.Connecting)

            is MainServiceEvent.StateStartFailure -> state.copy(
                connection = V2GetConnection.Disconnected,
                notice = V2GetNotice.StartFailure(event.message.orEmpty()),
            )

            else -> state
        }
    }
}
