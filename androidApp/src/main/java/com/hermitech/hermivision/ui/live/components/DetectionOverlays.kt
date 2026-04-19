package com.hermitech.hermivision.ui.live.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.hermitech.hermivision.domain.model.CourtResult
import com.hermitech.hermivision.ui.live.LiveViewModel

/**
 * Canvas overlay for ball bounding box and court keypoint lines.
 * Renders on top of the camera preview.
 */
@Composable
internal fun DetectionOverlays(ballState: LiveViewModel.BallState, courtResult: CourtResult, cameraFrameWidth: Int, cameraFrameHeight: Int)
{
    val textMeasurer = rememberTextMeasurer()

    // ── Ball bounding box + center dot ──
    if (ballState.isVisible) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaleX = size.width / cameraFrameWidth.toFloat()
            val scaleY = size.height / cameraFrameHeight.toFloat()
            val cx = ballState.x * scaleX
            val cy = ballState.y * scaleY
            val bw = ballState.w * scaleX
            val bh = ballState.h * scaleY

            if (bw > 2f && bh > 2f) {
                val boxLeft = cx - bw / 2f
                val boxTop = cy - bh / 2f

                // Box outline
                drawRect(
                    color = BallBoxColor,
                    topLeft = Offset(boxLeft, boxTop),
                    size = androidx.compose.ui.geometry.Size(bw, bh),
                    style = Stroke(width = 2f)
                )

                // Corner L-accents
                val cornerLen = minOf(bw, bh) * 0.3f
                val cornerStroke = 3f
                drawLine(BallBoxColor, Offset(boxLeft, boxTop), Offset(boxLeft + cornerLen, boxTop), cornerStroke)
                drawLine(BallBoxColor, Offset(boxLeft, boxTop), Offset(boxLeft, boxTop + cornerLen), cornerStroke)
                drawLine(BallBoxColor, Offset(boxLeft + bw, boxTop), Offset(boxLeft + bw - cornerLen, boxTop), cornerStroke)
                drawLine(BallBoxColor, Offset(boxLeft + bw, boxTop), Offset(boxLeft + bw, boxTop + cornerLen), cornerStroke)
                drawLine(BallBoxColor, Offset(boxLeft, boxTop + bh), Offset(boxLeft + cornerLen, boxTop + bh), cornerStroke)
                drawLine(BallBoxColor, Offset(boxLeft, boxTop + bh), Offset(boxLeft, boxTop + bh - cornerLen), cornerStroke)
                drawLine(BallBoxColor, Offset(boxLeft + bw, boxTop + bh), Offset(boxLeft + bw - cornerLen, boxTop + bh), cornerStroke)
                drawLine(BallBoxColor, Offset(boxLeft + bw, boxTop + bh), Offset(boxLeft + bw, boxTop + bh - cornerLen), cornerStroke)

                // Score label
                val label = "%.0f%%".format(ballState.score * 100)
                val textResult = textMeasurer.measure(
                    text = label,
                    style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                )
                drawRect(
                    color = BallBoxColor,
                    topLeft = Offset(boxLeft, boxTop - textResult.size.height - 2f),
                    size = androidx.compose.ui.geometry.Size(textResult.size.width.toFloat() + 6f, textResult.size.height.toFloat() + 2f)
                )
                drawText(textResult, topLeft = Offset(boxLeft + 3f, boxTop - textResult.size.height - 1f))
            }

            // Center dot
            drawCircle(color = BallDotColor.copy(alpha = 0.4f), radius = 16f, center = Offset(cx, cy))
            drawCircle(color = BallDotColor, radius = 8f, center = Offset(cx, cy))
            drawCircle(color = Color.White, radius = 3f, center = Offset(cx, cy))
        }
    }

    // ── Court keypoint overlay ──
    if (courtResult.valid && courtResult.keypoints.size == 14) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaleX = size.width / cameraFrameWidth.toFloat()
            val scaleY = size.height / cameraFrameHeight.toFloat()
            val pts = courtResult.keypoints.map { (x, y) -> Offset(x * scaleX, y * scaleY) }
            val lineStroke = Stroke(width = 2f, cap = StrokeCap.Round)

            drawOverlayLine(pts[0], pts[1], CourtLineColor.copy(alpha = 0.6f), lineStroke)
            drawOverlayLine(pts[2], pts[3], CourtLineColor.copy(alpha = 0.6f), lineStroke)
            drawOverlayLine(pts[0], pts[2], CourtLineColor.copy(alpha = 0.6f), lineStroke)
            drawOverlayLine(pts[1], pts[3], CourtLineColor.copy(alpha = 0.6f), lineStroke)
            drawOverlayLine(pts[4], pts[8], CourtLineColor.copy(alpha = 0.4f), lineStroke)
            drawOverlayLine(pts[8], pts[10], CourtLineColor.copy(alpha = 0.4f), lineStroke)
            drawOverlayLine(pts[10], pts[5], CourtLineColor.copy(alpha = 0.4f), lineStroke)
            drawOverlayLine(pts[6], pts[9], CourtLineColor.copy(alpha = 0.4f), lineStroke)
            drawOverlayLine(pts[9], pts[11], CourtLineColor.copy(alpha = 0.4f), lineStroke)
            drawOverlayLine(pts[11], pts[7], CourtLineColor.copy(alpha = 0.4f), lineStroke)
            drawOverlayLine(pts[8], pts[9], CourtLineColor.copy(alpha = 0.4f), lineStroke)
            drawOverlayLine(pts[10], pts[11], CourtLineColor.copy(alpha = 0.4f), lineStroke)
            drawOverlayLine(pts[12], pts[13], CourtNetColor.copy(alpha = 0.6f), Stroke(width = 3f, cap = StrokeCap.Round))
            pts.forEach { point ->
                drawCircle(color = CourtDotColor.copy(alpha = 0.7f), radius = 5f, center = point)
            }
        }
    }
}

internal fun DrawScope.drawOverlayLine(from: Offset, to: Offset, color: Color, stroke: Stroke) {
    drawLine(color = color, start = from, end = to, strokeWidth = stroke.width, cap = stroke.cap)
}