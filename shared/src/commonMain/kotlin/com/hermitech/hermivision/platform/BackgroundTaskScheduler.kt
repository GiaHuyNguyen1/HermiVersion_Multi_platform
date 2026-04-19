package com.hermitech.hermivision.platform

/**
 * expect/actual: Background task scheduler.
 *
 * Android actual: delegates to WorkManager.
 * iOS actual: delegates to BGTaskScheduler / async Task.
 */
expect class BackgroundTaskScheduler() {
    /**
     * Schedule or run the video processing task.
     *
     * @param videoPath Absolute path to the video file to process.
     * @param onComplete Callback invoked on the main thread when done.
     */
    fun scheduleVideoProcessing(videoPath: String, onComplete: (() -> Unit)?)

    /** Cancel any pending video processing task. */
    fun cancelVideoProcessing()
}
