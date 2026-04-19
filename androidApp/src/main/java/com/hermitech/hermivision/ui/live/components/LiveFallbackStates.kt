package com.hermitech.hermivision.ui.live.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown when camera permission is denied. */
@Composable
internal fun PermissionDeniedContent(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(OverlayBg), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("📷", fontSize = 64.sp)
            Text("Camera Permission Required", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Allow camera access to use live analysis",
                color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)
            ) {
                Text("Grant Permission")
            }
        }
    }
}

/** Shown while the AI pipeline is initializing. */
@Composable
internal fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize().background(OverlayBg), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = AccentOrange, modifier = Modifier.size(48.dp))
            Text("Loading AI Models...", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("Initializing YOLO + Court Detection", color = TextSecondary, fontSize = 13.sp)
        }
    }
}

/** Shown when pipeline initialization fails. */
@Composable
internal fun ErrorContent(error: String) {
    Box(modifier = Modifier.fillMaxSize().background(OverlayBg), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text("⚠️", fontSize = 64.sp)
            Text("Initialization Error", color = BallDotColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(error, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}