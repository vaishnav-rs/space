package com.perfectframe.camera.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

private const val TAG = "ImageProcessing"
private const val MAX_EDIT_DIMENSION = 4096

/**
 * Renders [adjustments] onto the image at [srcUri] and saves the result as a new JPEG in
 * Pictures/PerfectFrame. Runs off the main thread. The source is down-sampled to at most
 * [MAX_EDIT_DIMENSION] on the long edge so multi-hundred-megapixel captures don't OOM the editor.
 */
suspend fun renderEditedImage(
    context: Context,
    srcUri: Uri,
    adjustments: EditAdjustments,
): Uri? = withContext(Dispatchers.IO) {
    val src = decodeCapped(context, srcUri) ?: return@withContext null
    val rotation = ((adjustments.rotationDeg % 360) + 360) % 360
    val swap = rotation == 90 || rotation == 270
    val outW = if (swap) src.height else src.width
    val outH = if (swap) src.width else src.height

    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(adjustments.toFloatArray())
    }
    val matrix = Matrix().apply {
        postRotate(rotation.toFloat())
        when (rotation) {
            90 -> postTranslate(src.height.toFloat(), 0f)
            180 -> postTranslate(src.width.toFloat(), src.height.toFloat())
            270 -> postTranslate(0f, src.width.toFloat())
        }
    }
    canvas.drawBitmap(src, matrix, paint)
    src.recycle()

    val uri = writeJpeg(context, out)
    out.recycle()
    uri
}

private fun decodeCapped(context: Context, uri: Uri): Bitmap? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
        val longEdge = max(info.size.width, info.size.height)
        if (longEdge > MAX_EDIT_DIMENSION) {
            decoder.setTargetSampleSize(ceil(longEdge.toFloat() / MAX_EDIT_DIMENSION).toInt())
        }
    }
}.onFailure { Log.e(TAG, "decode failed", it) }.getOrNull()

internal fun writeJpeg(context: Context, bitmap: Bitmap): Uri? {
    val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "PF_edit_$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PerfectFrame")
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return runCatching {
        resolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        uri
    }.onFailure { Log.e(TAG, "save failed", it); resolver.delete(uri, null, null) }.getOrNull()
}
