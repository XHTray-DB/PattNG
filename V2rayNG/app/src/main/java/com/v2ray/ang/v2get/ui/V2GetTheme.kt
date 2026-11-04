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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.v2ray.ang.ui.compose.LocalDarkTheme
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

/**
 * The pearl-and-pink palette of the V2GET screens, taken from the Luminescent Prism Glass design, with a dark
 * variant for a phone in dark mode. The colors that carry text, icons and surfaces follow [dark]; the brand
 * colors that sit under white text (the gradients, the connect button) keep one value in both modes.
 *
 * [dark] is a snapshot state, so every composable that reads a color here is recomposed when it changes. It is set
 * once, before anything is drawn, by [V2GetThemeHost].
 */
object V2G {
    /** Whether the screens draw their dark palette; set by [V2GetThemeHost] from the app's theme. */
    var dark by mutableStateOf(false)
        internal set

    private fun pick(light: Long, night: Long) = Color(if (dark) night else light)

    // Brand colors, the same in both modes: white text and icons sit on them.
    val Pink = Color(0xFFFF758F)
    val RoseDeep = Color(0xFFA8334E)
    val HotDeep = Color(0xFFE20476)
    val MagentaDeep = Color(0xFFB5005D)
    val Green = Color(0xFF10B981)
    val Border = Color(0x26FF758F)

    // Text, icon and surface colors, lightened for the dark palette so that they stay readable on it.
    val Rose get() = pick(0xFFA8334E, 0xFFFF9DB2)
    val Magenta get() = pick(0xFFB5005D, 0xFFFF7BBD)
    val Hot get() = pick(0xFFE20476, 0xFFFF5BA5)
    val Pearl get() = pick(0xFFFDF8FF, 0xFF15111B)
    val Lilac get() = pick(0xFFF2EBFF, 0xFF30263F)
    val LilacSoft get() = pick(0xFFF7F1FF, 0xFF271F33)
    val PinkSoft get() = pick(0xFFFFE3E9, 0xFF402230)
    val Ink get() = pick(0xFF1D192E, 0xFFF3ECF7)
    val Muted get() = pick(0xFF7A6B70, 0xFFB9ADBB)
    val GreenSoft get() = pick(0xFFD9F7EC, 0xFF11382D)
    val Error get() = pick(0xFFBA1A1A, 0xFFFFB4AB)
    val Amber get() = pick(0xFF805521, 0xFFF3C987)
    val AmberSoft get() = pick(0xFFFFEFD9, 0xFF3B2D17)

    /** The face of cards, chips and bars: white in the light palette, a deep violet-grey in the dark one. */
    val Surface get() = pick(0xFFFFFFFF, 0xFF211A2B)

    /** The inner face of the connect button's ring. */
    val ButtonFace get() = pick(0xFFFFE9F0, 0xFF2C2036)

    val BrandBrush: Brush = Brush.horizontalGradient(listOf(RoseDeep, HotDeep, MagentaDeep))
    val SoftBrandBrush: Brush = Brush.horizontalGradient(listOf(Pink, HotDeep))
    val Mono: FontFamily = FontFamily.Monospace
}

/**
 * Applies the app's light or dark theme to the V2GET palette and gives the screens its text color. Every V2GET
 * screen's content goes inside it, in the right-to-left layout the design is written in.
 */
@Composable
fun V2GetThemeHost(rtl: Boolean = true, content: @Composable () -> Unit) {
    // Written while composing, before the first color is read, so that no frame is drawn in the wrong palette.
    V2G.dark = LocalDarkTheme.current
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LocalLayoutDirection.current,
        LocalContentColor provides V2G.Ink,
        content = content,
    )
}

/** The pearl-to-pink wash behind every V2GET screen, and its violet night counterpart. */
@Composable
fun Modifier.v2getBackground(): Modifier = background(
    Brush.verticalGradient(
        if (V2G.dark) listOf(Color(0xFF1C1324), Color(0xFF15111B), Color(0xFF1A1120))
        else listOf(Color(0xFFFFEFF4), Color(0xFFFDF8FF), Color(0xFFFCEEF5))
    )
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
            .background(V2G.Surface.copy(alpha = 0.94f))
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
            .background(V2G.Surface.copy(alpha = 0.85f))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        V2Logo(48.dp)
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
                .background(Brush.linearGradient(listOf(V2G.Pink, V2G.RoseDeep)))
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
            .background(V2G.Surface)
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

/** The V2GET logo, the app's icon, wherever the screens need the brand mark. It is decorative: the brand name beside it names it. */
@Composable
fun V2Logo(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painter = painterResource(R.drawable.v2get_logo),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}


private const val SUPPORT_URL = "https://t.me/axis_developer"

/** Opens the support chat on Telegram (the Telegram app when installed, the browser otherwise). */
fun openSupport(context: android.content.Context) {
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SUPPORT_URL))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: android.content.ActivityNotFoundException) {
    }
}

/** The card that sends the person to Telegram support: buying, renewing, any trouble. */
@Composable
fun SupportCard(title: String, body: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    GlassCard(modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { openSupport(context) }, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBox(R.drawable.ic_v2g_support, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = V2G.Ink)
                Text(body, fontSize = 12.sp, color = V2G.Muted)
            }
            Text("@axis_developer", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = V2G.Mono, color = V2G.Magenta)
        }
    }
}
