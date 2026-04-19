package com.hermitech.hermivision.ui.results

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermitech.hermivision.domain.model.BallFrame
import com.hermitech.hermivision.domain.model.CourtResult

// ── Design Tokens ──
private val DarkBg = Color(0xFF0F0F23)
private val CardBg = Color(0xFF1A1A2E)
private val CardBgAlt = Color(0xFF16213E)
private val AccentColor = Color(0xFFFF6B35)
private val CourtAccent = Color(0xFF00BFA5)
private val GreenColor = Color(0xFF00C853)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFF9E9E9E)
private val CourtLineColor = Color(0xFF4DD0E1)
private val CourtNetColor = Color(0xFFFFD54F)
private val CourtDotColor = Color(0xFFFF8A65)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    ballFrames: List<BallFrame>,
    totalFrames: Int,
    visibleFrames: Int,
    inferenceTimeMs: Long,
    totalDurationMs: Long = 0L,
    courtResult: CourtResult = CourtResult.EMPTY,
    onBackClick: () -> Unit = {}
) {
    val detectionRate = if (totalFrames > 0) (visibleFrames * 100f / totalFrames) else 0f
    val fps = if (inferenceTimeMs > 0) (totalFrames * 1000f / inferenceTimeMs) else 0f

    // Tab state: 0 = Ball, 1 = Court
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Analysis Results",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        if (totalDurationMs > 0) {
                            Text(
                                text = "Processed in %.1fs".format(totalDurationMs / 1000.0),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = DarkBg
                )
            )
        },
        containerColor = DarkBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Tab Row ──
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = CardBg,
                contentColor = AccentColor
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "🏸 Ball Tracking",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    selectedContentColor = AccentColor,
                    unselectedContentColor = TextSecondary
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "🏟️ Court Detection",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    selectedContentColor = CourtAccent,
                    unselectedContentColor = TextSecondary
                )
            }

            // ── Tab Content ──
            when (selectedTab) {
                0 -> BallTrackingTab(
                    ballFrames = ballFrames,
                    totalFrames = totalFrames,
                    visibleFrames = visibleFrames,
                    detectionRate = detectionRate,
                    fps = fps,
                    inferenceTimeMs = inferenceTimeMs,
                    totalDurationMs = totalDurationMs
                )
                1 -> CourtDetectionTab(courtResult = courtResult)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Tab 1: Ball Tracking
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun BallTrackingTab(
    ballFrames: List<BallFrame>,
    totalFrames: Int,
    visibleFrames: Int,
    detectionRate: Float,
    fps: Float,
    inferenceTimeMs: Long,
    totalDurationMs: Long
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Stats Cards
        item {
            Text(
                text = "📊 Detection Stats",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AccentColor,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    label = "Total Frames",
                    value = totalFrames.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Ball Detected",
                    value = visibleFrames.toString(),
                    valueColor = GreenColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    label = "Detection Rate",
                    value = "%.1f%%".format(detectionRate),
                    valueColor = if (detectionRate > 70) GreenColor else AccentColor,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Inference FPS",
                    value = "%.1f".format(fps),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    label = "Inference Time",
                    value = "%.1fs".format(inferenceTimeMs / 1000.0),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Total Time",
                    value = "%.1fs".format(totalDurationMs / 1000.0),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Frame Details
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "📋 Frame Details",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AccentColor,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // Header row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBg, shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Frame", color = AccentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("Visible", color = AccentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("X", color = AccentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("Y", color = AccentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
        }

        // Data rows
        itemsIndexed(ballFrames) { _, frame ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${frame.frameId}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (frame.isVisible) "✓" else "✗",
                    color = if (frame.isVisible) GreenColor else Color(0xFFFF5252),
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = frame.x?.let { "%.1f".format(it) } ?: "—",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = frame.y?.let { "%.1f".format(it) } ?: "—",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
            if (frame.frameId < ballFrames.lastOrNull()?.frameId ?: 0) {
                HorizontalDivider(color = CardBg, thickness = 0.5.dp)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Tab 2: Court Detection
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun CourtDetectionTab(courtResult: CourtResult) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (courtResult.valid) CardBgAlt else CardBg
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (courtResult.valid) "✅" else "❌",
                        fontSize = 28.sp
                    )
                    Column {
                        Text(
                            text = if (courtResult.valid) "Court Detected" else "No Court Detected",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (courtResult.valid) CourtAccent else Color(0xFFFF5252)
                        )
                        Text(
                            text = if (courtResult.valid)
                                "14 keypoints mapped (MobileNetV3-Small)"
                            else
                                "No valid court lines found in video",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        if (courtResult.valid && courtResult.keypoints.size == 14) {
            // Court Diagram
            item {
                Text(
                    text = "🗺️ Court Map",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = CourtAccent,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = MaterialTheme.shapes.medium
                ) {
                    CourtDiagram(
                        keypoints = courtResult.keypoints,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.65f)
                            .padding(16.dp)
                    )
                }
            }

            // Keypoint Details
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "📍 Keypoint Coordinates",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = CourtAccent,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBg, shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("#", color = CourtAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(0.5f))
                    Text("Name", color = CourtAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1.5f))
                    Text("X (px)", color = CourtAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("Y (px)", color = CourtAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }

            // Keypoint rows
            itemsIndexed(courtResult.keypoints) { index, (x, y) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        color = CourtDotColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(0.5f)
                    )
                    Text(
                        text = CourtResult.KEYPOINT_NAMES.getOrElse(index) { "KP${index + 1}" },
                        color = TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1.5f)
                    )
                    Text(
                        text = "%.1f".format(x),
                        color = TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "%.1f".format(y),
                        color = TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                }
                if (index < courtResult.keypoints.lastIndex) {
                    HorizontalDivider(color = CardBg, thickness = 0.5.dp)
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Court Diagram — draws the 14 keypoints on a schematic top-down court
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun CourtDiagram(
    keypoints: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (keypoints.size < 14) return@Canvas

        // Map keypoints to canvas coordinates
        val allX = keypoints.map { it.first }
        val allY = keypoints.map { it.second }
        val minX = allX.min()
        val maxX = allX.max()
        val minY = allY.min()
        val maxY = allY.max()

        val rangeX = (maxX - minX).coerceAtLeast(1f)
        val rangeY = (maxY - minY).coerceAtLeast(1f)

        // Add padding
        val pad = 32f
        val drawW = size.width - 2 * pad
        val drawH = size.height - 2 * pad

        // Scale keeping aspect ratio
        val scale = minOf(drawW / rangeX, drawH / rangeY)
        val offsetX = pad + (drawW - rangeX * scale) / 2f
        val offsetY = pad + (drawH - rangeY * scale) / 2f

        fun toCanvas(px: Float, py: Float): Offset {
            return Offset(
                x = offsetX + (px - minX) * scale,
                y = offsetY + (py - minY) * scale
            )
        }

        val pts = keypoints.map { toCanvas(it.first, it.second) }

        // ── Draw court lines ──
        // ACTUAL layout from model output (0-based, verified from coordinates):
        //
        //  0(TL)── 4(TL-in)──────── 6(TR-in)── 1(TR)    Row0  Y≈174
        //    │       │                  │          │
        //    │      8(L) ── 12(C) ── 9(R)         │      Row1  Y≈224
        //    │       │        │        │           │
        //    │     10(L) ── 13(C) ── 11(R)         │      Row2  Y≈415
        //    │       │                  │          │
        //  2(BL)── 5(BL-in)──────── 7(BR-in)── 3(BR)    Row3  Y≈562

        val lineStroke = Stroke(width = 2.5f, cap = StrokeCap.Round)

        // ── Outer rectangle (doubles boundary) ──
        drawCourtLine(pts[0], pts[1], CourtLineColor, lineStroke)  // Top baseline
        drawCourtLine(pts[2], pts[3], CourtLineColor, lineStroke)  // Bottom baseline
        drawCourtLine(pts[0], pts[2], CourtLineColor, lineStroke)  // Left sideline
        drawCourtLine(pts[1], pts[3], CourtLineColor, lineStroke)  // Right sideline

        // ── Singles sidelines (inner verticals) ──
        // Left inner:  4 → 8 → 10 → 5
        drawCourtLine(pts[4], pts[8], CourtLineColor.copy(alpha = 0.7f), lineStroke)
        drawCourtLine(pts[8], pts[10], CourtLineColor.copy(alpha = 0.7f), lineStroke)
        drawCourtLine(pts[10], pts[5], CourtLineColor.copy(alpha = 0.7f), lineStroke)

        // Right inner: 6 → 9 → 11 → 7
        drawCourtLine(pts[6], pts[9], CourtLineColor.copy(alpha = 0.7f), lineStroke)
        drawCourtLine(pts[9], pts[11], CourtLineColor.copy(alpha = 0.7f), lineStroke)
        drawCourtLine(pts[11], pts[7], CourtLineColor.copy(alpha = 0.7f), lineStroke)

        // ── Horizontal service lines ──
        drawCourtLine(pts[8], pts[9], CourtLineColor.copy(alpha = 0.6f), lineStroke)    // Row1: service line
        drawCourtLine(pts[10], pts[11], CourtLineColor.copy(alpha = 0.6f), lineStroke)  // Row2: service line

        // ── Net (thicker, yellow): Row1 center—Row2 center? or use mid-row ──
        // Net is between rows 1 and 2; draw as horizontal at the appropriate row
        // Using Row1 full width as one service line, Row2 as the other
        // The net conceptually is between them — mark center line
        drawCourtLine(pts[12], pts[13], CourtNetColor, Stroke(width = 3.5f, cap = StrokeCap.Round))  // Center line

        // ── Service line connections through center (horizontal completions) ──
        drawCourtLine(pts[8], pts[12], CourtLineColor.copy(alpha = 0.4f), lineStroke)   // Row1 left-center
        drawCourtLine(pts[12], pts[9], CourtLineColor.copy(alpha = 0.4f), lineStroke)   // Row1 center-right
        drawCourtLine(pts[10], pts[13], CourtLineColor.copy(alpha = 0.4f), lineStroke)  // Row2 left-center
        drawCourtLine(pts[13], pts[11], CourtLineColor.copy(alpha = 0.4f), lineStroke)  // Row2 center-right

        // ── Top/bottom inner baseline portions ──
        drawCourtLine(pts[4], pts[6], CourtLineColor.copy(alpha = 0.5f), lineStroke)  // Top singles baseline
        drawCourtLine(pts[5], pts[7], CourtLineColor.copy(alpha = 0.5f), lineStroke)  // Bottom singles baseline

        // ── Draw keypoint dots ──
        pts.forEachIndexed { _, point ->
            // Outer circle
            drawCircle(
                color = CourtDotColor,
                radius = 7f,
                center = point
            )
            // Inner dot
            drawCircle(
                color = Color.White,
                radius = 3f,
                center = point
            )
        }
    }
}

private fun DrawScope.drawCourtLine(
    from: Offset,
    to: Offset,
    color: Color,
    stroke: Stroke
) {
    drawLine(
        color = color,
        start = from,
        end = to,
        strokeWidth = stroke.width,
        cap = stroke.cap
    )
}

// ════════════════════════════════════════════════════════════════════════════
// Shared Components
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun StatCard(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
    }
}