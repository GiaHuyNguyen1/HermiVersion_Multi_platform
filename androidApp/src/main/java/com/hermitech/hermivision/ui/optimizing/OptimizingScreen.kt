package com.hermitech.hermivision.ui.optimizing

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
import kotlinx.coroutines.delay

private val AccentCyan = Color(0xFF00BCD4)
private val AccentGreen = Color(0xFF4CAF50)
private val DarkBg = Color(0xFF0F0F23)
private val CardBg = Color(0xFF1A1A2E)

@Composable
fun OptimizingScreen(uiState: OptimizingUiState, onComplete: () -> Unit) {
    LaunchedEffect(uiState.isDone) {
        if (uiState.isDone) {
            delay(2000L)
            onComplete()
        }
    }

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
                    error = uiState.error
                )
                uiState.isDone -> DoneContent(
                    benchmarkResults = uiState.benchmarkResults
                )
                else -> BenchmarkingContent(
                    stage = uiState.stage,
                    progress = uiState.progress
                )
            }
        }
    }
}

@Composable
private fun BenchmarkingContent(stage: String, progress: Int) {
    // Animated spinner
    val infiniteTransition = rememberInfiniteTransition(label = "spinner")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Pulsing glow
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Canvas(
        modifier = Modifier
            .size(100.dp)
            .rotate(rotation)
    ) {
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(Color.Transparent, AccentCyan)
            ),
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 6f, cap = StrokeCap.Round)
        )
    }

    Spacer(modifier = Modifier.height(40.dp))

    Text(
        text = "Optimizing for your device",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "This only happens once",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.5f)
    )

    Spacer(modifier = Modifier.height(32.dp))

    // Stage text
    Text(
        text = stage,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = AccentCyan.copy(alpha = pulseAlpha)
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Progress bar
    LinearProgressIndicator(
        progress = { progress / 100f },
        modifier = Modifier
            .fillMaxWidth(0.75f)
            .height(6.dp),
        color = AccentCyan,
        trackColor = CardBg,
        strokeCap = StrokeCap.Round
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "${progress}%",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.6f)
    )
}

@Composable
private fun DoneContent(benchmarkResults: String) {
    Text(
        text = "✓",
        fontSize = 56.sp,
        color = AccentGreen
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Ready to go!",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Your device has been optimized for AI inference",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.6f),
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Benchmark results card
    Surface(
        color = CardBg,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Benchmark Results",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AccentCyan
            )

            benchmarkResults.lines().forEach { line ->
                if (line.isNotBlank()) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = parts[0].trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                            Text(
                                text = parts[1].trim(),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(error: String) {
    Text(
        text = "✗",
        fontSize = 48.sp,
        color = Color(0xFFEF5350)
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Optimization Failed",
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
}