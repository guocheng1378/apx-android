package com.allperiph.ui.compose

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allperiph.ui.miuix.ApInsets
import com.allperiph.ui.miuix.rememberApAdaptiveLayout
import com.allperiph.ui.miuix.rememberApInsets

@Composable
fun ApKeyboardPage(topBar: Dp = 0.dp, bottomGap: Dp = 0.dp) {
    val adaptive = rememberApAdaptiveLayout()
    val widthDp = adaptive.width.value
    val shortSide = minOf(widthDp, adaptive.height.value)
    val hPad = when {
        shortSide >= 600f -> 32.dp
        shortSide >= 520f -> 24.dp
        else -> 12.dp
    }
    val limited = widthDp >= 840f && shortSide >= 520f
    val insets = rememberApInsets()
    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            modifier = (if (limited) androidx.compose.foundation.layout.Modifier.widthIn(max = 680.dp) else androidx.compose.foundation.layout.Modifier)
                .fillMaxSize()
                .padding(start = hPad + insets.left, end = hPad + insets.right),
        ) {
            androidx.compose.ui.unit.IntSize.Zero
            androidx.compose.ui.unit.IntSize.Zero
        }
    }
}