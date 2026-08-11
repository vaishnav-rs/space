package com.perfectframe.camera.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns the CameraX use-case graph and its binding to a [LifecycleOwner].
 *
 * Commit 1 binds only [Preview] so we can confirm a full-screen viewfinder renders on device.
 * Later passes add ImageCapture (max-res), ImageAnalysis (ML Kit), and the Camera2Interop
 * exposure hooks — all bound through this single controller so the use-case graph stays in one
 * place and lifecycle-safe.
 */
class CameraController(private val appContext: Context) {

    private var cameraProvider: ProcessCameraProvider? = null

    /** Binds the preview use case to [previewView]'s surface for [lifecycleOwner]. */
    suspend fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
    ) {
        val provider = awaitCameraProvider()
        cameraProvider = provider

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview)
            Log.i(TAG, "Preview bound to back camera.")
        } catch (t: Throwable) {
            Log.e(TAG, "Use-case binding failed", t)
        }
    }

    fun unbind() {
        cameraProvider?.unbindAll()
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
