package com.v2ray.ang.v2get.ui

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.v2get.V2GetAuthManager
import com.v2ray.ang.v2get.V2GetSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class V2GetLoginIssue {
    data object Empty : V2GetLoginIssue()
    data object BadCore : V2GetLoginIssue()
    data class Failed(val error: V2GetAuthManager.LoginError, val httpCode: Int) : V2GetLoginIssue()
}

data class V2GetLoginState(
    val loading: Boolean = false,
    val issue: V2GetLoginIssue? = null,
    val signedIn: Boolean = false,
)

class V2GetLoginViewModel(private val apiUrl: String = BuildConfig.V2GET_API_URL) : ViewModel() {

    private val _state = MutableStateFlow(V2GetLoginState())
    val state: StateFlow<V2GetLoginState> = _state.asStateFlow()

    fun login(username: String, password: String) {
        if (_state.value.loading) return
        if (username.isBlank() || password.isBlank()) {
            _state.update { it.copy(issue = V2GetLoginIssue.Empty) }
            return
        }
        _state.update { it.copy(loading = true, issue = null) }
        viewModelScope.launch {
            val result = V2GetAuthManager.loginWithCredentials(apiUrl, username.trim(), password)
            val next = when (result) {
                is V2GetAuthManager.LoginResult.Failure ->
                    V2GetLoginState(issue = V2GetLoginIssue.Failed(result.error, result.httpCode))

                is V2GetAuthManager.LoginResult.Success -> withContext(Dispatchers.IO) {
                    val core = result.config ?: V2GetAuthManager.CoreConfig(
                        core = "aether",
                        socksPort = 1819,
                        aetherCommand = V2GetAuthManager.DEFAULT_AETHER_COMMAND,
                    )
                    // The profile first: an account whose core cannot be set up is not signed in.
                    if (V2GetAuthManager.applyAetherCoreProfile(core)) {
                        V2GetSession.save(result.session)
                        V2GetLoginState(signedIn = true)
                    } else {
                        V2GetLoginState(issue = V2GetLoginIssue.BadCore)
                    }
                }
            }
            _state.value = next
        }
    }
}

class V2GetLoginActivity : BaseComponentActivity() {

    private val viewModel: V2GetLoginViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.signedIn) {
            if (state.signedIn) {
                startActivity(Intent(this@V2GetLoginActivity, V2GetMainActivity::class.java))
                finish()
            }
        }
        LoginScreen(state = state, onSubmit = viewModel::login)
    }

    @Composable
    private fun LoginScreen(state: V2GetLoginState, onSubmit: (String, String) -> Unit) {
        var username by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        val scheme = MaterialTheme.colorScheme

        Box(
            modifier = Modifier
                .fillMaxSize()
                .v2getBackground()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surface.copy(alpha = 0.9f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, scheme.primary.copy(alpha = 0.2f), RoundedCornerShape(28.dp)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.v2get_brand),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        color = scheme.secondary,
                    )
                    Text(
                        text = stringResource(R.string.v2get_login_subtitle),
                        fontSize = 14.sp,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.v2get_login_username)) },
                        singleLine = true,
                        enabled = !state.loading,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.v2get_login_password)) },
                        singleLine = true,
                        enabled = !state.loading,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.issue?.let {
                        Text(
                            text = issueText(it),
                            color = scheme.error,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { onSubmit(username, password) },
                        enabled = !state.loading,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(
                                color = scheme.onPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp),
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.v2get_login_submit),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun issueText(issue: V2GetLoginIssue): String = when (issue) {
        V2GetLoginIssue.Empty -> stringResource(R.string.v2get_login_empty)
        V2GetLoginIssue.BadCore -> stringResource(R.string.v2get_login_bad_core)
        is V2GetLoginIssue.Failed -> when (issue.error) {
            V2GetAuthManager.LoginError.BAD_CREDENTIALS -> stringResource(R.string.v2get_login_bad_credentials)
            V2GetAuthManager.LoginError.SERVER -> stringResource(R.string.v2get_login_server_error, issue.httpCode)
            V2GetAuthManager.LoginError.NETWORK -> stringResource(R.string.v2get_login_network_error)
            V2GetAuthManager.LoginError.BAD_RESPONSE -> stringResource(R.string.v2get_login_bad_response)
            V2GetAuthManager.LoginError.NO_SERVER -> stringResource(R.string.v2get_login_no_server)
        }
    }
}
