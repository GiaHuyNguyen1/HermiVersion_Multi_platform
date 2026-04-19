package com.hermitech.hermivision.platform

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Android actual BackgroundTaskScheduler backed by WorkManager.
 *
 * Note: Context must be supplied via a companion object or dependency injection.
 * The constructor is parameter-less per the expect declaration.
 */
actual class BackgroundTaskScheduler actual constructor() {

    actual fun scheduleVideoProcessing(videoPath: String, onComplete: (() -> Unit)?) {
        val context = AppContextHolder.requireApplicationContext()
        val workerClass = Class.forName(workerClassName)
            .asSubclass(ListenableWorker::class.java)
        val request = OneTimeWorkRequest.Builder(workerClass)
            .setInputData(workDataOf(KEY_VIDEO_URI to videoPath))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    actual fun cancelVideoProcessing() {
        val context = AppContextHolder.requireApplicationContext()
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    companion object {
        const val TAG = "video_processing"
        const val KEY_VIDEO_URI = "video_uri"
        @Volatile
        var workerClassName: String = ""
    }
}

/**
 * Minimal application context holder to allow the expect/actual class
 * to access Context without constructor parameters.
 *
 * Initialize in Application.onCreate():
 *   AppContextHolder.applicationContext = this
 */
object AppContextHolder {
    lateinit var applicationContext: Context

    fun requireApplicationContext(): Context {
        check(::applicationContext.isInitialized) {
            "AppContextHolder.applicationContext must be initialized in Application.onCreate()"
        }
        return applicationContext
    }
}
