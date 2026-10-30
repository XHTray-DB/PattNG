package com.v2ray.ang.v2get.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

/** The pearl-to-pink wash behind every V2GET screen, from the colors of the app theme. */
@Composable
fun Modifier.v2getBackground(): Modifier {
    val scheme = MaterialTheme.colorScheme
    return background(
        Brush.verticalGradient(listOf(scheme.background, scheme.surfaceVariant.copy(alpha = 0.35f), scheme.primaryContainer))
    )
}
