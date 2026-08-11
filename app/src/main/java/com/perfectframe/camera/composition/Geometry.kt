package com.perfectframe.camera.composition

import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle in normalized frame coordinates (0..1 on both axes, origin top-left).
 *
 * Pure Kotlin on purpose: the composition math must be unit-testable on the JVM without the
 * Android framework (no android.graphics.Rect/RectF). UI code converts these to pixel space.
 */
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = (width.coerceAtLeast(0f)) * (height.coerceAtLeast(0f))

    fun translate(dx: Float, dy: Float): NormRect = NormRect(left + dx, top + dy, right + dx, bottom + dy)

    /** Clamp fully inside the unit square, preserving size where possible. */
    fun clampInsideUnit(): NormRect {
        var l = left
        var t = top
        var r = right
        var b = bottom
        val w = min(width, 1f)
        val h = min(height, 1f)
        if (l < 0f) { l = 0f; r = l + w }
        if (t < 0f) { t = 0f; b = t + h }
        if (r > 1f) { r = 1f; l = r - w }
        if (b > 1f) { b = 1f; t = b - h }
        return NormRect(max(0f, l), max(0f, t), min(1f, r), min(1f, b))
    }

    companion object {
        /** Rule-of-thirds intersection points (the four "power points"). */
        val THIRDS_POINTS: List<Pair<Float, Float>> = listOf(
            1f / 3f to 1f / 3f,
            2f / 3f to 1f / 3f,
            1f / 3f to 2f / 3f,
            2f / 3f to 2f / 3f,
        )
    }
}
