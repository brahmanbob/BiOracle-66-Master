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
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.*

class MainActivity : AppCompatActivity() {
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var isScanning = false

    private lateinit var tvHrv: TextView
    private lateinit var tvVascular: TextView
    private lateinit var tvMeridian: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnScan: Button

    private val rppgEngine = CHROMEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHrv = findViewById(R.id.tvHrv)
        tvVascular = findViewById(R.id.tvVascular)
        tvMeridian = findViewById(R.id.tvMeridian)
        tvStatus = findViewById(R.id.tvStatus)
        btnScan = findViewById(R.id.btnScan)

        btnScan.setOnClickListener {
            if (isScanning) {
                stopCamera()
                isScanning = false
                btnScan.text = "INITIATE FULL SPECTRUM ANALYSIS"
                tvStatus.text = "Stopped"
            } else {
                startCamera()
            }
        }

        if (checkCameraPermission()) {
            // wait for button press
        } else {
            requestCameraPermission()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // permission granted, user must tap button
        }
    }

    private fun startCamera() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val handlerThread = HandlerThread("cameraBackground")
        handlerThread.start()
        backgroundHandler = Handler(handlerThread.looper)

        try {
            val cameraId = "1" // front camera
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    isScanning = true
                    btnScan.text = "STOP SCANNING"
                    startPreview()
                }
                override fun onDisconnected(device: CameraDevice) { device.close() }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    runOnUiThread { tvStatus.text = "Camera Error $error" }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            tvStatus.text = "Camera failed: ${e.message}"
        }
    }

    private fun startPreview() {
        val resolution = Size(640, 480)
        imageReader = ImageReader.newInstance(resolution.width, resolution.height, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val planes = image.planes
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
            image.close()

            rppgEngine.pushFrame(r, g, b)
            val (hr, rmssd) = rppgEngine.computeMetrics()
            if (hr > 0) {
                val vascularStiffness = if (hr > 80) 1.2 else 0.8
                val meridian = getCircadianMeridian()
                runOnUiThread {
                    tvHrv.text = "${rmssd.toInt()} ms"
                    tvVascular.text = "${(vascularStiffness * 100).toInt()} %"
                    tvMeridian.text = meridian
                    tvStatus.text = "HR: ${hr.toInt()} BPM"
                }
            }
        }, backgroundHandler)

        val surface = imageReader?.surface
        val captureRequest = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest?.addTarget(surface)
        captureRequest?.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(30, 60))
        captureRequest?.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        captureRequest?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        captureRequest?.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)

        cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                session.setRepeatingRequest(captureRequest?.build()!!, null, backgroundHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                runOnUiThread { tvStatus.text = "Preview failed" }
            }
        }, backgroundHandler)
    }

    private fun stopCamera() {
        cameraDevice?.close()
        imageReader?.close()
        cameraDevice = null
        imageReader = null
        isScanning = false
        rppgEngine.reset()
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
            for (i in 1 until ibiMs.size) {
                diffs.add((ibiMs[i] - ibiMs[i - 1]).pow(2))
            }
            val rmssd = sqrt(diffs.average())
            lastHr = hr
            lastRmssd = rmssd
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

        fun reset() {
            buffer.clear()
            lastHr = 0.0
            lastRmssd = 0.0
        }
    }
}
