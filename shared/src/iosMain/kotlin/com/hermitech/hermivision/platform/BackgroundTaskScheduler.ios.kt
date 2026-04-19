package com.hermitech.hermivision.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * iOS actual BackgroundTaskScheduler.
 *
 * Phase 1: Runs processing inline on a background coroutine.
 * TODO: Phase 2 — integrate BGTaskScheduler for true background fetch.
 */
actual class BackgroundTaskScheduler actual constructor() {

    private var currentTask: Job? = null

    actual fun scheduleVideoProcessing(videoPath: String, onComplete: (() -> Unit)?) {
        currentTask?.cancel()
        lateinit var scheduledTask: Job
        scheduledTask = CoroutineScope(Dispatchers.Default).launch {
            try {
                coroutineContext.ensureActive()
                // TODO: trigger native iOS inference pipeline here
                // For Phase 1 this is a stub; processing is driven by iosApp directly
                onComplete?.invoke()
            } finally {
                if (currentTask == scheduledTask) {
                    currentTask = null
                }
            }
        }
        currentTask = scheduledTask
    }

    actual fun cancelVideoProcessing() {
        currentTask?.cancel()
        currentTask = null
    }
}
