package com.hermitech.hermivision

import android.app.Application
import com.hermitech.hermivision.data.worker.VideoProcessingWorker
import com.hermitech.hermivision.platform.AppContextHolder
import com.hermitech.hermivision.platform.BackgroundTaskScheduler

class HermiVisionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.applicationContext = this
        BackgroundTaskScheduler.workerClassName = VideoProcessingWorker::class.java.name
    }
}
