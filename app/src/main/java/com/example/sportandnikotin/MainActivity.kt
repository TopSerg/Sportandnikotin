package com.example.sportandnikotin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.sportandnikotin.ui.theme.SportAndNikotinTheme
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : ComponentActivity(), PoseLandmarkerHelper.Listener {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var csvLogger: PoseCsvLogger

    private val poseState = mutableStateOf(PoseUiState())
    private val recordingState = mutableStateOf(false)
    private val lastSavedFileState = mutableStateOf<String?>(null)
    private val errorState = mutableStateOf<String?>(null)

    private var lastResultTimestampMs = 0L
    private var smoothedFps = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        csvLogger = PoseCsvLogger(this)
        poseLandmarkerHelper = PoseLandmarkerHelper(this, this)

        setContent {
            SportAndNikotinTheme {
                TrackerScreen(
                    lifecycleOwner = this,
                    cameraExecutor = cameraExecutor,
                    poseState = poseState.value,
                    recording = recordingState.value,
                    lastSavedFile = lastSavedFileState.value,
                    errorMessage = errorState.value,
                    onFrame = poseLandmarkerHelper::detectLiveStream,
                    onToggleRecording = ::toggleRecording,
                    onClearError = { errorState.value = null },
                )
            }
        }
    }

    override fun onDestroy() {
        csvLogger.stop()
        poseLandmarkerHelper.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun onResults(
        result: PoseLandmarkerResult,
        inputWidth: Int,
        inputHeight: Int,
        inferenceTimeMs: Long,
    ) {
        csvLogger.log(result, inputWidth, inputHeight, inferenceTimeMs)

        val timestamp = result.timestampMs()
        if (lastResultTimestampMs > 0L) {
            val delta = timestamp - lastResultTimestampMs
            if (delta > 0L) {
                val instantFps = 1000f / delta.toFloat()
                smoothedFps = if (smoothedFps == 0f) {
                    instantFps
                } else {
                    smoothedFps * 0.85f + instantFps * 0.15f
                }
            }
        }
        lastResultTimestampMs = timestamp

        val points = result.landmarks()
            .firstOrNull()
            ?.map { landmark ->
                PosePoint(
                    x = landmark.x(),
                    y = landmark.y(),
                    z = landmark.z(),
                    visibility = landmark.visibility().orElse(0f),
                )
            }
            .orEmpty()

        runOnUiThread {
            poseState.value = PoseUiState(
                detected = points.isNotEmpty(),
                points = points,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                inferenceTimeMs = inferenceTimeMs,
                fps = smoothedFps,
            )
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            errorState.value = message
        }
    }

    private fun toggleRecording() {
        try {
            if (csvLogger.isRecording) {
                lastSavedFileState.value = csvLogger.stop()
                recordingState.value = false
            } else {
                val target = csvLogger.start()
                lastSavedFileState.value = target
                recordingState.value = true
            }
        } catch (error: Exception) {
            recordingState.value = false
            errorState.value = error.message ?: error.javaClass.simpleName
        }
    }
}

data class PosePoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
)

data class PoseUiState(
    val detected: Boolean = false,
    val points: List<PosePoint> = emptyList(),
    val inputWidth: Int = 0,
    val inputHeight: Int = 0,
    val inferenceTimeMs: Long = 0,
    val fps: Float = 0f,
)

@Composable
private fun TrackerScreen(
    lifecycleOwner: LifecycleOwner,
    cameraExecutor: ExecutorService,
    poseState: PoseUiState,
    recording: Boolean,
    lastSavedFile: String?,
    errorMessage: String?,
    onFrame: (ImageProxy, Boolean) -> Unit,
    onToggleRecording: () -> Unit,
    onClearError: () -> Unit,
) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var lensFacing by rememberSaveable {
        mutableIntStateOf(CameraSelector.LENS_FACING_FRONT)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (cameraGranted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                ) {
                    CameraFeed(
                        modifier = Modifier.fillMaxSize(),
                        lifecycleOwner = lifecycleOwner,
                        cameraExecutor = cameraExecutor,
                        lensFacing = lensFacing,
                        onFrame = onFrame,
                        onError = { message ->
                            if (message.isNotBlank()) {
                                // The activity owns the persistent error state.
                            }
                        },
                    )
                    PoseOverlay(
                        state = poseState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Для трекинга нужен доступ к камере.")
                    Button(
                        onClick = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text("Разрешить камеру")
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (poseState.detected) {
                        "Поза найдена: ${poseState.points.size} точек"
                    } else {
                        "Поза не найдена"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )

                Text(
                    "Inference: ${poseState.inferenceTimeMs} мс · " +
                        "результаты: %.1f FPS".format(poseState.fps),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onToggleRecording) {
                        Text(if (recording) "Остановить запись" else "Начать запись")
                    }

                    Button(
                        onClick = {
                            lensFacing =
                                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                                    CameraSelector.LENS_FACING_BACK
                                } else {
                                    CameraSelector.LENS_FACING_FRONT
                                }
                        },
                    ) {
                        Text("Сменить камеру")
                    }
                }

                if (recording) {
                    Text("CSV записывается…")
                }

                lastSavedFile?.let { path ->
                    Text("Файл: $path")
                }

                errorMessage?.let { message ->
                    Text(
                        text = "Ошибка: $message",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onClearError) {
                        Text("Скрыть")
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraFeed(
    modifier: Modifier,
    lifecycleOwner: LifecycleOwner,
    cameraExecutor: ExecutorService,
    lensFacing: Int,
    onFrame: (ImageProxy, Boolean) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lensFacing, lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null
        var disposed = false

        val mainExecutor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener(
            {
                if (disposed) {
                    return@addListener
                }

                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider

                    val preview = Preview.Builder()
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    val isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT
                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        onFrame(imageProxy, isFrontCamera)
                    }

                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build()

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis,
                    )
                } catch (error: Exception) {
                    onError(error.message ?: error.javaClass.simpleName)
                }
            },
            mainExecutor,
        )

        onDispose {
            disposed = true
            cameraProvider?.unbindAll()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

@Composable
private fun PoseOverlay(
    state: PoseUiState,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (
            state.points.isEmpty() ||
            state.inputWidth <= 0 ||
            state.inputHeight <= 0
        ) {
            return@Canvas
        }

        val scale = min(
            size.width / state.inputWidth.toFloat(),
            size.height / state.inputHeight.toFloat(),
        )
        val imageWidth = state.inputWidth * scale
        val imageHeight = state.inputHeight * scale
        val offsetX = (size.width - imageWidth) / 2f
        val offsetY = (size.height - imageHeight) / 2f

        fun toScreen(point: PosePoint): Offset = Offset(
            x = offsetX + point.x * imageWidth,
            y = offsetY + point.y * imageHeight,
        )

        POSE_CONNECTIONS.forEach { (startIndex, endIndex) ->
            val start = state.points.getOrNull(startIndex)
            val end = state.points.getOrNull(endIndex)
            if (
                start != null &&
                end != null &&
                start.visibility >= 0.35f &&
                end.visibility >= 0.35f
            ) {
                drawLine(
                    color = Color(0xFF00E5FF),
                    start = toScreen(start),
                    end = toScreen(end),
                    strokeWidth = 5f,
                )
            }
        }

        state.points.forEach { point ->
            if (point.visibility >= 0.35f) {
                drawCircle(
                    color = Color(0xFFFFEB3B),
                    radius = 7f,
                    center = toScreen(point),
                )
            }
        }
    }
}

private val POSE_CONNECTIONS = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 7,
    0 to 4, 4 to 5, 5 to 6, 6 to 8,
    9 to 10,
    11 to 12,
    11 to 13, 13 to 15, 15 to 17, 17 to 19, 19 to 15, 15 to 21, 21 to 17,
    12 to 14, 14 to 16, 16 to 18, 18 to 20, 20 to 16, 16 to 22, 22 to 18,
    11 to 23, 12 to 24, 23 to 24,
    23 to 25, 25 to 27, 27 to 29, 29 to 31, 31 to 27,
    24 to 26, 26 to 28, 28 to 30, 30 to 32, 32 to 28,
)
