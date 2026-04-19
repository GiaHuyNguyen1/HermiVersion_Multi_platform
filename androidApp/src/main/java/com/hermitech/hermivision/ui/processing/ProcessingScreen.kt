package com.hermitech.hermivision.ui.processing

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AccentOrange = Color(0xFFFF6B35)
private val AccentGreen = Color(0xFF4CAF50)
private val DarkBg = Color(0xFF0F0F23)
private val CardBg = Color(0xFF1A1A2E)

@Composable
fun ProcessingScreen(uiState: ProcessingUiState, onCancelClick: () -> Unit, onViewResultsClick: () -> Unit, onRetryClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = DarkBg) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                uiState.error != null -> ErrorContent(
                    error = uiState.error,
                    onRetryClick = onRetryClick
                )
                uiState.isComplete -> CompleteContent(
                    uiState = uiState,
                    onViewResultsClick = onViewResultsClick
                )
                uiState.isProcessing -> ProcessingContent(
                    uiState = uiState,
                    onCancelClick = onCancelClick
                )
            }
        }
    }
}

@Composable
private fun ProcessingContent(uiState: ProcessingUiState, onCancelClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "spinner")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(
        modifier = Modifier
            .size(80.dp)
            .rotate(rotation)
    ) {
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(Color.Transparent, AccentOrange)
            ),
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 6f, cap = StrokeCap.Round)
        )
    }

    Spacer(modifier = Modifier.height(32.dp))

    Text(
        text = uiState.stage,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.White
    )

    Spacer(modifier = Modifier.height(16.dp))

    LinearProgressIndicator(
        progress = { uiState.progressPercent / 100f },
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .height(6.dp),
        color = AccentOrange,
        trackColor = CardBg,
        strokeCap = StrokeCap.Round
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "${uiState.progressPercent}%",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.6f)
    )

    if (uiState.currentFrame >= 0) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Frame ${uiState.currentFrame}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f)
        )
    }

    Spacer(modifier = Modifier.height(32.dp))

    OutlinedButton(
        onClick = onCancelClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.7f))
    ) {
        Text("Cancel")
    }
}

@Composable
private fun CompleteContent(uiState: ProcessingUiState, onViewResultsClick: () -> Unit) {
    Text(
        text = "✓",
        fontSize = 48.sp,
        color = AccentGreen
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Analysis Complete",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )

    Spacer(modifier = Modifier.height(24.dp))

    Surface(
        color = CardBg,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SummaryItem("Frames Analyzed", "${uiState.totalFrames}")
            SummaryItem("Ball Detected", "${uiState.visibleFrames} frames")
            SummaryItem("Processing Time", "%.1fs".format(uiState.durationMs / 1000.0))
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = onViewResultsClick,
        colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .height(48.dp)
    ) {
        Text(
            text = "View Results",
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ErrorContent(error: String, onRetryClick: () -> Unit) {
    Text(
        text = "✗",
        fontSize = 48.sp,
        color = Color(0xFFEF5350)
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Processing Failed",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = error,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.6f),
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = onRetryClick,
        colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("Retry", fontWeight = FontWeight.SemiBold)
    }
}