package com.v2ray.ang.v2get.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.v2ray.ang.AngApplication
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.main.MainRepository
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.v2get.V2GetAuthManager
import com.v2ray.ang.v2get.V2GetBlock
import com.v2ray.ang.v2get.V2GetConfigSource
import com.v2ray.ang.v2get.V2GetSettings

class V2GetMainActivity : HelperBaseComponentActivity() {

    private val viewModel: V2GetViewModel by viewModels {
        V2GetViewModel.Factory(MainRepository(application as AngApplication))
    }

    private val accountViewModel: V2GetAccountViewModel by viewModels()

    /** Whether the tunnel runs as a VPN or as a local proxy; read when the screen starts, shown on the connect tab. */
    private var vpnMode by mutableStateOf(true)

    /** A tap on another server while a core runs: the core is stopped, and the connection starts once the daemon says it stopped. */
    private var startAfterStop = false

    /** The switches of the settings tab, read when the screen starts and after each change. */
    private var perAppProxy by mutableStateOf(false)
    private var zeroDrop by mutableStateOf(false)
    private var dnsProtected by mutableStateOf(true)
    private var autoBest by mutableStateOf(true)

    /** The start that is measuring servers or waiting for the VPN permission; a tap on the button while it runs ends it. */
    private var startJob: kotlinx.coroutines.Job? = null

    /**
     * Zero-Drop: the servers that already failed to start in the current series of attempts, so each server is
     * tried once. Emptied when a connection comes up and when the person starts a connection by hand. A stop the
     * person asks for, here or from the notification, is never answered with another connection.
     */
    private val failedServers = mutableSetOf<String>()

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startCore() else viewModel.startCancelled()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    override fun onStart() {
        super.onStart()
        if (!V2GetAuthManager.isLoggedIn()) {
            startActivity(Intent(this, V2GetLoginActivity::class.java))
            finish()
            return
        }
        // An account the panel refused earlier (found by a report in the background, say) is shown at once.
        V2GetBlock.pending()?.let {
            V2GetBlock.show(this, it)
            return
        }
        vpnMode = SettingsManager.isVpnMode()
        readSettings()
        // The service can have gone away while the screen was not shown, and the account page can have changed the figures.
        viewModel.refresh()
        lifecycleScope.launch { viewModel.reloadServers() }
        checkAccount()
    }

    /**
     * Asks the panel about the account and the app version: usage still waiting goes out first, so the panel's
     * figures are whole. An old app, or an account the panel refuses, is stopped here, when the app is opened and
     * again once a connection is up, not only when it connects.
     */
    private fun checkAccount() {
        lifecycleScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                V2GetAuthManager.flushPending()
                V2GetAuthManager.syncAccount()
            }
            when (result) {
                is V2GetAuthManager.SyncResult.UpdateRequired -> result.info.show(this@V2GetMainActivity)
                is V2GetAuthManager.SyncResult.Blocked -> V2GetBlock.show(this@V2GetMainActivity, result.reason)
                else -> Unit
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        var tab by rememberSaveable { mutableStateOf(V2GetTab.Connect) }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val account by accountViewModel.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        // The exact reason of a failure stays on screen in a dialog until it is closed.
        var errorText by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(state.notice) {
            when (val notice = state.notice) {
                null -> return@LaunchedEffect
                V2GetNotice.QuotaExhausted -> context.toast(context.getString(R.string.v2get_quota_exhausted))
                V2GetNotice.Expired -> context.toast(context.getString(R.string.v2get_subscription_expired))
                is V2GetNotice.StartFailure -> {
                    // Zero-Drop: another server is tried before the person is told; the dialog comes when none is left.
                    if (!tryNextServer()) errorText = notice.reason.ifBlank { context.getString(R.string.v2get_error_unknown) }
                }
                is V2GetNotice.Failure -> errorText = notice.reason
                is V2GetNotice.UpdateRequired -> notice.info.show(context)
                is V2GetNotice.Blocked -> V2GetBlock.show(context, notice.reason)
            }
            viewModel.noticeShown()
        }
        errorText?.let { text ->
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AlertDialog(
                    onDismissRequest = { errorText = null },
                    title = { Text(stringResource(R.string.v2get_error_title)) },
                    text = { SelectionContainer { Text(text) } },
                    confirmButton = { TextButton(onClick = { errorText = null }) { Text(stringResource(R.string.v2get_error_ok)) } },
                )
            }
        }
        LaunchedEffect(state.connection) {
            if (state.connection == V2GetConnection.Connected) {
                failedServers.clear()
                // The network works now: what could not be reported before is sent, and the account is checked again.
                checkAccount()
            }
            // A refusal found in the background stopped the tunnel; the screen follows.
            if (state.connection == V2GetConnection.Disconnected) V2GetBlock.pending()?.let { V2GetBlock.show(context, it) }
            if (state.connection == V2GetConnection.Disconnected && startAfterStop) {
                startAfterStop = false
                requestStart()
            }
        }
        LaunchedEffect(account.loggedOut) {
            if (account.loggedOut) {
                startActivity(Intent(this@V2GetMainActivity, V2GetLoginActivity::class.java))
                finish()
            }
        }

        // The design is written right to left, and the screens are Persian-only.
        V2GetThemeHost {
            Scaffold(
                modifier = Modifier.fillMaxSize().v2getBackground(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    V2GetTopBar(
                        connected = state.connection == V2GetConnection.Connected,
                        onOpenAccount = { tab = V2GetTab.Account },
                    )
                },
                bottomBar = { V2GetBottomBar(selected = tab, onSelect = { tab = it }) },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                ) {
                    when (tab) {
                        V2GetTab.Connect -> ConnectTab(state, vpnMode, ::toggleConnection, onChangeServer = { tab = V2GetTab.Servers })
                        V2GetTab.Servers -> ServersTab(state, ::connectTo, ::toggleConnection)
                        V2GetTab.Account -> AccountTab(
                            session = state.session,
                            onCopyId = ::copyAccountId,
                            onLogout = {
                                // The core of the account goes first: a tunnel left running would outlive the profile it was built from.
                                LauncherManager.stopService(this@V2GetMainActivity)
                                accountViewModel.logout()
                            },
                        )
                        V2GetTab.Settings -> SettingsTab(
                            versionName = BuildConfig.VERSION_NAME,
                            perAppProxy = perAppProxy,
                            zeroDrop = zeroDrop,
                            dnsProtected = dnsProtected,
                            autoBest = autoBest,
                            onAutoBest = { on ->
                                V2GetSettings.setAutoBest(on)
                                readSettings()
                            },
                            onSplitTunneling = { startActivity(Intent(this@V2GetMainActivity, PerAppProxyActivity::class.java)) },
                            onKillSwitch = ::openVpnSettings,
                            onZeroDrop = { on ->
                                V2GetSettings.setZeroDrop(on)
                                failedServers.clear()
                                readSettings()
                            },
                            onDnsProtected = { on ->
                                V2GetSettings.setDnsProtected(on)
                                readSettings()
                                toast(getString(R.string.v2get_settings_applies_next))
                            },
                        )
                    }
                }
            }
        }
    }

    private fun copyAccountId(id: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.v2get_account_id_label), id))
    }

    private fun toggleConnection() {
        val state = viewModel.uiState.value
        if (state.connection == V2GetConnection.Disconnected) {
            failedServers.clear()
            requestStart(auto = true)
        } else if (state.phase != V2GetPhase.None) {
            // Still measuring the servers: nothing runs yet, so the tap ends the start itself.
            startJob?.cancel()
            viewModel.cancelStart()
        } else {
            LauncherManager.stopService(this)
            // A daemon that died takes no stop and answers nothing; asking for its state lets that silence show as stopped.
            viewModel.refresh()
        }
    }

    /** The connection of the server [guid]: started now, or after the running core stops when it is another server. */
    private fun connectTo(guid: String) {
        viewModel.selectServer(guid)
        failedServers.clear()
        if (viewModel.uiState.value.connection == V2GetConnection.Disconnected) {
            requestStart()
        } else {
            startAfterStop = true
            LauncherManager.stopService(this)
            viewModel.refresh()
        }
    }

    private fun readSettings() {
        perAppProxy = V2GetSettings.perAppProxy()
        zeroDrop = V2GetSettings.zeroDrop()
        dnsProtected = V2GetSettings.dnsProtected()
        autoBest = V2GetSettings.autoBest()
    }

    /**
     * Kill switch: Android's own. Its always-on VPN with "Block connections without VPN" cuts the traffic of the
     * whole phone whenever the tunnel is down, which an app cannot do for itself; the VPN service of this app
     * supports always-on, so the person only has to switch it on in the system's VPN settings.
     */
    private fun openVpnSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (e: Exception) {
            toast(getString(R.string.v2get_settings_kill_unavailable))
        }
    }

    /**
     * Zero-Drop: after a connection that could not start, the next server that has not failed in this series
     * takes over and a connection starts at once. False, with the series emptied, when the switch is off or every
     * server has failed.
     */
    private fun tryNextServer(): Boolean {
        if (!V2GetSettings.zeroDrop()) return false
        val state = viewModel.uiState.value
        state.selectedGuid?.let { failedServers.add(it) }
        if (state.servers.none { it.guid !in failedServers }) {
            failedServers.clear()
            return false
        }
        // The replacement is the best one the background tests found, so it is looked up off the main thread.
        lifecycleScope.launch {
            val next = viewModel.nextServer(failedServers.toSet()) ?: return@launch
            viewModel.selectServer(next)
            toast(getString(R.string.v2get_zero_drop_switching))
            requestStart()
        }
        return true
    }

    /** [auto]: the connection is not aimed at one server, so the best one is picked first (when that switch is on). */
    private fun requestStart(auto: Boolean = false) {
        startJob?.cancel()
        startJob = lifecycleScope.launch {
            // The account is checked against the panel and the configuration read again before anything starts.
            if (!viewModel.prepareStart(auto)) return@launch
            if (!SettingsManager.isVpnMode()) {
                startCore()
                return@launch
            }
            val intent = VpnService.prepare(this@V2GetMainActivity)
            if (intent == null) startCore() else requestVpnPermission.launch(intent)
        }
    }

    private fun startCore() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        LauncherManager.startService(this, V2GetConfigSource.selectedGuid() ?: V2GetAuthManager.PROFILE_GUID)
    }
}
