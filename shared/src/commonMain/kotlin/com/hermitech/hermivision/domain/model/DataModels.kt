package com.hermitech.hermivision.domain.model

data class BallFrame(
    val frameId: Int,
    val isVisible: Boolean,
    val x: Float?,
    val y: Float?
)

/**
 * 14 court keypoints in original pixel coordinates.
 *
 * Actual layout from model output (verified from device coordinates):
 *
 *  KP1(TL)── KP5(TL-in)────── KP7(TR-in)── KP2(TR)     Row0 (near baseline)
 *    │          │                  │            │
 *    │        KP9(L) ── KP13(C) ── KP10(R)     │         Row1 (service line)
 *    │          │          │          │          │
 *    │       KP11(L) ── KP14(C) ── KP12(R)     │         Row2 (service line)
 *    │          │                  │            │
 *  KP3(BL)── KP6(BL-in)────── KP8(BR-in)── KP4(BR)     Row3 (far baseline)
 */
data class CourtResult(val valid: Boolean, val keypoints: List<Pair<Float, Float>>) {
    companion object {
        val EMPTY = CourtResult(valid = false, keypoints = emptyList())

        val KEYPOINT_NAMES = listOf(
            "Near BL Outer L",    // KP1  — Top-left outer corner
            "Near BL Outer R",    // KP2  — Top-right outer corner
            "Far BL Outer L",     // KP3  — Bottom-left outer corner
            "Far BL Outer R",     // KP4  — Bottom-right outer corner
            "Near BL Inner L",    // KP5  — Top-left inner (singles)
            "Far BL Inner L",     // KP6  — Bottom-left inner (singles)
            "Near BL Inner R",    // KP7  — Top-right inner (singles)
            "Far BL Inner R",     // KP8  — Bottom-right inner (singles)
            "Service 1 Left",     // KP9  — Row1 left
            "Service 1 Right",    // KP10 — Row1 right
            "Service 2 Left",     // KP11 — Row2 left
            "Service 2 Right",    // KP12 — Row2 right
            "Center Line Top",    // KP13 — Row1 center
            "Center Line Bot"     // KP14 — Row2 center
        )
    }
}
