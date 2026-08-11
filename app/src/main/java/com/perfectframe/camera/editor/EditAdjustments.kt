package com.perfectframe.camera.editor

import android.graphics.ColorMatrix

/**
 * The full set of edits, all normalized to -1..1 (0 = neutral) except the discrete look + rotation.
 * Everything collapses to a single [ColorMatrix] so preview and export are identical.
 */
data class EditAdjustments(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val look: FilmLook = FilmLook.NONE,
    val rotationDeg: Int = 0,
) {
    /** The look, then the user's global adjustments, composed in a sensible order. */
    fun toColorMatrix(): ColorMatrix {
        val exposureScale = 1f + exposure * 0.6f
        val contrastScale = 1f + contrast * 0.5f
        val saturationScale = (1f + saturation).coerceIn(0f, 2f)
        val warm = channelScale(1f + temperature * 0.25f, 1f, 1f - temperature * 0.25f)
        val greenTint = channelScale(1f, 1f - tint * 0.15f, 1f)
        return concatMatrices(
            look.baseMatrix(),
            channelScale(exposureScale, exposureScale, exposureScale),
            contrastMatrix(contrastScale),
            warm,
            greenTint,
            saturationMatrix(saturationScale),
        )
    }

    fun toFloatArray(): FloatArray = toColorMatrix().array

    val isModified: Boolean
        get() = exposure != 0f || contrast != 0f || saturation != 0f ||
            temperature != 0f || tint != 0f || look != FilmLook.NONE || rotationDeg != 0
}
