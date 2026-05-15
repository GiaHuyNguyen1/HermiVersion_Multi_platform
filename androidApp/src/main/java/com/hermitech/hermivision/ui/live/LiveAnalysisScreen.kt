package com.hermitech.hermivision.ui.live

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermitech.hermivision.domain.camera.CameraAnalyzer
import com.hermitech.hermivision.domain.court.CourtDetectionManager
import com.hermitech.hermivision.ui.live.components.*
import java.util.concurrent.Executors

private const val TAG = "LiveAnalysisScreen"

@Composable
fun LiveAnalysisScreen(onBackClick: () -> Unit = {}, liveViewModel: LiveViewModel = viewModel())
{
    val context = LocalContext.current
    val activity = context as? Activity

    DisposableEffect(Unit) {
        val originalOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.requestedOrientation = originalOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, true)
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    val isInitialized by liveViewModel.isInitialized.collectAsState()
    val initError by liveViewModel.initError.collectAsState()
    val ballState by liveViewModel.ballState.collectAsState()
    val courtResult by liveViewModel.courtResult.collectAsState()
    val fps by liveViewModel.fps.collectAsState()
    val isStopped by liveViewModel.isStopped.collectAsState()
    val sessionStats by liveViewModel.sessionStats.collectAsState()
    val miniMapState by liveViewModel.miniMapState.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission && !isInitialized) liveViewModel.initPipeline(context)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !hasCameraPermission -> PermissionDeniedContent(
                onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) }
            )
            initError != null -> ErrorContent(error = initError!!)
            !isInitialized -> LoadingContent()
            else -> FullscreenCameraContent(
                liveViewModel = liveViewModel,
                ballState = ballState,
                courtResult = courtResult,
                fps = fps,
                isStopped = isStopped,
                sessionStats = sessionStats,
                miniMapState = miniMapState,
                onStop = { liveViewModel.stopSession() },
                onClose = onBackClick
            )
        }
    }
}

@Composable
private fun FullscreenCameraContent(
    liveViewModel: LiveViewModel,
    ballState: LiveViewModel.BallState,
    courtResult: com.hermitech.hermivision.domain.model.CourtResult,
    fps: Float,
    isStopped: Boolean,
    sessionStats: LiveViewModel.SessionStats,
    miniMapState: CourtDetectionManager.MiniMapState,
    onStop: () -> Unit,
    onClose: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewWidth by remember { mutableIntStateOf(1) }
    @Suppress("UNUSED_VARIABLE")
    var previewHeight by remember { mutableIntStateOf(1) }
    val cameraFrameWidth by remember { mutableIntStateOf(1280) }
    val cameraFrameHeight by remember { mutableIntStateOf(720) }

    Box(modifier = Modifier.fillMaxSize()) {

        // Layer 1: Camera preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val analysisExecutor = Executors.newSingleThreadExecutor()

                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                                        Size(1280, 720),
                                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                    )
                                ).build()
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                        .also {
                            it.setAnalyzer(analysisExecutor, CameraAnalyzer(
                                pipeline = liveViewModel.pipeline,
                                onResult = { result -> liveViewModel.onResult(result) },
                                onFrameDimensions = { w, h -> liveViewModel.setFrameDimensions(w, h) }
                            ))
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                            preview, imageAnalysis
                        )
                        Log.i(TAG, "Camera bound to lifecycle")
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                previewWidth = view.width.coerceAtLeast(1)
                previewHeight = view.height.coerceAtLeast(1)
            }
        )

        if (!isStopped && previewWidth > 1) {
            DetectionOverlays(
                ballState = ballState,
                courtResult = courtResult,
                cameraFrameWidth = cameraFrameWidth,
                cameraFrameHeight = cameraFrameHeight
            )
        }

        AnimatedVisibility(visible = !isStopped, enter = fadeIn(), exit = fadeOut()) {
            HudOverlay(
                ballState = ballState,
                courtResult = courtResult,
                fps = fps,
                miniMapState = miniMapState,
                onStop = onStop,
                onClose = onClose
            )
        }

        AnimatedVisibility(
            visible = isStopped,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut()
        ) {
            LiveResultsOverlay(sessionStats = sessionStats, onClose = onClose)
        }
    }
}