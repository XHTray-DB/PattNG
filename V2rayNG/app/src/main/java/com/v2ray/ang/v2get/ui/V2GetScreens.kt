package com.v2ray.ang.v2get.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.v2get.V2GetCountry
import com.v2ray.ang.v2get.V2GetQuota
import com.v2ray.ang.v2get.V2GetServer
import com.v2ray.ang.v2get.V2GetSession
import java.text.NumberFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.delay

/** A byte count in the unit that reads best: KB, MB or GB. */
internal fun bytesText(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1e6)
    else -> "%d KB".format(bytes / 1_000L)
}

private fun gb(value: Double) = "%.1f".format(value)

/**
 * What each server is called on screen: its flag and country once the app has measured the country, else the
 * name of its configuration. Two servers of one country are told apart by a number.
 */
internal fun serverTitles(servers: List<V2GetServer>): Map<String, String> {
    val numbers = NumberFormat.getInstance(Locale("fa"))
    val perCountry = servers.filter { it.countryCode.isNotBlank() }.groupBy { it.countryCode }
    return servers.associate { server ->
        val title = V2GetCountry.title(server.countryCode, server.name)
        val peers = perCountry[server.countryCode].orEmpty()
        server.guid to if (peers.size > 1) "$title ${numbers.format(peers.indexOf(server) + 1)}" else title
    }
}

/** The flag of a server's country in a rounded box; the globe while the country is not known yet. */
@Composable
private fun FlagBox(countryCode: String, size: androidx.compose.ui.unit.Dp) {
    if (countryCode.isBlank()) {
        IconBox(R.drawable.ic_v2g_globe, background = V2G.LilacSoft, size = size)
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(V2G.LilacSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(V2GetCountry.flag(countryCode), fontSize = (size.value * 0.55f).sp)
        }
    }
}

private fun familyLabel(family: String) = if (family == "AETHER") "Aether" else family

// ---------------------------------------------------------------------------------------------
// Connect
// ---------------------------------------------------------------------------------------------

@Composable
fun ConnectTab(state: V2GetUiState, vpnMode: Boolean, onToggle: () -> Unit, onChangeServer: () -> Unit = {}) {
    val connection = state.connection
    val connected = connection == V2GetConnection.Connected
    val server = state.servers.firstOrNull { it.guid == state.selectedGuid } ?: state.servers.firstOrNull()
    val serverName = server?.let { serverTitles(state.servers)[it.guid] } ?: stringResource(R.string.v2get_server_name)
    val protocolText = server?.protocol?.takeIf { it.isNotBlank() } ?: stringResource(R.string.v2get_protocol_value)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(14.dp))
        StatusCard(connection, state.phase)
        Spacer(Modifier.height(18.dp))
        PowerButton(connection, onToggle)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(protocolText, Color.White, V2G.Ink, mono = true)
            Pill(
                stringResource(
                    when (connection) {
                        V2GetConnection.Connected -> R.string.v2get_state_active
                        else -> R.string.v2get_state_ready
                    }
                ),
                if (connected) V2G.GreenSoft else V2G.PinkSoft,
                if (connected) V2G.Green else V2G.Rose,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SpeedCard(R.string.v2get_download, R.drawable.ic_v2g_arrow_down, connected, state.live.downSpeed, state.live.down, Modifier.weight(1f))
            SpeedCard(R.string.v2get_upload, R.drawable.ic_v2g_arrow_up, connected, state.live.upSpeed, state.live.up, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        // The whole card opens the server list: the swap icon on it is what the person taps to change the server.
        GlassCard(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = stringResource(R.string.v2get_change_server), role = Role.Button, onClick = onChangeServer)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlagBox(server?.countryCode.orEmpty(), 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(serverName, fontSize = 16.sp, fontWeight = FontWeight.Black, color = V2G.Ink)
                    Text(stringResource(R.string.v2get_server_detail), fontSize = 12.sp, color = V2G.Muted)
                }
                V2Icon(R.drawable.ic_v2g_swap, V2G.Hot, 22.dp)
            }
        }
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                DetailLine(R.string.v2get_duration, R.drawable.ic_v2g_clock) {
                    Text(durationText(connected), fontSize = 16.sp, fontWeight = FontWeight.Black, color = V2G.Ink, fontFamily = V2G.Mono)
                }
                HorizontalDivider(color = V2G.Border)
                DetailLine(R.string.v2get_tunnel_mode, R.drawable.ic_v2g_tune) {
                    Pill(
                        stringResource(if (vpnMode) R.string.v2get_tunnel_vpn else R.string.v2get_tunnel_proxy),
                        V2G.PinkSoft,
                        V2G.Rose,
                        mono = true,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun durationText(connected: Boolean): String {
    var seconds by remember(connected) { mutableLongStateOf(0L) }
    LaunchedEffect(connected) {
        while (connected) {
            delay(1000)
            seconds += 1
        }
    }
    return "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

@Composable
private fun DetailLine(label: Int, icon: Int, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        V2Icon(icon, V2G.Hot, 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(label), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = V2G.Ink, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun StatusCard(connection: V2GetConnection, phase: V2GetPhase = V2GetPhase.None) {
    val connected = connection == V2GetConnection.Connected
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBox(R.drawable.ic_v2g_shield_check, tint = if (connected) V2G.Hot else V2G.Muted, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            when (connection) {
                                V2GetConnection.Connected -> R.string.v2get_conn_connected_title
                                V2GetConnection.Connecting -> R.string.v2get_conn_connecting_title
                                V2GetConnection.Disconnected -> R.string.v2get_conn_disconnected_title
                            }
                        ),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black,
                        color = V2G.Ink,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) V2G.Green else V2G.Muted))
                }
                Text(
                    stringResource(
                        when (connection) {
                            V2GetConnection.Connected -> R.string.v2get_conn_connected_sub
                            V2GetConnection.Connecting -> when (phase) {
                                V2GetPhase.Tcping -> R.string.v2get_phase_tcping
                                V2GetPhase.RealPing -> R.string.v2get_phase_real
                                V2GetPhase.None -> R.string.v2get_conn_connecting_sub
                            }
                            V2GetConnection.Disconnected -> R.string.v2get_conn_disconnected_sub
                        }
                    ),
                    fontSize = 12.sp,
                    color = V2G.Muted,
                )
            }
        }
    }
}

@Composable
private fun PowerButton(connection: V2GetConnection, onToggle: () -> Unit) {
    val connected = connection == V2GetConnection.Connected
    val busy = connection != V2GetConnection.Disconnected
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (busy) 1.06f else 1.015f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val halo by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = if (busy) 0.6f else 0.35f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "haloAlpha",
    )
    val actionLabel = stringResource(
        if (connection == V2GetConnection.Disconnected) R.string.v2get_connect_action else R.string.v2get_disconnect_action
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(290.dp)) {
        Box(
            Modifier
                .size(280.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(V2G.Pink.copy(alpha = halo), Color.Transparent)))
        )
        Box(
            Modifier
                .size(260.dp)
                .shadow(18.dp, CircleShape, ambientColor = V2G.Pink, spotColor = V2G.Hot)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFFF9AAE), V2G.Hot)))
                .padding(10.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color.White, Color(0xFFFFE9F0))))
                .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(184.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.8f))
                    .border(1.dp, V2G.Border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    V2Icon(
                        R.drawable.ic_v2g_power,
                        if (connected) V2G.Hot else V2G.Rose,
                        64.dp,
                        Modifier.clearAndSetSemantics {},
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            when (connection) {
                                V2GetConnection.Connected -> R.string.v2get_power_stop
                                V2GetConnection.Connecting -> R.string.v2get_power_connecting
                                V2GetConnection.Disconnected -> R.string.v2get_power_connect
                            }
                        ),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        color = V2G.Rose,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedCard(label: Int, icon: Int, connected: Boolean, speed: Long, total: Long, modifier: Modifier) {
    GlassCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(V2G.PinkSoft),
                    contentAlignment = Alignment.Center,
                ) { V2Icon(icon, V2G.Hot, 18.dp) }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(label), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = V2G.Ink)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (connected) "%.2f".format(speed / 1_000_000.0) else stringResource(R.string.v2get_no_value),
                    fontSize = 28.sp, fontWeight = FontWeight.Black, color = V2G.Ink,
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.v2get_unit_mbps), fontSize = 11.sp, color = V2G.Muted, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(V2G.PinkSoft))
            if (connected) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.v2get_used_this_connection) + ": " + bytesText(total),
                    fontSize = 11.sp, color = V2G.Muted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Servers
// ---------------------------------------------------------------------------------------------

@Composable
fun ServersTab(state: V2GetUiState, onConnect: (String) -> Unit, onDisconnect: () -> Unit) {
    val connected = state.connection == V2GetConnection.Connected
    val busy = state.connection != V2GetConnection.Disconnected
    val count = NumberFormat.getInstance(Locale("fa")).format(state.servers.size)
    val titles = serverTitles(state.servers)
    // The filter is one of the kinds the list holds; a kind that left the list falls back to all.
    var filter by remember { mutableStateOf<String?>(null) }
    val families = state.servers.map { it.family }.filter { it.isNotBlank() }.distinct()
    val shown = state.servers.filter { filter == null || it.family == filter || filter !in families }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            val allOn = filter == null || filter !in families
            Text(
                stringResource(R.string.v2get_servers_filter_all, count),
                color = if (allOn) Color.White else V2G.Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (allOn) V2G.SoftBrandBrush else Brush.linearGradient(listOf(Color.White, Color.White)))
                    .clickable { filter = null }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (families.size > 1) families.forEach { family ->
                val on = filter == family
                Text(
                    familyLabel(family),
                    color = if (on) Color.White else V2G.Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) V2G.SoftBrandBrush else Brush.linearGradient(listOf(Color.White, Color.White)))
                        .clickable { filter = family }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(V2G.Green))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.v2get_servers_best), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = V2G.Ink)
        }
        if (state.servers.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.v2get_servers_empty), fontSize = 13.sp, color = V2G.Muted, modifier = Modifier.padding(18.dp))
            }
        }
        shown.forEachIndexed { index, server ->
            val isSelected = server.guid == state.selectedGuid
            val isRunning = isSelected && busy
            Spacer(Modifier.height(10.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlagBox(server.countryCode, 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(titles[server.guid] ?: server.name, fontSize = 17.sp, fontWeight = FontWeight.Black, color = V2G.Ink)
                            Text(
                                stringResource(
                                    if (server.countryCode.isBlank()) R.string.v2get_servers_country_pending else R.string.v2get_servers_unlimited
                                ),
                                fontSize = 12.sp,
                                color = V2G.Muted,
                            )
                        }
                        if (index == 0) Pill(stringResource(R.string.v2get_servers_recommended), V2G.PinkSoft, V2G.Rose)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(V2G.LilacSoft).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.v2get_servers_protocol), fontSize = 12.sp, color = V2G.Muted)
                        Spacer(Modifier.width(8.dp))
                        Pill(server.protocol.ifBlank { stringResource(R.string.v2get_protocol_value) }, Color.White, V2G.Rose, mono = true)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (connected && isSelected) {
                            V2Icon(R.drawable.ic_v2g_check_circle, V2G.Green, 18.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.v2get_servers_connected_default), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = V2G.Green)
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { if (isRunning) onDisconnect() else onConnect(server.guid) },
                            shape = RoundedCornerShape(50),
                            border = androidx.compose.foundation.BorderStroke(1.dp, V2G.Border),
                        ) {
                            Text(
                                stringResource(
                                    if (isRunning) R.string.v2get_servers_disconnect_here else R.string.v2get_servers_connect_here
                                ),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = V2G.Rose,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.v2get_failover_title), fontSize = 14.sp, fontWeight = FontWeight.Black, color = V2G.Ink)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.v2get_failover_body), fontSize = 12.sp, color = V2G.Muted)
                }
                Spacer(Modifier.width(10.dp))
                IconBox(R.drawable.ic_v2g_bolt, size = 40.dp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Account
// ---------------------------------------------------------------------------------------------

@Composable
fun AccountTab(session: V2GetSession?, onCopyId: (String) -> Unit, onLogout: () -> Unit) {
    val unlimited = session != null && session.quotaBytes <= 0
    val totalGb = session?.let { V2GetQuota.toGigabytes(it.quotaBytes) } ?: 0.0
    val usedGb = session?.let { V2GetQuota.toGigabytes(it.usedBytes) } ?: 0.0
    val remainingGb = session?.let { V2GetQuota.toGigabytes(V2GetQuota.remainingBytes(it)) } ?: 0.0
    val fraction = session?.let { V2GetQuota.usedFraction(it) } ?: 0f
    val block = session?.let { V2GetQuota.blockReason(it) } ?: V2GetQuota.Block.NONE
    val active = session != null && block == V2GetQuota.Block.NONE
    val expiry = session?.let { V2GetQuota.parseDate(it.expiresAt) }
    val daysLeft = expiry?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
    val name = session?.username?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.v2get_default_user)
    val accountId = remember(session?.token) { session?.token?.let { accountIdOf(it) }.orEmpty() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(R.drawable.ic_v2g_shield_check, size = 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.v2get_account_vip), fontSize = 18.sp, fontWeight = FontWeight.Black, color = V2G.Ink)
                            Spacer(Modifier.width(8.dp))
                            Pill(name, V2G.PinkSoft, V2G.Rose)
                        }
                        Text(stringResource(R.string.v2get_account_sub_active), fontSize = 12.sp, color = V2G.Muted)
                    }
                    Pill(
                        stringResource(if (active) R.string.v2get_account_active else R.string.v2get_account_inactive),
                        if (active) V2G.GreenSoft else V2G.PinkSoft,
                        if (active) V2G.Green else V2G.Error,
                    )
                }
                if (accountId.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(V2G.LilacSoft).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.v2get_account_id_label), fontSize = 11.sp, color = V2G.Muted)
                            Text(accountId, fontSize = 14.sp, fontFamily = V2G.Mono, color = V2G.Ink)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .clickable(role = Role.Button) { onCopyId(accountId) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            V2Icon(R.drawable.ic_v2g_copy, V2G.Rose, 16.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.v2get_account_copy), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = V2G.Rose)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            expiry == null -> stringResource(R.string.v2get_account_no_expiry)
                            else -> stringResource(R.string.v2get_account_expires_on, session?.expiresAt?.take(10).orEmpty())
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = V2G.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (daysLeft != null) {
                        Pill(
                            if (daysLeft >= 0) stringResource(R.string.v2get_account_days_left, daysLeft.toInt())
                            else stringResource(R.string.v2get_account_expired_badge),
                            V2G.PinkSoft,
                            V2G.Rose,
                            mono = true,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    V2Icon(R.drawable.ic_v2g_donut, V2G.Hot, 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.v2get_account_quota_cycle), fontSize = 16.sp, fontWeight = FontWeight.Black, color = V2G.Ink)
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.v2get_account_total_quota), fontSize = 12.sp, color = V2G.Muted)
                        Text(
                            if (unlimited) stringResource(R.string.v2get_account_unlimited)
                            else stringResource(R.string.v2get_gigabytes, gb(totalGb)),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = V2G.Ink,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.v2get_account_remaining_volume), fontSize = 12.sp, color = V2G.Muted)
                        Text(
                            if (unlimited) "∞" else stringResource(R.string.v2get_gigabytes, gb(remainingGb)),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = V2G.Magenta,
                        )
                    }
                    UsageDonut(fraction)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.v2get_account_gb_used, gb(usedGb)), fontSize = 11.sp, fontFamily = V2G.Mono, color = V2G.Muted)
                    if (!unlimited) {
                        Text(stringResource(R.string.v2get_account_gb_total, "%.0f".format(totalGb)), fontSize = 11.sp, fontFamily = V2G.Mono, color = V2G.Muted)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(V2G.Lilac)) {
                    if (fraction > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(V2G.SoftBrandBrush)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        OutlinedButton(
            onClick = onLogout,
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, V2G.Border),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            V2Icon(R.drawable.ic_v2g_logout, V2G.Rose, 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.v2get_account_logout), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = V2G.Rose)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A short, stable identifier derived from the token, so that the token itself is never shown or copied. */
internal fun accountIdOf(token: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    return digest.take(6).joinToString("") { "%02x".format(it) }.chunked(4).joinToString("-")
}

@Composable
private fun UsageDonut(fraction: Float) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(132.dp)) {
        Canvas(Modifier.size(132.dp)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = V2G.Lilac,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke),
            )
            if (fraction > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(V2G.Pink, V2G.Hot, V2G.Magenta, V2G.Pink)),
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(fraction * 100).toInt()}%", fontSize = 28.sp, fontWeight = FontWeight.Black, color = V2G.Ink)
            Text(stringResource(R.string.v2get_account_used_percent), fontSize = 11.sp, color = V2G.Muted)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsTab(
    versionName: String,
    perAppProxy: Boolean,
    zeroDrop: Boolean,
    dnsProtected: Boolean,
    autoBest: Boolean = true,
    onAutoBest: (Boolean) -> Unit = {},
    onSplitTunneling: () -> Unit,
    onKillSwitch: () -> Unit,
    onZeroDrop: (Boolean) -> Unit,
    onDnsProtected: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.v2get_settings_title), fontSize = 24.sp, fontWeight = FontWeight.Black, color = V2G.Ink)
                    Spacer(Modifier.width(8.dp))
                    Pill(versionName, V2G.PinkSoft, V2G.Rose, mono = true)
                }
                Text(stringResource(R.string.v2get_settings_subtitle), fontSize = 12.sp, color = V2G.Muted)
            }
        }
        Spacer(Modifier.height(18.dp))
        SectionTitle(R.string.v2get_settings_core_section, R.drawable.ic_v2g_bolt)
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBox(R.drawable.ic_v2g_bolt, tint = Color.White, background = V2G.Magenta, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.v2get_settings_core_title), fontSize = 16.sp, fontWeight = FontWeight.Black, color = V2G.Ink)
                    Text(stringResource(R.string.v2get_settings_core_body), fontSize = 12.sp, color = V2G.Muted)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        SectionTitle(R.string.v2get_settings_network_section, R.drawable.ic_v2g_tune)
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                ActionRow(
                    R.drawable.ic_v2g_swap, R.string.v2get_settings_split_title, R.string.v2get_settings_split_body,
                    status = stringResource(if (perAppProxy) R.string.v2get_settings_state_on else R.string.v2get_settings_state_off),
                    onClick = onSplitTunneling,
                )
                HorizontalDivider(color = V2G.Border)
                ActionRow(
                    R.drawable.ic_v2g_block, R.string.v2get_settings_kill_title, R.string.v2get_settings_kill_body,
                    status = stringResource(R.string.v2get_settings_kill_open),
                    onClick = onKillSwitch,
                )
                HorizontalDivider(color = V2G.Border)
                ToggleRow(
                    R.drawable.ic_v2g_bolt, R.string.v2get_settings_auto_title, R.string.v2get_settings_auto_body,
                    checked = autoBest, onChecked = onAutoBest,
                )
                HorizontalDivider(color = V2G.Border)
                ToggleRow(
                    R.drawable.ic_v2g_check_circle, R.string.v2get_settings_drop_title, R.string.v2get_settings_drop_body,
                    checked = zeroDrop, onChecked = onZeroDrop,
                )
                HorizontalDivider(color = V2G.Border)
                ToggleRow(
                    R.drawable.ic_v2g_lock, R.string.v2get_settings_dns_title, R.string.v2get_settings_dns_body,
                    checked = dnsProtected, onChecked = onDnsProtected,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        SectionTitle(R.string.v2get_settings_about_section, R.drawable.ic_v2g_info)
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(R.drawable.ic_v2g_shield, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.v2get_settings_version), fontSize = 15.sp, fontWeight = FontWeight.Black, color = V2G.Ink)
                        Text(stringResource(R.string.v2get_settings_version_value, versionName), fontSize = 12.sp, color = V2G.Muted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.v2get_settings_licenses),
                    fontSize = 12.sp,
                    fontFamily = V2G.Mono,
                    color = V2G.Rose,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(V2G.LilacSoft).padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: Int, icon: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        V2Icon(icon, V2G.Hot, 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(text), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = V2G.Ink)
    }
}

/** A setting with a switch; the change is stored by [onChecked] and read back by the screen. */
@Composable
private fun ToggleRow(icon: Int, title: Int, body: Int, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBox(icon, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = V2G.Ink)
            Text(stringResource(body), fontSize = 11.sp, color = V2G.Muted)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** A setting that is chosen on another screen: the row shows where it stands and opens that screen. */
@Composable
private fun ActionRow(icon: Int, title: Int, body: Int, status: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBox(icon, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = V2G.Ink)
            Text(stringResource(body), fontSize = 11.sp, color = V2G.Muted)
        }
        Spacer(Modifier.width(8.dp))
        Text(status, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = V2G.Rose, textAlign = TextAlign.Center)
        Spacer(Modifier.width(4.dp))
        V2Icon(R.drawable.ic_v2g_chevron, V2G.Muted, 18.dp)
    }
}
