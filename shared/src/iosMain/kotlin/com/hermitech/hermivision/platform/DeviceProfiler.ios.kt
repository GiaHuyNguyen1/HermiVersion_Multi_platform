package com.hermitech.hermivision.platform

import platform.UIKit.UIDevice

/**
 * iOS actual implementation of DeviceProfiler.
 * Uses UIKit UIDevice for model info.
 */
actual class DeviceProfiler actual constructor() {

    actual fun getDeviceModel(): String =
        UIDevice.currentDevice.model

    actual fun getCpuCoreCount(): Int =
        platform.posix.sysconf(platform.posix._SC_NPROCESSORS_ONLN).toInt()

    actual fun getAvailableRamMb(): Long {
        // Approximate: query mach_host_info for basic memory info
        // Full implementation requires mach/mach_host.h interop
        return -1L  // TODO: implement via mach_host_statistics
    }
}
