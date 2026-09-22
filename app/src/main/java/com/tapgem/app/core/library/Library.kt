package com.tapgem.app.core.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.model.DesktopMode
import com.tapgem.app.core.model.Themes
import com.tapgem.app.core.model.Wallpaper
import com.tapgem.app.core.model.WallpaperKind
import com.tapgem.app.core.model.WidgetType
import com.tapgem.app.core.store.Bookmarks
import com.tapgem.app.core.store.DesktopStore
import com.tapgem.app.core.tools.Args
import com.tapgem.app.core.tools.BookmarkTool
import com.tapgem.app.core.tools.WidgetOps
import java.io.File
import java.util.Locale

/**
 * What the three strip drawers show and what a tap in them does.
 *
 * The three are disjoint on purpose:
 *  - Apps & widgets — every vibe-coded app the glasses hold (an app's bookmark IS
 *    its library entry: it opens with its last saved state), the built-in widget
 *    kinds, and site launchers (a site's front page).
 *  - Bookmarks — saved pages and windows that are not apps: a YouTube video, a PDF
 *    at its page, a map, a note.
 *  - Wallpapers & themes — every wallpaper image on the device (with the words it
 *    was painted from), gradients in use, and the theme presets.
 */
object Library {

    // ── apps & widgets ────────────────────────────────────────────

    class AppEntry(val key: String, val title: String, val thumb: Bitmap?, val bookmark: Bookmarks.Bookmark?, val file: File?,
                   /** Shipped with TapGem and reinstalled at launch — cannot be deleted from the drawer. */
                   val builtIn: Boolean = false)

    private val BUILT_IN = setOf("music_player", "tutor_client", "interpreter_client", "discord_client", "irc_client", "weather_app")

    /** Bookmarked apps first (they carry state), then app files on desktops that have no bookmark; one per app name. */
    private val HELPER_PAGES = setOf(
        com.tapgem.app.core.tools.LiveApps.MUSIC_SKINS, com.tapgem.app.core.tools.LiveApps.READER)
    private val HELPER_BASES = HELPER_PAGES.map { File(it).nameWithoutExtension }.toSet()

    fun apps(): List<AppEntry> {
        val out = ArrayList<AppEntry>()
        val seen = HashSet<String>()
        for (b in Bookmarks.list()) {
            if (b.isWallpaper || b.type != WidgetType.APP) continue
            val base = appBase(File(b.widget!!.source))
            if (base in HELPER_BASES) continue          // a bookmarked copy of a helper page is still not an app
            if (!seen.add(base)) continue
            out += AppEntry("bm:" + b.id, b.title, thumbOf(b.thumb, 232, 148), b, null)
        }
        (DesktopStore.appsDir.listFiles { f -> f.extension == "html" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .forEach { f ->
                // Pages that only exist inside another window are not apps: the music player's
                // skin chooser, the read-along page. Listed, they showed up as "Music Skins" and
                // "Read-along" tiles that opened to nothing useful.
                if (f.name in HELPER_PAGES || appBase(f) in HELPER_BASES) return@forEach
                val base = appBase(f)
                if (!seen.add(base)) return@forEach
                out += AppEntry("file:" + f.absolutePath, prettify(base), thumbOf(appThumbFile(base), 232, 148), null, f, builtIn = base in BUILT_IN)
            }
        return out
    }

    /** `smart_aquarium__v2_1789…` / `bm_5cf76cb6_checkers__v1_…` → "smart_aquarium" / "checkers". */
    fun appBase(f: File): String = f.nameWithoutExtension.replace(Regex("^(bm_[0-9a-f]{8}_|[0-9a-f]{8}-)+"), "").substringBefore("__v").replace(Regex("_\\d{10,}$"), "")
    private fun prettify(base: String) = base.removeSuffix("_client").removeSuffix("_app").split('_').filter { it.isNotBlank() }
        .joinToString(" ") { w -> if (w.equals("irc", true)) "IRC" else w.replaceFirstChar { it.uppercase() } }

    /** Built-in kinds a tap can create on the spot, with what the tap adds. */
    class Kind(val key: String, val label: String, val glyph: String, val args: Map<String, String>)
    val KINDS = listOf(
        Kind("clock", "Clock", "◷", mapOf("type" to "clock")),
        Kind("note", "Note", "¶", mapOf("type" to "text", "text" to "New note — say what to write here.", "title" to "Note")),
        Kind("live", "Live card", "◉", mapOf("type" to "live", "query" to "top news headlines right now", "title" to "Headlines")),
        Kind("ticker", "Ticker", "≋", mapOf("type" to "ticker", "query" to "S&P 500, Nasdaq, Dow, Apple, Nvidia")),
        Kind("map", "Map", "⌖", mapOf("type" to "map", "query" to "here"))
        // Weather is an app now (LiveApps.WEATHER) and lives in the Apps row, not here.
    )

    class Site(val key: String, val label: String, val url: String)
    val SITES = listOf(
        Site("youtube", "YouTube", "https://www.youtube.com"),
        Site("radiogarden", "Radio Garden", "https://radio.garden"),
        Site("archive", "Internet Archive", "https://archive.org"),
        Site("wikipedia", "Wikipedia", "https://www.wikipedia.org"),
        Site("maps", "Google Maps", "https://www.google.com/maps"),
        Site("soundcloud", "SoundCloud", "https://soundcloud.com"),
        Site("bandcamp", "Bandcamp", "https://bandcamp.com")
    )

    /** Open an app entry on the current desktop; returns its title. */
    suspend fun openApp(context: Context, e: AppEntry): String {
        e.bookmark?.let { return BookmarkTool.place(it).title }
        val f = e.file ?: return e.title
        WidgetOps.add(context, Args(emptyMap()), forcedType = WidgetType.APP, forcedSource = f.absolutePath, forcedTitle = e.title)
        return e.title
    }

    suspend fun addKind(context: Context, k: Kind): Result<String> =
        // Weather is an app now, not a card of generated text: measurements from a forecast
        // service with their units, hourly and ten-day, air quality, settings.
        if (k.key == "weather") Result.success(com.tapgem.app.core.tools.LiveApps.ensureWindow(context, "weather.html",
                com.tapgem.app.core.tools.LiveApps.WEATHER, "Weather", Args(mapOf("w" to "560", "h" to "400", "anchor" to "top left"))).ifBlank { "Weather is already open." })
        else WidgetOps.add(context, Args(k.args))

    /**
     * Delete an app from the glasses: its bookmark, every version of its file, its picture, and
     * any window showing it. Built-ins are refused — they are reinstalled at launch anyway.
     */
    fun deleteApp(e: AppEntry): Boolean {
        if (e.builtIn) return false
        val base = e.file?.let { appBase(it) } ?: e.bookmark?.widget?.let { appBase(File(it.source)) } ?: return false
        e.bookmark?.let { Bookmarks.delete(it.id) }
        val files = (DesktopStore.appsDir.listFiles { f -> f.extension == "html" && appBase(f) == base } ?: emptyArray()).toList()
        val gone = files.map { it.absolutePath }.toSet()
        DesktopBridge.mutate { d -> d.copy(widgets = d.widgets.filterNot { it.type == WidgetType.APP && it.source in gone }) }
        files.forEach { it.delete() }
        appThumbFile(base).delete()
        return true
    }
    suspend fun openSite(context: Context, s: Site): Result<String> = WidgetOps.add(context, Args(mapOf("type" to "web", "url" to s.url, "title" to s.label)))

    // ── wallpapers & themes ───────────────────────────────────────

    class WallpaperEntry(val key: String, val title: String, val wallpaper: Wallpaper, val thumb: Bitmap?, val bookmark: Bookmarks.Bookmark?, val file: File?, val inUseBy: List<String>)

    /**
     * Every wallpaper the glasses hold: image files in the wallpapers folder, bookmarked
     * wallpapers, and the gradients/colours desktops use — one entry per distinct
     * wallpaper, titled by the words it was painted from when any desktop or bookmark
     * remembers them.
     */
    fun wallpapers(): List<WallpaperEntry> {
        val desktops = DesktopStore.list().mapNotNull { m -> DesktopStore.load(m.id)?.let { it.name to it.wallpaper } }
        val byKey = LinkedHashMap<String, WallpaperEntry>()
        fun titleFor(wp: Wallpaper, fallback: String): String = wp.description.takeIf { it.isNotBlank() }?.let { Bookmarks.wallpaperTitle(it) } ?: fallback
        // Bookmarked wallpapers come first: they have names and are meant to be kept.
        for (b in Bookmarks.list()) {
            val wp = b.wallpaper ?: continue
            val key = Bookmarks.wallpaperKey(wp)
            byKey[key] = WallpaperEntry(key, b.title, wp, thumbOf(b.thumb, 232, 148) ?: thumbFor(wp), b, wp.imagePath?.let { File(it) }, desktops.filter { Bookmarks.wallpaperKey(it.second) == key }.map { it.first })
        }
        // Wallpapers desktops are using (gradients included).
        for ((name, wp) in desktops) {
            if (wp.kind == WallpaperKind.NONE) continue
            val key = Bookmarks.wallpaperKey(wp)
            if (byKey.containsKey(key)) continue
            byKey[key] = WallpaperEntry(key, titleFor(wp, if (wp.kind == WallpaperKind.IMAGE) "Wallpaper" else if (wp.kind == WallpaperKind.GRADIENT) "Gradient" else "Colour"),
                wp, thumbFor(wp), null, wp.imagePath?.let { File(it) }, desktops.filter { Bookmarks.wallpaperKey(it.second) == key }.map { it.first })
        }
        // Painted images nobody references any more (still on disk until GC).
        (DesktopStore.wallpapersDir.listFiles { f -> f.extension == "png" || f.extension == "jpg" } ?: emptyArray()).sortedByDescending { it.lastModified() }.forEach { f ->
            val wp = Wallpaper(WallpaperKind.IMAGE, imagePath = f.absolutePath)
            val key = Bookmarks.wallpaperKey(wp)
            if (byKey.containsKey(key)) return@forEach
            byKey[key] = WallpaperEntry(key, "Wallpaper", wp, thumbFor(wp), null, f, emptyList())
        }
        return byKey.values.toList()
    }

    fun applyWallpaper(e: WallpaperEntry) {
        e.bookmark?.let { BookmarkTool.applyWallpaper(it); return }
        DesktopBridge.mutate { it.copy(wallpaper = e.wallpaper, mode = DesktopMode.DESKTOP) }
    }

    fun clearWallpaper() { DesktopBridge.mutate { it.copy(wallpaper = Wallpaper()) } }

    /** Forget a wallpaper nobody is using: its bookmark and its file. */
    fun deleteWallpaper(e: WallpaperEntry): Boolean {
        if (e.inUseBy.isNotEmpty()) return false
        e.bookmark?.let { Bookmarks.delete(it.id) }
        e.file?.let { f -> if (f.absolutePath.startsWith(DesktopStore.wallpapersDir.absolutePath)) f.delete() }
        return true
    }

    fun applyTheme(name: String) { Themes.byName(name)?.let { t -> DesktopBridge.mutate { it.copy(theme = t) } } }

    // ── thumbnails (decoded small, cached by path + size) ─────────

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 24
    }

    private fun thumbOf(f: File?, w: Int, h: Int): Bitmap? = f?.takeIf { it.exists() }?.let { decode(it, w, h) }

    /**
     * An app's picture for the drawer, taken from its window whenever the drawer opens while that
     * window is up. A bookmarked app carries a picture with the bookmark; the live clients and any
     * app that was never bookmarked had only a glyph, which told you nothing about them.
     */
    fun appThumbFile(base: String): File = File(File(DesktopStore.appsDir, "thumbs").apply { mkdirs() }, "$base.png")
    fun saveAppThumb(base: String, bmp: Bitmap) {
        runCatching { appThumbFile(base).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) } }
    }

    fun thumbFor(wp: Wallpaper, w: Int = 232, h: Int = 148): Bitmap? = wp.imagePath?.let { decode(File(it), w, h) } ?: BookmarkTool.wallpaperThumb(wp, w, h)

    private fun decode(f: File, w: Int, h: Int): Bitmap? {
        val key = "${f.absolutePath}|${f.length()}|$w"
        synchronized(cache) { cache[key]?.let { return it } }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        if (opts.outWidth <= 0) return null
        val sample = maxOf(1, minOf(opts.outWidth / w, opts.outHeight / h))
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scaled = if (bmp.width > w * 1.5) Bitmap.createScaledBitmap(bmp, w, (bmp.height * w / bmp.width.toFloat()).toInt().coerceAtLeast(1), true).also { if (it !== bmp) bmp.recycle() } else bmp
        synchronized(cache) { cache[key] = scaled }
        return scaled
    }
}
