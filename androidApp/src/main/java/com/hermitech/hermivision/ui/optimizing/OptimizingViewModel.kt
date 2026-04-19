package com.hermitech.hermivision.ui.optimizing

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermitech.hermivision.data.AppDatabaseFactory
import com.hermitech.hermivision.data.DatabaseDriverFactory
import com.hermitech.hermivision.data.saveAiConfig
import com.hermitech.hermivision.data.inference.DeviceProfiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OptimizingUiState(
    val isRunning: Boolean = false,
    val isDone: Boolean = false,
    val progress: Int = 0,
    val stage: String = "Preparing...",
    val deviceSummary: String = "",
    val benchmarkResults: String = "",
    val error: String? = null
)

class OptimizingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OptimizingViewModel"
    }

    private val db by lazy {
        AppDatabaseFactory.create(DatabaseDriverFactory().createDriver())
    }
    private val _uiState = MutableStateFlow(OptimizingUiState())
    val uiState: StateFlow<OptimizingUiState> = _uiState.asStateFlow()

    /**
     * Check if device config already exists in DB.
     * Returns true if optimizing can be skipped.
     */
    suspend fun hasExistingConfig(): Boolean = withContext(Dispatchers.IO) {
        db.deviceConfigQueries.getConfig().executeAsOneOrNull() != null
    }

    /**
     * Run the device benchmark and save results to SQLDelight.
     * Should only be called once (first launch).
     */
    fun startBenchmark() {
        if (_uiState.value.isRunning) return

        _uiState.value = OptimizingUiState(isRunning = true, stage = "Analyzing hardware...")

        viewModelScope.launch {
            try {
                val profiler = DeviceProfiler(getApplication())

                val config = withContext(Dispatchers.IO) {
                    profiler.runBenchmark { progress ->
                        _uiState.value = _uiState.value.copy(
                            progress = progress,
                            stage = when {
                                progress < 10 -> "Analyzing hardware..."
                                progress < 35 -> "Testing NPU (NNAPI)..."
                                progress < 65 -> "Testing GPU..."
                                progress < 85 -> "Testing CPU..."
                                else -> "Finalizing..."
                            }
                        )
                    }
                }

                // Save to SQLDelight
                withContext(Dispatchers.IO) {
                    saveAiConfig(config, db)
                }

                Log.i(TAG, "Benchmark complete, saved to DB: ${config.deviceSummary}")

                _uiState.value = OptimizingUiState(
                    isRunning = false,
                    isDone = true,
                    progress = 100,
                    stage = "Optimization complete!",
                    deviceSummary = config.deviceSummary,
                    benchmarkResults = buildResultsText(config)
                )

            } catch (e: Exception) {
                Log.e(TAG, "Benchmark failed", e)
                _uiState.value = OptimizingUiState(
                    isRunning = false,
                    error = "Optimization failed: ${e.message}"
                )
            }
        }
    }

    private fun buildResultsText(config: com.hermitech.hermivision.domain.inference.AIConfig): String {
        val lines = mutableListOf<String>()
        lines.add("Device: ${config.deviceSummary}")
        lines.add("Best: ${config.tfliteDelegate.name}")
        if (config.nnapiAvgMs > 0) lines.add("NPU: ${config.nnapiAvgMs}ms/frame")
        if (config.gpuAvgMs > 0) lines.add("GPU: ${config.gpuAvgMs}ms/frame")
        if (config.cpuAvgMs > 0) lines.add("CPU: ${config.cpuAvgMs}ms/frame")
        return lines.joinToString("\n")
    }
}
