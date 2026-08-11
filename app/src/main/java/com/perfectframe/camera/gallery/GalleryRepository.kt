package com.perfectframe.camera.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One image in the gallery, newest first. */
data class GalleryImage(val uri: Uri, val dateAddedSec: Long)

/**
 * Loads the photos this app captured (Pictures/PerfectFrame) from MediaStore. On Q+ an app can
 * always read its own contributions without a runtime permission; pre-Q relies on
 * READ_EXTERNAL_STORAGE. Runs off the main thread.
 */
suspend fun loadPerfectFrameImages(context: Context): List<GalleryImage> =
    withContext(Dispatchers.IO) {
        val images = ArrayList<GalleryImage>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf("%PerfectFrame%")
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%PerfectFrame%")
        }
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(collection, projection, selection, args, sortOrder)
        }.getOrNull()?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                images += GalleryImage(
                    uri = ContentUris.withAppendedId(collection, id),
                    dateAddedSec = cursor.getLong(dateCol),
                )
            }
        }
        images
    }
