package com.perfectframe.camera.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.perfectframe.camera.composition.NormRect
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

private const val PIPELINE_TAG = "CapturePipeline"
private const val MAX_PIPELINE_DIMENSION = 4096

/**
 * Post-processes a just-captured JPEG file and saves the result as a new photo in
 * Pictures/PerfectFrame, deleting the temporary source. Shared by the two automated capture
 * flows:
 *
 * - **Frameica** (tap inside the suggested frame): [cropBox] crops to exactly that composition,
 *   [straightenDegrees] levels the horizon, and a fixed [FilmLook] gives it a clean finish — so
 *   the saved photo shows *only* the perfect frame, not the wider scene it was taken from.
 * - **Film mode**: no crop/straighten (a real camera doesn't reframe for you) — just the loaded
 *   stock's [FilmLook] plus auto-tone.
 *
 * Straightening rotates then crops inward to remove the resulting blank corners. The inset uses a
 * deliberately conservative bound (`edge * sin(angle)`, rather than solving the exact largest
 * inscribed rectangle) so it can never leave a border — it costs a bit more crop than the
 * mathematical minimum, which is an acceptable trade for simplicity at the small angles (a few
 * degrees) this ever runs at.
 */
suspend fun processCapturedPhoto(
    context: Context,
    sourceFile: File,
    cropBox: NormRect?,
    straightenDegrees: Float?,
    autoTone: Boolean,
    look: FilmLook,
): Uri? = withContext(Dispatchers.IO) {
    var bitmap = decodeCappedFile(sourceFile) ?: run {
        sourceFile.delete()
        return@withContext null
    }

    cropBox?.let { box ->
        val cropped = cropToNormRect(bitmap, box)
        if (cropped !== bitmap) { bitmap.recycle(); bitmap = cropped }
    }
    straightenDegrees?.let { degrees ->
        val straightened = straighten(bitmap, degrees)
        if (straightened !== bitmap) { bitmap.recycle(); bitmap = straightened }
    }

    val toneMatrix = if (autoTone) computeAutoToneMatrix(bitmap) else identityMatrix()
    val finalMatrix = concatMatrices(toneMatrix, look.baseMatrix())

    val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(finalMatrix.array)
    }
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    bitmap.recycle()

    val uri = writeJpeg(context, out)
    out.recycle()
    sourceFile.delete()
    uri
}

private fun decodeCappedFile(file: File): Bitmap? = runCatching {
    val source = ImageDecoder.createSource(file)
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
        val longEdge = max(info.size.width, info.size.height)
        if (longEdge > MAX_PIPELINE_DIMENSION) {
            decoder.setTargetSampleSize(ceil(longEdge.toFloat() / MAX_PIPELINE_DIMENSION).toInt())
        }
    }
}.onFailure { Log.e(PIPELINE_TAG, "decode failed", it) }.getOrNull()

/** Crops to a normalized rectangle (0..1, upright-frame coords matching [com.perfectframe.camera.vision.DetectedSubject] boxes). */
internal fun cropToNormRect(bitmap: Bitmap, box: NormRect): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val left = (box.left.coerceIn(0f, 1f) * w).toInt().coerceIn(0, w - 1)
    val top = (box.top.coerceIn(0f, 1f) * h).toInt().coerceIn(0, h - 1)
    val right = (box.right.coerceIn(0f, 1f) * w).toInt().coerceIn(left + 1, w)
    val bottom = (box.bottom.coerceIn(0f, 1f) * h).toInt().coerceIn(top + 1, h)
    return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
}

/** Counter-rotates by [rollDegrees] to level the horizon, then insets to remove blank corners. */
internal fun straighten(bitmap: Bitmap, rollDegrees: Float): Bitmap {
    val angle = rollDegrees.coerceIn(-12f, 12f)
    if (abs(angle) < 0.3f) return bitmap

    val w = bitmap.width
    val h = bitmap.height
    val rotated = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(rotated)
    canvas.translate(w / 2f, h / 2f)
    canvas.rotate(-angle)
    canvas.translate(-w / 2f, -h / 2f)
    canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

    val rad = Math.toRadians(abs(angle).toDouble())
    val insetX = (h * sin(rad)).toInt().coerceIn(0, w / 3)
    val insetY = (w * sin(rad)).toInt().coerceIn(0, h / 3)
    val cw = (w - 2 * insetX).coerceAtLeast(1)
    val ch = (h - 2 * insetY).coerceAtLeast(1)
    val result = Bitmap.createBitmap(rotated, insetX, insetY, cw, ch)
    if (result !== rotated) rotated.recycle()
    return result
}

/**
 * A classic auto-levels stretch: clips the darkest/brightest 1% per channel (sampled from a small
 * downscaled copy for speed) and linearly stretches the rest to fill 0..255. The resulting scale
 * is clamped to a modest range so a flat, low-contrast scene doesn't get pushed to something
 * garish — this is meant to read as "well-exposed," not as an HDR effect.
 */
internal fun computeAutoToneMatrix(bitmap: Bitmap): ColorMatrix {
    val sampleW = 120
    val sampleH = (sampleW.toFloat() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
    val sample = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, true)
    val pixels = IntArray(sampleW * sampleH)
    sample.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
    sample.recycle()

    val rHist = IntArray(256)
    val gHist = IntArray(256)
    val bHist = IntArray(256)
    for (p in pixels) {
        rHist[(p shr 16) and 0xFF]++
        gHist[(p shr 8) and 0xFF]++
        bHist[p and 0xFF]++
    }
    val total = pixels.size

    fun percentile(hist: IntArray, pct: Float): Int {
        val target = (total * pct).toInt()
        var cumulative = 0
        for (i in 0..255) {
            cumulative += hist[i]
            if (cumulative >= target) return i
        }
        return 255
    }

    fun scaleFor(lo: Int, hi: Int): Float = if (hi > lo) (255f / (hi - lo)).coerceIn(0.85f, 1.6f) else 1f

    val rLo = percentile(rHist, 0.01f); val rHi = percentile(rHist, 0.99f)
    val gLo = percentile(gHist, 0.01f); val gHi = percentile(gHist, 0.99f)
    val bLo = percentile(bHist, 0.01f); val bHi = percentile(bHist, 0.99f)
    val rs = scaleFor(rLo, rHi); val gs = scaleFor(gLo, gHi); val bs = scaleFor(bLo, bHi)

    return channelScale(rs, gs, bs, addR = -rLo * rs, addG = -gLo * gs, addB = -bLo * bs)
}
