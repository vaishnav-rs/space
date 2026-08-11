package com.perfectframe.camera.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives auto exposure/WB via Camera2Interop and reads back live [CaptureResult] metadata every
 * frame into a [StateFlow] the HUD observes (spec §2 Phase 2). Auto is the default and headline;
 * a manual override (AE lock + EV compensation) layers on top in a later pass but is off here.
 *
 * The reasoning ([LimitingReason]) is computed against the sensor's *own* ISO/exposure ranges
 * (read from characteristics), so "ISO capped" / "shutter slowed" reflect the real hardware
 * limits rather than magic numbers.
 */
@AndroidxOptIn(ExperimentalCamera2Interop::class)
class ExposurePipeline {

    private val _state = MutableStateFlow(ExposureState())
    val state: StateFlow<ExposureState> = _state.asStateFlow()

    // --- manual override inputs (default: fully automatic) ---
    @Volatile private var manualOverride = false
    @Volatile private var aeLockRequested = false
    @Volatile private var aeCompensationSteps = 0

    // --- sensor limits, learned from characteristics for accurate reasoning ---
    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var aeCompStep: Double = 1.0 / 3.0

    /** Read the sensor's ISO/exposure ranges once so reasoning can detect real caps. */
    fun learnLimits(cameraInfo: CameraInfo) {
        val c2 = Camera2CameraInfo.from(cameraInfo)
        isoRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        exposureRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?.let { aeCompStep = it.toDouble() }
    }

    /** Applies AE/AWB request options (and manual overrides, when enabled) to a use-case builder. */
    fun <T> applyTo(extender: Camera2Interop.Extender<T>) {
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_ON
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_LOCK,
            manualOverride && aeLockRequested
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            aeCompensationSteps
        )
    }

    /** Session callback wired into the preview stream; parses each result into [ExposureState]. */
    val captureCallback: CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                _state.value = buildState(result)
            }
        }

    // --- manual-override controls (used by the manual mode pass) ---
    fun setManualOverride(enabled: Boolean) { manualOverride = enabled }
    fun setAeLock(locked: Boolean) { aeLockRequested = locked }
    fun setExposureCompensationSteps(steps: Int) { aeCompensationSteps = steps }
    fun exposureCompensationEv(): Double = aeCompensationSteps * aeCompStep

    private fun buildState(result: CaptureResult): ExposureState {
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val aperture = result.get(CaptureResult.LENS_APERTURE)
        val awb = mapAwbState(result.get(CaptureResult.CONTROL_AWB_STATE))
        val aeLocked = result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_LOCKED
        val kelvin = estimateApproxKelvin(result)

        return ExposureState(
            iso = iso,
            exposureTimeNanos = exposure,
            apertureFStop = aperture,
            awbState = awb,
            approxKelvin = kelvin,
            aeLocked = aeLocked || (manualOverride && aeLockRequested),
            isManualOverride = manualOverride,
            limitingReason = reason(iso, exposure),
        )
    }

    private fun reason(iso: Int?, exposureNs: Long?): LimitingReason {
        if (manualOverride) return LimitingReason.MANUAL
        if (iso == null || exposureNs == null) return LimitingReason.NONE

        val isoMax = isoRange?.upper
        val isoMin = isoRange?.lower
        val nearIsoCap = isoMax != null && iso >= isoMax * 0.9
        val lowIso = isoMin != null && iso <= (isoMin * 1.5)
        val slowShutter = exposureNs >= SLOW_SHUTTER_NS       // >= ~1/30s: handheld blur risk
        val fastShutter = exposureNs <= FAST_SHUTTER_NS       // <= ~1/125s

        return when {
            nearIsoCap && slowShutter -> LimitingReason.LOW_LIGHT_LIMIT
            nearIsoCap -> LimitingReason.ISO_CAPPED
            slowShutter -> LimitingReason.SHUTTER_SLOWED
            lowIso && fastShutter -> LimitingReason.BRIGHT
            else -> LimitingReason.NONE
        }
    }

    private fun mapAwbState(state: Int?): AwbState = when (state) {
        CaptureResult.CONTROL_AWB_STATE_SEARCHING -> AwbState.SEARCHING
        CaptureResult.CONTROL_AWB_STATE_CONVERGED -> AwbState.CONVERGED
        CaptureResult.CONTROL_AWB_STATE_LOCKED -> AwbState.LOCKED
        else -> AwbState.UNKNOWN
    }

    /**
     * A deliberately-coarse Kelvin estimate from the AWB color-correction gains. AWB never gives
     * an exact Kelvin, so this is only ever shown prefixed with "~" and can be null. Derived from
     * the blue/red gain ratio: more blue gain ⇒ warmer illuminant ⇒ lower Kelvin.
     */
    private fun estimateApproxKelvin(result: CaptureResult): Int? {
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return null
        val red = gains.red
        val blue = gains.blue
        if (red <= 0f || blue <= 0f) return null
        val ratio = blue / red
        val kelvin = (6500.0 - (ratio - 1.0) * 2500.0).coerceIn(2700.0, 8000.0)
        // Round to the nearest 100 so it reads as an estimate, not a precise measurement.
        return (Math.round(kelvin / 100.0) * 100).toInt()
    }

    companion object {
        private const val SLOW_SHUTTER_NS = 33_333_333L   // 1/30 s
        private const val FAST_SHUTTER_NS = 8_000_000L     // 1/125 s
    }
}
