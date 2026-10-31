package com.v2ray.ang.v2get.ui

import android.content.Intent
import androidx.activity.viewModels
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
        // The design is written right to left, and the screens are Persian-only.
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalContentColor provides V2G.Ink,
        ) {
            LoginScreen(state = state, onSubmit = viewModel::login)
        }
    }

    @Composable
    private fun LoginScreen(state: V2GetLoginState, onSubmit: (String, String) -> Unit) {
        var username by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var showPassword by rememberSaveable { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .v2getBackground()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Pill(stringResource(R.string.v2get_brand), V2G.PinkSoft, V2G.Rose, mono = true)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.v2get_login_top), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = V2G.Ink)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(V2G.BrandBrush),
                    contentAlignment = Alignment.Center,
                ) { V2Icon(R.drawable.ic_v2g_person, Color.White, 20.dp) }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(V2G.Magenta))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.v2get_login_badge), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = V2G.Magenta)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.v2get_login_headline),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = V2G.Ink,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.v2get_login_headline_sub),
                fontSize = 13.sp,
                color = V2G.Muted,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))

            // The two sign-in methods; the license link is not offered yet.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(V2G.Lilac)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.v2get_login_tab_user),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(V2G.BrandBrush)
                        .padding(vertical = 12.dp),
                )
                Text(
                    stringResource(R.string.v2get_login_tab_license),
                    color = V2G.Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))

            GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel(stringResource(R.string.v2get_login_field_user), R.drawable.ic_v2g_person)
                    LoginField(
                        value = username,
                        onValueChange = { username = it },
                        hint = stringResource(R.string.v2get_login_field_user_hint),
                        enabled = !state.loading,
                        keyboardType = KeyboardType.Text,
                        transformation = VisualTransformation.None,
                    )
                    Spacer(Modifier.height(6.dp))
                    FieldLabel(stringResource(R.string.v2get_login_field_pass), R.drawable.ic_v2g_key)
                    LoginField(
                        value = password,
                        onValueChange = { password = it },
                        hint = "",
                        enabled = !state.loading,
                        keyboardType = KeyboardType.Password,
                        transformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            V2Icon(
                                if (showPassword) R.drawable.ic_v2g_eye_off else R.drawable.ic_v2g_eye,
                                V2G.Muted,
                                22.dp,
                                Modifier.clickable(
                                    onClickLabel = stringResource(
                                        if (showPassword) R.string.v2get_login_hide_pass else R.string.v2get_login_show_pass
                                    ),
                                    role = Role.Button,
                                ) { showPassword = !showPassword },
                            )
                        },
                    )
                    state.issue?.let {
                        Text(text = issueText(it), color = V2G.Error, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(V2G.LilacSoft).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBox(R.drawable.ic_v2g_shield_check, size = 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(stringResource(R.string.v2get_login_secure_title), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = V2G.Ink)
                            Text(stringResource(R.string.v2get_login_secure_body), fontSize = 11.sp, color = V2G.Muted)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .shadow(14.dp, RoundedCornerShape(50), ambientColor = V2G.Hot, spotColor = V2G.Hot)
                            .clip(RoundedCornerShape(50))
                            .background(V2G.BrandBrush)
                            .clickable(enabled = !state.loading, role = Role.Button) { onSubmit(username, password) },
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                V2Icon(R.drawable.ic_v2g_bolt, Color.White, 22.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.v2get_login_cta),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    V2Icon(R.drawable.ic_v2g_support, V2G.Rose, 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.v2get_login_support), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = V2G.Ink)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.v2get_login_footer),
                fontSize = 11.sp,
                color = V2G.Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                BuildConfig.VERSION_NAME,
                fontSize = 11.sp,
                fontFamily = V2G.Mono,
                color = V2G.Muted,
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    @Composable
    private fun FieldLabel(text: String, icon: Int) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            V2Icon(icon, V2G.Magenta, 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = V2G.Ink)
        }
    }

    @Composable
    private fun LoginField(
        value: String,
        onValueChange: (String) -> Unit,
        hint: String,
        enabled: Boolean,
        keyboardType: KeyboardType,
        transformation: VisualTransformation,
        trailing: (@Composable () -> Unit)? = null,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint, color = V2G.Muted.copy(alpha = 0.6f)) },
            singleLine = true,
            enabled = enabled,
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            trailingIcon = trailing,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = V2G.LilacSoft,
                unfocusedContainerColor = V2G.LilacSoft,
                disabledContainerColor = V2G.LilacSoft,
                focusedTextColor = V2G.Ink,
                unfocusedTextColor = V2G.Ink,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = V2G.Magenta,
            ),
            modifier = Modifier.fillMaxWidth().border(1.dp, V2G.Border, RoundedCornerShape(16.dp)),
        )
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
