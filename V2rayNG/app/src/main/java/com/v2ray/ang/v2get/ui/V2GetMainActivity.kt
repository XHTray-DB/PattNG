package com.v2ray.ang.v2get.ui

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AngApplication
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.main.MainRepository
import com.v2ray.ang.v2get.V2GetAuthManager
import com.v2ray.ang.v2get.V2GetQuota

class V2GetMainActivity : HelperBaseComponentActivity() {

    private val viewModel: V2GetViewModel by viewModels {
        V2GetViewModel.Factory(MainRepository(application as AngApplication))
    }

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
        // The service can have gone away while the screen was not shown, and the account page can have changed the figures.
        viewModel.refresh()
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
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
        DashboardScreen(
            state = state,
            onToggle = ::toggleConnection,
            onOpenAccount = { startActivity(Intent(this, V2GetAccountActivity::class.java)) },
        )
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

    @Composable
    private fun DashboardScreen(state: V2GetUiState, onToggle: () -> Unit, onOpenAccount: () -> Unit) {
        val scheme = MaterialTheme.colorScheme
        val connected = state.connection == V2GetConnection.Connected
        val pulse = rememberInfiniteTransition(label = "pulse")
        val pulseScale by pulse.animateFloat(
            initialValue = 1f,
            targetValue = if (connected) 1.08f else 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulseScale",
        )
        val session = state.session
        val remaining = session?.let { V2GetQuota.toGigabytes(V2GetQuota.remainingBytes(it)) } ?: 0.0
        val remainingText = if (session != null && session.quotaBytes <= 0) "∞" else "%.1f".format(remaining)

        Scaffold(
            containerColor = scheme.background,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = true,
                        onClick = {},
                        icon = { Text("⚡", fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics {}) },
                        label = { Text(stringResource(R.string.v2get_nav_connect)) },
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = onOpenAccount,
                        icon = { Text("📊", fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics {}) },
                        label = { Text(stringResource(R.string.v2get_nav_account)) },
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .v2getBackground()
                    .padding(padding)
                    .statusBarsPadding()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            stringResource(R.string.v2get_brand),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = scheme.secondary,
                        )
                        Text(stringResource(R.string.v2get_tagline), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    }
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = scheme.surface.copy(alpha = 0.9f)),
                        onClick = onOpenAccount,
                    ) {
                        Text(
                            text = "👤 " + (session?.username?.takeIf { it.isNotEmpty() }
                                ?: stringResource(R.string.v2get_default_user)),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surface.copy(alpha = 0.85f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, scheme.primary.copy(alpha = 0.15f), RoundedCornerShape(20.dp)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(stringResource(R.string.v2get_quota_remaining), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                            Text(
                                stringResource(R.string.v2get_gigabytes, remainingText),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            stringResource(R.string.v2get_core_badge),
                            color = scheme.secondary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(scheme.primaryContainer)
                                .padding(8.dp),
                        )
                    }
                }

                Spacer(Modifier.height(48.dp))

                val actionLabel = stringResource(
                    if (state.connection == V2GetConnection.Disconnected) R.string.v2get_connect_action
                    else R.string.v2get_disconnect_action
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(210.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                if (connected) listOf(scheme.primary, scheme.secondary)
                                else listOf(scheme.surface, scheme.surfaceVariant)
                            )
                        )
                        .border(4.dp, if (connected) scheme.primaryContainer else scheme.primary.copy(alpha = 0.27f), CircleShape)
                        .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onToggle),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (connected) "⚡" else "🔒",
                            fontSize = 48.sp,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                when (state.connection) {
                                    V2GetConnection.Disconnected -> R.string.v2get_status_disconnected
                                    V2GetConnection.Connecting -> R.string.v2get_status_connecting
                                    V2GetConnection.Connected -> R.string.v2get_status_connected
                                }
                            ),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (connected) scheme.onPrimary else scheme.onSurface,
                        )
                    }
                }

                Spacer(Modifier.height(40.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surface.copy(alpha = 0.8f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        StatusItem(stringResource(R.string.v2get_protocol_title), stringResource(R.string.v2get_protocol_value))
                        StatusItem(
                            stringResource(R.string.v2get_state_title),
                            stringResource(if (connected) R.string.v2get_state_active else R.string.v2get_state_ready),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusItem(title: String, value: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
