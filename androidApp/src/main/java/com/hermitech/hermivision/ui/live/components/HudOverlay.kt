package com.hermitech.hermivision.ui.live.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermitech.hermivision.domain.model.CourtResult
import com.hermitech.hermivision.ui.live.LiveViewModel

/**
 * Always-on HUD overlay: FPS badge, close button, status chips, STOP button, LIVE indicator.
 */
@Composable
internal fun HudOverlay(ballState: LiveViewModel.BallState, courtResult: CourtResult, fps: Float, miniMapState: LiveViewModel.MiniMapState, onStop: () -> Unit, onClose: () -> Unit)
{
    Box(modifier = Modifier.fillMaxSize()) {

        // ── Top-left: FPS badge ──
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Black.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = when { fps > 7f -> AccentGreen; fps > 4f -> AccentOrange; else -> BallDotColor },
                    modifier = Modifier.size(8.dp)
                ) {}
                Spacer(modifier = Modifier.width(6.dp))
                Text("%.1f FPS".format(fps), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── Top-right: Close button ──
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
        ) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.5f), modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary,
                    modifier = Modifier.padding(8.dp).fillMaxSize())
            }
        }

        // ── Bottom-left: Status chips ──
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatusChip(
                label = "Ball",
                value = if (ballState.isVisible) "(%.0f, %.0f) %.0f%%".format(ballState.x, ballState.y, ballState.score * 100) else "—",
                dotColor = if (ballState.isVisible) AccentGreen else TextSecondary
            )
            StatusChip(
                label = "Court",
                value = if (courtResult.valid) "Detected" else "—",
                dotColor = if (courtResult.valid) CourtLineColor else TextSecondary
            )
        }

        // ── Bottom-center: STOP button ──
        Button(
            onClick = onStop,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).height(48.dp).width(140.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = StopRed),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
        ) {
            Text("STOP", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        // ── Right side: Mini-map ──
        if (miniMapState.isValid) {
            MiniCourtMap(
                miniMapState = miniMapState,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
            )
        }

        // ── Bottom-right: LIVE indicator ──
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Black.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Surface(shape = CircleShape, color = BallDotColor, modifier = Modifier.size(8.dp)) {}
                Spacer(modifier = Modifier.width(6.dp))
                Text("LIVE", color = BallDotColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Small pill-shaped status indicator chip. */
@Composable
internal fun StatusChip(label: String, value: String, dotColor: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.5f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Surface(shape = CircleShape, color = dotColor, modifier = Modifier.size(6.dp)) {}
            Spacer(modifier = Modifier.width(6.dp))
            Text("$label: ", color = TextSecondary, fontSize = 11.sp)
            Text(value, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}