package com.perfectframe.camera.composition

/** A single reframing hint shown as a subtle directional nudge (spec §3, §4). */
enum class Nudge(val label: String) {
    PAN_LEFT("Pan left"),
    PAN_RIGHT("Pan right"),
    TILT_UP("Tilt up"),
    TILT_DOWN("Tilt down"),
    STEP_CLOSER("Step closer"),
    STEP_BACK("Step back"),
    LEVEL_HORIZON("Level the horizon"),
}

/**
 * The composition engine's verdict for one frame.
 *
 * [box] is the suggested crop within the full-scene viewfinder (normalized 0..1). [confidence]
 * gates whether the UI draws it at all — a low-confidence box erodes trust, so it is hidden
 * below threshold (spec §3). [isIdeal] crossing true is the signature "good shot" moment that
 * pulses the box and fires a haptic tick.
 */
data class PerfectFrame(
    val hasSubject: Boolean,
    val box: NormRect,
    val confidence: Float,
    val reasoning: String,
    val nudges: List<Nudge>,
    val isIdeal: Boolean,
) {
    companion object {
        /** No confident suggestion (e.g. no subject yet, or a v2 landscape scene). */
        fun none(reasoning: String): PerfectFrame = PerfectFrame(
            hasSubject = false,
            box = NormRect(0.1f, 0.1f, 0.9f, 0.9f),
            confidence = 0f,
            reasoning = reasoning,
            nudges = emptyList(),
            isIdeal = false,
        )
    }
}
