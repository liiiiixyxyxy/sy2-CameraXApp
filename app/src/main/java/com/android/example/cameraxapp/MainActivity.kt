package com.android.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var brightnessText: TextView
    private lateinit var colorText: TextView
    private lateinit var frameRateText: TextView
    private lateinit var recordingIndicator: View
    
    private var frameCount = 0
    private var lastFpsUpdateTime = System.currentTimeMillis()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraExecutor = Executors.newFixedThreadPool(2)

        brightnessText = findViewById(R.id.brightness_text)
        colorText = findViewById(R.id.color_text)
        frameRateText = findViewById(R.id.frame_rate_text)
        recordingIndicator = findViewById(R.id.recording_indicator)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        val imageCaptureButton = findViewById<Button>(R.id.image_capture_button)
        imageCaptureButton.setOnClickListener { takePhoto() }

        val videoCaptureButton = findViewById<Button>(R.id.video_capture_button)
        videoCaptureButton.setOnClickListener { captureVideo() }
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        frameCount++
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - lastFpsUpdateTime
        
        if (elapsedTime >= 1000) {
            val fps = frameCount * 1000.0 / elapsedTime
            mainHandler.post {
                frameRateText.text = String.format(Locale.US, "FPS: %.1f", fps)
            }
            frameCount = 0
            lastFpsUpdateTime = currentTime
        }

        val buffer = imageProxy.planes[0].buffer
        val data = buffer.remaining()
        val pixelArray = ByteArray(data)
        buffer.get(pixelArray)

        var sumBrightness = 0L
        var sumRed = 0L
        var sumGreen = 0L
        var sumBlue = 0L
        
        val step = 4
        val sampleCount = pixelArray.size / (3 * step)

        for (i in 0 until pixelArray.size step 3 * step) {
            val r = pixelArray[i].toInt() and 0xFF
            val g = pixelArray[i + 1].toInt() and 0xFF
            val b = pixelArray[i + 2].toInt() and 0xFF

            sumRed += r
            sumGreen += g
            sumBlue += b

            val brightness = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            sumBrightness += brightness
        }

        val avgBrightness = if (sampleCount > 0) (sumBrightness / sampleCount).toInt() else 0
        val avgRed = if (sampleCount > 0) (sumRed / sampleCount).toInt() else 0
        val avgGreen = if (sampleCount > 0) (sumGreen / sampleCount).toInt() else 0
        val avgBlue = if (sampleCount > 0) (sumBlue / sampleCount).toInt() else 0

        val brightnessLevel = when {
            avgBrightness < 50 -> "Very Dark"
            avgBrightness < 100 -> "Dark"
            avgBrightness < 150 -> "Dim"
            avgBrightness < 200 -> "Normal"
            avgBrightness < 230 -> "Bright"
            else -> "Very Bright"
        }

        val dominantColor = when {
            avgRed > avgGreen && avgRed > avgBlue -> "Reddish"
            avgGreen > avgRed && avgGreen > avgBlue -> "Greenish"
            avgBlue > avgRed && avgBlue > avgGreen -> "Bluish"
            avgRed > 200 && avgGreen > 200 && avgBlue > 200 -> "Whiteish"
            avgRed < 50 && avgGreen < 50 && avgBlue < 50 -> "Blackish"
            else -> "Neutral"
        }

        mainHandler.post {
            brightnessText.text = "Brightness: $avgBrightness ($brightnessLevel)"
            colorText.text = "Color: $dominantColor (R:$avgRed G:$avgGreen B:$avgBlue)"
        }

        imageProxy.close()
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${outputFileResults.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    private fun captureVideo() {
        val videoCapture = videoCapture ?: return

        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            recordingIndicator.visibility = View.GONE
            return
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        val recordingBuilder = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            recordingBuilder.withAudioEnabled()
        }

        recordingIndicator.visibility = View.VISIBLE

        recording = recordingBuilder.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    findViewById<Button>(R.id.video_capture_button).apply {
                        text = getString(R.string.stop_capture)
                        isEnabled = true
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    recordingIndicator.visibility = View.GONE
                    
                    if (!recordEvent.hasError()) {
                        val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        Log.d(TAG, msg)
                    } else {
                        recording?.close()
                        recording = null
                        Log.e(
                            TAG, "Video capture ended with error: ${recordEvent.error}"
                        )
                    }
                    findViewById<Button>(R.id.video_capture_button).apply {
                        text = getString(R.string.start_capture)
                        isEnabled = true
                    }
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
                    }

                imageCapture = ImageCapture.Builder().build()

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            analyzeImage(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Camera initialization failed: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value) {
                    permissionGranted = false
                }
            }
            if (!permissionGranted) {
                Toast.makeText(
                    baseContext,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                startCamera()
            }
        }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}