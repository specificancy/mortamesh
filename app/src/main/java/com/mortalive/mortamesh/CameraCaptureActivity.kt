package com.mortalive.mortamesh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executor

/** Private in-app capture. Output goes only to this app's cache; nothing is inserted into MediaStore. */
class CameraCaptureActivity : ComponentActivity() {
    private var videoMode = false
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var activeOutputFile: File? = null
    private lateinit var previewView: PreviewView
    private lateinit var actionButton: TextView
    private lateinit var statusLabel: TextView
    private var isBusy = false
    private val captureExecutor: Executor by lazy { ContextCompat.getMainExecutor(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        videoMode = intent.getBooleanExtra(EXTRA_VIDEO_MODE, false)
        buildCameraUi()
        requestOrStartCamera()
    }

    private fun buildCameraUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xB8000000.toInt())
        }
        val close = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { closeCapture() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(42), dp(46)))
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        titleColumn.addView(TextView(this).apply {
            text = if (videoMode) "Record a video" else "Take a photo"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        titleColumn.addView(TextView(this).apply {
            text = "Private capture · never added to Gallery"
            textSize = 12f
            setTextColor(0xFFD7E2F1.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        top.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(16), dp(20), dp(22))
            setBackgroundColor(0xD9000000.toInt())
        }
        statusLabel = TextView(this).apply {
            text = if (videoMode) "Up to 60 seconds" else "Capture is saved to encrypted private storage after you return"
            textSize = 12f
            setTextColor(0xFFE2E8F0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        actionButton = TextView(this).apply {
            text = if (videoMode) "●  Start recording" else "Capture photo"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(15), dp(26), dp(15))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1877F2.toInt())
                cornerRadius = dp(28).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { if (videoMode) toggleRecording() else capturePhoto() }
        }
        bottom.addView(statusLabel, LinearLayout.LayoutParams(-1, -2))
        bottom.addView(actionButton, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun requestOrStartCamera() {
        val required = mutableListOf(Manifest.permission.CAMERA)
        if (videoMode) required += Manifest.permission.RECORD_AUDIO
        val missing = required.distinct().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
        } else bindCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is required to capture private media.", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        if (videoMode && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusLabel.text = "Video will be recorded without audio (microphone permission not granted)."
        }
        bindCamera()
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.unbindAll()
                if (videoMode) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
                        .build()
                    val capture = VideoCapture.withOutput(recorder)
                    videoCapture = capture
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                } else {
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    imageCapture = capture
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                }
                actionButton.isEnabled = true
            } catch (e: Exception) {
                Toast.makeText(this, "Camera could not start: ${e.message ?: "device camera unavailable"}", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }, captureExecutor)
    }

    private fun capturePhoto() {
        if (isBusy) return
        val capture = imageCapture ?: return
        val output = newPrivateOutput(".jpg")
        isBusy = true
        actionButton.isEnabled = false
        statusLabel.text = "Capturing photo…"
        val options = ImageCapture.OutputFileOptions.Builder(output).build()
        capture.takePicture(options, captureExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                isBusy = false
                returnCapturedFile(output, "image/jpeg")
            }
            override fun onError(exception: ImageCaptureException) {
                isBusy = false
                output.delete()
                actionButton.isEnabled = true
                statusLabel.text = "Capture failed. Try again."
                Toast.makeText(this@CameraCaptureActivity, "Photo capture failed: ${exception.message ?: "camera error"}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun toggleRecording() {
        if (recording != null) {
            statusLabel.text = "Finishing video…"
            actionButton.isEnabled = false
            recording?.stop()
            return
        }
        val capture = videoCapture ?: return
        val output = newPrivateOutput(".mp4")
        activeOutputFile = output
        val options = FileOutputOptions.Builder(output).setDurationLimitMillis(60_000L).build()
        var pending = capture.output.prepareRecording(this, options)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        isBusy = true
        recording = pending.start(captureExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    statusLabel.text = "Recording · tap Stop when finished"
                    actionButton.text = "■  Stop recording"
                    actionButton.isEnabled = true
                }
                is VideoRecordEvent.Status -> {
                    val seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    statusLabel.text = "Recording · ${seconds}s / 60s"
                    if (seconds >= 60L) {
                        actionButton.isEnabled = false
                        recording?.stop()
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    isBusy = false
                    val finishedOk = event.error == VideoRecordEvent.Finalize.ERROR_NONE ||
                        event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                    if (finishedOk && output.isFile && output.length() > 0L) {
                        returnCapturedFile(output, "video/mp4")
                    } else {
                        output.delete()
                        actionButton.isEnabled = true
                        actionButton.text = "●  Start recording"
                        statusLabel.text = "Recording failed. Please try again."
                        Toast.makeText(this, "Video recording failed (${event.error}).", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        actionButton.isEnabled = false
    }

    private fun newPrivateOutput(extension: String): File {
        val dir = File(cacheDir, "morta-capture").apply { mkdirs() }
        return File(dir, "capture_${System.currentTimeMillis()}$extension").apply { delete() }
    }

    private fun returnCapturedFile(file: File, mime: String) {
        if (!file.isFile || file.length() <= 0L) {
            file.delete()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, file.absolutePath).putExtra(EXTRA_RESULT_MIME, mime))
        finish()
    }

    private fun closeCapture() {
        val active = recording
        if (active != null) {
            statusLabel.text = "Finishing video…"
            actionButton.isEnabled = false
            active.stop()
        } else {
            activeOutputFile?.delete()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    @Deprecated("Camera capture uses the in-app camera and activity result flow")
    override fun onBackPressed() {
        closeCapture()
    }

    override fun onDestroy() {
        try { recording?.stop() } catch (_: Exception) { }
        recording = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_VIDEO_MODE = "capture_video_mode"
        const val EXTRA_RESULT_PATH = "capture_result_path"
        const val EXTRA_RESULT_MIME = "capture_result_mime"
        private const val PERMISSION_REQUEST = 961
    }
}
