package com.tapgem.app.core.tools

import com.tapgem.app.core.bridge.BookmarksBridge
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.model.Widget
import com.tapgem.app.core.store.Bookmarks
import java.util.Locale

/**
 * Bookmarks: keep one window for later and bring it back on any desktop.
 * "Bookmark this", "save the checkers game", "open my radio bookmark",
 * "forget the PDF bookmark", "show my bookmarks".
 */
class BookmarkTool : AiTool {
    override val name = "bookmark"

    override suspend fun execute(args: Args): Result<String> {
        val action = when (args.action) {
            "add", "keep", "create", "store", "remember" -> "save"
            "restore", "load", "bring_back", "place" -> "open"
            "remove", "forget", "clear" -> "delete"
            "panel", "browse" -> "show"
            "close" -> "hide"
            else -> args.action
        }
        return when (action) {
            "save" -> {
                val ref = args.str("target", "widget", "id", "title")
                if (ref != null && Regex("^(the )?(wallpaper|background|backdrop)$", RegexOption.IGNORE_CASE).matches(ref.trim()) || args.str("what")?.lowercase(Locale.US) == "wallpaper") {
                    val wp = DesktopBridge.current().wallpaper
                    val b = Bookmarks.saveWallpaper(wp, wallpaperThumb(wp), args.str("name", "as"))
                        ?: return Result.failure(IllegalStateException("This desktop has no wallpaper to keep — paint one first (wallpaper action=set)."))
                    return Result.success("Kept the wallpaper as \"${b.title}\" — it's in the bookmarks panel on every desktop; say 'use my ${b.title} wallpaper' to put it on any desktop.")
                }
                // A named window that isn't here must not silently become "whatever is on top".
                val w = if (ref != null) DesktopBridge.resolveWidget(ref)
                        ?: return Result.failure(IllegalStateException("There's no window called \"$ref\" on this desktop (${DesktopBridge.current().name}). Windows here: ${DesktopBridge.current().widgets.joinToString { it.title }.ifBlank { "none" }}."))
                    else DesktopBridge.activeWidgetId?.let { DesktopBridge.current().widget(it) }
                        ?: DesktopBridge.current().widgets.maxByOrNull { it.z }
                        ?: return Result.failure(IllegalStateException("There's no window to bookmark — open something first."))
                BookmarksBridge.freeze(w.id)   // app state as of this very moment, not the last tick
                val fresh = DesktopBridge.current().widget(w.id) ?: w
                val b = Bookmarks.save(fresh, BookmarksBridge.thumbnail(w.id), args.str("name", "as"))
                Result.success("Bookmarked \"${b.title}\" — it's in the bookmarks panel (the ribbon next to the camera) on every desktop; say 'open my ${b.title} bookmark' to bring it back.")
            }
            "open" -> {
                val b = Bookmarks.find(args.str("name", "target", "title", "id", "query"))
                    ?: return Result.failure(IllegalStateException(if (Bookmarks.list().isEmpty()) "There are no bookmarks yet — say 'bookmark this window' first." else "No bookmark matches that. Bookmarks: ${names()}."))
                BookmarksBridge.showPanel(false)
                if (b.isWallpaper) { applyWallpaper(b); return Result.success("Wallpaper \"${b.title}\" is now on this desktop (desktop mode).") }
                val placed = place(b)
                Result.success("Opened bookmark \"${b.title}\" as ${placed.type!!.name.lowercase(Locale.US)} (id ${placed.id}) at (${placed.x},${placed.y}) size ${placed.w}x${placed.h}.")
            }
            "delete" -> {
                val b = Bookmarks.find(args.str("name", "target", "title", "id", "query"))
                    ?: return Result.failure(IllegalStateException("No bookmark matches that. Bookmarks: ${names()}."))
                Bookmarks.delete(b.id)
                Result.success("Forgot the bookmark \"${b.title}\".")
            }
            "list" -> Result.success(if (Bookmarks.list().isEmpty()) "No bookmarks yet." else "Bookmarks (${Bookmarks.list().size}): ${names()}.")
            "show" -> { BookmarksBridge.showPanel(true); Result.success("Bookmarks panel is open" + (if (Bookmarks.list().isEmpty()) " — it's empty so far." else ": ${names()}.")) }
            "hide" -> { BookmarksBridge.showPanel(false); Result.success("Bookmarks panel closed.") }
            else -> Result.failure(IllegalArgumentException("Unknown bookmark action '${args.action}'. Use save, open, list, delete, show, hide."))
        }
    }

    private fun names() = Bookmarks.list().joinToString(", ") { "${it.title} (${if (it.isWallpaper) "wallpaper" else it.type!!.name.lowercase(Locale.US)})" }.ifBlank { "none" }

    companion object {
        /** A wallpaper bookmark onto the current desktop (image copied back so the desktop never points into the bookmark store). */
        fun applyWallpaper(b: Bookmarks.Bookmark) {
            val wp = b.wallpaper ?: return
            val applied = wp.imagePath?.let { p ->
                val src = java.io.File(p)
                val out = java.io.File(com.tapgem.app.core.store.DesktopStore.wallpapersDir, "bm_${b.id}_${src.name.substringAfter('-')}")
                runCatching { if (!out.exists()) src.copyTo(out) }
                if (out.exists()) wp.copy(imagePath = out.absolutePath) else wp
            } ?: wp
            DesktopBridge.mutate { it.copy(wallpaper = applied, mode = com.tapgem.app.core.model.DesktopMode.DESKTOP) }
        }

        /** Small bitmap of a wallpaper for its tile: the image scaled, or the gradient/colour painted. */
        fun wallpaperThumb(wp: com.tapgem.app.core.model.Wallpaper, w: Int = 232, h: Int = 148): android.graphics.Bitmap? {
            wp.imagePath?.let { p ->
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(p, opts)
                val sample = maxOf(1, minOf(opts.outWidth / w, opts.outHeight / h))
                val full = android.graphics.BitmapFactory.decodeFile(p, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
                return android.graphics.Bitmap.createScaledBitmap(full, w, h, true).also { if (it !== full) full.recycle() }
            }
            if (wp.colors.isEmpty()) return null
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val cols = if (wp.colors.size == 1) intArrayOf(wp.colors[0], wp.colors[0]) else wp.colors.toIntArray()
            android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, cols).apply { setBounds(0, 0, w, h) }.draw(android.graphics.Canvas(bmp))
            return bmp
        }

        /** Put a bookmark on the current desktop: same window if it's already open, else a fresh copy in a free spot. */
        fun place(b: Bookmarks.Bookmark): Widget {
            val cur = DesktopBridge.current()
            val bw = b.widget ?: throw IllegalStateException("not a window bookmark")
            cur.widgets.firstOrNull { it.type == b.type && it.source == bw.source && it.title.equals(b.title, ignoreCase = true) }?.let { dup ->
                DesktopBridge.mutate { d -> d.widget(dup.id)?.let { d.replaceWidget(it.copy(z = (d.widgets.maxOfOrNull { o -> o.z } ?: 0) + 1)) } ?: d }
                DesktopBridge.setActive(dup.id)
                return dup
            }
            val fresh = Bookmarks.materialize(b)
            val (w, h) = Layout.clampSize(fresh.type, fresh.w, fresh.h)
            var placed: Widget? = null
            DesktopBridge.mutate { d ->
                val (px, py) = Layout.freeSlot(d.widgets, w, h)
                val (x, y) = Layout.clampPos(px, py, w, h)
                val widget = fresh.copy(x = x, y = y, w = w, h = h, z = (d.widgets.maxOfOrNull { it.z } ?: 0) + 1)
                placed = widget
                d.copy(widgets = d.widgets + widget)
            }
            DesktopBridge.setActive(placed!!.id)
            return placed!!
        }
    }
}
