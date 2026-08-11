package com.perfectframe.camera.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.lifecycle.Observer
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.perfectframe.camera.vision.SubjectDetector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 * [CameraCapabilities] so the UI can be honest about what was actually captured. Commit 5 adds
 * the ML Kit [ImageAnalysis] path (throttled, KEEP_ONLY_LATEST so it never starves the preview),
 * and the Camera2Interop exposure hooks all flow through this single controller.
 */
@AndroidxOptIn(ExperimentalCamera2Interop::class, ExperimentalGetImage::class)
class CameraController(private val appContext: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    // Remembered so manual-override / aspect changes can transparently rebind the use cases.
    private var boundLifecycleOwner: LifecycleOwner? = null
    private var boundPreviewView: PreviewView? = null

    // Selected capture aspect ratio (drives preview/analysis/capture so they stay WYSIWYG).
    private var aspect: AspectRatioOption = AspectRatioOption.RATIO_4_3

    // Front/back lens; toggled by switchCamera() with a transparent rebind.
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    // Live zoom capabilities/value, mirrored from CameraX's ZoomState LiveData.
    private val _zoom = MutableStateFlow(ZoomInfo())
    val zoom: StateFlow<ZoomInfo> = _zoom.asStateFlow()
    private var zoomObserver: Observer<ZoomState>? = null

    // The most recent capture, so the shutter bar can show a thumbnail that opens the gallery.
    private val _lastCapture = MutableStateFlow<android.net.Uri?>(null)
    val lastCapture: StateFlow<android.net.Uri?> = _lastCapture.asStateFlow()

    // Single-threaded analysis pump so ML Kit work never runs on the main or camera threads.
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _capabilities = MutableStateFlow<CameraCapabilities?>(null)
    val capabilities: StateFlow<CameraCapabilities?> = _capabilities.asStateFlow()

    /** Auto exposure/WB pipeline; its [ExposurePipeline.state] feeds the HUD. */
    val exposure = ExposurePipeline()

    /** On-device subject detector; its [SubjectDetector.subjects] feeds the composition engine. */
    val subjectDetector = SubjectDetector()

    /** Binds Preview + ImageCapture + ImageAnalysis to [previewView] for [lifecycleOwner]. */
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
    ) {
        val provider = awaitCameraProvider()
        cameraProvider = provider
        boundLifecycleOwner = lifecycleOwner
        boundPreviewView = previewView

        // Build Preview with Camera2 interop: drive AE/AWB and read back live metadata per frame.
        val previewBuilder = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(aspect.strategy).build()
            )
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
            .setAspectRatioStrategy(aspect.strategy)
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

        // Analysis stream: a modest resolution is plenty for framing, and KEEP_ONLY_LATEST means
        // slow ML Kit frames are dropped rather than queued — the preview never waits (spec §6).
        val analysisResolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspect.strategy)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, subjectDetector) }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        try {
            // Detach any prior zoom observer before the old camera is unbound.
            zoomObserver?.let { obs -> camera?.cameraInfo?.zoomState?.removeObserver(obs) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner, selector, preview, capture, analysis
            )
            camera?.cameraInfo?.let { info ->
                _capabilities.value = CameraCapabilities.from(info)
                exposure.learnLimits(info)
                val obs = Observer<ZoomState> { zs ->
                    _zoom.value = ZoomInfo(
                        minRatio = zs.minZoomRatio,
                        maxRatio = zs.maxZoomRatio,
                        ratio = zs.zoomRatio,
                        linear = zs.linearZoom,
                    )
                }
                info.zoomState.observeForever(obs)
                zoomObserver = obs
            }
            Log.i(TAG, "Preview + ImageCapture + ImageAnalysis bound to back camera.")
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
                        _lastCapture.value = uri
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

    /**
     * Captures a full-resolution still to a private cache file rather than shared storage. Used
     * by pipelines that post-process the frame (auto-frame crop, film-look development) before
     * anything reaches the user's gallery — so no unprocessed intermediate ever appears there.
     * Caller owns deleting the file once done with it.
     */
    suspend fun captureToTempFile(): File? {
        val capture = imageCapture ?: return null
        val file = File(appContext.cacheDir, "pf_tmp_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(appContext),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        cont.resume(file)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Temp capture failed", exception)
                        cont.resume(null)
                    }
                }
            )
        }
    }

    /** Records a capture that happened outside [capture] (e.g. after post-processing) so the
     *  gallery-thumbnail button in the shutter bar still reflects the latest photo. */
    fun noteExternalCapture(uri: android.net.Uri) {
        _lastCapture.value = uri
    }

    /**
     * Live EV compensation on top of auto exposure — a *manual override that respects the auto
     * engine* rather than replacing it (spec §0.2). Uses CameraX's first-class
     * [androidx.camera.core.CameraControl.setExposureCompensationIndex] so it takes effect without
     * a rebind. [index] is in the sensor's own compensation steps.
     */
    fun setExposureCompensationIndex(index: Int) {
        runCatching { camera?.cameraControl?.setExposureCompensationIndex(index) }
    }

    /** The device's supported EV range/step, so the UI can present real limits (or hide EV). */
    fun cameraExposureState(): androidx.camera.core.ExposureState? =
        camera?.cameraInfo?.exposureState

    /**
     * Locks/unlocks AE+AWB. This flows through the Camera2Interop request options, which are set
     * at bind time, so toggling it rebinds the use cases with the new lock state.
     */
    suspend fun setExposureLocked(locked: Boolean) {
        exposure.setManualOverride(locked)
        exposure.setAeLock(locked)
        val owner = boundLifecycleOwner
        val view = boundPreviewView
        if (owner != null && view != null) bind(owner, view)
    }

    // --- Zoom ---------------------------------------------------------------------------------

    /** Set an absolute zoom ratio, clamped to the sensor's supported range. */
    fun setZoomRatio(ratio: Float) {
        val z = _zoom.value
        runCatching { camera?.cameraControl?.setZoomRatio(ratio.coerceIn(z.minRatio, z.maxRatio)) }
    }

    /** Multiply the current zoom (pinch gestures report a scale factor). */
    fun scaleZoom(factor: Float) {
        setZoomRatio(_zoom.value.ratio * factor)
    }

    /** Snap to one of the quick-zoom stops shown in the UI. */
    fun jumpToZoom(ratio: Float) = setZoomRatio(ratio)

    // --- Tap to focus -------------------------------------------------------------------------

    fun startFocusAndMetering(action: FocusMeteringAction) {
        runCatching { camera?.cameraControl?.startFocusAndMetering(action) }
    }

    // --- Aspect ratio -------------------------------------------------------------------------

    fun currentAspect(): AspectRatioOption = aspect

    fun isFrontFacing(): Boolean = lensFacing == CameraSelector.LENS_FACING_FRONT

    /** Flip between the back and front cameras, rebinding the use-case graph. */
    suspend fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val owner = boundLifecycleOwner
        val view = boundPreviewView
        if (owner != null && view != null) bind(owner, view)
    }

    /** Change the capture aspect ratio; transparently rebinds the use-case graph. */
    suspend fun setAspect(option: AspectRatioOption) {
        if (option == aspect) return
        aspect = option
        val owner = boundLifecycleOwner
        val view = boundPreviewView
        if (owner != null && view != null) bind(owner, view)
    }

    fun unbind() {
        zoomObserver?.let { obs -> camera?.cameraInfo?.zoomState?.removeObserver(obs) }
        zoomObserver = null
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
    }

    /** Fully release resources. Call when the owning composable leaves composition for good. */
    fun release() {
        unbind()
        subjectDetector.close()
        analysisExecutor.shutdown()
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
