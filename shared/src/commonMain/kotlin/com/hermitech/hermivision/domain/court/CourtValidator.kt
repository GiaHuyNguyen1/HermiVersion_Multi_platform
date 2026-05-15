package com.hermitech.hermivision.domain.court

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min

/**
 * Validates court keypoints to filter out false positives.
 *
 * 14 keypoints layout for badminton court:
 *   0: top-left corner       1: top-right corner
 *   2: bottom-left corner    3: bottom-right corner
 *   4-13: inner court line intersections (service lines, center line, etc.)
 *
 * Checks performed:
 *   1. Bounds: all 4 corners within frame
 *   2. Area: quad area between 5% and 80% of frame
 *   3. Corner distance: no two corners closer than 50px
 *   4. Convexity: corners form a convex quadrilateral
 *   5. Court aspect ratio: perspective-corrected ratio must be ~1.5:1 to ~3.5:1
 *   6. Inner keypoints: at least 70% must lie inside or near the outer quad
 *   7. Perspective sanity: top/bottom edge ratio not too extreme
 */
object CourtValidator {

    // Minimum quadrilateral area as fraction of frame area (5%)
    private const val MIN_AREA_RATIO = 0.05f

    // Maximum quadrilateral area as fraction of frame area (80%)
    private const val MAX_AREA_RATIO = 0.80f

    // Minimum distance between any two corner points (pixels)
    private const val MIN_CORNER_DISTANCE = 50f

    // Court aspect ratio range (length/width in perspective)
    // Real badminton court: 13.4m / 6.1m = ~2.2:1
    // Allow perspective distortion: 1.2 to 4.0
    private const val MIN_COURT_ASPECT = 1.2f
    private const val MAX_COURT_ASPECT = 4.0f

    // Max edge ratio (perspective): longer edge / shorter parallel edge
    // In extreme perspective, far edge is shorter but ratio shouldn't exceed 5:1
    private const val MAX_PERSPECTIVE_RATIO = 5.0f

    // Min ratio of inner points inside the outer quad
    private const val MIN_INNER_POINTS_RATIO = 0.7f

    // Margin for inner point check (pixels outside quad boundary still OK)
    private const val INNER_POINT_MARGIN = 30f

    data class ValidationResult(
        val isValid: Boolean,
        val reason: String = ""
    )

    fun validate(
        keypoints: List<Pair<Float, Float>>,
        frameWidth: Float,
        frameHeight: Float
    ): ValidationResult {
        if (keypoints.size < 14) {
            return ValidationResult(false, "Not enough keypoints: ${keypoints.size}")
        }

        val tl = keypoints[0] // top-left
        val tr = keypoints[1] // top-right
        val bl = keypoints[2] // bottom-left
        val br = keypoints[3] // bottom-right
        val corners = listOf(tl, tr, bl, br)

        // ── Check 1: Bounds ──
        for ((i, c) in corners.withIndex()) {
            if (c.first < 0 || c.first > frameWidth || c.second < 0 || c.second > frameHeight) {
                return ValidationResult(false, "Corner $i out of bounds: (${c.first}, ${c.second})")
            }
        }

        // ── Check 2: Area ──
        val area = quadArea(tl, tr, br, bl)
        val frameArea = frameWidth * frameHeight
        val areaRatio = area / frameArea
        if (areaRatio < MIN_AREA_RATIO) {
            return ValidationResult(false, "Area too small: ${"%.1f".format(areaRatio * 100)}% of frame (min ${MIN_AREA_RATIO * 100}%)")
        }
        if (areaRatio > MAX_AREA_RATIO) {
            return ValidationResult(false, "Area too large: ${"%.1f".format(areaRatio * 100)}% of frame")
        }

        // ── Check 3: Corner distance ──
        val distances = listOf(
            dist(tl, tr), dist(tl, bl), dist(tl, br),
            dist(tr, bl), dist(tr, br), dist(bl, br)
        )
        val minDist = distances.min()
        if (minDist < MIN_CORNER_DISTANCE) {
            return ValidationResult(false, "Corners too close: ${"%.0f".format(minDist)}px (min ${MIN_CORNER_DISTANCE}px)")
        }

        // ── Check 4: Convexity ──
        if (!isConvexQuad(tl, tr, br, bl)) {
            return ValidationResult(false, "Non-convex quadrilateral")
        }

        // ── Check 5: Court aspect ratio ──
        // Use average of top/bottom edges as "width" and left/right edges as "height"
        val topEdge = dist(tl, tr)
        val bottomEdge = dist(bl, br)
        val leftEdge = dist(tl, bl)
        val rightEdge = dist(tr, br)
        val avgWidth = (topEdge + bottomEdge) / 2f
        val avgHeight = (leftEdge + rightEdge) / 2f

        // Court is oriented portrait in camera view (longer dimension = height)
        // or landscape (longer dimension = width)
        val longerSide = max(avgWidth, avgHeight)
        val shorterSide = max(avgWidth.coerceAtLeast(1f), avgHeight).let { min(avgWidth, avgHeight) }
        val courtAspect = if (shorterSide > 0f) longerSide / shorterSide else 999f

        if (courtAspect < MIN_COURT_ASPECT || courtAspect > MAX_COURT_ASPECT) {
            return ValidationResult(false, "Bad court aspect: ${"%.2f".format(courtAspect)} (expected ${MIN_COURT_ASPECT}-${MAX_COURT_ASPECT})")
        }

        // ── Check 6: Perspective sanity ──
        // Parallel edges shouldn't have extreme ratio (one very long, other very short)
        val topBottomRatio = if (bottomEdge > 0f) max(topEdge, bottomEdge) / min(topEdge, bottomEdge) else 999f
        val leftRightRatio = if (rightEdge > 0f) max(leftEdge, rightEdge) / min(leftEdge, rightEdge) else 999f
        if (topBottomRatio > MAX_PERSPECTIVE_RATIO) {
            return ValidationResult(false, "Extreme perspective (top/bottom): ${"%.1f".format(topBottomRatio)}:1")
        }
        if (leftRightRatio > MAX_PERSPECTIVE_RATIO) {
            return ValidationResult(false, "Extreme perspective (left/right): ${"%.1f".format(leftRightRatio)}:1")
        }

        // ── Check 7: Inner keypoints inside outer quad ──
        if (keypoints.size >= 14) {
            val innerPoints = keypoints.subList(4, 14)
            var insideCount = 0
            for (pt in innerPoints) {
                if (isPointInsideQuadWithMargin(pt, tl, tr, br, bl, INNER_POINT_MARGIN)) {
                    insideCount++
                }
            }
            val insideRatio = insideCount.toFloat() / innerPoints.size
            if (insideRatio < MIN_INNER_POINTS_RATIO) {
                return ValidationResult(false, "Only ${(insideRatio * 100).toInt()}% inner points inside quad (min ${(MIN_INNER_POINTS_RATIO * 100).toInt()}%)")
            }
        }

        return ValidationResult(true)
    }

    /**
     * Dump keypoints for debugging. Call this from CourtDetectionManager when needed.
     */
    fun dumpKeypoints(keypoints: List<Pair<Float, Float>>, frameWidth: Float, frameHeight: Float): String {
        val sb = StringBuilder()
        sb.appendLine("Frame: ${frameWidth.toInt()}x${frameHeight.toInt()}")
        for ((i, kp) in keypoints.withIndex()) {
            val label = when (i) {
                0 -> "TL"
                1 -> "TR"
                2 -> "BL"
                3 -> "BR"
                else -> "P${i}"
            }
            sb.appendLine("  $label: (${"%.1f".format(kp.first)}, ${"%.1f".format(kp.second)})")
        }
        // Compute stats
        val tl = keypoints[0]; val tr = keypoints[1]; val bl = keypoints[2]; val br = keypoints[3]
        val area = quadArea(tl, tr, br, bl)
        val areaRatio = area / (frameWidth * frameHeight)
        sb.appendLine("  Area: ${"%.0f".format(area)}px² (${"%.1f".format(areaRatio * 100)}% of frame)")
        sb.appendLine("  TopEdge: ${"%.0f".format(dist(tl, tr))}  BottomEdge: ${"%.0f".format(dist(bl, br))}")
        sb.appendLine("  LeftEdge: ${"%.0f".format(dist(tl, bl))}  RightEdge: ${"%.0f".format(dist(tr, br))}")
        return sb.toString()
    }

    // ═══════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════

    private fun quadArea(
        p0: Pair<Float, Float>, p1: Pair<Float, Float>,
        p2: Pair<Float, Float>, p3: Pair<Float, Float>
    ): Float {
        return abs(
            (p0.first * p1.second - p1.first * p0.second) +
            (p1.first * p2.second - p2.first * p1.second) +
            (p2.first * p3.second - p3.first * p2.second) +
            (p3.first * p0.second - p0.first * p3.second)
        ) / 2f
    }

    private fun isConvexQuad(
        p0: Pair<Float, Float>, p1: Pair<Float, Float>,
        p2: Pair<Float, Float>, p3: Pair<Float, Float>
    ): Boolean {
        val pts = listOf(p0, p1, p2, p3)
        var pos = 0; var neg = 0
        for (i in 0 until 4) {
            val a = pts[i]; val b = pts[(i + 1) % 4]; val c = pts[(i + 2) % 4]
            val cross = (b.first - a.first) * (c.second - b.second) -
                        (b.second - a.second) * (c.first - b.first)
            if (cross > 0) pos++ else if (cross < 0) neg++
        }
        return pos == 0 || neg == 0
    }

    /**
     * Check if point is inside quadrilateral (with margin tolerance).
     * Uses cross-product sign test against all 4 edges.
     */
    private fun isPointInsideQuadWithMargin(
        pt: Pair<Float, Float>,
        p0: Pair<Float, Float>, p1: Pair<Float, Float>,
        p2: Pair<Float, Float>, p3: Pair<Float, Float>,
        margin: Float
    ): Boolean {
        // Expand quad by margin for tolerance
        val cx = (p0.first + p1.first + p2.first + p3.first) / 4f
        val cy = (p0.second + p1.second + p2.second + p3.second) / 4f

        fun expand(p: Pair<Float, Float>): Pair<Float, Float> {
            val dx = p.first - cx
            val dy = p.second - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d < 1f) return p
            val scale = (d + margin) / d
            return Pair(cx + dx * scale, cy + dy * scale)
        }

        val e0 = expand(p0); val e1 = expand(p1)
        val e2 = expand(p2); val e3 = expand(p3)

        return isPointInsideQuad(pt, e0, e1, e2, e3)
    }

    private fun isPointInsideQuad(
        pt: Pair<Float, Float>,
        p0: Pair<Float, Float>, p1: Pair<Float, Float>,
        p2: Pair<Float, Float>, p3: Pair<Float, Float>
    ): Boolean {
        // Split quad into 2 triangles and check both
        return isPointInTriangle(pt, p0, p1, p2) || isPointInTriangle(pt, p0, p2, p3)
    }

    private fun isPointInTriangle(
        pt: Pair<Float, Float>,
        a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>
    ): Boolean {
        val d1 = crossSign(pt, a, b)
        val d2 = crossSign(pt, b, c)
        val d3 = crossSign(pt, c, a)
        val hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0)
        val hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0)
        return !(hasNeg && hasPos)
    }

    private fun crossSign(
        p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>
    ): Float {
        return (a.first - p.first) * (b.second - p.second) -
               (a.second - p.second) * (b.first - p.first)
    }

    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = a.first - b.first
        val dy = a.second - b.second
        return sqrt(dx * dx + dy * dy)
    }
}
