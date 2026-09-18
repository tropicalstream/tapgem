package com.tapgem.app.core.bridge

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tapgem.app.core.model.Canvas
import com.tapgem.app.core.model.Desktop
import com.tapgem.app.core.model.DesktopMode
import com.tapgem.app.core.model.Widget
import com.tapgem.app.core.model.WidgetType
import com.tapgem.app.core.store.DesktopStore
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The single source of truth for the desktop on screen. Tools (voice) and the
 * UI (cursor drag/resize) both mutate through [mutate]; the host view
 * re-renders from the published snapshot. Every user-visible mutation pushes
 * an undo snapshot; every mutation autosaves (debounced) and refreshes the
 * strip thumbnail (debounced) so "save" is never something the user can forget.
 */
object DesktopBridge {

    private const val TAG = "DesktopBridge"
    private const val UNDO_DEPTH = 25
    private const val AUTOSAVE_DELAY_MS = 800L
    private const val THUMB_DELAY_MS = 2_500L

    /** An undo entry remembers what the transform changed so undo can leave machine state alone. */
    private class UndoEntry(val before: Desktop, val after: Desktop)

    private val state = AtomicReference<Desktop?>(null)
    private val listeners = CopyOnWriteArrayList<(Desktop) -> Unit>()
    private val catalogListeners = CopyOnWriteArrayList<() -> Unit>()
    private val undoStack = ArrayDeque<UndoEntry>()
    private val main = Handler(Looper.getMainLooper())

    /** Installed by MainActivity: renders the current host view to a bitmap. */
    @Volatile var thumbnailRenderer: (() -> Bitmap?)? = null

    /** The widget the user last focused (title-bar tap / voice). */
    @Volatile var activeWidgetId: String? = null
        private set

    private val saveRunnable = Runnable { persistNow() }
    private val thumbRunnable = Runnable { renderThumbNow() }

    fun init(context: Context) {
        DesktopStore.init(context)
        val cur = DesktopStore.currentId()?.let { DesktopStore.load(it) }
            ?: DesktopStore.list().firstOrNull()?.let { DesktopStore.load(it.id) }
            ?: defaultDesktop().also { DesktopStore.save(it) }
        state.set(cur)
        DesktopStore.setCurrentId(cur.id)
    }

    fun current(): Desktop = state.get() ?: defaultDesktop().also { state.set(it) }

    fun observe(listener: (Desktop) -> Unit): AutoCloseable {
        listeners.add(listener)
        runCatching { listener(current()) }
        return AutoCloseable { listeners.remove(listener) }
    }

    fun observeCatalog(listener: () -> Unit): AutoCloseable {
        catalogListeners.add(listener)
        runCatching { listener() }
        return AutoCloseable { catalogListeners.remove(listener) }
    }

    /**
     * Apply [transform] to the current desktop and publish. [pushUndo]=false
     * for machine-driven updates (live refreshes, playback position) that
     * shouldn't pollute the user's undo history. Returns the new desktop.
     */
    @Synchronized
    fun mutate(pushUndo: Boolean = true, transform: (Desktop) -> Desktop): Desktop {
        val before = current()
        val after = transform(before).copy(updatedAt = System.currentTimeMillis())
        if (pushUndo && after != before) {
            undoStack.addLast(UndoEntry(before, after))
            while (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        }
        state.set(after)
        fire(after)
        scheduleAutosave()
        return after
    }

    /** Update one widget by id (no-op if it is gone — never resurrects a closed window). */
    fun mutateWidget(id: String, pushUndo: Boolean = true, transform: (Widget) -> Widget): Desktop =
        mutate(pushUndo) { d -> d.widget(id)?.let { w -> d.replaceWidget(transform(w)) } ?: d }

    /** Switch to a different desktop (load / new). Flushes the previous one first. Clears undo. */
    @Synchronized
    fun replace(d: Desktop) {
        val prev = state.get()
        main.removeCallbacks(saveRunnable)
        if (prev != null && prev.id != d.id) DesktopStore.save(prev)
        state.set(d)
        DesktopStore.setCurrentId(d.id)
        undoStack.clear()
        activeWidgetId = null
        fire(d)
        scheduleAutosave()
        main.removeCallbacks(thumbRunnable)
        main.postDelayed(thumbRunnable, 1_200L)
        catalogChanged()
    }

    /**
     * Undo the last user-visible mutation. Machine-owned fields (fetched
     * content, playback/page state) that the undone step did not touch are
     * kept from the live widget so undoing a "move" never rewinds a video.
     */
    @Synchronized
    fun undo(): Boolean {
        val entry = undoStack.removeLastOrNull() ?: return false
        val now = current()
        val restored = entry.before.widgets.map { b ->
            val cur = now.widget(b.id) ?: return@map b
            val stepped = entry.after.widget(b.id)
            val stateTouched = stepped == null || stepped.state != b.state
            val contentTouched = stepped == null || stepped.content != b.content
            b.copy(
                state = if (stateTouched) b.state else cur.state,
                content = if (contentTouched) b.content else cur.content
            )
        }
        state.set(entry.before.copy(widgets = restored, updatedAt = System.currentTimeMillis()))
        fire(current())
        scheduleAutosave()
        return true
    }

    fun setActive(id: String?) {
        activeWidgetId = id?.takeIf { current().widget(it) != null }
        fire(current())
    }

    /** Flush: write JSON now and render the thumbnail now (main thread). */
    fun saveNow() {
        main.removeCallbacks(saveRunnable)
        state.get()?.let { DesktopStore.save(it) }
        main.removeCallbacks(thumbRunnable)
        if (Looper.myLooper() == Looper.getMainLooper()) renderThumbNow() else main.post(thumbRunnable)
    }

    fun catalogChanged() {
        for (l in catalogListeners) runCatching { l() }
    }

    // ── Lookup helpers shared by the tools ────────────────────────────

    private val FILLER = setOf("the", "a", "an", "my", "this", "that", "widget", "window", "panel", "one", "please")

    /** Resolve "the clock", "vacation video", an id, or "last" to a widget. */
    fun resolveWidget(ref: String?, typeHint: String? = null): Widget? {
        val d = current()
        if (d.widgets.isEmpty()) return null
        val raw = ref?.trim()?.lowercase(Locale.US).orEmpty()
        val words = raw.split(Regex("[\\s,]+")).filter { it.isNotBlank() && it !in FILLER }
        val q = words.joinToString(" ")
        val typeFromQuery = WidgetType.parse(q) ?: words.firstNotNullOfOrNull { WidgetType.parse(it) }
        val type = WidgetType.parse(typeHint) ?: typeFromQuery
        if (q in setOf("last", "latest", "newest", "it", "")) {
            if (q.isBlank()) {
                activeWidgetId?.let { id -> d.widget(id)?.let { return it } }
                if (type != null) d.widgets.filter { it.type == type }.maxByOrNull { it.createdAt }?.let { return it }
                return if (d.widgets.size == 1) d.widgets.first() else null
            }
            return d.widgets.maxByOrNull { it.createdAt }
        }
        if (q in setOf("active", "focused", "current", "selected", "front", "top")) {
            activeWidgetId?.let { id -> d.widget(id)?.let { return it } }
            return d.widgets.maxByOrNull { it.z }
        }
        d.widgets.firstOrNull { it.id.lowercase(Locale.US) == raw || it.id.lowercase(Locale.US) == q }?.let { return it }
        d.widgets.firstOrNull { it.title.lowercase(Locale.US) == q }?.let { return it }
        // Query made only of type words ("the video") → newest of that type.
        if (typeFromQuery != null && words.all { WidgetType.parse(it) != null }) {
            d.widgets.filter { it.type == typeFromQuery }.maxByOrNull { it.createdAt }?.let { return it }
        }
        // Title contains / query contains title (ignoring filler & type words).
        val core = words.filter { WidgetType.parse(it) == null }.joinToString(" ")
        if (core.isNotBlank()) {
            d.widgets.filter { val t = it.title.lowercase(Locale.US); t.contains(core) || core.contains(t) }
                .let { hits -> if (type != null) hits.filter { it.type == type }.ifEmpty { hits } else hits }
                .maxByOrNull { it.createdAt }?.let { return it }
            d.widgets.firstOrNull { it.source.lowercase(Locale.US).contains(core) }?.let { return it }
            // Word overlap (e.g. "vacation" vs "Vacation.mp4").
            val cw = core.split(' ').toSet()
            d.widgets.map { w -> w to w.title.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).count { it in cw } }
                .filter { it.second > 0 }.maxByOrNull { it.second }?.first?.let { return it }
        }
        if (type != null) d.widgets.filter { it.type == type }.maxByOrNull { it.createdAt }?.let { return it }
        return null
    }

    /** Compact, model-readable snapshot of what's on screen. */
    fun describe(): String {
        val d = current()
        val sb = StringBuilder()
        sb.append("Desktop \"${d.name}\" mode=${d.mode.name.lowercase(Locale.US)} theme=${d.theme.name} ")
        sb.append("wallpaper=${d.wallpaper.kind.name.lowercase(Locale.US)}")
        if (d.wallpaper.description.isNotBlank()) sb.append(" (\"${d.wallpaper.description.take(40)}\")")
        sb.append(". Canvas ${Canvas.WIDTH}x${Canvas.HEIGHT}, usable y ${Canvas.CONTENT_TOP}-${Canvas.HEIGHT}.\n")
        if (d.widgets.isEmpty()) sb.append("No widgets.\n") else {
            sb.append("Widgets (${d.widgets.size}):\n")
            d.widgets.sortedBy { it.z }.forEach { w ->
                sb.append("- id=${w.id} ${w.type.name.lowercase(Locale.US)} \"${w.title}\" at (${w.x},${w.y}) ${w.w}x${w.h}")
                if (w.id == activeWidgetId) sb.append(" [active]")
                if (w.onTop) sb.append(" [stays on top]")
                if (w.refreshSec > 0) sb.append(" refresh=${w.refreshSec}s")
                if (w.type == WidgetType.WEB || w.type.isFetched) sb.append(" src=\"${w.source.take(60)}\"")
                sb.append('\n')
            }
        }
        val saved = DesktopStore.list()
        sb.append("Saved desktops: ")
        sb.append(saved.joinToString(", ") { if (it.id == d.id) "${it.name}*" else it.name }.ifBlank { "none" })
        return sb.toString()
    }

    fun defaultDesktop(): Desktop = Desktop(
        name = "Home",
        mode = DesktopMode.HUD,
        widgets = listOf(
            Widget(
                type = WidgetType.TEXT,
                title = "Welcome",
                x = 180, y = 300, w = 280, h = 120,
                source = "Tap the wave and tell me what you want on your desktop — " +
                    "widgets, media, apps, themes, wallpapers. Double-tap to close the assistant."
            )
        )
    )

    // ── internals ─────────────────────────────────────────────────────

    private fun fire(d: Desktop) {
        for (l in listeners) runCatching { l(d) }
    }

    private fun scheduleAutosave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, AUTOSAVE_DELAY_MS)
        main.removeCallbacks(thumbRunnable)
        main.postDelayed(thumbRunnable, THUMB_DELAY_MS)
    }

    private fun persistNow() {
        val d = state.get() ?: return
        Thread({ DesktopStore.save(d) }, "tapgem-save").start()
    }

    private fun renderThumbNow() {
        val d = state.get() ?: return
        val bmp = runCatching { thumbnailRenderer?.invoke() }.getOrNull() ?: return
        Thread({
            DesktopStore.saveThumb(d.id, bmp)
            main.post { catalogChanged() }
        }, "tapgem-thumb").start()
        Log.d(TAG, "thumbnail rendered for ${d.name}")
    }
}
