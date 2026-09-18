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
                val placed = place(b)
                BookmarksBridge.showPanel(false)
                Result.success("Opened bookmark \"${b.title}\" as ${placed.type.name.lowercase(Locale.US)} (id ${placed.id}) at (${placed.x},${placed.y}) size ${placed.w}x${placed.h}.")
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

    private fun names() = Bookmarks.list().joinToString(", ") { "${it.title} (${it.type.name.lowercase(Locale.US)})" }.ifBlank { "none" }

    companion object {
        /** Put a bookmark on the current desktop: same window if it's already open, else a fresh copy in a free spot. */
        fun place(b: Bookmarks.Bookmark): Widget {
            val cur = DesktopBridge.current()
            cur.widgets.firstOrNull { it.type == b.type && it.source == b.widget.source && it.title.equals(b.title, ignoreCase = true) }?.let { dup ->
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
