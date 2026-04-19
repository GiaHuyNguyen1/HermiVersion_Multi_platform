package com.hermitech.hermivision.data.inference

import com.hermitech.hermivision.domain.inference.INativePipeline
import com.hermitech.hermivision.domain.inference.AIConfig
import com.hermitech.hermivision.domain.inference.DeviceTier
import com.hermitech.hermivision.domain.inference.DelegateType

import android.content.Context
import android.os.Build
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Profiles device hardware capabilities and determines the optimal AI configuration.
 *
 * Stateless utility — runs benchmark and returns AIConfig.
 * Persistence is handled externally (Room DB via OptimizingViewModel).
 *
 * Flow:
 *   1. Load benchmark model from assets
 *   2. Benchmark NNAPI (2 warm-up + 3 timed inferences)
 *   3. Benchmark GPU (2 warm-up + 3 timed inferences)
 *   4. Benchmark CPU (2 warm-up + 3 timed inferences)
 *   5. Pick fastest → classify tier → return AIConfig
 */
class DeviceProfiler(private val context: Context) {

    companion object {
        private const val TAG = "DeviceProfiler"

        // Benchmark config
        private const val BENCHMARK_MODEL = "YoloBall-nano-FP32.tflite"
        private const val WARMUP_INFERENCES = 2
        private const val TIMED_INFERENCES = 3
        private const val INPUT_SIZE = 640
        private const val NUM_THREADS = 4

        // Tier classification thresholds (ms per frame)
        private const val HIGH_TIER_THRESHOLD_MS = 100L
        private const val MEDIUM_TIER_THRESHOLD_MS = 300L

        // Currently all tiers use the same model variant
        private const val DEFAULT_MODEL = "YoloBall-nano-FP32.tflite"
    }

    /**
     * Run hardware benchmark to determine the optimal AI configuration.
     *
     * Typical duration: 5-30s depending on device (HIGH tier: ~5s, LOW tier: ~25s).
     *
     * @param onProgress Progress callback (0-100) for UI updates
     * @return The optimal AIConfig for this device
     */
    fun runBenchmark(onProgress: (Int) -> Unit = {}): AIConfig {
        val deviceInfo = getDeviceInfo()
        Log.i(TAG, "═══════════════════════════════════════════════")
        Log.i(TAG, "Starting hardware benchmark on $deviceInfo")
        Log.i(TAG, "═══════════════════════════════════════════════")

        onProgress(5)

        val modelBuffer = loadModelFile(BENCHMARK_MODEL)
        val dummyInput = createDummyInput()
        val dummyOutput = Array(1) { Array(5) { FloatArray(8400) } }

        // Benchmark NNAPI
        onProgress(10)
        val nnapiAvgMs = benchmarkDelegate(DelegateType.NNAPI, modelBuffer, dummyInput, dummyOutput)
        onProgress(35)

        // Benchmark GPU
        val gpuAvgMs = benchmarkDelegate(DelegateType.GPU, modelBuffer, dummyInput, dummyOutput)
        onProgress(65)

        // Benchmark CPU (always works)
        val cpuAvgMs = benchmarkDelegate(DelegateType.CPU, modelBuffer, dummyInput, dummyOutput)
        onProgress(85)

        // Determine best delegate (lowest avg ms, excluding failures)
        val results = mutableMapOf<DelegateType, Long>()
        if (nnapiAvgMs > 0) results[DelegateType.NNAPI] = nnapiAvgMs
        if (gpuAvgMs > 0) results[DelegateType.GPU] = gpuAvgMs
        if (cpuAvgMs > 0) results[DelegateType.CPU] = cpuAvgMs

        val bestDelegate = results.minByOrNull { it.value }?.key ?: DelegateType.CPU
        val bestAvgMs = results[bestDelegate] ?: cpuAvgMs
        val tier = classifyTier(bestAvgMs)

        Log.i(TAG, "═══════════════════════════════════════════════")
        Log.i(TAG, "Benchmark Results:")
        Log.i(TAG, "  NNAPI: ${formatMs(nnapiAvgMs)}")
        Log.i(TAG, "  GPU:   ${formatMs(gpuAvgMs)}")
        Log.i(TAG, "  CPU:   ${formatMs(cpuAvgMs)}")
        Log.i(TAG, "  Best:  ${bestDelegate.name} (${bestAvgMs}ms/frame)")
        Log.i(TAG, "  Tier:  ${tier.name}")
        Log.i(TAG, "═══════════════════════════════════════════════")

        onProgress(100)

        return AIConfig(
            tier = tier,
            tfliteDelegate = bestDelegate,
            yoloModelName = DEFAULT_MODEL,
            numThreads = NUM_THREADS,
            deviceSummary = "$deviceInfo → ${tier.name} (${bestDelegate.name})",
            nnapiAvgMs = nnapiAvgMs,
            gpuAvgMs = gpuAvgMs,
            cpuAvgMs = cpuAvgMs
        )
    }

    // ======================== BENCHMARK ENGINE ========================

    /**
     * Benchmark a specific delegate type.
     *
     * Runs [WARMUP_INFERENCES] to warm up caches/compilation, then
     * [TIMED_INFERENCES] timed inferences. Returns average ms per frame.
     *
     * @return Average ms per inference frame, or -1 if delegate is unavailable
     */
    private fun benchmarkDelegate(type: DelegateType, modelBuffer: MappedByteBuffer, dummyInput: ByteBuffer, dummyOutput: Array<Array<FloatArray>>): Long {
        var delegate: Any? = null
        var interpreter: Interpreter? = null

        try {
            Log.i(TAG, "Benchmarking ${type.name}...")

            modelBuffer.rewind()

            val options = Interpreter.Options().setNumThreads(NUM_THREADS)

            when (type) {
                DelegateType.NNAPI -> {
                    val d = NnApiDelegate()
                    options.addDelegate(d)
                    delegate = d
                }
                DelegateType.GPU -> {
                    val d = GpuDelegate()
                    options.addDelegate(d)
                    delegate = d
                }
                DelegateType.CPU -> { /* XNNPACK auto-enabled */ }
            }

            interpreter = Interpreter(modelBuffer, options)

            // Warm-up
            for (i in 0 until WARMUP_INFERENCES) {
                dummyInput.rewind()
                interpreter.run(dummyInput, dummyOutput)
            }

            // Timed inferences
            val startNanos = System.nanoTime()
            for (i in 0 until TIMED_INFERENCES) {
                dummyInput.rewind()
                interpreter.run(dummyInput, dummyOutput)
            }
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            val avgMs = elapsedMs / TIMED_INFERENCES

            Log.i(TAG, "  ${type.name}: avg ${avgMs}ms/frame (${elapsedMs}ms total / $TIMED_INFERENCES frames)")
            return avgMs

        } catch (e: Throwable) {
            Log.w(TAG, "  ${type.name}: UNAVAILABLE — ${e.message}")
            return -1
        } finally {
            interpreter?.close()
            when (delegate) {
                is NnApiDelegate -> delegate.close()
                is GpuDelegate -> delegate.close()
            }
        }
    }

    // ======================== TIER CLASSIFICATION ========================

    private fun classifyTier(bestAvgMs: Long): DeviceTier {
        return when {
            bestAvgMs < HIGH_TIER_THRESHOLD_MS -> DeviceTier.HIGH
            bestAvgMs < MEDIUM_TIER_THRESHOLD_MS -> DeviceTier.MEDIUM
            else -> DeviceTier.LOW
        }
    }

    // ======================== UTILITIES ========================

    private fun getDeviceInfo(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }
        return "${Build.MANUFACTURER} ${Build.MODEL} ($soc)"
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val assetPath = "models/$modelName"
        val fd = context.assets.openFd(assetPath)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        val buffer = channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        inputStream.close()
        fd.close()
        return buffer
    }

    private fun createDummyInput(): ByteBuffer {
        return ByteBuffer
            .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
    }

    private fun formatMs(ms: Long): String = if (ms > 0) "${ms}ms/frame" else "N/A"
}