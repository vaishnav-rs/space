package com.perfectframe.camera.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns the CameraX use-case graph and its binding to a [LifecycleOwner].
 *
 * Commit 2 adds [ImageCapture] configured for the largest resolution the HAL will expose
 * (high-resolution/sensor mode included, per spec §2 Phase 1), and reports the negotiated
 * [CameraCapabilities] so the UI can be honest about what was actually captured. Later passes
 * add ImageAnalysis (ML Kit) and the Camera2Interop exposure hooks through the same controller.
 */
class CameraController(private val appContext: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private val _capabilities = MutableStateFlow<CameraCapabilities?>(null)
    val capabilities: StateFlow<CameraCapabilities?> = _capabilities.asStateFlow()

    /** Auto exposure/WB pipeline; its [ExposurePipeline.state] feeds the HUD. */
    val exposure = ExposurePipeline()

    /** Binds Preview + ImageCapture to [previewView]'s surface for [lifecycleOwner]. */
    @AndroidxOptIn(ExperimentalCamera2Interop::class)
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
    ) {
        val provider = awaitCameraProvider()
        cameraProvider = provider

        // Build Preview with Camera2 interop: drive AE/AWB and read back live metadata per frame.
        val previewBuilder = Preview.Builder()
        Camera2Interop.Extender(previewBuilder).apply {
            exposure.applyTo(this)
            setSessionCaptureCallback(exposure.captureCallback)
        }
        val preview = previewBuilder.build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        // Ask for the highest still resolution the device will give a third-party app. The
        // PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE mode unlocks the high-resolution (sensor
        // mode / burst) output sizes on devices that expose them — this is what reaches for the
        // 200MP mode. If the HAL caps us lower, capabilities reporting says so honestly.
        val resolutionSelector = ResolutionSelector.Builder()
            .setAllowedResolutionMode(
                ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
            )
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolutionSelector)
            .build()
        imageCapture = capture

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            camera?.cameraInfo?.let { info ->
                _capabilities.value = CameraCapabilities.from(info)
                exposure.learnLimits(info)
            }
            Log.i(TAG, "Preview + ImageCapture bound to back camera.")
        } catch (t: Throwable) {
            Log.e(TAG, "Use-case binding failed", t)
        }
    }

    /**
     * Captures a full-resolution still to shared storage (Pictures/PerfectFrame). Logs the
     * negotiated capture size and the resulting file size so Phase-1 verification can confirm
     * the device actually produced a max-resolution file (spec §2).
     */
    suspend fun capture(): CaptureResult {
        val capture = imageCapture ?: return CaptureResult.Error(
            IllegalStateException("ImageCapture not bound")
        )

        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PF_$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PerfectFrame")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            appContext.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(appContext),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        val uri = results.savedUri
                        val bytes = uri?.let { u ->
                            runCatching {
                                appContext.contentResolver.openFileDescriptor(u, "r")
                                    ?.use { it.statSize }
                            }.getOrNull()
                        }
                        Log.i(
                            TAG,
                            "Captured → $uri (${bytes ?: "?"} bytes) at " +
                                "${_capabilities.value?.maxJpegSize}"
                        )
                        cont.resume(CaptureResult.Saved(uri, bytes))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Capture failed", exception)
                        cont.resume(CaptureResult.Error(exception))
                    }
                }
            )
        }
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }, ContextCompat.getMainExecutor(appContext))
        }

    companion object {
        private const val TAG = "CameraController"
    }
}

/** Outcome of a still capture. */
sealed interface CaptureResult {
    data class Saved(val uri: android.net.Uri?, val sizeBytes: Long?) : CaptureResult
    data class Error(val cause: Throwable) : CaptureResult
}
