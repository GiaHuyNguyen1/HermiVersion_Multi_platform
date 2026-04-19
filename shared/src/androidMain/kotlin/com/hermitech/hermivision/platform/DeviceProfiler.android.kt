package com.hermitech.hermivision.platform

import android.os.Build
import java.io.File

/**
 * Android actual implementation of DeviceProfiler.
 * Reads device build properties and /proc/meminfo for hardware info.
 */
actual class DeviceProfiler actual constructor() {

    actual fun getDeviceModel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE})"

    actual fun getCpuCoreCount(): Int =
        Runtime.getRuntime().availableProcessors()

    actual fun getAvailableRamMb(): Long = try {
        val memInfo = File("/proc/meminfo").readLines()
        val memAvailable = memInfo.firstOrNull { it.startsWith("MemAvailable:") }
        memAvailable?.split("\\s+".toRegex())?.getOrNull(1)?.toLongOrNull()?.div(1024) ?: -1L
    } catch (e: Exception) {
        -1L
    }
}
