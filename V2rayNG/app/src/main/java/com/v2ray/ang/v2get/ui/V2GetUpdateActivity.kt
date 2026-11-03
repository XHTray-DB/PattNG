package com.v2ray.ang.v2get.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.BaseComponentActivity

/**
 * Shown when the panel refuses this app because a newer one exists. There is no way past it but the download
 * link: back only sends the app to the background, and the screens behind it check the version again when opened.
 */
class V2GetUpdateActivity : BaseComponentActivity() {

    companion object {
        const val EXTRA_VERSION = "v2get_update_version"
        const val EXTRA_URL = "v2get_update_url"
        const val EXTRA_NOTES = "v2get_update_notes"
    }

    @Composable
    override fun ScreenContent() {
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val notes = intent.getStringExtra(EXTRA_NOTES).orEmpty()
        BackHandler { moveTaskToBack(false) }
        V2GetThemeHost {
            UpdateScreen(version = version, url = url, notes = notes, onDownload = { open(url) })
        }
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.v2get_update_no_link))
        }
    }

    @Composable
    private fun UpdateScreen(version: String, url: String, notes: String, onDownload: () -> Unit) {
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
                Box(Modifier.size(8.dp).clip(CircleShape).background(V2G.Magenta))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.v2get_update_badge), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = V2G.Magenta)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.v2get_update_headline),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = V2G.Ink,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.v2get_update_sub), fontSize = 13.sp, color = V2G.Muted, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))

            GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(V2G.LilacSoft).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBox(R.drawable.ic_v2g_refresh, size = 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.v2get_update_new_version), fontSize = 11.sp, color = V2G.Muted)
                            Text(version.ifBlank { "—" }, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = V2G.Mono, color = V2G.Ink)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(stringResource(R.string.v2get_update_your_version), fontSize = 11.sp, color = V2G.Muted)
                            Text(BuildConfig.VERSION_NAME, fontSize = 13.sp, fontFamily = V2G.Mono, color = V2G.Muted)
                        }
                    }
                    if (notes.isNotBlank()) {
                        Text(notes, fontSize = 13.sp, color = V2G.Ink, modifier = Modifier.fillMaxWidth())
                    }
                    if (url.isBlank()) {
                        Text(
                            stringResource(R.string.v2get_update_no_link),
                            color = V2G.Error,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .shadow(14.dp, RoundedCornerShape(50), ambientColor = V2G.Hot, spotColor = V2G.Hot)
                                .clip(RoundedCornerShape(50))
                                .background(V2G.BrandBrush)
                                .clickable(role = Role.Button, onClick = onDownload),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                V2Icon(R.drawable.ic_v2g_arrow_down, Color.White, 22.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.v2get_update_cta),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.v2get_update_install_hint),
                            fontSize = 11.sp,
                            color = V2G.Muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
