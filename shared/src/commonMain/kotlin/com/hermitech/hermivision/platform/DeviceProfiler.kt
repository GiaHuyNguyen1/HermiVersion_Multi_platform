package com.hermitech.hermivision.platform

/**
 * expect/actual: Device hardware profiler.
 *
 * Android actual: reads Build info, runs TFLite benchmark to pick delegate.
 * iOS actual: reads device model, uses CoreML availability heuristics.
 */
expect class DeviceProfiler() {
    /** Human-readable device identifier (e.g. "Samsung A05 (mt6769v)") */
    fun getDeviceModel(): String

    /** Number of available CPU cores for threading. */
    fun getCpuCoreCount(): Int

    /** Available RAM in MB. */
    fun getAvailableRamMb(): Long
}
