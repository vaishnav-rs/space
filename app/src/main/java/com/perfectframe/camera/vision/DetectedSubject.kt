package com.perfectframe.camera.vision

import com.perfectframe.camera.composition.NormRect

/** What kind of thing we detected. Faces are weighted more heavily by the composition scorer. */
enum class SubjectKind { FACE, OBJECT }

/**
 * One tracked subject in the current frame, in normalized (0..1) upright-frame coordinates.
 *
 * [facingYawDegrees] (from a face's head Euler-Y) lets the composition engine give lead room in
 * the direction the subject faces (spec §2 Phase 4). Null for objects or when unavailable.
 */
data class DetectedSubject(
    val kind: SubjectKind,
    val box: NormRect,
    val trackingId: Int? = null,
    val confidence: Float = 1f,
    val facingYawDegrees: Float? = null,
    val label: String? = null,
) {
    /** Larger, face-weighted subjects dominate; used to pick the primary subject in v1. */
    val prominence: Float
        get() = box.area * (if (kind == SubjectKind.FACE) FACE_WEIGHT else 1f) * confidence

    companion object {
        private const val FACE_WEIGHT = 2.2f
    }
}
