package com.bioracle.pure

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import kotlin.math.*

class MainActivity : ComponentActivity() {

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private var imageAnalysis: ImageAnalysis? = null
    private val rppgEngine = CHROMEngine()
    private var isScanning = false

    // Biometric state
    private var hr by mutableStateOf(0.0)
    private var rmssd by mutableStateOf(0.0)
    private var meridian by mutableStateOf("")
    private var faceCentered by mutableStateOf(false)

    // Camera permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera() else Toast.makeText(this, "Camera required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkCameraPermission()) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BiOracleUI(
                    hr = hr,
                    rmssd = rmssd,
                    meridian = meridian,
                    faceCentered = faceCentered,
                    onScanToggle = { if (isScanning) stopScanning() else startScanning() },
                    isScanning = isScanning
                )
            }
        }

        // Update meridian every minute
        GlobalScope.launch(Dispatchers.Main) {
            while (true) {
                meridian = getCircadianMeridian()
                delay(60000)
            }
        }
    }

    private fun checkCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis?.setAnalyzer(Dispatchers.Default.asExecutor(), FrameAnalyzer())

            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startScanning() {
        isScanning = true
    }

    private fun stopScanning() {
        isScanning = false
        rppgEngine.reset()
        hr = 0.0
        rmssd = 0.0
    }

    inner class FrameAnalyzer : ImageAnalysis.Analyzer {
        private val faceDetector = FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build())

        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image ?: return
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            faceDetector.process(inputImage).addOnSuccessListener { faces ->
                faceCentered = faces.isNotEmpty() && faces[0].boundingRect.centerX() in 200..600 // rough
                if (faceCentered && isScanning) {
                    // Extract RGB from YUV (simplified – use same method as before)
                    val planes = mediaImage.planes
                    val yBuffer = planes[0].buffer
                    val uBuffer = planes[1].buffer
                    val vBuffer = planes[2].buffer
                    val ySum = (0 until yBuffer.remaining()).sumOf { yBuffer.get(it).toInt() and 0xFF }
                    val uSum = (0 until uBuffer.remaining()).sumOf { uBuffer.get(it).toInt() and 0xFF }
                    val vSum = (0 until vBuffer.remaining()).sumOf { vBuffer.get(it).toInt() and 0xFF }
                    val pixelCount = yBuffer.remaining()
                    val yAvg = ySum.toDouble() / pixelCount
                    val uAvg = uSum.toDouble() / pixelCount
                    val vAvg = vSum.toDouble() / pixelCount
                    val r = yAvg + 1.402 * (vAvg - 128)
                    val g = yAvg - 0.344 * (uAvg - 128) - 0.714 * (vAvg - 128)
                    val b = yAvg + 1.772 * (uAvg - 128)

                    rppgEngine.pushFrame(r, g, b)
                    val (hrVal, rmssdVal) = rppgEngine.computeMetrics()
                    if (hrVal > 0) {
                        hr = hrVal
                        rmssd = rmssdVal
                    }
                }
            }.addOnCompleteListener { imageProxy.close() }
        }
    }

    // ---------- CHROM Engine (same as before) ----------
    inner class CHROMEngine {
        private val buffer = mutableListOf<Triple<Double, Double, Double>>()
        private val windowSize = 300
        private var lastHr = 0.0
        private var lastRmssd = 0.0

        fun pushFrame(r: Double, g: Double, b: Double) {
            buffer.add(Triple(r, g, b))
            if (buffer.size > windowSize) buffer.removeAt(0)
        }

        fun computeMetrics(): Pair<Double, Double> {
            if (buffer.size < windowSize) return Pair(lastHr, lastRmssd)
            val n = windowSize
            val r = buffer.map { it.first }
            val g = buffer.map { it.second }
            val b = buffer.map { it.third }
            val rMean = r.average()
            val gMean = g.average()
            val bMean = b.average()
            val rNorm = r.map { it / rMean }
            val gNorm = g.map { it / gMean }
            val bNorm = b.map { it / bMean }
            val xs = rNorm.mapIndexed { i, v -> 3 * v - 2 * gNorm[i] }
            val ys = rNorm.mapIndexed { i, v -> 1.5 * v + 1.2 * gNorm[i] - 1.5 * bNorm[i] }
            val xsMean = xs.average()
            val ysMean = ys.average()
            val xsVar = xs.map { (it - xsMean).pow(2) }.average()
            val ysVar = ys.map { (it - ysMean).pow(2) }.average()
            val alpha = sqrt(xsVar / ysVar)
            val bvp = xs.mapIndexed { i, v -> v - alpha * ys[i] }
            val peaks = findPeaks(bvp, (60 * 0.4).toInt())
            if (peaks.size < 2) return Pair(lastHr, lastRmssd)
            val ibiMs = mutableListOf<Double>()
            for (i in 1 until peaks.size) {
                val intervalSec = (peaks[i] - peaks[i - 1]) / 60.0
                ibiMs.add(intervalSec * 1000)
            }
            val meanIbi = ibiMs.average()
            val hr = 60000 / meanIbi
            val diffs = mutableListOf<Double>()
            for (i in 1 until ibiMs.size) diffs.add((ibiMs[i] - ibiMs[i - 1]).pow(2))
            val rmssd = sqrt(diffs.average())
            lastHr = hr; lastRmssd = rmssd
            return Pair(hr, rmssd)
        }
        private fun findPeaks(signal: List<Double>, minDistance: Int): List<Int> {
            val peaks = mutableListOf<Int>()
            for (i in 1 until signal.size - 1) {
                if (signal[i] > signal[i-1] && signal[i] > signal[i+1]) {
                    if (peaks.isEmpty() || i - peaks.last() >= minDistance) peaks.add(i)
                }
            }
            return peaks
        }
        fun reset() { buffer.clear(); lastHr = 0.0; lastRmssd = 0.0 }
    }

    private fun getCircadianMeridian(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 1..2 -> "Liver Pathway Cellular Purge"
            in 3..4 -> "Lung Pathway Oxygenation"
            in 5..6 -> "Large Intestine Assimilation"
            in 7..8 -> "Stomach Nutrient Processing"
            in 9..10 -> "Spleen Enzymatic Transmutation"
            in 11..12 -> "Heart Circulatory Command"
            in 13..14 -> "Small Intestine Sorting"
            in 15..16 -> "Bladder Metabolic Flush"
            in 17..18 -> "Kidney Filtration Upregulation"
            in 19..20 -> "Pericardium Systemic Protection"
            in 21..22 -> "Triple Burner Thermoregulatory"
            in 23,0 -> "Gallbladder Pathway Active"
            else -> "General Systemic Baseline"
        }
    }
}

// ---------- Compose UI ----------
@Composable
fun BiOracleUI(
    hr: Double,
    rmssd: Double,
    meridian: String,
    faceCentered: Boolean,
    onScanToggle: () -> Unit,
    isScanning: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val infiniteTransition = rememberInfiniteTransition()
    val mandalaRadius by infiniteTransition.animateFloat(
        initialValue = 100f,
        targetValue = 150f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing))
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider(surfaceProvider)
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Eye reticle overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(250.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(
                    width = if (faceCentered) 4.dp else 2.dp,
                    color = if (faceCentered) Color.Green else Color.White
                )
            ) {
                // Inner pulsing dot
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = if (faceCentered) Color.Green.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.3f),
                        radius = 20f,
                        center = Offset(size.width / 2, size.height / 2)
                    )
                }
            }
        }

        // Pulse Mandala (bottom center)
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
                .size(200.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val petals = 32
                val angleStep = 360.0 / petals
                val hrFactor = (hr / 60f).coerceIn(0.5f, 1.5f)
                for (i in 0 until petals) {
                    val angle = i * angleStep
                    val rad = Math.toRadians(angle).toFloat()
                    val length = (60 * hrFactor + sin(rad * 6) * 20).coerceAtLeast(20f)
                    val start = Offset(center.x, center.y)
                    val end = Offset(center.x + length * cos(rad), center.y + length * sin(rad))
                    drawLine(
                        color = Color(0xFF00FFAA),
                        start = start,
                        end = end,
                        strokeWidth = 4f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // Data panel (top)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.7f), shape = MaterialTheme.shapes.medium)
                .padding(16.dp)
        ) {
            Text("HR: ${hr.toInt()} BPM", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text("HRV: ${rmssd.toInt()} ms", color = Color.Cyan)
            Text("Meridian: $meridian", color = Color.Yellow)
        }

        // Scan button
        Button(
            onClick = onScanToggle,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) Color.Red else Color(0xFF003366))
        ) {
            Text(if (isScanning) "STOP" else "INITIATE FULL SPECTRUM ANALYSIS")
        }

        // Status
        if (!faceCentered && isScanning) {
            Text(
                text = "👁️ Center your face in the reticle",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 100.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(8.dp)
            )
        }
    }
}

private fun darkColorScheme() = darkColorScheme(
    primary = Color(0xFF00FFAA),
    secondary = Color(0xFF00FFFF),
    background = Color.Black,
    surface = Color.DarkGray
)
