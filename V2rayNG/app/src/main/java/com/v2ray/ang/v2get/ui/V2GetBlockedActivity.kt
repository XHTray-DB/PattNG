package com.v2ray.ang.v2get.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.v2get.V2GetAuthManager
import com.v2ray.ang.v2get.V2GetBlock
import com.v2ray.ang.v2get.V2GetBlockReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown when the panel refuses the account: deleted, blocked, ended or out of quota. The tunnel is stopped, the other
 * screens are closed, and the person is sent to Telegram support. Each time it comes to the front the panel is asked
 * again, so an account that was renewed goes straight back into the app.
 */
class V2GetBlockedActivity : BaseComponentActivity() {

    companion object {
        const val EXTRA_REASON = "v2get_block_reason"
    }

    private var reason by mutableStateOf(V2GetBlockReason.DISABLED)
    private var checking by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        reason = readReason(intent)
        super.onCreate(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reason = readReason(intent)
    }

    override fun onStart() {
        super.onStart()
        // Whatever brought the person here, no tunnel of this account stays up.
        LauncherManager.stopService(this)
        recheck()
    }

    private fun readReason(intent: Intent): V2GetBlockReason =
        runCatching { V2GetBlockReason.valueOf(intent.getStringExtra(EXTRA_REASON).orEmpty()) }.getOrNull()
            ?: V2GetBlock.pending()
            ?: V2GetBlockReason.DISABLED

    /** Asks the panel again; an account that is active now goes back into the app. */
    private fun recheck() {
        if (checking || !V2GetAuthManager.isLoggedIn()) return
        checking = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                V2GetAuthManager.flushPending()
                V2GetAuthManager.syncAccount()
            }
            checking = false
            when (result) {
                is V2GetAuthManager.SyncResult.Ok -> {
                    V2GetBlock.clear()
                    startActivity(Intent(this@V2GetBlockedActivity, V2GetMainActivity::class.java))
                    finish()
                }
                is V2GetAuthManager.SyncResult.Blocked -> reason = result.reason
                is V2GetAuthManager.SyncResult.UpdateRequired -> result.info.show(this@V2GetBlockedActivity)
                else -> Unit
            }
        }
    }

    private fun signOut() {
        LauncherManager.stopService(this)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { V2GetAuthManager.logout() }
            startActivity(
                Intent(this@V2GetBlockedActivity, V2GetLoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }
    }

    @Composable
    override fun ScreenContent() {
        // Back closes the app: there is nothing behind this screen to go to.
        BackHandler { finishAffinity() }
        V2GetThemeHost { BlockedScreen() }
    }

    @Composable
    private fun BlockedScreen() {
        val badge = when (reason) {
            V2GetBlockReason.REMOVED -> R.string.v2get_block_removed_badge
            V2GetBlockReason.DISABLED -> R.string.v2get_block_disabled_badge
            V2GetBlockReason.EXPIRED -> R.string.v2get_block_expired_badge
            V2GetBlockReason.EXHAUSTED -> R.string.v2get_block_exhausted_badge
        }
        val headline = when (reason) {
            V2GetBlockReason.REMOVED -> R.string.v2get_block_removed_headline
            V2GetBlockReason.DISABLED -> R.string.v2get_block_disabled_headline
            V2GetBlockReason.EXPIRED -> R.string.v2get_block_expired_headline
            V2GetBlockReason.EXHAUSTED -> R.string.v2get_block_exhausted_headline
        }
        val sub = when (reason) {
            V2GetBlockReason.REMOVED -> R.string.v2get_block_removed_sub
            V2GetBlockReason.DISABLED -> R.string.v2get_block_disabled_sub
            V2GetBlockReason.EXPIRED -> R.string.v2get_block_expired_sub
            V2GetBlockReason.EXHAUSTED -> R.string.v2get_block_exhausted_sub
        }
        val icon = if (reason == V2GetBlockReason.EXPIRED) R.drawable.ic_v2g_clock else R.drawable.ic_v2g_block

        Column(
            modifier = Modifier
                .fillMaxSize()
                .v2getBackground()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Pill(stringResource(R.string.v2get_brand), V2G.PinkSoft, V2G.Rose, mono = true)
                Spacer(Modifier.weight(1f))
                V2Logo(48.dp)
            }
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(V2G.Hot))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(badge), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = V2G.Hot)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(headline),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = V2G.Ink,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(stringResource(sub), fontSize = 13.sp, color = V2G.Muted, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))

            GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(V2G.LilacSoft).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBox(icon, size = 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(headline), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = V2G.Ink)
                    }
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .shadow(14.dp, RoundedCornerShape(50), ambientColor = V2G.Hot, spotColor = V2G.Hot)
                            .clip(RoundedCornerShape(50))
                            .background(V2G.BrandBrush)
                            .clickable(role = Role.Button) { openSupport(context) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            V2Icon(R.drawable.ic_v2g_support, Color.White, 22.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.v2get_block_support_cta) + "  @axis_developer",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { recheck() },
                        enabled = !checking,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, V2G.Border),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = V2G.Surface),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        V2Icon(R.drawable.ic_v2g_refresh, V2G.Rose, 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(if (checking) R.string.v2get_block_checking else R.string.v2get_block_recheck),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = V2G.Rose,
                        )
                    }
                    OutlinedButton(
                        onClick = { signOut() },
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, V2G.Border),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = V2G.Surface),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        V2Icon(R.drawable.ic_v2g_logout, V2G.Muted, 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.v2get_account_logout), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = V2G.Muted)
                    }
                }
            }
        }
    }
}
