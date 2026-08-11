package com.perfectframe.camera.camera

/**
 * A single frame's worth of exposure telemetry, already formatted for the HUD.
 *
 * The headline feature (spec §0.2) is that exposure is fully automatic but *legible*: the HUD
 * shows live ISO / shutter / WB and, crucially, *why* — [limitingReason]. f-stop is a fixed
 * physical property, carried here only as a static readout ([apertureLabel]); it is never an
 * adjustable control (spec §0, §6).
 *
 * White balance is deliberately honest: AWB does not expose an exact Kelvin, so [whiteBalanceLabel]
 * leads with the AWB lock state and, when gains are available, appends an explicitly-approximate
 * temperature. No fabricated precise number (spec §2 Phase 2).
 */
data class ExposureState(
    val iso: Int? = null,
    val exposureTimeNanos: Long? = null,
    val apertureFStop: Float? = null,
    val awbState: AwbState = AwbState.UNKNOWN,
    val approxKelvin: Int? = null,
    val aeLocked: Boolean = false,
    val isManualOverride: Boolean = false,
    val limitingReason: LimitingReason = LimitingReason.NONE,
) {
    val isoLabel: String get() = iso?.let { "ISO $it" } ?: "ISO —"

    /** Shutter as a photographer's fraction (1/x) or seconds for long exposures. */
    val shutterLabel: String
        get() {
            val ns = exposureTimeNanos ?: return "—"
            if (ns <= 0L) return "—"
            val seconds = ns / 1_000_000_000.0
            return if (seconds >= 1.0) {
                "%.1fs".format(seconds)
            } else {
                val denom = (1.0 / seconds).let { if (it >= 10) Math.round(it / 5.0) * 5 else Math.round(it) }
                "1/$denom"
            }
        }

    /** Fixed aperture readout, e.g. "f/1.9". Static — not a control. */
    val apertureLabel: String get() = apertureFStop?.let { "f/%.1f".format(it) } ?: "f/—"

    val whiteBalanceLabel: String
        get() {
            val base = if (isManualOverride) "WB Lock" else "AWB ${awbState.short}"
            return approxKelvin?.let { "$base · ~${it}K" } ?: base
        }

    val reasonLabel: String get() = limitingReason.message
}

/** Coarse AWB convergence state, mapped from CaptureResult.CONTROL_AWB_STATE. */
enum class AwbState(val short: String) {
    UNKNOWN("—"),
    SEARCHING("seeking"),
    CONVERGED("locked"),
    LOCKED("locked");
}

/**
 * Why the auto system settled where it did. Drives the "what's limiting them" HUD line
 * (spec §0.2). Computed from live values against the sensor's own ISO/exposure ranges, so the
 * reasoning is device-accurate rather than guessed.
 */
enum class LimitingReason(val message: String) {
    NONE("Auto exposure balanced"),
    BRIGHT("Bright scene — base ISO, fast shutter"),
    ISO_CAPPED("ISO capped to limit noise"),
    SHUTTER_SLOWED("Shutter slowed for low light — hold steady"),
    LOW_LIGHT_LIMIT("Low light — ISO high & shutter slow"),
    MANUAL("Manual override active"),
}
