package com.v2ray.ang.v2get.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AngApplication
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.main.MainRepository
import com.v2ray.ang.v2get.V2GetAuthManager

class V2GetMainActivity : HelperBaseComponentActivity() {

    private val viewModel: V2GetViewModel by viewModels {
        V2GetViewModel.Factory(MainRepository(application as AngApplication))
    }

    private val accountViewModel: V2GetAccountViewModel by viewModels()

    /** Whether the tunnel runs as a VPN or as a local proxy; read when the screen starts, shown on the connect tab. */
    private var vpnMode by mutableStateOf(true)

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
        vpnMode = SettingsManager.isVpnMode()
        // The service can have gone away while the screen was not shown, and the account page can have changed the figures.
        viewModel.refresh()
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        var tab by rememberSaveable { mutableStateOf(V2GetTab.Connect) }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val account by accountViewModel.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        LaunchedEffect(state.notice) {
            val text = when (val notice = state.notice) {
                null -> return@LaunchedEffect
                V2GetNotice.QuotaExhausted -> context.getString(R.string.v2get_quota_exhausted)
                V2GetNotice.Expired -> context.getString(R.string.v2get_subscription_expired)
                is V2GetNotice.StartFailure -> context.getString(R.string.v2get_start_failure, notice.reason)
            }
            context.toast(text)
            viewModel.noticeShown()
        }
        LaunchedEffect(account.loggedOut) {
            if (account.loggedOut) {
                startActivity(Intent(this@V2GetMainActivity, V2GetLoginActivity::class.java))
                finish()
            }
        }

        // The design is written right to left, and the screens are Persian-only.
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalContentColor provides V2G.Ink,
        ) {
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
                        V2GetTab.Connect -> ConnectTab(state, vpnMode, ::toggleConnection)
                        V2GetTab.Servers -> ServersTab(state, ::toggleConnection)
                        V2GetTab.Account -> AccountTab(
                            session = state.session,
                            onCopyId = ::copyAccountId,
                            onLogout = {
                                // The core of the account goes first: a tunnel left running would outlive the profile it was built from.
                                LauncherManager.stopService(this@V2GetMainActivity)
                                accountViewModel.logout()
                            },
                        )
                        V2GetTab.Settings -> SettingsTab(BuildConfig.VERSION_NAME)
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
        if (viewModel.uiState.value.connection == V2GetConnection.Disconnected) {
            requestStart()
        } else {
            LauncherManager.stopService(this)
            // A daemon that died takes no stop and answers nothing; asking for its state lets that silence show as stopped.
            viewModel.refresh()
        }
    }

    private fun requestStart() {
        if (!viewModel.beginStart()) return
        if (!SettingsManager.isVpnMode()) {
            startCore()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startCore() else requestVpnPermission.launch(intent)
    }

    private fun startCore() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        LauncherManager.startService(this, V2GetAuthManager.PROFILE_GUID)
    }
}
