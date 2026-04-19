package com.hermitech.hermivision.domain.court

/**
 * Pure-math homography for projecting camera-space court keypoints
 * onto a 2D top-down rectangular mini-map.
 *
 * Uses 4-point DLT (Direct Linear Transform) to compute a 3×3
 * perspective transformation matrix H such that:
 *   dst = H × src  (homogeneous coordinates)
 *
 * Tennis court dimensions (meters):
 *   Full court:  23.77 × 10.97 (doubles)
 *   Singles:     23.77 × 8.23
 *   Half court:  11.885m
 *   Service box: 6.40m from net
 *   Alley width: 1.37m each side
 *   No-man's-land: 5.485m (baseline to service line)
 */
object CourtHomography {

    // ── Tennis court dimensions (meters) ──
    private const val COURT_LENGTH = 23.77f      // Total length
    private const val COURT_WIDTH = 10.97f        // Doubles width
    private const val SINGLES_WIDTH = 8.23f       // Singles width
    private const val HALF_COURT = 11.885f        // Net to baseline
    private const val SERVICE_LINE = 6.40f        // Net to service line
    private const val ALLEY = 1.37f               // Doubles alley width
    private const val NO_MANS_LAND = 5.485f       // Baseline to service line

    /**
     * Mini-map drawing dimensions (pixels).
     * Aspect ratio matches real court proportions: 23.77:10.97 ≈ 2.17:1
     */
    const val MINI_MAP_WIDTH = 130f
    const val MINI_MAP_HEIGHT = 282f  // width * (23.77 / 10.97)
    private const val PADDING = 10f

    /**
     * 14 keypoints on the rectangular mini-map (top-down view, in mini-map pixel coords).
     *
     * Keypoint mapping (matching the court detection model):
     *   0,1 = top baseline (left, right) — doubles outer
     *   2,3 = bottom baseline (left, right) — doubles outer
     *   4,5 = singles sideline (top-left, bottom-left)
     *   6,7 = singles sideline (top-right, bottom-right)
     *   8,9 = top service line (left, right)
     *   10,11 = bottom service line (left, right)
     *   12,13 = net (left, right)
     */
    val miniMapKeypoints: List<Pair<Float, Float>> by lazy {
        val courtW = MINI_MAP_WIDTH - 2 * PADDING
        val courtH = MINI_MAP_HEIGHT - 2 * PADDING

        // Scale factors: meters → pixels
        val sx = courtW / COURT_WIDTH
        val sy = courtH / COURT_LENGTH

        val left = PADDING
        val right = PADDING + courtW
        val top = PADDING
        val bottom = PADDING + courtH

        val singlesLeft = left + ALLEY * sx
        val singlesRight = right - ALLEY * sx

        val serviceDist = SERVICE_LINE * sy
        val netY = top + HALF_COURT * sy

        listOf(
            Pair(left, top),        // 0: top-left baseline (doubles)
            Pair(right, top),       // 1: top-right baseline (doubles)
            Pair(left, bottom),     // 2: bottom-left baseline (doubles)
            Pair(right, bottom),    // 3: bottom-right baseline (doubles)
            Pair(singlesLeft, top),                  // 4: top-left singles sideline
            Pair(singlesLeft, bottom),               // 5: bottom-left singles sideline
            Pair(singlesRight, top),                 // 6: top-right singles sideline
            Pair(singlesRight, bottom),              // 7: bottom-right singles sideline
            Pair(singlesLeft, netY - serviceDist),   // 8: top service line left
            Pair(singlesRight, netY - serviceDist),  // 9: top service line right
            Pair(singlesLeft, netY + serviceDist),   // 10: bottom service line left
            Pair(singlesRight, netY + serviceDist),  // 11: bottom service line right
            Pair(left, netY),                        // 12: net left
            Pair(right, netY)                        // 13: net right
        )
    }

    /**
     * Court line connections for drawing.
     * Each pair is (startKeypointIndex, endKeypointIndex).
     */
    val courtLines: List<Pair<Int, Int>> = listOf(
        Pair(0, 1), Pair(2, 3), Pair(0, 2), Pair(1, 3),  // Outer rectangle (doubles)
        Pair(4, 5), Pair(6, 7),                            // Singles sidelines
        Pair(8, 9), Pair(10, 11),                          // Service lines
        Pair(8, 10), Pair(9, 11),                          // Center service line
        Pair(12, 13)                                       // Net
    )

    /**
     * Compute 3x3 homography matrix from 4 source points to 4 destination points.
     * Uses the 4-point DLT algorithm.
     */
    fun computeHomography(src: List<Pair<Float, Float>>, dst: List<Pair<Float, Float>>): FloatArray? {
        if (src.size < 4 || dst.size < 4) return null

        val a = Array(8) { FloatArray(8) }
        val b = FloatArray(8)

        for (i in 0 until 4) {
            val (sx, sy) = src[i]
            val (dx, dy) = dst[i]
            val row1 = i * 2
            val row2 = row1 + 1

            a[row1][0] = sx; a[row1][1] = sy; a[row1][2] = 1f
            a[row1][3] = 0f; a[row1][4] = 0f; a[row1][5] = 0f
            a[row1][6] = -sx * dx; a[row1][7] = -sy * dx
            b[row1] = dx

            a[row2][0] = 0f; a[row2][1] = 0f; a[row2][2] = 0f
            a[row2][3] = sx; a[row2][4] = sy; a[row2][5] = 1f
            a[row2][6] = -sx * dy; a[row2][7] = -sy * dy
            b[row2] = dy
        }

        val h = solveLinearSystem(a, b) ?: return null
        return floatArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1f)
    }

    /**
     * Apply homography matrix to transform a single point.
     */
    fun transformPoint(H: FloatArray, x: Float, y: Float): Pair<Float, Float>? {
        val w = H[6] * x + H[7] * y + H[8]
        if (kotlin.math.abs(w) < 1e-10f) return null
        val dx = (H[0] * x + H[1] * y + H[2]) / w
        val dy = (H[3] * x + H[4] * y + H[5]) / w
        return Pair(dx, dy)
    }

    /**
     * Compute homography from detected court corners (camera) to mini-map corners.
     */
    fun computeCourtToMiniMap(cameraCorners: List<Pair<Float, Float>>): FloatArray? {
        if (cameraCorners.size < 4) return null
        val miniCorners = listOf(
            miniMapKeypoints[0],  // top-left
            miniMapKeypoints[1],  // top-right
            miniMapKeypoints[2],  // bottom-left
            miniMapKeypoints[3]   // bottom-right
        )
        return computeHomography(cameraCorners, miniCorners)
    }

    private fun solveLinearSystem(a: Array<FloatArray>, b: FloatArray): FloatArray? {
        val n = b.size
        val aug = Array(n) { i -> FloatArray(n + 1).also { row ->
            for (j in 0 until n) row[j] = a[i][j]
            row[n] = b[i]
        }}

        for (col in 0 until n) {
            var maxVal = kotlin.math.abs(aug[col][col])
            var maxRow = col
            for (row in col + 1 until n) {
                val v = kotlin.math.abs(aug[row][col])
                if (v > maxVal) { maxVal = v; maxRow = row }
            }
            if (maxVal < 1e-10f) return null

            if (maxRow != col) {
                val temp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = temp
            }

            val pivot = aug[col][col]
            for (row in col + 1 until n) {
                val factor = aug[row][col] / pivot
                for (j in col until n + 1) aug[row][j] -= factor * aug[col][j]
            }
        }

        val x = FloatArray(n)
        for (i in n - 1 downTo 0) {
            var sum = aug[i][n]
            for (j in i + 1 until n) sum -= aug[i][j] * x[j]
            if (kotlin.math.abs(aug[i][i]) < 1e-10f) return null
            x[i] = sum / aug[i][i]
        }
        return x
    }
}
