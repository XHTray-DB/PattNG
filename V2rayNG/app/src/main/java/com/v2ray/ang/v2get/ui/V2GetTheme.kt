package com.v2ray.ang.v2get.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R

/** The pearl-and-pink palette of the V2GET screens, taken from the Luminescent Prism Glass design. */
object V2G {
    val Pink = Color(0xFFFF758F)
    val Rose = Color(0xFFA8334E)
    val Magenta = Color(0xFFB5005D)
    val Hot = Color(0xFFE20476)
    val Pearl = Color(0xFFFDF8FF)
    val Lilac = Color(0xFFF2EBFF)
    val LilacSoft = Color(0xFFF7F1FF)
    val PinkSoft = Color(0xFFFFE3E9)
    val Ink = Color(0xFF1D192E)
    val Muted = Color(0xFF7A6B70)
    val Green = Color(0xFF10B981)
    val GreenSoft = Color(0xFFD9F7EC)
    val Error = Color(0xFFBA1A1A)
    val Amber = Color(0xFF805521)
    val AmberSoft = Color(0xFFFFEFD9)
    val Border = Color(0x26FF758F)

    val BrandBrush: Brush = Brush.horizontalGradient(listOf(Rose, Hot, Magenta))
    val SoftBrandBrush: Brush = Brush.horizontalGradient(listOf(Pink, Hot))
    val Mono: FontFamily = FontFamily.Monospace
}

/** The pearl-to-pink wash behind every V2GET screen. */
@Composable
fun Modifier.v2getBackground(): Modifier = background(
    Brush.verticalGradient(listOf(Color(0xFFFFEFF4), Color(0xFFFDF8FF), Color(0xFFFCEEF5)))
)

/** A white glass card with the soft pink hairline and shadow every panel in the design shares. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .shadow(6.dp, shape, ambientColor = V2G.Pink.copy(alpha = 0.25f), spotColor = V2G.Pink.copy(alpha = 0.25f))
            .clip(shape)
            .background(Color.White.copy(alpha = 0.94f))
            .border(1.dp, V2G.Border, shape),
        content = content,
    )
}

@Composable
fun V2Icon(@DrawableRes id: Int, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** A small rounded label such as the protocol or a status chip. */
@Composable
fun Pill(text: String, background: Color, color: Color, mono: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = if (mono) V2G.Mono else null,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A rounded square holding an icon, the leading mark of cards and rows. */
@Composable
fun IconBox(@DrawableRes icon: Int, tint: Color = V2G.Rose, background: Color = V2G.PinkSoft, size: Dp = 44.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(background),
    ) { V2Icon(icon, tint, size = size / 2) }
}

enum class V2GetTab { Connect, Servers, Account, Settings }

@Composable
fun V2GetTopBar(connected: Boolean, onOpenAccount: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.85f))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .shadow(6.dp, RoundedCornerShape(14.dp), spotColor = V2G.Pink)
                .clip(RoundedCornerShape(14.dp))
                .background(V2G.SoftBrandBrush),
        ) { V2Icon(R.drawable.ic_v2g_lock, Color.White, 22.dp) }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.v2get_brand), fontSize = 20.sp, fontWeight = FontWeight.Black, color = V2G.Ink)
            Text(stringResource(R.string.v2get_client_subtitle), fontSize = 11.sp, color = V2G.Muted)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(V2G.LilacSoft)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) V2G.Green else V2G.Muted))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (connected) R.string.v2get_top_connected else R.string.v2get_top_disconnected),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = V2G.Ink,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(V2G.Pink, V2G.Rose)))
                .clickable(onClickLabel = stringResource(R.string.v2get_open_account), role = Role.Button, onClick = onOpenAccount),
        ) { V2Icon(R.drawable.ic_v2g_person, Color.White, 20.dp) }
    }
}

@Composable
fun V2GetBottomBar(selected: V2GetTab, onSelect: (V2GetTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(16.dp, ambientColor = V2G.Pink, spotColor = V2G.Pink)
            .background(Color.White)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        NavItem(V2GetTab.Connect, R.drawable.ic_v2g_power, R.string.v2get_nav_connect, selected, onSelect)
        NavItem(V2GetTab.Servers, R.drawable.ic_v2g_globe, R.string.v2get_nav_servers, selected, onSelect)
        NavItem(V2GetTab.Account, R.drawable.ic_v2g_donut, R.string.v2get_nav_account_short, selected, onSelect)
        NavItem(V2GetTab.Settings, R.drawable.ic_v2g_tune, R.string.v2get_nav_settings, selected, onSelect)
    }
}

@Composable
private fun NavItem(
    tab: V2GetTab,
    @DrawableRes icon: Int,
    label: Int,
    selected: V2GetTab,
    onSelect: (V2GetTab) -> Unit,
) {
    val active = tab == selected
    val tint = if (active) V2G.Hot else V2G.Ink.copy(alpha = 0.7f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) })
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        V2Icon(icon, tint, 26.dp)
        Text(
            stringResource(label),
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Black else FontWeight.Medium,
            color = tint,
        )
    }
}
