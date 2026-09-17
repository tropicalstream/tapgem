package com.tapgem.app.core.store

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import com.tapgem.app.core.model.Desktop
import com.tapgem.app.core.model.WallpaperKind
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * On-disk persistence for desktops: one JSON + one PNG thumbnail per desktop
 * under files/desktops, plus helper dirs for generated wallpapers, vibe-coded
 * apps, and the adb "media drop" folder. Every write goes through a uniquely
 * named tmp file + rename under a lock, so two autosaves racing for the same
 * desktop can never delete each other's output or leave a torn file.
 */
object DesktopStore {

    private const val TAG = "DesktopStore"
    private const val PREFS = "tapgem_desktops"
    private const val KEY_CURRENT = "current"

    data class Meta(val id: String, val name: String, val updatedAt: Long, val thumb: File?)

    @SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private val prefs: SharedPreferences get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val ioLock = Any()
    private val tmpSeq = AtomicLong()

    val desktopsDir: File get() = File(app.filesDir, "desktops").apply { mkdirs() }
    val wallpapersDir: File get() = File(app.filesDir, "wallpapers").apply { mkdirs() }
    val appsDir: File get() = File(app.filesDir, "apps").apply { mkdirs() }
    /** Downloaded web media lives in filesDir (cacheDir can be purged under a saved desktop). */
    val downloadsDir: File get() = File(app.filesDir, "downloads").apply { mkdirs() }
    val screenshotsDir: File get() = File(app.filesDir, "screenshots").apply { mkdirs() }
    /** `adb push book.epub /sdcard/Android/data/com.tapgem.app/files/media/` */
    val mediaDropDir: File? get() = app.getExternalFilesDir(null)?.let { File(it, "media").apply { mkdirs() } }

    fun init(context: Context) {
        app = context.applicationContext
        desktopsDir; wallpapersDir; appsDir; downloadsDir; mediaDropDir
        // Old builds kept downloads in cache; anything still there is adopted.
        runCatching {
            File(app.cacheDir, "downloads").listFiles()?.forEach { f -> f.renameTo(File(downloadsDir, f.name)) }
        }
    }

    fun list(): List<Meta> = synchronized(ioLock) {
        desktopsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    val id = o.optString("id").ifBlank { return@runCatching null }
                    Meta(
                        id = id,
                        name = o.optString("name").ifBlank { "Desktop" },
                        updatedAt = o.optLong("updatedAt"),
                        thumb = thumbFile(id).takeIf { it.exists() }
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    fun load(id: String): Desktop? = synchronized(ioLock) {
        runCatching {
            val f = jsonFile(id)
            if (!f.exists()) return null
            Desktop.fromJson(JSONObject(f.readText()))
        }.onFailure { Log.w(TAG, "load $id failed: ${it.message}") }.getOrNull()
    }

    fun save(d: Desktop) {
        synchronized(ioLock) {
            runCatching { atomicWrite(jsonFile(d.id)) { it.writeText(d.toJson().toString()) } }
                .onFailure { Log.w(TAG, "save ${d.id} failed: ${it.message}") }
        }
    }

    fun delete(id: String) {
        synchronized(ioLock) {
            jsonFile(id).delete()
            thumbFile(id).delete()
            if (currentId() == id) prefs.edit().remove(KEY_CURRENT).apply()
        }
        gc()
    }

    fun currentId(): String? = prefs.getString(KEY_CURRENT, null)
    fun setCurrentId(id: String) = prefs.edit().putString(KEY_CURRENT, id).apply()

    fun thumbFile(id: String): File = File(desktopsDir, "$id.png")
    private fun jsonFile(id: String): File = File(desktopsDir, "$id.json")

    fun saveThumb(id: String, bmp: Bitmap) {
        synchronized(ioLock) {
            runCatching {
                atomicWrite(thumbFile(id)) { tmp -> tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) } }
            }.onFailure { Log.w(TAG, "thumb $id failed: ${it.message}") }
        }
    }

    /** Only an exact (case-insensitive, whitespace-normalised) name match. */
    fun findExact(name: String?): Meta? {
        val q = norm(name) ?: return null
        return list().firstOrNull { norm(it.name) == q }
    }

    /**
     * Exact, else unique prefix, else unique contains. Ambiguous partial
     * matches return null so a tool never acts on the wrong desktop.
     */
    fun findByName(name: String?): Meta? {
        val q = norm(name) ?: return null
        val all = list()
        all.firstOrNull { norm(it.name) == q }?.let { return it }
        all.filter { norm(it.name)!!.startsWith(q) }.takeIf { it.size == 1 }?.let { return it.first() }
        all.filter { val n = norm(it.name)!!; n.contains(q) || q.contains(n) }.takeIf { it.size == 1 }?.let { return it.first() }
        return null
    }

    fun candidates(name: String?): List<Meta> {
        val q = norm(name) ?: return emptyList()
        return list().filter { val n = norm(it.name)!!; n.contains(q) || q.contains(n) }
    }

    /**
     * Remove generated wallpapers, apps and downloads no saved desktop refers
     * to any more. Runs after deletes and on a periodic engine tick.
     */
    fun gc() {
        runCatching {
            val referenced = HashSet<String>()
            synchronized(ioLock) {
                desktopsDir.listFiles { f -> f.extension == "json" }?.forEach { f ->
                    runCatching { Desktop.fromJson(JSONObject(f.readText())) }.getOrNull()?.let { d ->
                        if (d.wallpaper.kind == WallpaperKind.IMAGE) d.wallpaper.imagePath?.let { referenced += canonical(it) }
                        d.widgets.forEach { w -> referenced += canonical(w.source) }
                    }
                }
            }
            var removed = 0
            val cutoff = System.currentTimeMillis() - 10 * 60_000L // never touch files younger than 10 min
            listOf(wallpapersDir, appsDir, downloadsDir).forEach { dir ->
                dir.listFiles()?.forEach { f ->
                    val key = canonical(f.absolutePath)
                    if (!referenced.contains(key) && f.lastModified() < cutoff && f.isFile) {
                        if (f.delete()) removed++
                    }
                }
            }
            if (removed > 0) Log.i(TAG, "gc removed $removed unreferenced file(s)")
        }.onFailure { Log.w(TAG, "gc failed: ${it.message}") }
    }

    private fun canonical(path: String): String =
        path.removePrefix("file://").substringBefore('?').substringBefore('#').let { p ->
            runCatching { File(p).canonicalPath }.getOrDefault(p)
        }

    private fun norm(s: String?): String? =
        s?.trim()?.lowercase(Locale.US)?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }

    private inline fun atomicWrite(target: File, write: (File) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.${tmpSeq.incrementAndGet()}.tmp")
        try {
            write(tmp)
            if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) error("rename failed") }
        } finally { if (tmp.exists()) tmp.delete() }
    }
}
