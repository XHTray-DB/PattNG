package com.v2ray.ang.v2get.ui

import androidx.activity.viewModels
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.v2get.V2GetAuthManager
import com.v2ray.ang.v2get.V2GetQuota
import com.v2ray.ang.v2get.V2GetSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class V2GetAccountState(val session: V2GetSession? = null, val loggedOut: Boolean = false)

class V2GetAccountViewModel : ViewModel() {

    private val _state = MutableStateFlow(V2GetAccountState())
    val state: StateFlow<V2GetAccountState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Read again every second: while a connection runs the usage is counted in the app and grows with it.
            while (true) {
                val session = withContext(Dispatchers.IO) { V2GetSession.loadLive() }
                if (_state.value.loggedOut) break
                _state.value = V2GetAccountState(session = session)
                delay(1_000L)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { V2GetAuthManager.logout() }
            _state.value = V2GetAccountState(loggedOut = true)
        }
    }
}

class V2GetAccountActivity : BaseComponentActivity() {

    private val viewModel: V2GetAccountViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.loggedOut) {
            if (state.loggedOut) finish()
        }
        V2GetThemeHost(rtl = false) {
            AccountScreen(
                state = state,
                onBack = ::finish,
                onLogout = {
                    // The core of the account goes first: a tunnel left running would outlive the profile it was built from.
                    LauncherManager.stopService(this)
                    viewModel.logout()
                },
            )
        }
    }

    @Composable
    private fun AccountScreen(state: V2GetAccountState, onBack: () -> Unit, onLogout: () -> Unit) {
        val scheme = MaterialTheme.colorScheme
        val session = state.session
        val totalGb = session?.let { V2GetQuota.toGigabytes(it.quotaBytes) } ?: 0.0
        val usedText = V2GetQuota.formatBytes(session?.usedBytes ?: 0L)
        val remainingText = V2GetQuota.formatBytes(session?.let { V2GetQuota.remainingBytes(it) } ?: 0L)
        val unlimited = session != null && session.quotaBytes <= 0

        Column(
            modifier = Modifier
                .fillMaxSize()
                .v2getBackground()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text(
                        text = "← " + stringResource(R.string.v2get_account_back),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.secondary,
                    )
                }
            }
            Text(
                stringResource(R.string.v2get_account_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surface.copy(alpha = 0.9f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, scheme.primary.copy(alpha = 0.2f), RoundedCornerShape(24.dp)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = session?.username?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.v2get_default_user),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.secondary,
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { session?.let { V2GetQuota.usedFraction(it) } ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(12.dp),
                        color = scheme.primary,
                        trackColor = scheme.primaryContainer,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            stringResource(R.string.v2get_account_used, usedText),
                            fontSize = 13.sp,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.v2get_account_remaining, if (unlimited) "∞" else remainingText),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surface.copy(alpha = 0.85f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    DetailRow(stringResource(R.string.v2get_account_plan_label), stringResource(R.string.v2get_account_plan_value))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = scheme.surfaceVariant)
                    DetailRow(stringResource(R.string.v2get_account_expires_label), session?.expiresAt.orEmpty())
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = scheme.surfaceVariant)
                    DetailRow(
                        stringResource(R.string.v2get_account_quota_label),
                        if (unlimited) "∞" else stringResource(R.string.v2get_gigabytes, "%.0f".format(totalGb)),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            OutlinedButton(
                onClick = onLogout,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(
                    stringResource(R.string.v2get_account_logout),
                    color = scheme.secondary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    @Composable
    private fun DetailRow(label: String, value: String) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
