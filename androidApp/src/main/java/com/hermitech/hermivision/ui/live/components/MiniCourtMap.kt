package com.hermitech.hermivision.ui.live.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.hermitech.hermivision.domain.court.CourtDetectionManager
import com.hermitech.hermivision.domain.court.CourtHomography

/**
 * 2D top-down mini court map showing ball position projected via homography.
 */
@Composable
internal fun MiniCourtMap(miniMapState: CourtDetectionManager.MiniMapState, modifier: Modifier = Modifier)
{
    val mapWidth = CourtHomography.MINI_MAP_WIDTH
    val mapHeight = CourtHomography.MINI_MAP_HEIGHT
    val miniKps = CourtHomography.miniMapKeypoints
    val lines = CourtHomography.courtLines

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.3f),
        modifier = modifier.width((mapWidth + 8).dp).height((mapHeight + 8).dp)
    ) {
        Canvas(
            modifier = Modifier
                .padding(4.dp)
                .width(mapWidth.dp)
                .height(mapHeight.dp)
        ) {
            val sx = size.width / mapWidth
            val sy = size.height / mapHeight

            // Green court background
            drawRect(color = MiniMapBg, topLeft = Offset.Zero, size = size)

            // Court lines
            for ((i1, i2) in lines) {
                val (x1, y1) = miniKps[i1]
                val (x2, y2) = miniKps[i2]
                val isNet = (i1 == 12 && i2 == 13)
                drawLine(
                    color = if (isNet) MiniMapNet else MiniMapLine,
                    start = Offset(x1 * sx, y1 * sy),
                    end = Offset(x2 * sx, y2 * sy),
                    strokeWidth = if (isNet) 2.5f else 1.5f,
                    cap = StrokeCap.Round
                )
            }

            // Center service line
            val centerX = (miniKps[8].first + miniKps[9].first) / 2f
            drawLine(
                color = MiniMapLine,
                start = Offset(centerX * sx, miniKps[8].second * sy),
                end = Offset(centerX * sx, miniKps[10].second * sy),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round
            )

            // Ball position
            miniMapState.ballPosition?.let { (bx, by) ->
                val px = bx * sx
                val py = by * sy
                drawCircle(color = MiniMapBall.copy(alpha = 0.4f), radius = 8f, center = Offset(px, py))
                drawCircle(color = MiniMapBall, radius = 5f, center = Offset(px, py))
                drawCircle(color = Color.White, radius = 2f, center = Offset(px, py))
            }
        }
    }
}