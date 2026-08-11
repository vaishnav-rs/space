package com.perfectframe.camera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo

/**
 * Honest, device-derived report of what the camera can actually do.
 *
 * The spec is explicit (§0, §6): true 200MP mode may be gated behind OEM HAL extensions not
 * exposed to third-party apps, and f-stop is a fixed physical property. This type carries the
 * negotiated truth so the UI can surface it plainly instead of pretending. Nothing here is
 * fabricated — every field is read from [CameraCharacteristics] or left null/unknown.
 */
data class CameraCapabilities(
    /** Largest JPEG output size the HAL exposes to us (incl. high-resolution/burst sizes). */
    val maxJpegSize: Size?,
    /** Whether that size clears an arbitrary "this is really a high-res sensor mode" bar (~90MP). */
    val exposesHighResSensorMode: Boolean,
    /** Fixed physical aperture, if the device reports it. Shown as a static readout, never a slider. */
    val fixedApertureFStop: Float?,
    /** All standard JPEG sizes, largest first — useful for the settings resolution picker. */
    val standardJpegSizes: List<Size>,
    /** High-resolution (may be slower/burst) JPEG sizes, largest first. */
    val highResJpegSizes: List<Size>,
) {
    val maxMegapixels: Double?
        get() = maxJpegSize?.let { (it.width.toLong() * it.height.toLong()) / 1_000_000.0 }

    /** One-line, honest summary for a debug/about readout. */
    fun summary(): String {
        val mp = maxMegapixels?.let { String.format("%.0fMP", it) } ?: "unknown"
        val res = maxJpegSize?.let { "${it.width}x${it.height}" } ?: "unknown"
        val fstop = fixedApertureFStop?.let { "f/%.1f".format(it) } ?: "f/—"
        val gate = if (exposesHighResSensorMode) "high-res mode exposed" else "capped to binned output"
        return "Max JPEG $res ($mp), $fstop, $gate"
    }

    companion object {
        private const val TAG = "CameraCapabilities"

        /** ~90MP: comfortably above any binned 12/50MP mode, below a true 100/200MP sensor mode. */
        private const val HIGH_RES_MP_THRESHOLD = 90_000_000L

        /**
         * Reads capabilities from a bound [CameraInfo] via Camera2 interop. Safe to call on any
         * thread; only pulls static characteristics. Returns a best-effort report and logs the
         * negotiated max size so it's visible in logcat during bring-up (spec §2 Phase 1).
         */
        fun from(cameraInfo: CameraInfo): CameraCapabilities {
            val c2 = Camera2CameraInfo.from(cameraInfo)
            val map = c2.getCameraCharacteristic(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )

            val standard: List<Size> = map?.getOutputSizes(ImageFormat.JPEG)
                ?.sortedByDescending { it.area() }
                ?: emptyList()

            val highRes: List<Size> = runCatching {
                map?.getHighResolutionOutputSizes(ImageFormat.JPEG)
            }.getOrNull()?.sortedByDescending { it.area() } ?: emptyList()

            val maxSize = (standard + highRes).maxByOrNull { it.area() }

            val aperture: Float? = c2.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES
            )?.firstOrNull()

            val exposesHighRes = (maxSize?.area() ?: 0L) >= HIGH_RES_MP_THRESHOLD

            val caps = CameraCapabilities(
                maxJpegSize = maxSize,
                exposesHighResSensorMode = exposesHighRes,
                fixedApertureFStop = aperture,
                standardJpegSizes = standard,
                highResJpegSizes = highRes,
            )
            Log.i(TAG, "Negotiated capabilities → ${caps.summary()}")
            Log.i(TAG, "Standard JPEG sizes: ${standard.take(4)}; high-res: ${highRes.take(4)}")
            return caps
        }

        private fun Size.area(): Long = width.toLong() * height.toLong()
    }
}
