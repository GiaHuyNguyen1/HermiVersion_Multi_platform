package com.hermitech.hermivision.ui.live.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermitech.hermivision.ui.live.LiveViewModel

/**
 * Full-screen overlay displayed when a live session is stopped.
 * Shows session aggregated stats with a Done button.
 */
@Composable
internal fun LiveResultsOverlay(sessionStats: LiveViewModel.SessionStats, onClose: () -> Unit)
{
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(OverlayBg.copy(alpha = 0.85f), OverlayBg.copy(alpha = 0.95f)))
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text("Session Results", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SessionStatCard("Frames", "${sessionStats.totalFrames}", AccentTeal)
                SessionStatCard("Ball Hits", "${sessionStats.ballDetections}", AccentGreen)
                SessionStatCard("Court", "${sessionStats.courtDetections}", CourtLineColor)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                val detectionRate = if (sessionStats.totalFrames > 0)
                    sessionStats.ballDetections * 100f / sessionStats.totalFrames else 0f
                SessionStatCard("Detect Rate", "%.1f%%".format(detectionRate), AccentOrange)
                SessionStatCard("Avg FPS", "%.1f".format(sessionStats.avgFps), AccentGreen)
                SessionStatCard("Duration", "%.1fs".format(sessionStats.durationMs / 1000f), TextPrimary)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onClose,
                modifier = Modifier.width(200.dp).height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)
            ) {
                Text("Done", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/** Small stat card used inside [LiveResultsOverlay]. */
@Composable
internal fun SessionStatCard(label: String, value: String, color: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.08f), modifier = Modifier.width(100.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
        ) {
            Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}