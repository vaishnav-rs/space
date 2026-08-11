package com.perfectframe.camera.vision

import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.perfectframe.camera.composition.NormRect
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ML Kit face + object detector wired as an [ImageAnalysis.Analyzer] (spec §2 Phase 4, step 1).
 *
 * Fully on-device (bundled models) — no network. Detection is deliberately throttled to
 * ~[targetFps] because full framerate is wasteful for framing analysis and would starve the
 * preview; the UI decouples render rate from this by interpolating the box between updates.
 *
 * Results are published as normalized, upright-frame [DetectedSubject]s and logged, so Phase-5
 * verification can confirm detection works before any framing UI exists.
 */
@ExperimentalGetImage
class SubjectDetector(
    private val targetFps: Int = 8,
) : ImageAnalysis.Analyzer {

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .enableTracking()
            .build()
    )

    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    private val _subjects = MutableStateFlow<List<DetectedSubject>>(emptyList())
    val subjects: StateFlow<List<DetectedSubject>> = _subjects.asStateFlow()

    private val minFrameIntervalMs: Long = (1000L / targetFps.coerceAtLeast(1))
    @Volatile private var lastProcessedMs: Long = 0L

    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastProcessedMs < minFrameIntervalMs) {
            imageProxy.close()
            return
        }
        lastProcessedMs = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        // Upright frame dimensions (width/height swap on 90/270) — the space ML Kit reports in.
        val uprightW: Int
        val uprightH: Int
        if (rotation == 90 || rotation == 270) {
            uprightW = mediaImage.height
            uprightH = mediaImage.width
        } else {
            uprightW = mediaImage.width
            uprightH = mediaImage.height
        }

        val input = InputImage.fromMediaImage(mediaImage, rotation)

        val faces = ArrayList<DetectedSubject>()
        val objects = ArrayList<DetectedSubject>()
        val faceDone = AtomicBoolean(false)
        val objDone = AtomicBoolean(false)

        fun finishIfReady() {
            if (faceDone.get() && objDone.get()) {
                val combined = faces + objects
                _subjects.value = combined
                if (combined.isNotEmpty()) {
                    Log.d(TAG, "Detected ${faces.size} face(s), ${objects.size} object(s): $combined")
                }
                imageProxy.close()
            }
        }

        faceDetector.process(input)
            .addOnSuccessListener { list ->
                list.forEach { face ->
                    faces += DetectedSubject(
                        kind = SubjectKind.FACE,
                        box = face.boundingBox.toNormRect(uprightW, uprightH),
                        trackingId = face.trackingId,
                        facingYawDegrees = face.headEulerAngleY,
                    )
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "Face detection failed", e) }
            .addOnCompleteListener { faceDone.set(true); finishIfReady() }

        objectDetector.process(input)
            .addOnSuccessListener { list ->
                list.forEach { obj ->
                    objects += DetectedSubject(
                        kind = SubjectKind.OBJECT,
                        box = obj.boundingBox.toNormRect(uprightW, uprightH),
                        trackingId = obj.trackingId,
                        label = obj.labels.firstOrNull()?.text,
                        confidence = obj.labels.firstOrNull()?.confidence ?: 1f,
                    )
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "Object detection failed", e) }
            .addOnCompleteListener { objDone.set(true); finishIfReady() }
    }

    fun close() {
        faceDetector.close()
        objectDetector.close()
    }

    private fun android.graphics.Rect.toNormRect(w: Int, h: Int): NormRect {
        val fw = w.toFloat().coerceAtLeast(1f)
        val fh = h.toFloat().coerceAtLeast(1f)
        return NormRect(
            left = (left / fw).coerceIn(0f, 1f),
            top = (top / fh).coerceIn(0f, 1f),
            right = (right / fw).coerceIn(0f, 1f),
            bottom = (bottom / fh).coerceIn(0f, 1f),
        )
    }

    companion object {
        private const val TAG = "SubjectDetector"
    }
}
