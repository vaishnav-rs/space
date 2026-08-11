package com.perfectframe.camera.camera

import androidx.camera.core.resolutionselector.AspectRatioStrategy

/**
 * The capture aspect ratios the user can choose (spec extension). Only the two ratios CameraX can
 * negotiate natively via [AspectRatioStrategy] are offered, so the preview, analysis, and saved
 * JPEG all share the exact same framing — the viewfinder is truly WYSIWYG. (1:1 would require a
 * ViewPort crop and is intentionally left out to keep every stream aligned.)
 *
 * [previewAspect] is width/height for the portrait-locked preview container, so 4:3 shows as a
 * 3:4 tall frame and 16:9 as a 9:16 frame.
 */
enum class AspectRatioOption(
    val label: String,
    val previewAspect: Float,
    val strategy: AspectRatioStrategy,
) {
    RATIO_4_3("4:3", 3f / 4f, AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY),
    RATIO_16_9("16:9", 9f / 16f, AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY),
}

/**
 * A snapshot of the camera's zoom capabilities + current value, mirrored from CameraX's
 * `ZoomState` LiveData into a Compose-friendly [kotlinx.coroutines.flow.StateFlow].
 */
data class ZoomInfo(
    val minRatio: Float = 1f,
    val maxRatio: Float = 1f,
    val ratio: Float = 1f,
    val linear: Float = 0f,
) {
    /** Whether the device actually offers a usable zoom range (hide the control otherwise). */
    val hasRange: Boolean get() = maxRatio > minRatio + 0.01f
}
