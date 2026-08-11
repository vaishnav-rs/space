package com.perfectframe.camera.editor

import android.graphics.ColorMatrix

/**
 * Film-*inspired* colour looks (honest approximations — not literal recreations of any
 * trademarked stock, which would need proprietary 3D LUTs and can't be verified without a device).
 * Each look is real colour science: luminance-weighted monochrome, channel scaling, contrast and
 * saturation shaping, and lifted blacks, composed into a single [ColorMatrix] so both the live
 * preview (Compose ColorFilter) and the saved file use the exact same transform.
 */
enum class FilmLook(val displayName: String, val blurb: String) {
    NONE("Original", "No look applied"),
    GOLD("Gold", "Warm consumer-negative glow"),
    PORTRAIT("Portrait", "Soft, skin-flattering pastel"),
    CHROME("Chrome", "Punchy neutral slide"),
    VIVID("Vivid", "Landscape-grade saturation"),
    CLEAR("Clear", "Fine, saturated, neutral"),
    CINE("Cinematic", "Cool shadows, warm highlights"),
    FADED("Faded", "Matte, lifted blacks"),
    CROSS("Cross Process", "Skewed greens & yellows"),
    SEPIA("Sepia", "Warm monochrome"),
    TRIX("Tri-X B&W", "High-contrast mono"),
    HP5("HP5 B&W", "Soft classic mono"),
    NOIR("Noir", "Deep-contrast black & white");

    fun baseMatrix(): ColorMatrix = when (this) {
        NONE -> identityMatrix()
        GOLD -> concatMatrices(channelScale(1.10f, 1.02f, 0.90f, addB = 4f), contrastMatrix(1.06f), saturationMatrix(1.18f))
        PORTRAIT -> concatMatrices(channelScale(1.06f, 1.0f, 0.96f, addR = 4f, addG = 3f, addB = 3f), contrastMatrix(0.92f), saturationMatrix(0.94f))
        CHROME -> concatMatrices(contrastMatrix(1.14f), saturationMatrix(1.22f))
        VIVID -> concatMatrices(channelScale(1.04f, 1.06f, 1.0f), contrastMatrix(1.10f), saturationMatrix(1.5f))
        CLEAR -> concatMatrices(contrastMatrix(1.05f), saturationMatrix(1.3f))
        CINE -> concatMatrices(channelScale(1.08f, 1.0f, 0.96f, addB = 10f, addG = 2f), contrastMatrix(1.12f), saturationMatrix(1.05f))
        FADED -> concatMatrices(liftBlacks(20f), contrastMatrix(0.82f), saturationMatrix(0.82f))
        CROSS -> concatMatrices(channelScale(1.12f, 1.05f, 0.85f, addG = -8f, addB = 14f), contrastMatrix(1.1f), saturationMatrix(1.25f))
        SEPIA -> sepiaMatrix()
        TRIX -> concatMatrices(monochromeMatrix(), contrastMatrix(1.25f))
        HP5 -> concatMatrices(monochromeMatrix(), contrastMatrix(1.05f))
        NOIR -> concatMatrices(monochromeMatrix(), contrastMatrix(1.4f))
    }
}

// --- ColorMatrix building blocks (android.graphics, 4x5 row-major, 0..255 domain) -------------

internal fun identityMatrix(): ColorMatrix = ColorMatrix()

/** Per-channel scale with optional additive offset (in 0..255 units). */
internal fun channelScale(
    r: Float, g: Float, b: Float,
    addR: Float = 0f, addG: Float = 0f, addB: Float = 0f,
): ColorMatrix = ColorMatrix(
    floatArrayOf(
        r, 0f, 0f, 0f, addR,
        0f, g, 0f, 0f, addG,
        0f, 0f, b, 0f, addB,
        0f, 0f, 0f, 1f, 0f,
    ),
)

/** Contrast about mid-grey: out = c*in + 128*(1-c). */
internal fun contrastMatrix(c: Float): ColorMatrix {
    val t = 128f * (1f - c)
    return ColorMatrix(
        floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

internal fun saturationMatrix(s: Float): ColorMatrix = ColorMatrix().apply { setSaturation(s) }

/** Raises the black point so shadows go matte. */
internal fun liftBlacks(amount: Float): ColorMatrix = ColorMatrix(
    floatArrayOf(
        1f, 0f, 0f, 0f, amount,
        0f, 1f, 0f, 0f, amount,
        0f, 0f, 1f, 0f, amount,
        0f, 0f, 0f, 1f, 0f,
    ),
)

internal fun monochromeMatrix(): ColorMatrix = ColorMatrix(
    floatArrayOf(
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

internal fun sepiaMatrix(): ColorMatrix = ColorMatrix(
    floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

/** Compose matrices so they apply left-to-right (first arg applied first). */
internal fun concatMatrices(vararg matrices: ColorMatrix): ColorMatrix {
    val result = ColorMatrix()
    matrices.forEach { result.postConcat(it) }
    return result
}
