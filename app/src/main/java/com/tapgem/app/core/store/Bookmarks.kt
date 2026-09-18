package com.tapgem.app.core.store

import android.graphics.Bitmap
import android.util.Log
import com.tapgem.app.core.model.Wallpaper
import com.tapgem.app.core.model.WallpaperKind
import com.tapgem.app.core.model.Widget
import com.tapgem.app.core.model.WidgetType
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bookmarks: single windows saved for later, independent of any desktop — the
 * checkers game mid-match, a radio station, a PDF at page 40. Each is a
 * snapshot of the widget (type, source, size, style, state and content) plus a
 * thumbnail, kept under files/bookmarks. Files a widget depends on (vibe-coded
 * apps, downloaded media) are copied in, so a bookmark outlives the desktop it
 * came from and the store's garbage collection.
 */
object Bookmarks {

    private const val TAG = "Bookmarks"
    private const val MAX = 60

    /**
     * A saved window ([widget]) or a saved wallpaper ([wallpaper]). [origin] = the live
     * source (widget source / wallpaper image path) when saved — how "already bookmarked"
     * is recognised.
     */
    data class Bookmark(val id: String, val title: String, val widget: Widget?, val createdAt: Long, val thumb: File?,
                        val origin: String = widget?.source.orEmpty(), val wallpaper: Wallpaper? = null) {
        val isWallpaper: Boolean get() = wallpaper != null
        /** Widget type, or null for a wallpaper. */
        val type: WidgetType? get() = widget?.type
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val dir: File get() = File(DesktopStore.appFilesDir, "bookmarks").apply { mkdirs() }
    private val filesDir: File get() = File(dir, "files").apply { mkdirs() }

    fun observe(l: () -> Unit): AutoCloseable { listeners.add(l); return AutoCloseable { listeners.remove(l) } }
    private fun changed() { for (l in listeners) runCatching { l() } }

    fun list(): List<Bookmark> = synchronized(lock) {
        dir.listFiles { f -> f.extension == "json" }?.mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
            ?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    /** Snapshot [w] (with [thumb] if available). A bookmark of the same source+title replaces the old one. */
    fun save(w: Widget, thumb: Bitmap?, name: String? = null): Bookmark {
        val title = (name?.trim()?.takeIf { it.isNotBlank() } ?: w.title).take(32)
        val existing = list().firstOrNull { !it.isWallpaper && it.origin == w.source && it.title.equals(title, ignoreCase = true) }
        val id = existing?.id ?: UUID.randomUUID().toString().take(8)
        val source = keepFile(w.source, id)
        // Geometry is kept for size only; the bookmark is placed afresh when opened. Live playback
        // position/page/app state travel with it — that is the point.
        val snap = w.copy(id = id, title = title, source = source, z = 0, onTop = false)
        val b = Bookmark(id, title, snap, System.currentTimeMillis(), null, origin = w.source)
        synchronized(lock) {
            File(dir, "$id.json").writeText(toJson(b).toString())
            if (thumb != null) runCatching { File(dir, "$id.png").outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 90, it) } }
            // Cap: oldest go first.
            list().drop(MAX).forEach { deleteFiles(it.id) }
        }
        changed()
        return b.copy(thumb = File(dir, "$id.png").takeIf { it.exists() })
    }

    /** Keep the current desktop's wallpaper (image file copied in; gradients/colours by value). */
    fun saveWallpaper(wp: Wallpaper, thumb: Bitmap?, name: String? = null): Bookmark? {
        if (wp.kind == WallpaperKind.NONE) return null
        val title = (name?.trim()?.takeIf { it.isNotBlank() } ?: wp.description.takeIf { it.isNotBlank() }?.let { wallpaperTitle(it) }
            ?: when (wp.kind) { WallpaperKind.IMAGE -> "Wallpaper"; WallpaperKind.GRADIENT -> "Gradient"; else -> "Colour" }).take(32)
        val originKey = wallpaperKey(wp)
        val existing = list().firstOrNull { it.isWallpaper && it.origin == originKey }
        val id = existing?.id ?: UUID.randomUUID().toString().take(8)
        val stored = if (wp.imagePath != null) wp.copy(imagePath = keepFile(wp.imagePath, id)) else wp
        val b = Bookmark(id, title, null, System.currentTimeMillis(), null, origin = originKey, wallpaper = stored)
        synchronized(lock) {
            File(dir, "$id.json").writeText(toJson(b).toString())
            if (thumb != null) runCatching { File(dir, "$id.png").outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 90, it) } }
            list().drop(MAX).forEach { deleteFiles(it.id) }
        }
        changed()
        return b.copy(thumb = File(dir, "$id.png").takeIf { it.exists() })
    }

    /**
     * Identity of a wallpaper regardless of where its file sits: the generated file's own
     * name and size (a bookmark copy is "bm_<id>_<name>", an applied one lands in the
     * wallpapers folder under the same name) — so keeping it twice is one bookmark.
     */
    fun wallpaperKey(wp: Wallpaper): String = wp.imagePath?.let { p ->
        val f = File(p); f.name.replace(Regex("^(bm_[0-9a-f]{8}_|[0-9a-f]{8}-)+"), "") + "#" + f.length()
    } ?: (wp.kind.name + ":" + wp.colors.joinToString(","))

    /** "a serene nature landscape with a gentle river at dusk" → "Serene Nature Landscape". */
    fun wallpaperTitle(desc: String): String {
        val stop = setOf("a", "an", "the", "of", "with", "and", "in", "on", "at", "very", "some", "wallpaper", "background", "image", "picture", "photo")
        val words = desc.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() && it !in stop }
        return words.take(3).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.ifBlank { "Wallpaper" }
    }

    fun delete(id: String): Boolean {
        val ok = synchronized(lock) { deleteFiles(id) }
        if (ok) changed()
        return ok
    }

    private fun deleteFiles(id: String): Boolean {
        val json = File(dir, "$id.json")
        val existed = json.exists()
        json.delete(); File(dir, "$id.png").delete()
        filesDir.listFiles { f -> f.name.startsWith("$id-") }?.forEach { it.delete() }
        return existed
    }

    /** "checkers", "the radio one", an id → best match (exact title, then contains, then word overlap). */
    fun find(ref: String?): Bookmark? {
        val all = list(); if (all.isEmpty()) return null
        val q = ref?.trim()?.lowercase(Locale.US).orEmpty()
        if (q.isBlank()) return all.firstOrNull()
        all.firstOrNull { it.id == q }?.let { return it }
        all.firstOrNull { it.title.lowercase(Locale.US) == q }?.let { return it }
        all.firstOrNull { it.title.lowercase(Locale.US).contains(q) || q.contains(it.title.lowercase(Locale.US)) }?.let { return it }
        val qw = q.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        return all.map { b -> b to b.title.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).count { it in qw } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    /**
     * A fresh widget to put on a desktop from this bookmark: new id, the app file
     * copied back into the apps folder so the running window has its own copy and
     * the snapshot stays intact.
     */
    fun materialize(b: Bookmark): Widget {
        val w = b.widget ?: throw IllegalStateException("not a window bookmark")
        val src = if (w.source.startsWith(filesDir.absolutePath)) {
            val f = File(w.source)
            val target = when (w.type) { WidgetType.APP -> DesktopStore.appsDir; else -> DesktopStore.downloadsDir }
            val base = f.name.substringAfter('-').ifBlank { f.name }.replace(Regex("^(bm_[0-9a-f]{8}_)+"), "")
            val out = File(target, "bm_${b.id}_$base")
            runCatching { if (!out.exists()) f.copyTo(out, overwrite = false) }.onFailure { Log.w(TAG, "copy back: ${it.message}") }
            if (out.exists()) out.absolutePath else w.source
        } else w.source
        return w.copy(id = UUID.randomUUID().toString().take(8), source = src, createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(), z = 0)
    }

    /** Copy a widget's private file (app HTML, downloaded media) into the bookmark folder; other sources pass through. */
    private fun keepFile(source: String, id: String): String {
        val path = source.removePrefix("file://")
        val f = File(path)
        val private = listOf(DesktopStore.appsDir, DesktopStore.downloadsDir, DesktopStore.wallpapersDir).any { d -> f.absolutePath.startsWith(d.absolutePath) }
        if (!private || !f.isFile) return source
        val out = File(filesDir, "$id-${f.name.replace(Regex("^(bm_[0-9a-f]{8}_)+"), "")}")
        return runCatching { f.copyTo(out, overwrite = true); out.absolutePath }.getOrElse { Log.w(TAG, "keep: ${it.message}"); source }
    }

    private fun toJson(b: Bookmark) = JSONObject().put("id", b.id).put("title", b.title).put("createdAt", b.createdAt).put("origin", b.origin)
        .apply { b.widget?.let { put("widget", it.toJson()) }; b.wallpaper?.let { put("wallpaper", it.toJson()) } }

    private fun fromJson(o: JSONObject): Bookmark? {
        val id = o.optString("id").ifBlank { return null }
        val thumb = File(dir, "$id.png").takeIf { it.exists() }
        o.optJSONObject("wallpaper")?.let { wpj ->
            val wp = Wallpaper.fromJson(wpj)
            return Bookmark(id, o.optString("title").ifBlank { "Wallpaper" }, null, o.optLong("createdAt"), thumb, o.optString("origin"), wp)
        }
        val w = Widget.fromJson(o.optJSONObject("widget") ?: return null) ?: return null
        return Bookmark(id, o.optString("title").ifBlank { w.title }, w, o.optLong("createdAt"), thumb, o.optString("origin").ifBlank { w.source })
    }
}
