package com.tapgem.app.core.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Log
import com.tapgem.app.core.store.DesktopStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves display captures to the photo gallery (Pictures/TapGem) and keeps a private copy. */
object Screenshots {

    private const val TAG = "Screenshots"

    data class Saved(val displayName: String, val privateFile: File, val inGallery: Boolean)

    fun save(context: Context, bmp: Bitmap): Saved? {
        val name = "tapgem_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val private = File(DesktopStore.screenshotsDir, name)
        val ok = runCatching { private.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }.isSuccess
        if (!ok) return null
        val gallery = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TapGem")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("no stream")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.onFailure { Log.w(TAG, "gallery save failed: ${it.message}") }.getOrDefault(false)
        // Keep the private folder small.
        runCatching {
            DesktopStore.screenshotsDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }
        }
        return Saved(name, private, gallery)
    }
}
