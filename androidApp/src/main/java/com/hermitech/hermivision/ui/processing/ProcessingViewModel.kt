package com.hermitech.hermivision.ui.processing

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.hermitech.hermivision.data.worker.VideoProcessingWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ProcessingUiState(
    val isProcessing: Boolean = false,
    val isComplete: Boolean = false,
    val stage: String = "",
    val progressPercent: Int = 0,
    val currentFrame: Int = -1,
    val totalFrames: Int = 0,
    val visibleFrames: Int = 0,
    val durationMs: Long = 0L,
    val error: String? = null
)

class ProcessingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ProcessingViewModel"
    }

    private val workManager = WorkManager.getInstance(application)
    private val _uiState = MutableStateFlow(ProcessingUiState())
    val uiState: StateFlow<ProcessingUiState> = _uiState.asStateFlow()
    private var currentWorkId: java.util.UUID? = null

    fun processVideo(videoUri: Uri) {
        // Reset state
        _uiState.value = ProcessingUiState(isProcessing = true, stage = "Copying video...")

        viewModelScope.launch {
            try {
                val localPath = copyVideoToCache(videoUri)
                if (localPath == null) {
                    _uiState.value = ProcessingUiState(error = "Failed to copy video file")
                    return@launch
                }

                _uiState.value = ProcessingUiState(isProcessing = true, stage = "Starting...")

                val inputData = Data.Builder()
                    .putString(VideoProcessingWorker.KEY_VIDEO_URI, localPath)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<VideoProcessingWorker>()
                    .setInputData(inputData)
                    .build()

                currentWorkId = workRequest.id
                workManager.enqueue(workRequest)

                observeWork(workRequest.id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start processing", e)
                _uiState.value = ProcessingUiState(error = "Failed to start: ${e.message}")
            }
        }
    }

    private suspend fun copyVideoToCache(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val context = getApplication<Application>()
            val cacheDir = File(context.cacheDir, "video_input")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val destFile = File(cacheDir, "input_video.mp4")
            if (destFile.exists()) destFile.delete()

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            } ?: run {
                Log.e(TAG, "ContentResolver.openInputStream returned null for $uri")
                return@withContext null
            }

            Log.i(TAG, "Video copied to cache: ${destFile.absolutePath} (${destFile.length() / 1024}KB)")
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy video to cache", e)
            null
        }
    }

    private fun observeWork(workId: java.util.UUID) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                if (workInfo == null) return@collect

                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress
                        _uiState.value = ProcessingUiState(
                            isProcessing = true,
                            stage = progress.getString(VideoProcessingWorker.KEY_STAGE) ?: "Processing...",
                            progressPercent = progress.getInt(VideoProcessingWorker.KEY_PROGRESS, 0),
                            currentFrame = progress.getInt(VideoProcessingWorker.KEY_CURRENT_FRAME, -1)
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val output = workInfo.outputData
                        _uiState.value = ProcessingUiState(
                            isProcessing = false,
                            isComplete = true,
                            stage = "Complete",
                            progressPercent = 100,
                            totalFrames = output.getInt(VideoProcessingWorker.KEY_TOTAL_FRAMES, 0),
                            visibleFrames = output.getInt(VideoProcessingWorker.KEY_VISIBLE_FRAMES, 0),
                            durationMs = output.getLong(VideoProcessingWorker.KEY_DURATION_MS, 0L)
                        )
                    }
                    WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData.getString("error") ?: "Unknown error"
                        _uiState.value = ProcessingUiState(
                            isProcessing = false,
                            isComplete = false,
                            error = error
                        )
                    }
                    WorkInfo.State.CANCELLED -> {
                        _uiState.value = ProcessingUiState(
                            isProcessing = false,
                            error = "Processing cancelled"
                        )
                    }
                    else -> { /* ENQUEUED, BLOCKED */ }
                }
            }
        }
    }

    fun cancelProcessing() {
        currentWorkId?.let { workManager.cancelWorkById(it) }
    }
}