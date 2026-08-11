package com.perfectframe.camera.composition

import com.perfectframe.camera.vision.DetectedSubject
import com.perfectframe.camera.vision.SubjectKind
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Scores the scene and proposes the ideal crop (spec §2 Phase 4). Deliberately pure Kotlin —
 * no Android dependencies — so it is unit-testable on the JVM, the one part of the pipeline that
 * *can* be verified off-device.
 *
 * v1 (this pass): a single dominant subject, offset toward the nearest rule-of-thirds power
 * point, with headroom for faces and lead room in the direction the subject faces. The crop is a
 * scaled copy of the full frame (so it keeps the sensor's aspect ratio) — in normalized
 * coordinates that is an s×s rectangle, where s is chosen so the subject fills a pleasing
 * fraction of the crop. Multi-subject balancing and no-subject landscape framing are documented
 * v2 work and intentionally out of scope here (spec §2: "don't over-engineer before v1 works").
 */
class CompositionEngine(
    private val config: Config = Config(),
) {

    data class Config(
        /** Face height as a fraction of the crop height (headroom-friendly). */
        val desiredFaceFraction: Float = 0.34f,
        /** Object's larger dimension as a fraction of the crop. */
        val desiredObjectFraction: Float = 0.55f,
        val minCropScale: Float = 0.35f,
        val maxCropScale: Float = 1.0f,
        /** Yaw magnitude (deg) beyond which we allocate lead room in the facing direction. */
        val facingYawThreshold: Float = 12f,
        /** Confidence at/above which the UI shows the box. */
        val showThreshold: Float = 0.55f,
        /** Confidence at/above which framing is celebrated as "ideal". */
        val idealThreshold: Float = 0.74f,
        val nudgeMargin: Float = 0.06f,
    )

    /**
     * @param subjects detected subjects in normalized upright-frame coords
     * @param horizonRollDegrees current device roll (for the level term / nudge)
     * @param levelToleranceDegrees band within which the horizon counts as level
     */
    fun compute(
        subjects: List<DetectedSubject>,
        horizonRollDegrees: Float,
        levelToleranceDegrees: Float,
    ): PerfectFrame {
        val primary = subjects.maxByOrNull { it.prominence }
            ?: return PerfectFrame.none("Point at a subject to compose")

        val isLevel = abs(horizonRollDegrees) <= levelToleranceDegrees

        // 1. Crop scale so the subject fills the desired fraction of the crop.
        val desired = if (primary.kind == SubjectKind.FACE)
            config.desiredFaceFraction else config.desiredObjectFraction
        val byH = primary.box.height / desired
        val byW = primary.box.width / desired
        val scale = maxOf(byH, byW).coerceIn(config.minCropScale, config.maxCropScale)

        // 2. Anchor: for faces bias to the eye line (upper part of the box) for natural headroom.
        val anchorX = primary.box.centerX
        val anchorY = if (primary.kind == SubjectKind.FACE)
            primary.box.top + primary.box.height * 0.42f else primary.box.centerY

        // 3. Pick the thirds power point. Lead room: if the subject faces clearly left/right,
        //    place it on the opposite third so there's space in front of it.
        val yaw = primary.facingYawDegrees ?: 0f
        val thirdFracX = when {
            yaw > config.facingYawThreshold -> 1f / 3f     // facing right → sit on left third
            yaw < -config.facingYawThreshold -> 2f / 3f    // facing left → sit on right third
            else -> if (anchorX < 0.5f) 1f / 3f else 2f / 3f
        }
        // Faces read best near the upper third; otherwise pick nearest horizontally-chosen row.
        val thirdFracY = if (primary.kind == SubjectKind.FACE) {
            1f / 3f
        } else {
            if (anchorY < 0.5f) 1f / 3f else 2f / 3f
        }

        // 4. Position crop so the anchor lands on that power point inside the crop, then clamp.
        val idealLeft = anchorX - thirdFracX * scale
        val idealTop = anchorY - thirdFracY * scale
        val unclamped = NormRect(idealLeft, idealTop, idealLeft + scale, idealTop + scale)
        val box = unclamped.clampInsideUnit()

        // 5. Confidence from how well the ideal was achievable + subject quality + level.
        val clampShift = hypot((box.left - unclamped.left), (box.top - unclamped.top))
        val clampScore = (1f - (clampShift / scale)).coerceIn(0f, 1f)
        val actualFrac = maxOf(primary.box.height, primary.box.width) / scale
        val sizeScore = (1f - abs(actualFrac - desired) / desired).coerceIn(0f, 1f)
        val edgeClipped = primary.box.left <= EDGE_EPS || primary.box.top <= EDGE_EPS ||
            primary.box.right >= 1f - EDGE_EPS || primary.box.bottom >= 1f - EDGE_EPS
        val edgeScore = if (edgeClipped) 0.35f else 1f
        val levelScore = if (isLevel) 1f else
            (1f - (abs(horizonRollDegrees) - levelToleranceDegrees) / 20f).coerceIn(0f, 1f)
        val subjectScore = primary.confidence.coerceIn(0f, 1f)

        val confidence = (
            0.30f * clampScore +
                0.22f * sizeScore +
                0.18f * edgeScore +
                0.15f * levelScore +
                0.15f * subjectScore
            ).coerceIn(0f, 1f)

        val nudges = buildNudges(box, scale, isLevel, edgeClipped)
        val isIdeal = confidence >= config.idealThreshold && isLevel && !edgeClipped

        val reasoning = when {
            isIdeal -> "Nailed it — subject on the thirds, horizon level"
            edgeClipped -> "Subject clipped at the edge — recompose"
            !isLevel -> "Straighten up — horizon is tilted"
            confidence < config.showThreshold -> "Composing…"
            else -> "Nudge the frame to the highlighted crop"
        }

        return PerfectFrame(
            hasSubject = true,
            box = box,
            confidence = confidence,
            reasoning = reasoning,
            nudges = nudges,
            isIdeal = isIdeal,
        )
    }

    private fun buildNudges(
        box: NormRect,
        scale: Float,
        isLevel: Boolean,
        edgeClipped: Boolean,
    ): List<Nudge> {
        val out = ArrayList<Nudge>(3)
        val dx = box.centerX - 0.5f
        val dy = box.centerY - 0.5f
        if (dx > config.nudgeMargin) out += Nudge.PAN_RIGHT
        else if (dx < -config.nudgeMargin) out += Nudge.PAN_LEFT
        if (dy > config.nudgeMargin) out += Nudge.TILT_DOWN
        else if (dy < -config.nudgeMargin) out += Nudge.TILT_UP
        // A crop much tighter than the full frame means the subject is small — get closer.
        if (scale < 0.6f && out.size < 2) out += Nudge.STEP_CLOSER
        if (!isLevel) out += Nudge.LEVEL_HORIZON
        return out.take(2)
    }

    /** Whether a frame should be drawn at all. */
    fun shouldShow(frame: PerfectFrame): Boolean =
        frame.hasSubject && frame.confidence >= config.showThreshold

    companion object {
        private const val EDGE_EPS = 0.02f
    }
}
