package com.tapgem.app.core.tools

import android.content.Context
import android.util.Log
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.bridge.HudStateBridge
import com.tapgem.app.core.bridge.WebCommandBus
import com.tapgem.app.core.live.WidgetRefreshEngine
import com.tapgem.app.core.media.EpubUnpacker
import com.tapgem.app.core.media.MediaScanner
import com.tapgem.app.core.model.Canvas
import com.tapgem.app.core.model.ColorUtil
import com.tapgem.app.core.model.Desktop
import com.tapgem.app.core.model.DesktopMode
import com.tapgem.app.core.model.Theme
import com.tapgem.app.core.model.Themes
import com.tapgem.app.core.model.Wallpaper
import com.tapgem.app.core.model.WallpaperKind
import com.tapgem.app.core.model.Widget
import com.tapgem.app.core.model.WidgetStyle
import com.tapgem.app.core.model.WidgetType
import com.tapgem.app.core.network.GeminiRest
import com.tapgem.app.core.network.Geocoder
import com.tapgem.app.core.network.Router
import com.tapgem.app.core.location.LocationSource
import com.tapgem.app.core.store.DesktopStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────
// Geometry helpers shared by the tools
// ─────────────────────────────────────────────────────────────────────

object Layout {
    const val MARGIN = 8

    fun sizeFor(type: WidgetType, sizeName: String?, fallback: Pair<Int, Int>? = null): Pair<Int, Int> {
        val base = fallback ?: type.defaultSize()
        val k = sizeName?.lowercase(Locale.US)?.replace(Regex("[^a-z]"), "") ?: return base
        val (bw, bh) = base
        val area = Canvas.HEIGHT - Canvas.CONTENT_TOP
        if (type == WidgetType.TICKER) return when (k) {
            "tiny", "small", "smaller" -> (bw * 0.7).roundToInt() to bh
            "large", "big", "bigger", "larger", "huge", "xl", "giant", "full", "fullscreen", "max", "maximize", "maximized", "wide", "banner" -> Canvas.WIDTH - 2 * MARGIN to bh
            else -> base
        }
        return when (k) {
            "tiny" -> (bw * 0.55).roundToInt() to (bh * 0.55).roundToInt()
            "small", "smaller" -> (bw * 0.72).roundToInt() to (bh * 0.72).roundToInt()
            "medium", "normal", "default" -> base
            "large", "big", "bigger", "larger" -> (bw * 1.4).roundToInt() to (bh * 1.4).roundToInt()
            "huge", "xl", "giant" -> (bw * 1.8).roundToInt() to (bh * 1.8).roundToInt()
            "full", "fullscreen", "max", "maximize", "maximized" -> Canvas.WIDTH to area
            "half", "halfleft", "halfright" -> Canvas.WIDTH / 2 to area
            "wide", "banner" -> Canvas.WIDTH - 2 * MARGIN to bh
            "tall", "column" -> bw to area
            else -> base
        }
    }

    /** Size names that imply a position too (half_left …, full). */
    fun impliedAnchor(sizeName: String?): String? {
        val k = sizeName?.lowercase(Locale.US)?.replace(Regex("[^a-z]"), "") ?: return null
        return when (k) {
            "halfleft" -> "halfleft"; "halfright" -> "halfright"; "half" -> "halfleft"
            "full", "fullscreen", "max", "maximize", "maximized" -> "full"
            "wide", "banner" -> "top"; "tall", "column" -> "left"
            else -> null
        }
    }

    fun clampSize(w: Int, h: Int): Pair<Int, Int> =
        w.coerceIn(Canvas.MIN_W, Canvas.WIDTH) to h.coerceIn(Canvas.MIN_H, Canvas.HEIGHT - Canvas.CONTENT_TOP)

    /** A ticker is a strip: it can get wider, never tall. */
    fun clampSize(type: WidgetType, w: Int, h: Int): Pair<Int, Int> {
        val (cw, ch) = clampSize(w, h)
        return if (type == WidgetType.TICKER) cw to ch.coerceIn(TICKER_MIN_H, TICKER_MAX_H) else cw to ch
    }
    const val TICKER_MIN_H = 40
    const val TICKER_MAX_H = 72

    fun clampPos(x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> =
        x.coerceIn(0, max(0, Canvas.WIDTH - w)) to y.coerceIn(Canvas.CONTENT_TOP, max(Canvas.CONTENT_TOP, Canvas.HEIGHT - h))

    fun anchorPos(anchor: String?, w: Int, h: Int): Pair<Int, Int>? {
        val k = anchor?.lowercase(Locale.US)?.replace(Regex("[^a-z]"), "") ?: return null
        val right = Canvas.WIDTH - w - MARGIN
        val bottom = Canvas.HEIGHT - h - MARGIN
        val cx = (Canvas.WIDTH - w) / 2
        val cy = Canvas.CONTENT_TOP + (Canvas.HEIGHT - Canvas.CONTENT_TOP - h) / 2
        val top = Canvas.CONTENT_TOP + MARGIN
        return when (k) {
            "topleft", "upperleft", "lefttop", "northwest" -> MARGIN to top
            "top", "topcenter", "topmiddle", "uppercenter", "north" -> cx to top
            "topright", "upperright", "righttop", "northeast" -> right to top
            "left", "middleleft", "centerleft", "leftcenter", "west" -> MARGIN to cy
            "center", "centre", "middle" -> cx to cy
            "right", "middleright", "centerright", "rightcenter", "east" -> right to cy
            "bottomleft", "lowerleft", "leftbottom", "southwest" -> MARGIN to bottom
            "bottom", "bottomcenter", "bottommiddle", "lowercenter", "south" -> cx to bottom
            "bottomright", "lowerright", "rightbottom", "southeast" -> right to bottom
            "halfleft" -> 0 to Canvas.CONTENT_TOP
            "halfright" -> Canvas.WIDTH / 2 to Canvas.CONTENT_TOP
            "full" -> 0 to Canvas.CONTENT_TOP
            else -> null
        }
    }

    /** First spot (16px grid) where a w×h box overlaps nothing; cascade fallback. */
    fun freeSlot(widgets: List<Widget>, w: Int, h: Int): Pair<Int, Int> {
        var y = Canvas.CONTENT_TOP + MARGIN
        while (y + h <= Canvas.HEIGHT) {
            var x = MARGIN
            while (x + w <= Canvas.WIDTH) {
                if (widgets.none { intersects(x, y, w, h, it) }) return x to y
                x += 16
            }
            y += 16
        }
        val n = widgets.size
        return clampPos(MARGIN + n * 24, Canvas.CONTENT_TOP + MARGIN + n * 24, w, h)
    }

    private fun intersects(x: Int, y: Int, w: Int, h: Int, o: Widget): Boolean {
        val m = 6
        return x < o.x + o.w + m && x + w + m > o.x && y < o.y + o.h + m && y + h + m > o.y
    }

    class Arrangement(val widgets: List<Widget>, val description: String)

    /**
     * Re-lay out every window so nothing overlaps. grid balances rows and
     * columns (a short last row is stretched); columns / rows are one line;
     * cascade staggers same-size windows; a focus window takes the left ~62%
     * with the others stacked beside it.
     */
    fun arrange(allIn: List<Widget>, layout: String?, focusId: String?, gapIn: Int?): Arrangement {
        if (allIn.isEmpty()) return Arrangement(allIn, "There are no windows to arrange.")
        val gap = (gapIn ?: 8).coerceIn(0, 40)
        // Tickers are strips, not windows: they line the bottom edge full-width and the
        // windows share what is left above them.
        val strips = allIn.filter { it.type == WidgetType.TICKER }.sortedBy { it.y }
        val all = allIn.filter { it.type != WidgetType.TICKER }
        val stripOut = ArrayList<Widget>()
        var bottom = Canvas.HEIGHT - gap
        for (t in strips.asReversed()) {
            val h = t.h.coerceIn(TICKER_MIN_H, TICKER_MAX_H)
            stripOut += t.copy(x = gap, y = bottom - h, w = Canvas.WIDTH - 2 * gap, h = h)
            bottom -= h + gap
        }
        if (all.isEmpty()) return Arrangement(stripOut, "The ticker${if (strips.size > 1) "s" else ""} line${if (strips.size > 1) "" else "s"} the bottom of the desktop.")
        val left = gap; val top = Canvas.CONTENT_TOP + gap
        val areaW = Canvas.WIDTH - 2 * gap; val areaH = bottom - top
        val ordered = all.sortedWith(compareBy({ it.y / 40 }, { it.x }))
        val k = layout?.lowercase(Locale.US)?.replace(Regex("[^a-z]"), "").orEmpty()
        val focus = focusId?.let { id -> ordered.firstOrNull { it.id == id } }
        val out = ArrayList<Widget>(ordered.size)
        val desc = StringBuilder()

        fun cell(w: Widget, x: Int, y: Int, cw: Int, ch: Int): Widget {
            val (sw, sh) = clampSize(cw, ch)
            val (px, py) = clampPos(x, y, sw, sh)
            return w.copy(x = px, y = py, w = sw, h = sh)
        }

        when {
            focus != null && ordered.size > 1 -> {
                val others = ordered.filter { it.id != focus.id }
                val mainW = (areaW * 0.62).roundToInt()
                val sideW = areaW - mainW - gap
                out += cell(focus, left, top, mainW, areaH)
                val rh = (areaH - (others.size - 1) * gap) / others.size
                others.forEachIndexed { i, w -> out += cell(w, left + mainW + gap, top + i * (rh + gap), sideW, rh) }
                desc.append("\"${focus.title}\" fills the left two thirds; ")
                desc.append(others.joinToString(", ") { "\"${it.title}\"" }).append(" stacked on the right.")
            }
            k == "cascade" || k == "stack" || k == "stacked" -> {
                val cw = (areaW * 0.62).roundToInt(); val ch = (areaH * 0.66).roundToInt()
                val stepX = if (ordered.size > 1) ((areaW - cw) / (ordered.size - 1)).coerceIn(12, 40) else 0
                val stepY = if (ordered.size > 1) ((areaH - ch) / (ordered.size - 1)).coerceIn(12, 40) else 0
                ordered.forEachIndexed { i, w -> out += cell(w, left + i * stepX, top + i * stepY, cw, ch).copy(z = i + 1) }
                desc.append("Cascaded ${ordered.size} windows front to back: ").append(ordered.joinToString(", ") { "\"${it.title}\"" }).append('.')
            }
            else -> {
                val n = ordered.size
                val cols = when {
                    k == "columns" || k == "column" || k == "sidebyside" || k == "horizontal" || k == "row" -> n
                    k == "rows" || k == "stacked" || k == "vertical" || k == "list" -> 1
                    n == 1 -> 1
                    n == 2 -> 2
                    n <= 4 -> 2
                    n <= 9 -> 3
                    else -> 4
                }
                val rows = ceil(n / cols.toDouble()).toInt()
                val rh = (areaH - (rows - 1) * gap) / rows
                var i = 0
                val rowNames = ArrayList<String>()
                for (r in 0 until rows) {
                    val inRow = minOf(cols, n - i)
                    val cw = (areaW - (inRow - 1) * gap) / inRow
                    val names = ArrayList<String>()
                    for (c in 0 until inRow) {
                        val w = ordered[i++]
                        out += cell(w, left + c * (cw + gap), top + r * (rh + gap), cw, rh)
                        names += "\"${w.title}\""
                    }
                    rowNames += names.joinToString(", ")
                }
                desc.append(when {
                    cols == n && n > 1 -> "Lined up $n windows side by side: ${rowNames.first()}."
                    cols == 1 && n > 1 -> "Stacked $n windows top to bottom: ${rowNames.joinToString("; ")}."
                    n == 1 -> "\"${ordered.first().title}\" now fills the desktop."
                    else -> "Tiled $n windows in a $rows×$cols grid — " + rowNames.mapIndexed { r, s -> "row ${r + 1}: $s" }.joinToString("; ") + "."
                })
            }
        }
        // Preserve stacking order for non-cascade layouts.
        val zById = all.associate { it.id to it.z }
        val fixed = if (k == "cascade") out else out.map { it.copy(z = zById[it.id] ?: it.z) }
        if (strips.isNotEmpty()) desc.append(" The ticker${if (strips.size > 1) "s stay" else " stays"} along the bottom.")
        return Arrangement(fixed + stripOut, desc.toString())
    }
}

// ─────────────────────────────────────────────────────────────────────
// Shared "add a widget" flow (used by widget, media, app_builder)
// ─────────────────────────────────────────────────────────────────────

object WidgetOps {
    /** Default window for the navigation HUD ("mini window"): arrow + info left, minimap right. */
    val HUD_SIZE = 340 to 210


    private const val TAG = "WidgetOps"
    const val MAX_TEXT_FILE_CHARS = 24_000

    fun isUrl(s: String?) = s != null && (s.startsWith("http://") || s.startsWith("https://"))

    /** Style from args; [warnings] collects colour words we couldn't parse. */
    fun styleFrom(args: Args, base: WidgetStyle = WidgetStyle(), warnings: MutableList<String>? = null): WidgetStyle {
        val bgRaw = args.str("bg_color", "background", "bg"); val fgRaw = args.str("text_color", "color")
        if (ColorUtil.isUnknown(bgRaw)) warnings?.add("I don't know the colour \"$bgRaw\" — use a hex code or a common name")
        if (ColorUtil.isUnknown(fgRaw)) warnings?.add("I don't know the colour \"$fgRaw\" — use a hex code or a common name")
        // The frame has three states, so the word wins over the boolean when one was spoken.
        val chromeArg = args.str("chrome", "title_bar", "frame")?.trim()?.lowercase()
            ?.let { w -> when {
                w in setOf("auto", "hover", "on_hover", "onhover", "auto_hide", "autohide") -> "auto"
                w in setOf("false", "off", "no", "none", "hide", "hidden") -> "off"
                w in setOf("true", "on", "yes", "show", "shown") -> "on"
                else -> null
            } }
        return base.copy(
            bgColor = ColorUtil.parse(bgRaw) ?: base.bgColor,
            textColor = ColorUtil.parse(fgRaw) ?: base.textColor,
            opacity = args.float("opacity")?.let { if (it > 1f) it / 100f else it }?.coerceIn(0.05f, 1f) ?: base.opacity,
            cornerRadius = args.int("corner_radius", "corner") ?: base.cornerRadius,
            fontSize = args.float("font_size", "text_size")?.coerceIn(6f, 96f) ?: base.fontSize,
            chrome = chromeArg?.let { it != "off" } ?: args.bool("chrome", "title_bar") ?: base.chrome,
            // "hide the frame until I point at it" — the third state the plain boolean cannot carry.
            chromeAuto = chromeArg?.let { it == "auto" } ?: base.chromeAuto
        )
    }

    fun refreshFrom(args: Args, default: Int): Int {
        args.int("refresh_seconds", "refresh", "interval_seconds", "update_seconds")?.let { return max(0, it) }
        args.int("refresh_minutes", "interval_minutes", "update_minutes")?.let { return max(0, it * 60) }
        return default
    }

    fun titleFromWords(s: String, n: Int = 3): String =
        s.split(Regex("\\s+")).filter { it.isNotBlank() }.take(n).joinToString(" ").take(24).replaceFirstChar { it.uppercase() }

    fun readTextFile(f: File): String = runCatching {
        val s = f.readText()
        if (s.length > MAX_TEXT_FILE_CHARS) s.take(MAX_TEXT_FILE_CHARS) + "\n…" else s
    }.getOrDefault("Couldn't read ${f.name}.")

    fun isAppPath(path: String): Boolean = runCatching {
        File(path).canonicalPath.startsWith(DesktopStore.appsDir.canonicalPath + File.separator)
    }.getOrDefault(false)

    suspend fun add(
        context: Context,
        args: Args,
        forcedType: WidgetType? = null,
        forcedSource: String? = null,
        forcedTitle: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val explicit = forcedSource ?: args.str("path", "url", "source", "file")
        val query = args.str("query", "name", "search", "place", "location")
        var type = forcedType ?: WidgetType.parse(args.str("type"))
            ?: explicit?.let { WidgetType.forExtension(it.substringBefore('?').substringAfterLast('.', "")) }
            ?: when {
                args.has("text") || args.has("content") || args.has("prompt") -> WidgetType.TEXT
                args.has("place") || args.has("location") || args.has("directions") -> WidgetType.MAP
                args.has("query") -> WidgetType.LIVE
                isUrl(explicit) -> WidgetType.WEB
                else -> null
            }
            ?: return@withContext Result.failure(IllegalArgumentException("widget add needs a type (text, clock, live, ticker, image, video, audio, pdf, epub, web, app, model3d, map)."))

        var source = ""
        var content = ""
        var note = ""
        var title = forcedTitle ?: args.str("title", "label")
        var refreshDefault = 0
        val state = HashMap<String, String>()
        val warnings = ArrayList<String>()

        when (type) {
            WidgetType.TEXT -> {
                val prompt = args.str("prompt", "generate")
                val text = args.str("text", "content", "body")?.let(::unescapeModelText)
                when {
                    prompt != null -> {
                        source = "prompt:$prompt"
                        content = GeminiRest.generateText(context, prompt, system = WidgetRefreshEngine.PROMPT_TEXT_SYSTEM, search = true)
                            .getOrElse { warnings += "the text couldn't be generated yet (${it.message?.take(60)}); it will retry on refresh"; "" }
                        title = title ?: titleFromWords(prompt)
                        if (content.isBlank()) refreshDefault = 60
                    }
                    text != null -> { source = text; title = title ?: titleFromWords(text) }
                    else -> {
                        // A text file from disk (Notes.txt) by path or by name.
                        val f = explicit?.let { MediaScanner.ensureLocal(context, it) }
                            ?: MediaScanner.find(context, query ?: explicit?.let { File(it).name }, WidgetType.TEXT).firstOrNull()?.let { File(it.path) }
                            ?: return@withContext Result.failure(IllegalArgumentException("text widget needs 'text', 'prompt', or a text file (path/query)."))
                        source = "file:" + f.absolutePath
                        content = readTextFile(f)
                        title = title ?: f.nameWithoutExtension.replace(Regex("[_\\-]+"), " ").take(28)
                    }
                }
            }
            WidgetType.CLOCK -> {
                source = args.str("format")?.lowercase(Locale.US) ?: "time+date"
                val (st, bad) = WidgetOps.clockState(args, emptyMap())
                state.putAll(st)
                bad?.let { warnings += it }
                val zones = st["zones"]?.split(',')?.filter { it.isNotBlank() && it != "local" } ?: emptyList()
                title = title ?: when {
                    zones.size > 1 -> "World Clock"
                    zones.size == 1 -> com.tapgem.app.core.model.WorldClocks.label(zones[0])
                    else -> "Clock"
                }
            }
            WidgetType.LIVE -> {
                val q = query ?: args.str("text", "prompt")
                    ?: return@withContext Result.failure(IllegalArgumentException("live widget needs 'query' — what to watch."))
                source = q
                title = title ?: liveTitle(q)
                refreshDefault = 300
                content = WidgetRefreshEngine.fetchLive(q).getOrElse {
                    warnings += "the first fetch failed (${it.message?.take(60)}); it will retry shortly"
                    state["failCount"] = "1"; ""
                }
            }
            WidgetType.TICKER -> {
                val q = query ?: args.str("text", "prompt")
                    ?: return@withContext Result.failure(IllegalArgumentException("ticker widget needs 'query' — what should scroll by (stocks, headlines, scores, weather)."))
                source = q
                title = title ?: liveTitle(q)
                refreshDefault = 300
                content = WidgetRefreshEngine.fetchTicker(q).getOrElse {
                    warnings += "the first fetch failed (${it.message?.take(60)}); it will retry shortly"
                    state["failCount"] = "1"; ""
                }
            }
            WidgetType.WEB -> {
                val raw = explicit ?: query ?: args.str("text")
                    ?: return@withContext Result.failure(IllegalArgumentException("web widget needs 'url' (or a search query)."))
                source = normalizeUrl(raw)
                title = title ?: runCatching { java.net.URL(source).host.removePrefix("www.") }.getOrDefault("Web")
            }
            WidgetType.APP -> {
                val p = explicit ?: return@withContext Result.failure(IllegalArgumentException("Use app_builder to create apps."))
                if (!isAppPath(p) || !File(p).isFile) return@withContext Result.failure(IllegalArgumentException("Apps can only be loaded from TapGem's own app folder — use app_builder to make one."))
                source = p
                title = title ?: File(p).nameWithoutExtension.substringBefore("__v").replace('_', ' ').take(28)
            }
            WidgetType.MAP -> {
                val place = query ?: args.str("text", "title", "destination")
                val style = args.str("style", "provider", "map_style")?.lowercase(Locale.US).orEmpty()
                val simple = style in setOf("simple", "osm", "tiles", "offline", "clean", "minimal")
                val wantsDirections = args.bool("directions", "navigate") == true || style == "directions"
                if (wantsDirections && style != "google") {
                    // Turn-by-turn lives in TapGem itself: Google's web "Start" only hands off to an app the glasses don't have.
                    return@withContext startNavigation(context, place, args.str("travel_mode", "mode"), null, args)
                }
                if (!simple) {
                    // Default: Google Maps in a web window — search, directions, "ask Maps" all work through the web tool.
                    val directions = wantsDirections
                    val mode = args.str("travel_mode", "mode")?.lowercase(Locale.US)?.let {
                        when { it.startsWith("walk") -> "walking"; it.startsWith("bik") || it.startsWith("cycl") -> "bicycling"
                            it.startsWith("transit") || it.startsWith("bus") || it.startsWith("train") -> "transit"; else -> "driving" }
                    } ?: "driving"
                    // The device's own position (fused/network fix → last known → IP) is the
                    // origin of every route and the centre of "where am I", so Maps never asks.
                    val here = if (Geocoder.isHere(place) || directions) LocationSource.current(context) else null
                    source = when {
                        Geocoder.isHere(place) ->
                            if (here != null) "https://www.google.com/maps/@%.5f,%.5f,%dz".format(Locale.US, here.lat, here.lon, if (here.isPrecise) 16 else 13)
                            else "https://www.google.com/maps"
                        directions -> "https://www.google.com/maps/dir/?api=1" +
                            (here?.let { "&origin=" + URLEncoder.encode(it.latLon(), "UTF-8") } ?: "") +
                            "&destination=" + URLEncoder.encode(place, "UTF-8") + "&travelmode=$mode"
                        else -> "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(place, "UTF-8")
                    }
                    type = WidgetType.WEB
                    title = title ?: (if (directions) "Directions: " else "Maps: ") + (place?.take(22) ?: "here")
                    val whence = when { here == null -> ""; here.isPrecise -> " from your current location"; else -> " from a rough (internet-based) position" }
                    note = if (directions) " Google Maps directions ($mode) to $place$whence." else " Google Maps: ${place ?: "your location"}$whence."
                    // "Restaurants near X": the map shows the pins; Maps' mobile list is ads-first and lazy, so the
                    // spoken shortlist should come from the model's own Google Search, not from clicking the page.
                    if (!directions && place != null && isPlacesQuery(place)) note += PLACES_NOTE
                    if (Geocoder.isHere(place) && here == null) warnings += "I couldn't get your location (permission or location services off)"
                } else {
                    val geo = if (Geocoder.isHere(place)) LocationSource.current(context)?.let { f ->
                            Geocoder.Place(f.lat, f.lon, Geocoder.reverse(f.lat, f.lon) ?: "You are here", if (f.isPrecise) "road" else "city")
                        } else Geocoder.lookup(place)
                    geo ?: return@withContext Result.failure(IllegalStateException(
                            if (Geocoder.isHere(place)) "I couldn't work out where you are right now." else "I couldn't find a place called \"$place\"."))
                    source = "geo:%.6f,%.6f?q=%s".format(Locale.US, geo.lat, geo.lon, URLEncoder.encode(geo.label, "UTF-8"))
                    state["zoom"] = (args.int("zoom") ?: Geocoder.zoomFor(geo.kind)).coerceIn(1, 18).toString()
                    title = title ?: geo.label.take(28)
                    note = " Showing ${geo.label} on the simple map."
                }
            }
            WidgetType.IMAGE, WidgetType.VIDEO, WidgetType.AUDIO, WidgetType.PDF, WidgetType.EPUB, WidgetType.MODEL3D -> {
                var resolved: String? = null
                if (explicit != null) {
                    resolved = if (isUrl(explicit)) {
                        if (type == WidgetType.IMAGE || type == WidgetType.VIDEO || type == WidgetType.AUDIO) explicit
                        else MediaScanner.ensureLocal(context, explicit)?.absolutePath
                            ?: return@withContext Result.failure(IllegalStateException("Couldn't download that file."))
                    } else MediaScanner.ensureLocal(context, explicit)?.absolutePath
                }
                if (resolved == null && (query != null || explicit != null)) {
                    val hit = MediaScanner.find(context, query ?: File(explicit!!).name, type).firstOrNull()
                    if (hit != null) { resolved = hit.path; type = hit.type; note = " Found ${hit.name}." }
                }
                if (resolved == null) {
                    val hint = if (!MediaScanner.hasAllFilesAccess())
                        " (Nothing matched. For files outside Pictures/Movies/Music, grant All-files access or push into the media folder.)" else " (Nothing matched.)"
                    return@withContext Result.failure(IllegalStateException("No ${type.name.lowercase(Locale.US)} found for \"${query ?: explicit}\".$hint"))
                }
                if (type == WidgetType.EPUB) {
                    val ch = EpubUnpacker.chapters(context, File(resolved))
                    if (ch.isEmpty()) return@withContext Result.failure(IllegalStateException("That EPUB has no readable chapters."))
                    state["chapters"] = ch.size.toString()
                    note += " ${ch.size} chapters."
                }
                source = resolved
                title = title ?: File(resolved).nameWithoutExtension.replace(Regex("[_\\-]+"), " ").take(28)
            }
        }

        // One request, one window: the same page / query / file already open is reused, not duplicated.
        if (args.bool("new_window", "duplicate") != true) {
            // Google Maps is one window per desktop: a new search/place/directions query re-points the
            // Maps window that is already open instead of stacking a second map.
            if (type == WidgetType.WEB && isGoogleMaps(source)) {
                val maps = DesktopBridge.current().widgets.firstOrNull { it.type == WidgetType.WEB && isGoogleMaps(it.source) }
                if (maps != null) {
                    val t = (title ?: maps.title).take(32)
                    DesktopBridge.mutate { d -> d.widget(maps.id)?.let { d.replaceWidget(it.copy(source = source, title = t, z = (d.widgets.maxOfOrNull { o -> o.z } ?: 0) + 1)) } ?: d }
                    DesktopBridge.setActive(maps.id)
                    return@withContext Result.success("Showing \"$t\" in the open Maps window (id ${maps.id}).$note")
                }
            }
            val key = dedupeKey(type, source, state["zones"])
            val dup = DesktopBridge.current().widgets.firstOrNull { dedupeKey(it.type, it.source, it.state["zones"]) == key }
            if (dup != null) {
                DesktopBridge.mutate(pushUndo = false) { d -> d.widget(dup.id)?.let { d.replaceWidget(it.copy(z = (d.widgets.maxOfOrNull { o -> o.z } ?: 0) + 1)) } ?: d }
                DesktopBridge.setActive(dup.id)
                return@withContext Result.success("\"${dup.title}\" is already open — brought it to the front instead of opening a second copy (pass new_window=true if a second window is really wanted).")
            }
        }

        // Geometry (computed against the live desktop inside mutate so two
        // quick adds never land on the same free slot).
        val (dw, dh) = Layout.sizeFor(type, args.str("size"))
        val (w, h) = Layout.clampSize(type, args.int("w", "width") ?: dw, args.int("h", "height") ?: dh)
        val anchor = args.str("anchor", "position", "place_at") ?: Layout.impliedAnchor(args.str("size"))
            ?: if (type == WidgetType.TICKER) "bottom" else null
        val refresh = refreshFrom(args, refreshDefault)
        args.bool("autoplay")?.let { state["playing"] = it.toString() }
        args.bool("loop")?.let { state["loop"] = it.toString() }
        args.bool("muted", "mute")?.let { state["muted"] = it.toString() }
        args.int("page")?.let { state["page"] = (it - 1).coerceAtLeast(0).toString() }
        args.int("chapter")?.let { state["chapter"] = (it - 1).coerceAtLeast(0).toString() }
        val finalTitle = (title ?: type.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() }).take(32)
        val style = styleFrom(args, warnings = warnings)

        var placed: Widget? = null
        DesktopBridge.mutate { d ->
            val existing = d.widgets
            val (px, py) = when {
                args.has("x") || args.has("y") -> (args.int("x") ?: Layout.MARGIN) to (args.int("y") ?: Canvas.CONTENT_TOP + Layout.MARGIN)
                else -> Layout.anchorPos(anchor, w, h) ?: Layout.freeSlot(existing, w, h)
            }
            val (x, y) = Layout.clampPos(px, py, w, h)
            val widget = Widget(
                type = type, title = finalTitle, x = x, y = y, w = w, h = h,
                z = (existing.maxOfOrNull { it.z } ?: 0) + 1,
                source = source, refreshSec = refresh, style = style, state = state,
                content = content, updatedAt = if (content.isNotBlank()) System.currentTimeMillis() else 0L
            )
            placed = widget
            d.copy(widgets = existing + widget)
        }
        val widget = placed!!
        DesktopBridge.setActive(widget.id)

        Log.i(TAG, "added ${widget.type} '${widget.title}' at (${widget.x},${widget.y}) ${w}x$h src=${source.take(60)}")
        val refreshNote = if (refresh > 0) " Refreshes every ${humanSecs(refresh)}." else ""
        val warn = if (warnings.isEmpty()) "" else " Note: " + warnings.joinToString("; ") + "."
        Result.success("Added ${type.name.lowercase(Locale.US)} \"${widget.title}\" (id ${widget.id}) at (${widget.x},${widget.y}) size ${w}x$h.$refreshNote$note$warn")
    }

    /**
     * TapGem's own navigation: destination geocoded, position from
     * [LocationSource], route from OSRM, drawn on the dark map with the
     * current step as a banner. Replaces [existingId] (e.g. an open Google
     * Maps directions window) or adds a new map window.
     */
    suspend fun startNavigation(context: Context, destination: String?, modeArg: String?, existingId: String?, args: Args): Result<String> = withContext(Dispatchers.IO) {
        val dest = destination?.trim()?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalArgumentException("Where to? Navigation needs a destination."))
        val modeGiven = modeArg?.lowercase(Locale.US)?.let {
            when { it.startsWith("walk") || it.startsWith("foot") -> "walking"; it.startsWith("bik") || it.startsWith("cycl") -> "bicycling"
                it.startsWith("driv") || it.startsWith("car") -> "driving"; else -> null }
        }
        // "Stop at X on the way": via=X (comma / "then" separated, in order).
        val viaNames = (args.str("via", "stop", "stops", "waypoint", "waypoints", "through") ?: "")
            .split(Regex("\\s*(,|;| then | and then )\\s*", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotBlank() }
        val viaKey = viaNames.joinToString("|").lowercase(Locale.US)
        // One navigation at a time — and the model likes to repeat itself: an identical, fresh
        // request just re-reads the current instruction instead of routing again.
        val navWidget = existingId?.let { DesktopBridge.current().widget(it) }
            ?: DesktopBridge.current().widgets.firstOrNull { it.type == WidgetType.MAP && (it.state["navMap"] == "1" || it.state["nav"] == "on" || it.state.containsKey("mode") || it.title.startsWith("→ ")) }
        // No mode said: keep the mode of the navigation already running (a stop added to a drive stays a drive).
        val mode = modeGiven ?: navWidget?.state?.get("mode")?.takeIf { navWidget.state["nav"] == "on" } ?: "walking"
        if (navWidget != null && navWidget.state["nav"] == "on" && navWidget.state["dest"].equals(dest, ignoreCase = true)
            && navWidget.state["via"].orEmpty().equals(viaKey, ignoreCase = true)
            && System.currentTimeMillis() - navWidget.updatedAt < 120_000L) {
            val r = Router.Route.fromJson(navWidget.content)
            val st = r?.steps?.getOrNull(navWidget.state["step"]?.toIntOrNull() ?: 0)
            DesktopBridge.setActive(navWidget.id)
            // Same trip asked again with a different look ("… with the minimap", "… in fallout"): just restyle it.
            val (delta, _) = WidgetOps.navState(args, navWidget.state)
            val changed = if (delta.isNotEmpty()) {
                DesktopBridge.mutateWidget(navWidget.id, pushUndo = false) { it.withState(delta) }
                WidgetOps.rememberHud(context, delta["view"]?.let { it == "hud" }, delta["theme"])
                " Switched to " + delta.entries.joinToString(", ") { (k, v) -> when (k) { "view" -> if (v == "hud") "the minimap HUD" else "the full map"; "theme" -> "the ${v.ifBlank { "default" }} theme"; else -> "$k ${v.ifBlank { "auto" }}" } } + "."
            } else ""
            return@withContext Result.success("Already navigating to ${r?.dest ?: dest}${st?.let { ": ${it.text}${if (it.distM > 0) " for ${Router.distance(it.distM)}" else ""}" } ?: ""}.$changed")
        }
        // Where the user is comes first: it biases place lookups to their area and is the route origin.
        val from = LocationSource.current(context) ?: return@withContext Result.failure(IllegalStateException("I can't tell where you are right now, so I can't route from here."))
        suspend fun place(name: String, what: String): Geocoder.Place {
            HudStateBridge.notice("Finding $name…")
            try {
                return Geocoder.resolve(context, name, from.lat, from.lon)
                    ?: throw IllegalStateException("I couldn't find $what \"$name\" — try its address or the town.")
            } catch (e: Geocoder.FarAway) {
                HudStateBridge.notice(null)
                val miles = (e.km * 0.621371).toInt()
                throw IllegalStateException("The only \"$name\" I can find is ${e.place.label}, about $miles miles away" +
                    (e.nearby?.let { " — did you mean ${it.label} nearby? Say so and I'll route there." } ?: " — if you really mean it, say the city too."))
            }
        }
        val to = runCatching { place(dest, "a place called") }.getOrElse { return@withContext Result.failure(it) }
        val via = ArrayList<Router.Via>()
        for (name in viaNames) {
            val p = runCatching { place(name, "the stop") }.getOrElse { return@withContext Result.failure(it) }
            via += Router.Via(p.lat, p.lon, p.label)
        }
        HudStateBridge.notice("Routing…")
        val route = Router.route(from.lat, from.lon, to.lat, to.lon, mode, to.label, via)
            ?: run { HudStateBridge.notice(null); return@withContext Result.failure(IllegalStateException("I couldn't get a $mode route to ${to.label}.")) }
        HudStateBridge.notice(null)
        val json = route.toJson().toString()
        val source = "geo:%.6f,%.6f?q=%s".format(Locale.US, to.lat, to.lon, URLEncoder.encode(to.label, "UTF-8"))
        // "… with minimap" → the compact HUD (arrow · info · heading-up minimap) instead of the slippy map;
        // an existing navigation keeps its view and theme unless the request names one.
        val (hudDelta, hudWarn) = WidgetOps.navState(args, emptyMap(), filterUnchanged = false)
        val (rememberedView, rememberedTheme) = WidgetOps.rememberedHud(context)
        val activity = WidgetOps.impliedActivity(args)                     // "run to" / "hike to": this trip only
        val hudAsked = WidgetOps.hudRequested(args)
        val hud = hudAsked ?: (if (activity != null) true else null) ?: navWidget?.let { it.state["view"] == "hud" } ?: rememberedView
        val theme = when {
            hudDelta.containsKey("theme") -> hudDelta["theme"]!!          // named, or an explicit "default" ("")
            activity != null -> activity
            else -> navWidget?.state?.get("theme")?.takeIf { it.isNotBlank() } ?: rememberedTheme
        }
        val orient = if (hudDelta.containsKey("orient")) hudDelta["orient"]!! else navWidget?.state?.get("orient").orEmpty()
        val keep = navWidget?.state.orEmpty()
        val hudExtras = mapOf("mzoom" to (hudDelta["mzoom"] ?: keep["mzoom"].orEmpty()), "arrow" to (hudDelta["arrow"] ?: keep["arrow"].orEmpty()), "units" to (hudDelta["units"] ?: keep["units"].orEmpty()))
        // Only what the user said outright becomes the default for next time.
        WidgetOps.rememberHud(context, hudAsked, if (hudDelta.containsKey("theme")) hudDelta["theme"] else null)
        val state = mapOf("zoom" to "17", "step" to "0", "nav" to "on", "mode" to mode, "dest" to dest, "via" to viaKey, "navMap" to "1",
            "pos" to "%.6f,%.6f,%d".format(Locale.US, from.lat, from.lon, from.accuracyM.toInt()), "posSrc" to from.source,
            "vel" to (from.speedMps?.let { sp -> "%.1f,%s".format(Locale.US, sp, from.bearingDeg?.let { "%.0f".format(Locale.US, it) } ?: "") } ?: ""))
            .filterValues { it.isNotEmpty() } + (mapOf("view" to (if (hud) "hud" else ""), "theme" to theme, "orient" to orient) + hudExtras).filterValues { it.isNotEmpty() }
        val viaText = if (via.isEmpty()) "" else " via " + via.joinToString(", ") { it.label }
        // Title leads with the next stop: "→ Glenview Taqueria → Montera Middle School".
        val title = ("→ " + (via.map { it.label } + to.label).joinToString(" → ")).take(32)
        // One navigation window per desktop: a new destination re-routes the existing map (even a stopped one).
        var id = navWidget?.id
        if (id != null && DesktopBridge.current().widget(id) != null) {
            DesktopBridge.mutateWidget(id) { w -> w.copy(type = WidgetType.MAP, title = title, source = source, content = json, state = state, updatedAt = System.currentTimeMillis()) }
        } else {
            val (dw, dh) = if (hud) Layout.sizeFor(WidgetType.MAP, args.str("size"), fallback = HUD_SIZE) else Layout.sizeFor(WidgetType.MAP, args.str("size") ?: "large")
            val (w, h) = Layout.clampSize(args.int("w", "width") ?: dw, args.int("h", "height") ?: dh)
            var placed: Widget? = null
            DesktopBridge.mutate { d ->
                // The HUD sits out of the way by default (top right, under the strip); the full map takes the centre.
                val (px, py) = Layout.anchorPos(args.str("anchor", "position") ?: (if (hud) "top right" else "center"), w, h) ?: Layout.freeSlot(d.widgets, w, h)
                val (x, y) = Layout.clampPos(px, py, w, h)
                val widget = Widget(type = WidgetType.MAP, title = title, x = x, y = y, w = w, h = h, z = (d.widgets.maxOfOrNull { it.z } ?: 0) + 1,
                    source = source, state = state, content = json, updatedAt = System.currentTimeMillis())
                placed = widget
                d.copy(widgets = d.widgets + widget)
            }
            id = placed!!.id
        }
        DesktopBridge.setActive(id)
        val first = route.steps.firstOrNull()
        val quality = when {
            from.source == "phone" -> " Using your phone's GPS."
            from.isPrecise -> ""
            else -> " Your position is only approximate — connect your phone in the RayNeo app for real GPS; ${com.tapgem.app.core.location.PhoneGps.whyNot(context)}."
        }
        if (from.source == "phone") LocationSource.keepPhoneStream(context)
        val hudNote = if (hud) " The minimap HUD is up${theme.takeIf { it.isNotBlank() }?.let { " in the ${HUD_THEMES[it] ?: it} theme" } ?: ""} — themes: fallout, synthwave, hiking, running; say 'full map' for the big map." else ""
        Result.success("Navigation started to ${to.label}$viaText: ${Router.distance(route.distM)}, about ${Router.duration(route.durS)} $mode. " +
            (first?.let { "First: ${it.text}${if (it.distM > 0) " for ${Router.distance(it.distM)}" else ""}." } ?: "") +
            " Say next step / previous step / stop navigation.$quality$hudNote${hudWarn?.let { " ($it)" } ?: ""}")
    }

    /** Navigation HUD themes: the words the user says → the theme key the page knows. */
    val HUD_THEMES = linkedMapOf("fallout" to "Fallout", "synthwave" to "Synthwave", "hiking" to "Hiking", "running" to "Running")
    fun parseHudTheme(raw: String?): String? {
        val k = raw?.trim()?.lowercase(Locale.US) ?: return null
        return when {
            k.isBlank() -> null
            k.contains("synth") || k.contains("neon") || k.contains("retrowave") || k.contains("outrun") || k.contains("cyber") || k.contains("vapor") || k.contains("miami") || k.contains("eighties") || k.contains("80s") -> "synthwave"
            k.contains("fallout") || k.contains("pip") || k.contains("vault") || k.contains("terminal") || k.contains("wasteland") || k.contains("phosphor") || k.contains("retro") -> "fallout"
            k.contains("hik") || k.contains("nature") || k.contains("trail") || k.contains("forest") || k.contains("outdoor") || k.contains("topo") -> "hiking"
            k.contains("run") || k.contains("jog") || k.contains("sport") || k.contains("fitness") || k.contains("pace") -> "running"
            k == "auto" || k == "default" || k == "none" || k == "plain" || k == "classic" || k == "normal" || k == "blue" || k == "hud" || k == "standard" -> "auto"
            else -> null
        }
    }
    fun parseHudOrientation(raw: String?): String? {
        val k = raw?.trim()?.lowercase(Locale.US)?.replace(Regex("[^a-z]"), "") ?: return null
        return when {
            k.isBlank() -> null
            k.startsWith("north") -> "north"
            k.startsWith("head") || k.contains("compass") || k.contains("look") -> "heading"
            k.startsWith("course") || k.contains("travel") || k.contains("gps") || k.contains("track") -> "course"
            k == "auto" || k == "default" -> "auto"
            else -> null
        }
    }

    /** minimap=true / view=minimap|hud → true; minimap=false / view=full|map → false; unsaid → null. */
    fun hudRequested(args: Args): Boolean? {
        val view = args.str("view", "layout", "display")?.lowercase(Locale.US)
        val minimap = args.bool("minimap", "hud", "compact", "mini")
        return when {
            minimap == true || view != null && (view.contains("mini") || view.contains("hud") || view.contains("compact") || view.contains("arrow")) -> true
            minimap == false || view != null && (view.contains("full") || view.contains("map") || view.contains("tile") || view.contains("big")) -> false
            else -> null
        }
    }

    /**
     * Navigation HUD settings from tool args → state delta: view (minimap|map), theme
     * (fallout|synthwave|hiking|running|auto), orientation (auto|heading|course|north). Warns on unknown names.
     */
    fun navState(args: Args, current: Map<String, String>, filterUnchanged: Boolean = true): Pair<Map<String, String>, String?> {
        val out = HashMap<String, String>()
        var warn: String? = null
        hudRequested(args)?.let { out["view"] = if (it) "hud" else "" }
        args.str("theme", "hud_theme", "skin", "look")?.let { raw ->
            parseHudTheme(raw)?.let { out["theme"] = if (it == "auto") "" else it } ?: run { warn = "I don't have a \"$raw\" theme — the HUD comes in fallout, synthwave, hiking and running." }
        }
        args.str("orientation", "orient", "map_orientation", "rotation")?.let { raw ->
            parseHudOrientation(raw)?.let { out["orient"] = if (it == "auto") "" else it } ?: run { warn = (warn?.let { "$it " } ?: "") + "Orientation is heading, course, north or auto." }
        }
        args.str("arrow", "arrow_size")?.lowercase(Locale.US)?.let { a ->
            out["arrow"] = when { a.startsWith("larg") || a.startsWith("big") -> "large"; a.startsWith("small") || a.startsWith("tiny") -> "small"; else -> "" }
        }
        args.str("units", "unit", "measure")?.lowercase(Locale.US)?.let { u ->
            out["units"] = when { u.startsWith("met") || u.startsWith("km") || u.startsWith("kilo") -> "metric"; u.startsWith("us") || u.startsWith("imp") || u.startsWith("mi") || u.startsWith("feet") || u.startsWith("ft") -> "us"; else -> "" }
        }
        // Minimap radius presets: only when the caller names one (a bare number is the tile map's zoom level).
        args.str("zoom", "radius", "minimap_zoom")?.lowercase(Locale.US)?.takeIf { it.toIntOrNull() == null }?.let { z ->
            hudZoomPreset(z)?.let { out["mzoom"] = if (it == "auto") "" else it }
        }
        return (if (filterUnchanged) out.filter { (k, v) -> current[k].orEmpty() != v } else out) to warn
    }

    /** near · mid · far · wide · region (200 ft · 400 ft · 0.15 mi · 0.3 mi · 0.6 mi / 60 · 120 · 250 · 500 · 1000 m). */
    val HUD_ZOOMS = listOf("near", "mid", "far", "wide", "region")
    private val HUD_RADII = listOf("near" to 60.0, "mid" to 120.0, "far" to 250.0, "wide" to 500.0, "region" to 1000.0)
    fun hudZoomPreset(raw: String?): String? {
        val k = raw?.trim()?.lowercase(Locale.US)?.replace(",", "") ?: return null
        if (k.isBlank()) return null
        // "600 feet", "0.3 mi", "250 m", "1 km": the preset whose radius is nearest.
        Regex("([0-9]*\\.?[0-9]+)\\s*(ft|feet|foot|m|meters|metres|mi|mile|miles|km|kilometers|kilometres|yd|yards)?").find(k)?.let { m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: return@let
            val metres = when (m.groupValues[2]) { "ft", "feet", "foot" -> n * 0.3048; "mi", "mile", "miles" -> n * 1609.344; "km", "kilometers", "kilometres" -> n * 1000; "yd", "yards" -> n * 0.9144; "" -> if (n <= 5) n * 1609.344 else n; else -> n }
            return HUD_RADII.minByOrNull { abs(it.second - metres) }!!.first
        }
        return when {
            k == "auto" || k == "default" -> "auto"
            k.startsWith("near") || k.startsWith("close") -> "near"
            k.startsWith("mid") -> "mid"
            k.startsWith("far") -> "far"
            k.startsWith("wide") -> "wide"
            k.startsWith("region") -> "region"
            else -> null
        }
    }

    private const val PREF_HUD_THEME = "nav_hud_theme"
    private const val PREF_HUD_VIEW = "nav_hud_view"
    /** The last theme / view chosen become the defaults for the next navigation. */
    /** [theme] "" clears the remembered theme (an explicit "default"); null leaves it alone. */
    fun rememberHud(context: Context, view: Boolean?, theme: String?) {
        val e = context.getSharedPreferences("tapgem_config", Context.MODE_PRIVATE).edit()
        view?.let { e.putBoolean(PREF_HUD_VIEW, it) }
        theme?.let { if (it.isBlank()) e.remove(PREF_HUD_THEME) else e.putString(PREF_HUD_THEME, it) }
        e.apply()
    }
    /** The model sometimes emits "\\n" as two characters inside an already-decoded JSON string. */
    fun unescapeModelText(t: String): String = t.replace("\\n", "\n").replace("\\t", "\t")

    /** "Run to" / "hike to": a theme (and the minimap) for this trip only, never remembered. */
    fun impliedActivity(args: Args): String? = args.str("activity", "sport")?.lowercase(Locale.US)?.let { a ->
        when { a.startsWith("run") || a.startsWith("jog") -> "running"; a.startsWith("hik") || a.startsWith("trail") || a.startsWith("walk") && a.contains("nature") -> "hiking"; else -> null }
    }
    fun rememberedHud(context: Context): Pair<Boolean, String> {
        val p = context.getSharedPreferences("tapgem_config", Context.MODE_PRIVATE)
        return p.getBoolean(PREF_HUD_VIEW, false) to p.getString(PREF_HUD_THEME, "").orEmpty()
    }

    /**
     * Clock settings from tool args → state delta: style (digital|thin|led|analog|modern), hours (12|24),
     * seconds/date (true|false), zones (cities / zone ids, comma-separated; add_zone / remove_zone edit the
     * list). Returns the delta and a warning for names it couldn't place.
     */
    fun clockState(args: Args, current: Map<String, String>): Pair<Map<String, String>, String?> {
        val out = HashMap<String, String>()
        var warn: String? = null
        args.str("style", "look", "face", "clock_style")?.let { raw ->
            val st = com.tapgem.app.ui.ClockFaceView.parseStyle(raw)
            if (st != null) out["style"] = st else warn = "unknown clock style \"$raw\" — use digital, thin, led, analog or modern"
        }
        args.str("hours", "hour_format", "clock_hours")?.let { h ->
            val v = h.filter { it.isDigit() }
            if (v == "12" || v == "24") out["hours"] = v
        }
        args.bool("seconds", "show_seconds")?.let { out["seconds"] = it.toString() }
        args.bool("date", "show_date")?.let { out["date"] = it.toString() }
        val unknown = ArrayList<String>()
        fun zonesOf(raw: String): List<String> = raw.split(Regex("\\s*(,|;|\\band\\b|\\+)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { name -> com.tapgem.app.core.model.WorldClocks.resolve(name)?.let { if (it.isBlank()) "local" else it } ?: run { unknown += name; null } }
        args.str("zones", "cities", "timezones", "time_zones", "timezone", "time_zone", "zone", "city")?.let { raw ->
            val z = zonesOf(raw)
            if (z.isNotEmpty()) out["zones"] = z.distinct().joinToString(",")
        }
        args.str("add_zone", "add_city")?.let { raw ->
            val cur = current["zones"]?.split(',')?.filter { it.isNotBlank() } ?: listOf("local")
            val z = (cur + zonesOf(raw)).distinct()
            if (z != cur) out["zones"] = z.joinToString(",")
        }
        args.str("remove_zone", "remove_city")?.let { raw ->
            val cur = current["zones"]?.split(',')?.filter { it.isNotBlank() } ?: listOf("local")
            val gone = zonesOf(raw).toSet()
            val z = cur.filter { it !in gone }.ifEmpty { listOf("local") }
            if (z != cur) out["zones"] = z.joinToString(",")
        }
        if (unknown.isNotEmpty()) warn = (warn?.let { "$it; " } ?: "") + "I don't know the time zone for ${unknown.joinToString(", ") { "\"$it\"" }} — say the nearest big city"
        return out to warn
    }

    /** Same type + same normalised content = the same window. */
    private val PLACES_WORDS = Regex("\\b(near|nearby|around|close to|on the way|along|restaurants?|food|eat|lunch|dinner|breakfast|brunch|coffee|caf[eé]s?|bars?|pubs?|gas|fuel|charg(?:er|ing)|hotels?|motels?|pharmac(?:y|ies)|grocer(?:y|ies)|supermarkets?|shops?|stores?|parks?|playgrounds?|gyms?|things to do|best|good|top|cheap|open now|atms?|banks?|parking|hospitals?|clinics?|dentists?|vets?)\\b", RegexOption.IGNORE_CASE)

    /** "restaurants near X", "good coffee on the way", "gas around here" — a category, not one named place. */
    fun isPlacesQuery(q: String): Boolean = PLACES_WORDS.containsMatchIn(q)

    private const val PLACES_NOTE = " The map shows the matching pins. Don't click through Maps' list (it starts with " +
        "sponsored entries and loads slowly): name 2-3 well-rated options from your own Google Search, then offer " +
        "to show one on the map by name (widget add type=map query=<name>)."

    fun isGoogleMaps(url: String): Boolean {
        val u = url.lowercase(Locale.US)
        return Regex("^https?://(www\\.)?google\\.[a-z.]+/maps").containsMatchIn(u) || u.startsWith("https://maps.google.") || u.startsWith("https://maps.app.goo.gl")
    }

    fun dedupeKey(type: WidgetType, source: String, zones: String? = null): String {
        val s = source.trim().lowercase(Locale.US)
        val norm = when (type) {
            WidgetType.WEB -> s.removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("m.").trimEnd('/')
            WidgetType.LIVE, WidgetType.TICKER -> s.replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ")
            WidgetType.TEXT -> if (s.startsWith("prompt:") || s.startsWith("file:")) s else "text:" + s.hashCode()
            // One clock per set of cities: a Tokyo clock beside the local one is fine, two local clocks aren't.
            WidgetType.CLOCK -> "clock:" + (zones?.lowercase(Locale.US)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.sorted()?.joinToString(",") ?: "local")
            else -> s
        }
        return "${type.name}|$norm"
    }

    /** "current weather in oakland" → "Weather · Oakland"-ish short title. */
    fun liveTitle(q: String): String {
        val words = q.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in setOf("the", "current", "latest", "today", "todays", "now", "right", "please", "show", "me", "what", "is", "whats", "a", "an", "of", "for", "in", "at", "on") }
        return words.take(3).joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }.take(24).ifBlank { "Live" }
    }

    fun normalizeUrl(raw: String): String {
        val s = raw.trim()
        if (isUrl(s)) return s
        return if (s.contains('.') && !s.contains(' ')) "https://$s"
        else "https://duckduckgo.com/?q=" + URLEncoder.encode(s, "UTF-8")
    }

    fun humanSecs(s: Int): String = when {
        s % 3600 == 0 -> "${s / 3600} hour${if (s / 3600 == 1) "" else "s"}"
        s % 60 == 0 -> "${s / 60} minute${if (s / 60 == 1) "" else "s"}"
        else -> "$s seconds"
    }
}

// ─────────────────────────────────────────────────────────────────────
// widget
// ─────────────────────────────────────────────────────────────────────

class WidgetTool(private val context: Context) : AiTool {
    override val name = "widget"

    override suspend fun execute(args: Args): Result<String> {
        return when (args.action) {
            "add", "create", "new", "place", "show", "open" -> WidgetOps.add(context, args)
            "list", "describe" -> Result.success(DesktopBridge.describe())
            "update", "set", "style", "edit", "change", "rename" -> update(args)
            "remove", "delete", "close", "hide" -> remove(args)
            "move", "position" -> move(args)
            "resize", "size", "scale" -> resize(args)
            "front", "focus", "raise", "activate", "select" -> front(args)
            "pin", "unpin", "stay_on_top", "on_top", "always_on_top", "toggle_pin", "toggle_on_top", "keep_on_top" -> pin(args)
            "navigate", "control", "nav" -> navigate(args)
            "refresh", "reload", "update_now" -> refresh(args)
            "settings", "options", "preferences", "configure", "open_settings" -> settings(args)
            "close_settings", "hide_settings" -> { com.tapgem.app.core.bridge.SettingsBridge.close(); Result.success("Settings closed.") }
            else -> Result.failure(IllegalArgumentException("Unknown widget action '${args.action}'. Use add, update, remove, move, resize, front, list, navigate, refresh, settings."))
        }
    }

    private fun resolve(args: Args): Widget? =
        DesktopBridge.resolveWidget(args.str("id", "title", "widget", "name", "target"), args.str("type"))

    private fun missing(args: Args): Result<String> = Result.success(
        "No widget matches \"${args.str("id", "title", "widget", "name", "type") ?: ""}\". ${DesktopBridge.describe()}"
    )

    /**
     * Network work happens first against a snapshot; the change itself is
     * then applied to the LIVE widget inside mutate so a drag or refresh that
     * landed meanwhile is never overwritten.
     */
    private suspend fun update(args: Args): Result<String> = withContext(Dispatchers.IO) {
        val w = resolve(args) ?: return@withContext missing(args)
        val warnings = ArrayList<String>()
        val changes = ArrayList<String>()

        val generated: String? = args.str("prompt")?.let { p ->
            GeminiRest.generateText(context, p, system = WidgetRefreshEngine.PROMPT_TEXT_SYSTEM, search = true)
                .getOrElse { warnings += "couldn't generate the text (${it.message?.take(60)})"; null }
        }
        val liveContent: String? = if (w.type.isFetched) args.str("query")?.let { q ->
            (if (w.type == WidgetType.TICKER) WidgetRefreshEngine.fetchTicker(q) else WidgetRefreshEngine.fetchLive(q))
                .getOrElse { warnings += "couldn't fetch \"$q\" yet"; "" }
        } else null
        val geo = if (w.type == WidgetType.MAP) args.str("query", "place", "location")?.let { q ->
            Geocoder.lookup(q).also { if (it == null) warnings += "couldn't find \"$q\"" }
        } else null
        val newSource: String? = args.str("url", "path", "source")?.let { s ->
            when (w.type) {
                WidgetType.WEB -> WidgetOps.normalizeUrl(s)
                WidgetType.APP -> if (WidgetOps.isAppPath(s)) s else { warnings += "apps can only load from TapGem's app folder"; null }
                WidgetType.IMAGE, WidgetType.VIDEO, WidgetType.AUDIO -> if (WidgetOps.isUrl(s)) s else MediaScanner.ensureLocal(context, s)?.absolutePath.also { if (it == null) warnings += "couldn't find $s" }
                WidgetType.TEXT -> MediaScanner.ensureLocal(context, s)?.let { "file:" + it.absolutePath }.also { if (it == null) warnings += "couldn't find $s" }
                else -> MediaScanner.ensureLocal(context, s)?.absolutePath.also { if (it == null) warnings += "couldn't find $s" }
            }
        }
        val newSourceText = newSource?.takeIf { w.type == WidgetType.TEXT }?.let { WidgetOps.readTextFile(File(it.removePrefix("file:"))) }
        WidgetOps.styleFrom(args, WidgetStyle(), warnings) // only for the colour validation messages

        val after = DesktopBridge.mutateWidget(w.id) { f ->
            var n = f
            args.str("new_title", "rename", "rename_to")?.let { t -> n = n.copy(title = t.take(32)); changes += "renamed to \"${t.take(32)}\"" }
            args.str("on_top", "pinned")?.lowercase(Locale.US)?.let { v ->
                val want = if (v == "toggle") !n.onTop else v in setOf("true", "yes", "on", "1")
                if (want != n.onTop) { n = n.copy(onTop = want); changes += if (want) "stays on top" else "no longer on top" }
            }
            args.str("text", "content", "body")?.let(WidgetOps::unescapeModelText)?.let { t ->
                n = if (f.type == WidgetType.TEXT) n.copy(source = t, content = "") else n.copy(content = t, updatedAt = System.currentTimeMillis())
                changes += "text changed"
            }
            args.str("prompt")?.let { p ->
                n = n.copy(source = "prompt:$p", content = generated ?: n.content, updatedAt = if (generated != null) System.currentTimeMillis() else n.updatedAt)
                changes += "prompt changed"
            }
            if (f.type.isFetched) args.str("query")?.let { q ->
                n = n.copy(source = q, title = if (args.has("new_title")) n.title else WidgetOps.liveTitle(q),
                    content = liveContent?.ifBlank { n.content } ?: n.content, updatedAt = System.currentTimeMillis())
                changes += "now watching \"$q\""
            }
            if (geo != null) {
                n = n.copy(source = "geo:%.6f,%.6f?q=%s".format(Locale.US, geo.lat, geo.lon, URLEncoder.encode(geo.label, "UTF-8")),
                    title = if (args.has("new_title")) n.title else geo.label.take(28))
                    .withState("zoom" to (args.int("zoom") ?: Geocoder.zoomFor(geo.kind, n.state["zoom"]?.toIntOrNull() ?: 13)).coerceIn(1, 18).toString())
                changes += "map moved to ${geo.label}"
            } else if (f.type == WidgetType.MAP) args.int("zoom")?.let { z -> n = n.withState("zoom" to z.coerceIn(1, 18).toString()); changes += "zoom $z" }
            if (f.type == WidgetType.MAP && (f.state["nav"] == "on" || f.state["navMap"] == "1")) {
                val (st, bad) = WidgetOps.navState(args, n.state)
                if (st.isNotEmpty()) {
                    n = n.withState(st)
                    changes += st.entries.joinToString(", ") { (k, v) -> when (k) {
                        "view" -> if (v == "hud") "minimap HUD" else "full map"; "theme" -> "theme ${v.ifBlank { "default" }}"; "orient" -> "orientation ${v.ifBlank { "auto" }}"
                        "arrow" -> "${v.ifBlank { "normal" }} arrow"; "units" -> if (v == "metric") "metric units" else "miles and feet"; "mzoom" -> "minimap zoom ${v.ifBlank { "auto" }}"; else -> "$k $v" } }
                    WidgetOps.rememberHud(context, st["view"]?.let { it == "hud" }, st["theme"])
                }
                bad?.let { warnings += it }
            }
            if (f.type == WidgetType.CLOCK) {
                args.str("format")?.let { fm -> n = n.copy(source = fm.lowercase(Locale.US)); changes += "format $fm" }
                val (st, bad) = WidgetOps.clockState(args, n.state)
                if (st.isNotEmpty()) { n = n.withState(st); changes += st.entries.joinToString(", ") { (k, v) -> "$k $v" } }
                bad?.let { warnings += it }
            }
            newSource?.let { s ->
                n = n.copy(source = s, content = newSourceText ?: n.content).withState("reload" to System.currentTimeMillis().toString())
                changes += "source changed"
            }
            val refresh = WidgetOps.refreshFrom(args, n.refreshSec)
            if (refresh != n.refreshSec) { n = n.copy(refreshSec = refresh); changes += if (refresh == 0) "refresh off" else "refreshes every ${WidgetOps.humanSecs(refresh)}" }
            val style = WidgetOps.styleFrom(args, n.style)
            if (style != n.style) { n = n.copy(style = style); changes += "style updated" }
            // Geometry: explicit w/h, size names, scale; then anchor or x/y.
            val scale = args.float("scale", "factor")
            val (bw, bh) = when {
                scale != null -> (n.w * scale).roundToInt() to (n.h * scale).roundToInt()
                args.has("size") -> Layout.sizeFor(n.type, args.str("size"), n.w to n.h)
                else -> (args.int("w", "width") ?: n.w) to (args.int("h", "height") ?: n.h)
            }
            val (sw, sh) = Layout.clampSize(n.type, bw, bh)
            val anchor = args.str("anchor", "position") ?: Layout.impliedAnchor(args.str("size"))
            val (ax, ay) = Layout.anchorPos(anchor, sw, sh)
                ?: ((args.int("x") ?: (n.x + (args.int("dx") ?: 0))) to (args.int("y") ?: (n.y + (args.int("dy") ?: 0))))
            val (x, y) = Layout.clampPos(ax, ay, sw, sh)
            if (sw != n.w || sh != n.h) changes += "resized to ${sw}x$sh"
            if (x != n.x || y != n.y) changes += "moved to ($x,$y)"
            n.copy(x = x, y = y, w = sw, h = sh)
        }
        val nw = after.widget(w.id) ?: return@withContext Result.success("\"${w.title}\" was closed before the change applied.")
        val warn = if (warnings.isEmpty()) "" else " Note: " + warnings.joinToString("; ") + "."
        Result.success(if (changes.isEmpty()) "Nothing to change on \"${nw.title}\".$warn" else "Updated \"${nw.title}\": ${changes.joinToString(", ")}.$warn")
    }

    private fun remove(args: Args): Result<String> {
        val ref = args.str("id", "title", "widget", "name")?.lowercase(Locale.US)
        if (ref in setOf("all", "everything", "*", "all widgets", "all windows")) {
            DesktopBridge.mutate { it.copy(widgets = emptyList()) }
            return Result.success("Removed all widgets. Say undo to bring them back.")
        }
        val w = resolve(args) ?: return missing(args)
        DesktopBridge.mutate { d -> d.copy(widgets = d.widgets.filterNot { it.id == w.id }) }
        return Result.success("Removed \"${w.title}\". Say undo to bring it back.")
    }

    private fun move(args: Args): Result<String> {
        val w = resolve(args) ?: return missing(args)
        val after = DesktopBridge.mutateWidget(w.id) { f ->
            val (tx, ty) = Layout.anchorPos(args.str("anchor", "position", "to"), f.w, f.h)
                ?: ((args.int("x") ?: (f.x + (args.int("dx") ?: 0))) to (args.int("y") ?: (f.y + (args.int("dy") ?: 0))))
            val (x, y) = Layout.clampPos(tx, ty, f.w, f.h)
            f.copy(x = x, y = y)
        }
        val nw = after.widget(w.id) ?: return Result.success("\"${w.title}\" is gone.")
        return Result.success("Moved \"${nw.title}\" to (${nw.x},${nw.y}).")
    }

    private fun resize(args: Args): Result<String> {
        val w = resolve(args) ?: return missing(args)
        val after = DesktopBridge.mutateWidget(w.id) { f ->
            val scale = args.float("scale", "factor")
            val (bw, bh) = when {
                scale != null -> (f.w * scale).roundToInt() to (f.h * scale).roundToInt()
                args.has("size") -> Layout.sizeFor(f.type, args.str("size"), f.w to f.h)
                else -> (args.int("w", "width") ?: f.w) to (args.int("h", "height") ?: f.h)
            }
            val (nw, nh) = Layout.clampSize(f.type, bw, bh)
            val anchor = args.str("anchor", "position") ?: Layout.impliedAnchor(args.str("size"))
            val (ax, ay) = Layout.anchorPos(anchor, nw, nh) ?: (f.x to f.y)
            val (x, y) = Layout.clampPos(ax, ay, nw, nh)
            f.copy(x = x, y = y, w = nw, h = nh)
        }
        val nw = after.widget(w.id) ?: return Result.success("\"${w.title}\" is gone.")
        return Result.success("Resized \"${nw.title}\" to ${nw.w}x${nw.h}.")
    }

    /** "Keep this on top" / "toggle stay on top" / "unpin": pinned windows sit above everything else. */
    private fun pin(args: Args): Result<String> {
        val w = resolve(args) ?: return missing(args)
        val raw = args.str("on_top", "pinned", "value", "state")?.lowercase(Locale.US)
        val want = when {
            args.action in setOf("unpin") -> false
            args.action.startsWith("toggle") || raw == "toggle" || raw == "flip" -> !w.onTop
            raw == null -> if (args.action == "pin" || args.action == "stay_on_top" || args.action == "always_on_top" || args.action == "keep_on_top" || args.action == "on_top") true else !w.onTop
            else -> raw in setOf("true", "yes", "on", "1", "pin", "pinned")
        }
        if (want == w.onTop) return Result.success(if (want) "\"${w.title}\" already stays on top." else "\"${w.title}\" wasn't pinned on top.")
        DesktopBridge.mutateWidget(w.id) { it.copy(onTop = want) }
        return Result.success(if (want) "\"${w.title}\" now stays on top of every other window." else "\"${w.title}\" no longer stays on top.")
    }

    /** Open the window's settings sheet (what its ⚙ shows); the model can also change the same things with update. */
    private fun settings(args: Args): Result<String> {
        val w = resolve(args) ?: return missing(args)
        DesktopBridge.setActive(w.id)
        com.tapgem.app.core.bridge.SettingsBridge.open(w.id)
        val what = when (w.type) {
            WidgetType.CLOCK -> {
                val cfg = com.tapgem.app.ui.ClockFaceView.configOf(w, 0, 0, 1f, false)
                "style ${cfg.style}, ${if (cfg.hours24) "24" else "12"}-hour, seconds ${if (cfg.seconds) "on" else "off"}, date ${if (cfg.date) "on" else "off"}, " +
                    "cities ${cfg.zones.joinToString(", ") { com.tapgem.app.core.model.WorldClocks.label(it) }}. Styles: digital, thin, led, analog, modern; " +
                    "change with widget action=update style=… hours=12|24 seconds=… date=… zones=<cities> (or add_zone / remove_zone)"
            }
            WidgetType.MAP -> if (w.state["nav"] == "on") "view ${if (w.state["view"] == "hud") "minimap HUD" else "full map"}, theme ${w.state["theme"]?.ifBlank { null } ?: "default"}, " +
                "orientation ${w.state["orient"]?.ifBlank { null } ?: "auto"}. Change with widget action=update minimap=true|false theme=fallout|synthwave|hiking|running orientation=heading|course|north|auto"
                else "zoom ${w.state["zoom"] ?: "13"}, opacity ${(w.style.opacity * 100).toInt()}%, stay on top ${w.onTop}"
            WidgetType.LIVE, WidgetType.TICKER -> "refresh every ${w.refreshSec}s (refresh_seconds), opacity ${(w.style.opacity * 100).toInt()}%, stay on top ${w.onTop}"
            else -> "opacity ${(w.style.opacity * 100).toInt()}%, stay on top ${w.onTop}, text size ${w.style.fontSize ?: "default"}"
        }
        return Result.success("Opened the settings for \"${w.title}\": $what.")
    }

    private fun front(args: Args): Result<String> {
        val w = resolve(args) ?: return missing(args)
        DesktopBridge.mutate(pushUndo = false) { d -> d.widget(w.id)?.let { d.replaceWidget(it.copy(z = (d.widgets.maxOfOrNull { o -> o.z } ?: 0) + 1)) } ?: d }
        DesktopBridge.setActive(w.id)
        return Result.success("\"${w.title}\" is now the active window, in front.")
    }

    private suspend fun navigate(args: Args): Result<String> {
        val w = resolve(args) ?: return missing(args)
        val nav = args.str("nav", "command", "do")?.lowercase(Locale.US)?.replace(Regex("[\\s_-]+"), "_")
            ?: return Result.failure(IllegalArgumentException("navigate needs 'nav'."))
        val value = args.str("value", "to", "page", "chapter", "url", "zoom")
        val now = System.currentTimeMillis().toString()
        val pages = w.state["pages"]?.toIntOrNull()
        val chapters = w.state["chapters"]?.toIntOrNull()

        // Web-like widgets: history and media playback go through the page itself.
        if (w.type.isWebLike || w.type == WidgetType.EPUB && nav in setOf("scroll_down", "scroll_up")) {
            if (w.type.isWebLike && nav in setOf("start", "navigate", "start_navigation", "go") && w.source.contains("google.") && w.source.contains("/maps")) {
                val dest = value ?: Regex("destination=([^&]+)").find(w.source)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?: Regex("/maps/dir/[^/]+/([^/@]+)").find(w.source)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it.replace('+', ' '), "UTF-8") }
                    ?: w.title.removePrefix("Directions: ")
                val mode = Regex("travelmode=([a-z]+)").find(w.source)?.groupValues?.get(1) ?: args.str("travel_mode", "mode")
                return WidgetOps.startNavigation(context, dest, mode, w.id, args)
            }
            when (nav) {
                "next", "forward" -> if (w.type.isWebLike) return Result.success(WebCommandBus.execute(w.id, WebCommandBus.Command("forward", emptyMap())))
                "prev", "previous", "back" -> if (w.type.isWebLike) return Result.success(WebCommandBus.execute(w.id, WebCommandBus.Command("back", emptyMap())))
                "play", "resume", "start", "pause", "stop" -> if (w.type.isWebLike)
                    return Result.success(WebCommandBus.execute(w.id, WebCommandBus.Command(if (nav == "pause" || nav == "stop") "pause" else "play", emptyMap())))
                "scroll_down", "scroll_up", "scroll" -> return Result.success(WebCommandBus.execute(w.id,
                    WebCommandBus.Command("scroll", mapOf("direction" to (if (nav == "scroll_up") "up" else value ?: "down")))))
                "reload", "refresh" -> return Result.success(WebCommandBus.execute(w.id, WebCommandBus.Command("reload", emptyMap())))
                "url", "open", "go" -> {
                    val u = value ?: return Result.failure(IllegalArgumentException("url needs 'value'."))
                    DesktopBridge.mutateWidget(w.id) { it.copy(source = WidgetOps.normalizeUrl(u)).withState("reload" to now) }
                    return Result.success("Opening ${WidgetOps.normalizeUrl(u)} in \"${w.title}\".")
                }
            }
        }

        fun clampPage(p: Int) = if (pages != null && pages > 0) p.coerceIn(0, pages - 1) else max(0, p)
        fun clampChapter(c: Int) = if (chapters != null && chapters > 0) c.coerceIn(0, chapters - 1) else max(0, c)
        val page = w.state["page"]?.toIntOrNull() ?: 0
        val chapter = w.state["chapter"]?.toIntOrNull() ?: 0
        var pushUndo = false
        var detail = ""
        val transform: (Widget) -> Widget = when (w.type) {
            WidgetType.PDF -> when (nav) {
                "next", "forward", "next_page" -> { val p = clampPage(page + 1); detail = if (p == page) " (already on the last page)" else " — page ${p + 1}${pages?.let { " of $it" } ?: ""}"; { it.withState("page" to p.toString()) } }
                "prev", "previous", "back", "previous_page" -> { val p = clampPage(page - 1); detail = if (p == page) " (already on the first page)" else " — page ${p + 1}"; { it.withState("page" to p.toString()) } }
                "page", "chapter", "go_to", "goto", "jump" -> { val p = clampPage((value?.toIntOrNull() ?: 1) - 1); detail = " — page ${p + 1}"; { it.withState("page" to p.toString()) } }
                "first", "start", "beginning" -> { detail = " — page 1"; { it.withState("page" to "0") } }
                "last", "end" -> { val p = clampPage((pages ?: 1) - 1); detail = " — page ${p + 1}"; { it.withState("page" to p.toString()) } }
                else -> return Result.failure(IllegalArgumentException("A PDF understands next, prev, page N, first, last."))
            }
            WidgetType.EPUB -> when (nav) {
                "next", "forward", "next_chapter", "next_page" -> { val c = clampChapter(chapter + 1); detail = if (c == chapter) " (already on the last chapter)" else " — chapter ${c + 1}${chapters?.let { " of $it" } ?: ""}"; { it.withState("chapter" to c.toString()) } }
                "prev", "previous", "back", "previous_chapter" -> { val c = clampChapter(chapter - 1); detail = if (c == chapter) " (already on the first chapter)" else " — chapter ${c + 1}"; { it.withState("chapter" to c.toString()) } }
                "chapter", "page", "go_to", "goto", "jump" -> { val c = clampChapter((value?.toIntOrNull() ?: 1) - 1); detail = " — chapter ${c + 1}"; { it.withState("chapter" to c.toString()) } }
                "first", "start", "beginning" -> { detail = " — chapter 1"; { it.withState("chapter" to "0") } }
                "last", "end" -> { val c = clampChapter((chapters ?: 1) - 1); detail = " — chapter ${c + 1}"; { it.withState("chapter" to c.toString()) } }
                else -> return Result.failure(IllegalArgumentException("An ebook understands next, prev, chapter N, first, last."))
            }
            WidgetType.VIDEO, WidgetType.AUDIO -> when (nav) {
                "next", "forward", "skip", "skip_forward" -> { detail = " — skipped ahead 15 s"; { it.withState("seekDelta" to "15000", "seekMs" to "", "seekNonce" to now) } }
                "prev", "previous", "back", "rewind", "skip_back" -> { detail = " — back 15 s"; { it.withState("seekDelta" to "-15000", "seekMs" to "", "seekNonce" to now) } }
                "play", "resume", "start" -> { { it.withState("playing" to "true") } }
                "pause", "stop" -> { { it.withState("playing" to "false") } }
                "restart", "beginning", "first" -> { { it.withState("seekMs" to "0", "seekDelta" to "", "seekNonce" to now, "playing" to "true") } }
                "mute" -> { { it.withState("muted" to "true") } }
                "unmute" -> { { it.withState("muted" to "false") } }
                "loop" -> { val on = if (value == null) w.state["loop"] != "true" else value.equals("true", true) || value == "on"; detail = if (on) " on" else " off"; { it.withState("loop" to on.toString()) } }
                "seek", "go_to", "goto", "jump" -> { val secs = parseSeconds(value); detail = " — to ${secs}s"; { it.withState("seekMs" to (secs * 1000).toString(), "seekDelta" to "", "seekNonce" to now) } }
                else -> return Result.failure(IllegalArgumentException("Media understands play, pause, next, prev, restart, mute, unmute, loop, seek."))
            }
            WidgetType.MAP -> {
                val z = w.state["zoom"]?.toIntOrNull() ?: 13
                val route = if (w.state["nav"] == "on") Router.Route.fromJson(w.content) else null
                val step = w.state["step"]?.toIntOrNull() ?: 0
                if (route != null && nav in setOf("next", "next_step", "forward", "prev", "previous", "back", "previous_step", "stop", "end", "cancel", "stop_navigation", "repeat", "current", "first", "start")) {
                    return when (nav) {
                        "stop", "end", "cancel", "stop_navigation" -> { DesktopBridge.mutateWidget(w.id) { it.copy(content = "", title = it.title.removePrefix("→ ")).withState("nav" to "", "step" to "", "pos" to "", "vel" to "", "offRoute" to "", "rerouting" to "", "arrived" to "", "zoom" to "15") }; Result.success("Navigation stopped.") }
                        "start", "first" -> { DesktopBridge.mutateWidget(w.id, pushUndo = false) { it.withState("step" to "0") }; Result.success("Back to the first step: ${route.steps.firstOrNull()?.text}.") }
                        "repeat", "current" -> Result.success(route.steps.getOrNull(step)?.let { "${it.text}${if (it.distM > 0) " for ${Router.distance(it.distM)}" else ""}." } ?: "No current step.")
                        "prev", "previous", "back", "previous_step" -> { val n = (step - 1).coerceAtLeast(0); DesktopBridge.mutateWidget(w.id, pushUndo = false) { it.withState("step" to n.toString()) }; Result.success("Step ${n + 1}: ${route.steps[n].text}.") }
                        else -> {
                            val n = (step + 1).coerceAtMost(route.steps.size - 1)
                            DesktopBridge.mutateWidget(w.id, pushUndo = false) { it.withState("step" to n.toString()) }
                            val st = route.steps[n]
                            Result.success(if (n == step) "That was the last step — ${st.text}." else "Step ${n + 1} of ${route.steps.size}: ${st.text}${if (st.distM > 0) " for ${Router.distance(st.distM)}" else ""}.")
                        }
                    }
                }
                if (nav in setOf("start", "navigate", "go", "directions")) {
                    val dest = value ?: Regex("q=([^&]+)").find(w.source)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: w.title
                    return WidgetOps.startNavigation(context, dest, w.state["mode"] ?: args.str("travel_mode", "mode"), w.id, args)
                }
                if (w.state["view"] == "hud" && nav in setOf("zoom_in", "in", "closer", "zoom_out", "out", "farther", "further", "zoom_auto", "auto", "zoom")) {
                    // The minimap zooms by radius presets; the page picks the next one and reports back what it chose.
                    val cmd = when (nav) { "zoom_in", "in", "closer" -> "in"; "zoom_out", "out", "farther", "further" -> "out"; else -> WidgetOps.hudZoomPreset(value) ?: "auto" }
                    DesktopBridge.mutateWidget(w.id, pushUndo = false) { it.withState("mzoomCmd" to "$cmd:$now") }
                    return Result.success(when (cmd) { "in" -> "Minimap zoomed in."; "out" -> "Minimap zoomed out."; "auto" -> "Minimap zoom back to auto."; else -> "Minimap zoom: $cmd." })
                }
                when (nav) {
                    // A spoken "zoom in/out" is two levels (a clearly visible 4× change); value=N sets the notch.
                    "zoom_in", "in", "closer", "next" -> { val nz = (z + (value?.toIntOrNull() ?: 2)).coerceIn(1, 18); detail = " — zoom $nz of 18"; { it.withState("zoom" to nz.toString()) } }
                    "zoom_out", "out", "farther", "further", "prev", "back" -> { val nz = (z - (value?.toIntOrNull() ?: 2)).coerceIn(1, 18); detail = " — zoom $nz of 18"; { it.withState("zoom" to nz.toString()) } }
                    "zoom" -> { val nz = (value?.toIntOrNull() ?: z).coerceIn(1, 18); detail = " — zoom $nz"; { it.withState("zoom" to nz.toString()) } }
                    "north", "up", "south", "down", "east", "right", "west", "left", "pan" -> {
                        val dir = if (nav == "pan") (value ?: "north") else nav
                        detail = " — panned $dir"; { it.withState("pan" to dir, "panNonce" to now) }
                    }
                    "center", "recenter", "reset", "home" -> { { it.withState("pan" to "center", "panNonce" to now) } }
                    "reload", "refresh" -> { { it.withState("reload" to now) } }
                    else -> return Result.failure(IllegalArgumentException("A map understands zoom in/out, zoom N, north/south/east/west, recenter."))
                }
            }
            WidgetType.LIVE, WidgetType.TEXT, WidgetType.TICKER -> when (nav) {
                "reload", "refresh", "update", "next" -> { WidgetRefreshEngine.refreshNow(w.id); return Result.success("Refreshing \"${w.title}\".") }
                else -> return Result.failure(IllegalArgumentException("Use widget update to change what a ${w.type.name.lowercase(Locale.US)} widget shows."))
            }
            WidgetType.IMAGE, WidgetType.MODEL3D -> when (nav) {
                "reload", "refresh" -> { { it.withState("reload" to now) } }
                else -> return Result.failure(IllegalArgumentException("That widget only supports reload."))
            }
            WidgetType.CLOCK -> return Result.failure(IllegalArgumentException("Use widget update format=time|time+date|time+seconds for the clock."))
            WidgetType.WEB, WidgetType.APP -> return Result.failure(IllegalArgumentException("Use the web tool to operate pages and apps (click, type, scroll, play)."))
        }
        DesktopBridge.mutateWidget(w.id, pushUndo = pushUndo, transform = transform)
        return Result.success("OK — ${nav.replace('_', ' ')} on \"${w.title}\"$detail.")
    }

    private fun parseSeconds(v: String?): Long {
        val s = v?.trim()?.lowercase(Locale.US) ?: return 0
        if (s.contains(':')) { val p = s.split(':').mapNotNull { it.toLongOrNull() }; return p.fold(0L) { acc, x -> acc * 60 + x } }
        val m = Regex("(\\d+)\\s*m").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val sec = Regex("(\\d+)\\s*s").find(s)?.groupValues?.get(1)?.toLongOrNull()
        return if (m > 0 || sec != null) m * 60 + (sec ?: 0L) else s.toDoubleOrNull()?.toLong() ?: 0L
    }

    private fun refresh(args: Args): Result<String> {
        val w = resolve(args) ?: return missing(args)
        WidgetRefreshEngine.refreshNow(w.id)
        return Result.success("Refreshing \"${w.title}\".")
    }
}

// ─────────────────────────────────────────────────────────────────────
// desktop
// ─────────────────────────────────────────────────────────────────────

class DesktopTool(private val context: Context) : AiTool {
    override val name = "desktop"

    override suspend fun execute(args: Args): Result<String> = withContext(Dispatchers.IO) {
        val cur = DesktopBridge.current()
        val name = args.str("name", "title", "desktop")
        when (args.action) {
            "describe", "status", "what", "list_widgets" -> Result.success(DesktopBridge.describe())
            "list" -> {
                val all = DesktopStore.list()
                Result.success(if (all.isEmpty()) "No saved desktops." else
                    "Saved desktops: " + all.joinToString(", ") { if (it.id == cur.id) "${it.name} (current)" else it.name })
            }
            "arrange", "tile", "organize", "organise", "layout", "clean_up", "cleanup", "line_up" -> {
                val focus = args.str("focus", "main", "big")?.let { DesktopBridge.resolveWidget(it) }
                val layout = args.str("layout", "style", "mode")
                var description = ""
                DesktopBridge.mutate { d ->
                    val a = Layout.arrange(d.widgets, layout, focus?.id, args.int("gap"))
                    description = a.description
                    d.copy(widgets = a.widgets)
                }
                Result.success(description + if (cur.widgets.isNotEmpty()) " Say undo to put them back." else "")
            }
            "new", "create" -> {
                val n = (name ?: "Desktop ${DesktopStore.list().size + 1}").take(32)
                DesktopBridge.saveNow()
                val d = Desktop(name = n, mode = cur.mode, theme = cur.theme)
                DesktopStore.save(d)
                DesktopBridge.replace(d)
                Result.success("Created a new empty desktop \"$n\" and switched to it.")
            }
            "save", "snapshot", "store" -> {
                if (name != null && !name.equals(cur.name, ignoreCase = true)) {
                    // Only an EXACT name reuses a saved desktop; anything else is a new one.
                    val existing = DesktopStore.findExact(name)
                    val d = cur.copy(id = existing?.id ?: java.util.UUID.randomUUID().toString().take(8), name = name.take(32),
                        updatedAt = System.currentTimeMillis())
                    DesktopStore.save(d)
                    DesktopBridge.replace(d)
                    DesktopBridge.saveNow()
                    Result.success(if (existing != null) "Saved over \"${d.name}\"." else "Saved the desktop as \"${d.name}\" — its thumbnail is in the strip.")
                } else {
                    DesktopBridge.saveNow()
                    Result.success("Saved \"${cur.name}\".")
                }
            }
            "load", "open", "switch", "show" -> {
                val meta = DesktopStore.findByName(name) ?: return@withContext Result.success(ambiguous(name))
                if (meta.id == cur.id) return@withContext Result.success("\"${meta.name}\" is already showing.")
                DesktopBridge.saveNow()
                val d = DesktopStore.load(meta.id) ?: return@withContext Result.failure(IllegalStateException("Couldn't read \"${meta.name}\"."))
                DesktopBridge.replace(d)
                Result.success("Loaded \"${d.name}\" with ${d.widgets.size} widget${if (d.widgets.size == 1) "" else "s"}.")
            }
            "delete", "remove" -> {
                val meta = DesktopStore.findByName(name) ?: return@withContext Result.success(ambiguous(name))
                if (meta.id == cur.id) {
                    val other = DesktopStore.list().firstOrNull { it.id != meta.id }?.let { DesktopStore.load(it.id) }
                        ?: DesktopBridge.defaultDesktop().also { DesktopStore.save(it) }
                    DesktopBridge.replace(other)
                }
                DesktopStore.delete(meta.id)
                DesktopBridge.catalogChanged()
                Result.success("Deleted the desktop \"${meta.name}\".")
            }
            "rename" -> {
                val n = name ?: return@withContext Result.failure(IllegalArgumentException("rename needs 'name'."))
                DesktopBridge.mutate(pushUndo = false) { it.copy(name = n.take(32)) }
                DesktopBridge.saveNow(); DesktopBridge.catalogChanged()
                Result.success("Renamed to \"$n\".")
            }
            "set_mode", "mode" -> {
                val m = args.str("mode", "name")?.lowercase(Locale.US)
                val mode = when {
                    m == null || m == "toggle" -> if (cur.mode == DesktopMode.HUD) DesktopMode.DESKTOP else DesktopMode.HUD
                    m.startsWith("hud") || m.contains("head") || m.contains("overlay") || m.contains("glance") -> DesktopMode.HUD
                    else -> DesktopMode.DESKTOP
                }
                DesktopBridge.mutate { it.copy(mode = mode) }
                Result.success("Switched to ${mode.name.lowercase(Locale.US)} mode.")
            }
            "undo", "revert" -> Result.success(if (DesktopBridge.undo()) "Undone." else "Nothing to undo.")
            "clear", "empty" -> {
                DesktopBridge.mutate { it.copy(widgets = emptyList()) }
                Result.success("Cleared all widgets from \"${cur.name}\". Say undo to restore.")
            }
            "locate", "where", "where_am_i", "location" -> {
                val fix = LocationSource.current(context)
                    ?: return@withContext Result.success("I can't determine your location right now — ${com.tapgem.app.core.location.PhoneGps.whyNot(context)}.")
                val label = Geocoder.reverse(fix.lat, fix.lon)
                val hint = if (fix.isPrecise) "" else " For a precise position, connect your phone in the RayNeo app (its GPS is relayed to the glasses)."
                Result.success("You're ${LocationSource.describe(fix, label)}. Coordinates ${fix.latLon()}.$hint")
            }
            "usage", "tokens", "session_info", "which_model", "model" -> Result.success(com.tapgem.app.core.session.SessionStats.report())
            "phone_gps", "check_phone_gps", "gps_status" -> {
                // Troubleshooting: ask the launcher for the phone stream and report what comes back.
                val pg = com.tapgem.app.core.location.PhoneGps
                // The phone needs ~10 s to begin pushing after it is asked; wait long enough for the first fix.
                val fix = pg.awaitFix(context, timeoutMs = 15_000L, maxAgeMs = 30_000L)
                Result.success(if (fix != null) "Phone GPS is flowing: ${fix.latLon()}, about ${fix.accuracyM.toInt()} m."
                    else "No phone GPS yet — ${pg.whyNot(context)}. Launcher status: ${pg.lastStatus} ${pg.lastStatusMessage ?: ""}; BLE link ${if (pg.isPhoneConnected(context)) "up" else "down"}.")
            }
            "apps", "app_drawer", "widgets" -> {
                com.tapgem.app.core.bridge.LibraryBridge.show(com.tapgem.app.core.bridge.LibraryBridge.Drawer.APPS)
                val apps = com.tapgem.app.core.library.Library.apps().joinToString(", ") { it.title }.ifBlank { "none yet" }
                Result.success("Opened the apps & widgets drawer. Apps: $apps. Widgets: ${com.tapgem.app.core.library.Library.KINDS.joinToString(", ") { it.label }}. Sites: ${com.tapgem.app.core.library.Library.SITES.joinToString(", ") { it.label }}.")
            }
            "wallpapers", "themes", "wallpaper_drawer" -> {
                com.tapgem.app.core.bridge.LibraryBridge.show(com.tapgem.app.core.bridge.LibraryBridge.Drawer.WALLPAPERS)
                val wps = com.tapgem.app.core.library.Library.wallpapers().joinToString(", ") { it.title }.ifBlank { "none yet" }
                Result.success("Opened the wallpapers & themes drawer. Wallpapers: $wps. Themes: ${Themes.ALL.joinToString(", ") { it.name }} (current ${DesktopBridge.current().theme.name}).")
            }
            "close_drawers", "hide_drawers" -> {
                for (d in com.tapgem.app.core.bridge.LibraryBridge.Drawer.values()) com.tapgem.app.core.bridge.LibraryBridge.show(d, false)
                Result.success("Drawers closed.")
            }
            else -> Result.failure(IllegalArgumentException("Unknown desktop action '${args.action}'."))
        }
    }

    private fun ambiguous(name: String?): String {
        val c = DesktopStore.candidates(name)
        return if (c.size > 1) "Which one — " + c.joinToString(" or ") { "\"${it.name}\"" } + "?"
        else "No saved desktop named \"$name\". " + listNames()
    }

    private fun listNames() = "Saved: " + DesktopStore.list().joinToString(", ") { it.name }.ifBlank { "none" }
}

// ─────────────────────────────────────────────────────────────────────
// theme
// ─────────────────────────────────────────────────────────────────────

class ThemeTool : AiTool {
    override val name = "theme"

    override suspend fun execute(args: Args): Result<String> {
        return when (args.action) {
            "list" -> Result.success("Themes: " + Themes.ALL.joinToString(", ") { it.name } + ". Current: ${DesktopBridge.current().theme.name}.")
            "set", "apply", "use", "change", "update" -> {
                val cur = DesktopBridge.current().theme
                val preset = Themes.byName(args.str("name", "preset", "theme"))
                val base = preset ?: cur
                val unknown = listOf(args.str("accent", "accent_color"), args.str("panel", "panel_color", "background", "bg_color"), args.str("text_color", "text"))
                    .filter { ColorUtil.isUnknown(it) }
                val t = Theme(
                    name = preset?.name ?: args.str("name")?.take(20) ?: cur.name,
                    accent = ColorUtil.parse(args.str("accent", "accent_color")) ?: base.accent,
                    panel = ColorUtil.parse(args.str("panel", "panel_color", "background", "bg_color")) ?: base.panel,
                    text = ColorUtil.parse(args.str("text_color", "text")) ?: base.text,
                    fontScale = args.float("font_scale", "text_scale")?.coerceIn(0.6f, 2.2f) ?: base.fontScale,
                    corner = args.int("corner_radius", "corner") ?: base.corner,
                    texture = args.str("texture")?.lowercase(Locale.US)?.let { if (it in setOf("none", "flat", "off")) null else it } ?: base.texture
                )
                if (preset == null && t == cur && unknown.isNotEmpty())
                    return Result.success("I don't know the colour${if (unknown.size > 1) "s" else ""} ${unknown.joinToString(", ") { "\"$it\"" }} — try a hex code or a common colour name. Presets: ${Themes.ALL.joinToString(", ") { it.name }}.")
                DesktopBridge.mutate { it.copy(theme = t) }
                val warn = if (unknown.isEmpty()) "" else " (Ignored unknown colour ${unknown.joinToString(", ") { "\"$it\"" }}.)"
                Result.success("Theme set to ${t.name}.$warn")
            }
            else -> Result.failure(IllegalArgumentException("Unknown theme action '${args.action}'."))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// wallpaper
// ─────────────────────────────────────────────────────────────────────

class WallpaperTool(private val context: Context) : AiTool {
    override val name = "wallpaper"

    /**
     * The picture a page is actually showing, if it is showing one.
     *
     * Capturing the window was the wrong default for this: it keeps the site's own furniture —
     * search bar, labels, buttons — baked into the wallpaper, which is never what "use this image"
     * meant. So ask the page instead. The biggest image actually drawn on screen is the one being
     * looked at; anything under 120px square is an icon or a thumbnail in a grid, not the subject.
     */
    private suspend fun pageImage(w: Widget): String? {
        if (!w.type.isWebLike) return null
        val js = """
            (function(){
              var best=null,area=0,i,im,r;
              var all=document.querySelectorAll('img');
              for(i=0;i<all.length;i++){
                im=all[i]; r=im.getBoundingClientRect();
                if(r.width<120||r.height<120) continue;
                if(r.bottom<0||r.top>innerHeight||r.right<0||r.left>innerWidth) continue;
                if(r.width*r.height>area){area=r.width*r.height;best=im;}
              }
              if(best) return best.currentSrc||best.src||'';
              /* nothing as an <img>: a hero photo is often a background instead */
              var els=document.querySelectorAll('*'),j,bg,m,el,rr,a2=0,found='';
              for(j=0;j<els.length&&j<400;j++){
                el=els[j]; rr=el.getBoundingClientRect();
                if(rr.width<200||rr.height<200) continue;
                bg=getComputedStyle(el).backgroundImage||'';
                m=bg.match(/url\(["']?(.*?)["']?\)/);
                if(m&&rr.width*rr.height>a2){a2=rr.width*rr.height;found=m[1];}
              }
              return found;
            })()
        """.trimIndent()
        val raw = runCatching {
            WebCommandBus.execute(w.id, WebCommandBus.Command("eval", mapOf("js" to js)), 10_000L)
        }.getOrNull().orEmpty().trim().trim('"')
        return raw.takeIf { it.startsWith("http") || it.startsWith("data:image") }
    }

    /**
     * Paint a window as it currently looks into the wallpaper folder. Captured at the canvas width
     * and the window's own proportions, so nothing is letterboxed; the wallpaper view crops to fill
     * from there. A window smaller than the canvas is being enlarged, so it will look soft.
     */
    private fun captureWindow(w: Widget): File? = runCatching {
        val shot = DesktopBridge.windowShot ?: return null
        val h = (Canvas.WIDTH.toLong() * w.h / maxOf(1, w.w)).toInt().coerceIn(120, 2048)
        val bmp = shot(w.id, Canvas.WIDTH, h) ?: return null
        val f = File(DesktopStore.wallpapersDir, "wp_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        f.takeIf { it.length() > 0 }
    }.onFailure { Log.w("WallpaperTool", "window capture failed: ${it.message}") }.getOrNull()

    /**
     * Take a copy of an existing picture into the wallpaper folder. A copy rather than a reference
     * because the original may be a download, which is treated as scratch and swept within
     * minutes; the wallpaper folder only sweeps what nothing refers to.
     */
    private fun adoptImage(src: String): File? = runCatching {
        val bytes = if (src.startsWith("data:image")) {
            android.util.Base64.decode(src.substringAfter("base64,", ""), android.util.Base64.DEFAULT)
        } else if (WidgetOps.isUrl(src)) {
            (java.net.URL(src).openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 20_000; readTimeout = 20_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TapGem/1.0 (RayNeo X3 Pro)")
                if (responseCode !in 200..299) { disconnect(); return null }
                inputStream.use { it.readBytes() }.also { disconnect() }
            }
        } else File(src.removePrefix("file://")).takeIf { it.isFile }?.readBytes() ?: return null
        if (bytes.isEmpty()) return null
        val ext = if (src.startsWith("data:")) src.substringAfter("data:image/").substringBefore(';').take(4)
            else src.substringAfterLast('.', "").lowercase(Locale.US).takeIf { it.length in 2..4 } ?: "png"
        File(DesktopStore.wallpapersDir, "wp_${System.currentTimeMillis()}.$ext").apply { writeBytes(bytes) }
    }.onFailure { Log.w("WallpaperTool", "could not adopt $src: ${it.message}") }.getOrNull()

    override suspend fun execute(args: Args): Result<String> = withContext(Dispatchers.IO) {
        when (args.action) {
            "clear", "none", "remove" -> {
                DesktopBridge.mutate { it.copy(wallpaper = Wallpaper()) }
                Result.success("Wallpaper cleared.")
            }
            "set", "paint", "change", "generate", "create" -> {
                val desc = args.str("description", "prompt", "text", "name")
                val rawColors = args.str("colors", "color")?.split(',', ';', '/')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                val colors = rawColors.mapNotNull { ColorUtil.parse(it) }
                val unknown = rawColors.filter { ColorUtil.isUnknown(it) }
                val kind = args.str("kind", "type")?.lowercase(Locale.US)

                // Either an explicit picture to use, or — when nothing else was asked for and a
                // picture is the window in focus — that one. Naming a description, colours or a
                // kind means the request was for something new, so none of this applies.
                val ref = args.str("image", "from", "source", "photo", "picture", "use_image")
                val adoptedFrom: Widget? = when {
                    ref != null && !WidgetOps.isUrl(ref) && !ref.startsWith("/") ->
                        DesktopBridge.resolveWidget(ref)
                    ref == null && desc == null && colors.isEmpty() && kind == null ->
                        DesktopBridge.activeWidgetId?.let { DesktopBridge.current().widget(it) }
                    else -> null
                }
                // A picture widget has a file worth using at full quality. Anything else — a page,
                // an app, a map — has no file, but it does have what it is showing, so paint the
                // window itself. "Make this my wallpaper" then means the same thing whatever the
                // window happens to be.
                val adopted: String? = when {
                    ref != null && (WidgetOps.isUrl(ref) || ref.startsWith("/")) -> ref
                    adoptedFrom?.type == WidgetType.IMAGE -> adoptedFrom.source.takeIf { it.isNotBlank() }
                    // A page: take the picture it is showing. Only if it has none is a picture of
                    // the window itself the best available answer.
                    adoptedFrom != null -> pageImage(adoptedFrom) ?: captureWindow(adoptedFrom)?.absolutePath
                    else -> null
                }
                val adoptedName = adoptedFrom?.title?.takeIf { it.isNotBlank() }
                val wp: Wallpaper
                var note = if (unknown.isEmpty()) "" else " (Ignored unknown colour ${unknown.joinToString(", ") { "\"$it\"" }}.)"
                when {
                    kind == "none" -> wp = Wallpaper()
                    (kind == "color" || (kind == null && desc == null)) && colors.size == 1 ->
                        wp = Wallpaper(WallpaperKind.COLOR, colors.take(1), description = desc.orEmpty())
                    (kind == "gradient" || (kind == null && desc == null)) && colors.size >= 2 ->
                        wp = Wallpaper(WallpaperKind.GRADIENT, colors, description = desc.orEmpty())
                    // A picture that already exists becomes the wallpaper as it is. "Make this my
                    // wallpaper" should keep the photo; only "paint a wallpaper based on this" asks
                    // for a new one, and that arrives as a description instead.
                    adopted != null -> {
                        val already = adopted.startsWith(DesktopStore.wallpapersDir.absolutePath)
                        val f = (if (already) File(adopted) else adoptImage(adopted))
                            ?: return@withContext Result.success("Couldn't read that image to use as a wallpaper.")
                        wp = Wallpaper(WallpaperKind.IMAGE, imagePath = f.absolutePath,
                            description = adoptedName ?: "your picture")
                    }
                    desc != null -> {
                        HudStateBridge.notice("Painting wallpaper…")
                        val prompt = "Wallpaper for a 640x480 landscape AR-glasses display. Rich, dark-friendly, high contrast, " +
                            "no text, no watermarks, no borders. Scene: $desc"
                        val png = GeminiRest.generateImage(context, prompt)
                        wp = png.fold(
                            onSuccess = { bytes ->
                                val f = File(DesktopStore.wallpapersDir, "wp_${System.currentTimeMillis()}.png")
                                f.writeBytes(bytes)
                                Wallpaper(WallpaperKind.IMAGE, imagePath = f.absolutePath, description = desc)
                            },
                            onFailure = {
                                Log.w("WallpaperTool", "image gen failed: ${it.message}")
                                note += " (Image painting failed — used a gradient from the description instead.)"
                                Wallpaper(WallpaperKind.GRADIENT, gradientFor(desc), description = desc)
                            }
                        )
                        HudStateBridge.notice(null)
                    }
                    unknown.isNotEmpty() -> return@withContext Result.success("I don't know the colour ${unknown.joinToString(", ") { "\"$it\"" }} — try hex codes or common names, or describe a scene to paint.")
                    else -> return@withContext Result.failure(IllegalArgumentException("wallpaper set needs 'description' or 'colors'."))
                }
                DesktopBridge.mutate { it.copy(wallpaper = wp, mode = if (wp.kind == WallpaperKind.NONE) it.mode else DesktopMode.DESKTOP) }
                Result.success(when (wp.kind) {
                    WallpaperKind.NONE -> "Wallpaper cleared."
                    WallpaperKind.IMAGE -> (if (adopted != null)
                        "Using ${adoptedName ?: "that picture"} as the wallpaper, as it is."
                        else "Painted a new wallpaper: $desc.") + " Desktop mode is on.$note"
                    else -> "Wallpaper set.$note Desktop mode is on."
                })
            }
            else -> Result.failure(IllegalArgumentException("Unknown wallpaper action '${args.action}'."))
        }
    }

    /** Deterministic pleasant two-tone gradient from the words. */
    private fun gradientFor(desc: String): List<Int> {
        val h = desc.lowercase(Locale.US).hashCode()
        val hue1 = ((h and 0xFFFF) % 360).toFloat()
        val hue2 = (hue1 + 40f + ((h ushr 16) % 80)) % 360f
        return listOf(android.graphics.Color.HSVToColor(floatArrayOf(hue1, 0.75f, 0.45f)),
            android.graphics.Color.HSVToColor(floatArrayOf(hue2, 0.85f, 0.12f)))
    }
}

// ─────────────────────────────────────────────────────────────────────
// app_builder — vibe-coded mini apps
// ─────────────────────────────────────────────────────────────────────

class AppBuilderTool(private val context: Context) : AiTool {
    override val name = "app_builder"

    /** `smart_aquarium__v2_1789…html` / `pomodoro_1789…html` / `bm_5cf76cb6_checkers__v1_….html` → "smart_aquarium" / "pomodoro" / "checkers". */
    private fun appBase(f: File): String = f.nameWithoutExtension.replace(Regex("^(bm_[0-9a-f]{8}_)+"), "").substringBefore("__v").replace(Regex("_\\d{10,}$"), "")

    /** Saved app files by display name (newest version of each). Apps nothing holds are garbage-collected a month after they were last touched. */
    private fun savedApps(): List<Pair<String, File>> = (DesktopStore.appsDir.listFiles { f -> f.extension == "html" } ?: emptyArray())
        .groupBy { appBase(it) }
        .map { (base, files) -> base.split('_').filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } to files.maxByOrNull { it.lastModified() }!! }
        .sortedByDescending { it.second.lastModified() }

    private fun findSavedApp(name: String): Pair<String, File>? {
        val q = name.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").trim()
        val qw = q.split(Regex("\\s+")).filter { it.length > 2 && it !in setOf("app", "game", "the") }.toSet()
        return savedApps().map { it to it.first.lowercase(Locale.US) }
            .filter { (_, n) -> n == q || n.contains(q) || q.contains(n) || qw.isNotEmpty() && n.split(' ').count { it in qw } >= maxOf(1, qw.size - 1) }
            .maxByOrNull { it.first.second.lastModified() }?.first
    }

    override suspend fun execute(args: Args): Result<String> = withContext(Dispatchers.IO) {
        when (args.action) {
            "list", "saved" -> return@withContext Result.success(savedApps().takeIf { it.isNotEmpty() }
                ?.joinToString(", ", prefix = "Saved apps: ") { it.first } ?: "No saved apps yet.")
            "open", "load", "restore" -> {
                val want = args.str("name", "title", "app") ?: return@withContext Result.failure(IllegalArgumentException("open needs 'name'."))
                val (title, file) = findSavedApp(want) ?: return@withContext Result.failure(IllegalStateException(
                    "No saved app matches \"$want\"." + (savedApps().takeIf { it.isNotEmpty() }?.let { " Saved apps: ${it.joinToString { a -> a.first }}." } ?: "") +
                        " Ask the user whether to build it before calling app_builder create."))
                return@withContext WidgetOps.add(context, args, forcedType = WidgetType.APP, forcedSource = file.absolutePath, forcedTitle = title)
                    .map { "Opened the saved app \"$title\"." }
            }
        }
        val appName = args.str("name", "title", "app") ?: return@withContext Result.failure(IllegalArgumentException("app_builder needs 'name'."))
        val desc = args.str("description", "prompt", "text", "change") ?: return@withContext Result.failure(IllegalArgumentException("app_builder needs 'description'."))
        when (args.action) {
            "create", "build", "make", "new" -> {
                // "Open the golf game" is not "build me a golf game": something that already exists is
                // opened, and when nothing does the user is asked first. Judged from what the user
                // actually said, not from how the model read it.
                val rebuild = args.bool("rebuild", "force") == true
                if (!rebuild) {
                    findSavedApp(appName)?.let { (title, file) ->
                        return@withContext WidgetOps.add(context, args, forcedType = WidgetType.APP, forcedSource = file.absolutePath, forcedTitle = title)
                            .map { "There is already a saved app \"$title\" — opened that instead of building a new one. Say 'rebuild it' for a fresh version." }
                    }
                    com.tapgem.app.core.store.Bookmarks.find(appName)?.takeIf { it.type == WidgetType.APP }?.let { b ->
                        val placed = BookmarkTool.place(b)
                        return@withContext Result.success("\"${placed.title}\" was bookmarked — opened the bookmark instead of building a new app.")
                    }
                    if (!com.tapgem.app.core.session.ConversationContext.wantsCreation()) {
                        return@withContext Result.success("Nothing called \"$appName\" exists yet — no window, bookmark or saved app. Do NOT build it now: " +
                            "tell the user it doesn't exist and ask whether they'd like it created as a new app. Call app_builder create again only after they say yes.")
                    }
                }
                HudStateBridge.notice("Building $appName…")
                val html = GeminiRest.generateText(context, "App name: $appName\nWhat it should do: $desc", system = APP_SYSTEM)
                    .map(::cleanHtml).getOrElse { HudStateBridge.notice(null); return@withContext Result.failure(IllegalStateException("Couldn't generate the app: ${it.message}")) }
                val f = File(DesktopStore.appsDir, "${slug(appName)}__v1_${System.currentTimeMillis()}.html")
                f.writeText(html)
                HudStateBridge.notice(null)
                WidgetOps.add(context, args, forcedType = WidgetType.APP, forcedSource = f.absolutePath, forcedTitle = appName)
                    .map { "Built the app \"$appName\" and placed it on the desktop." }
            }
            "update", "change", "edit", "modify", "fix" -> {
                val w = DesktopBridge.resolveWidget(appName, "app")?.takeIf { it.type == WidgetType.APP }
                    ?: return@withContext Result.success("No app named \"$appName\" on this desktop.")
                val current = runCatching { File(w.source).readText() }.getOrDefault("")
                HudStateBridge.notice("Updating ${w.title}…")
                val html = GeminiRest.generateText(context,
                    "Here is the current app HTML:\n\n$current\n\nChange request: $desc\n\nReturn the COMPLETE updated HTML document.",
                    system = APP_SYSTEM).map(::cleanHtml).getOrElse { HudStateBridge.notice(null); return@withContext Result.failure(IllegalStateException("Couldn't update the app: ${it.message}")) }
                // Versioned: a new file each update, so undo can bring the previous version back.
                val version = Regex("__v(\\d+)_").find(File(w.source).name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val f = File(DesktopStore.appsDir, "${slug(w.title)}__v${version + 1}_${System.currentTimeMillis()}.html")
                f.writeText(html)
                HudStateBridge.notice(null)
                DesktopBridge.mutateWidget(w.id) { it.copy(source = f.absolutePath).withState("reload" to System.currentTimeMillis().toString()) }
                Result.success("Updated the app \"${w.title}\" (version ${version + 1}). Say undo to go back to the previous version.")
            }
            else -> Result.failure(IllegalArgumentException("Unknown app_builder action '${args.action}'."))
        }
    }

    private fun cleanHtml(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("```html").removePrefix("```HTML").removePrefix("```").removeSuffix("```").trim()
        val i = s.indexOf("<!doctype", ignoreCase = true).takeIf { it >= 0 } ?: s.indexOf("<html", ignoreCase = true)
        if (i > 0) s = s.substring(i)
        if (!s.contains("<html", ignoreCase = true)) s = "<!doctype html><html><head><meta charset=\"utf-8\"></head><body style=\"background:#000;color:#eee\">$s</body></html>"
        return s
    }

    private fun slug(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifBlank { "app" }

    companion object {
        private const val APP_SYSTEM =
            "You write complete, self-contained single-file HTML mini apps for a 640x480 AR-glasses display. " +
                "Constraints: dark or black background, bright high-contrast text (min 14px), large touch targets " +
                "(min 36px) with visible text labels on buttons, fluid layout that fills its container (use % / vw / " +
                "vh, no fixed 640px assumptions — the window may be smaller), no scrolling if avoidable, no external " +
                "resources, no fonts from the web, no alert()/prompt()/confirm(), vanilla HTML/CSS/JS only, " +
                "everything inline. There is NO keyboard: never rely on typed input; use buttons, sliders and taps. " +
                "The device runs on a small battery: NO infinite CSS animations, glows, pulses or " +
                "requestAnimationFrame loops — update visuals only when state changes or at most once per second " +
                "(setInterval >= 1000 ms); transitions on user actions are fine. Physics or motion toys may animate " +
                "with requestAnimationFrame ONLY while something is actually moving and must stop the loop at rest. " +
                "Layout: a flex column with a <canvas> needs `canvas{flex:1 1 0;min-height:0}` or it overflows the " +
                "window. Anything the user might add by voice (a task, a note, a search) needs a real <input> with a " +
                "clear placeholder plus a button, because the assistant types into fields by their placeholder. " +
                "Must work offline in Chrome 95. A tiny host bridge exists as window.TapGem with " +
                "notify(text) to flash a one-line message on the glasses, setTitle(text) to rename the window, eco() " +
                "(true on battery — halve any animation rate), " +
                "save(key, value) and load(key) (strings) for persistence — guard every call with " +
                "`if (window.TapGem)`. PERSIST THE WHOLE STATE, not just scores: after every state change call " +
                "TapGem.save('state', JSON.stringify(fullState)) and on load restore it from TapGem.load('state') " +
                "(fall back to a fresh start when empty), so the window resumes exactly where it was — mid-game " +
                "board, timer, notes — when the desktop reloads or the user bookmarks it. Output ONLY the HTML " +
                "document — no markdown fences, no commentary."
    }
}

// ─────────────────────────────────────────────────────────────────────
// media
// ─────────────────────────────────────────────────────────────────────

class MediaTool(private val context: Context) : AiTool {
    override val name = "media"

    override suspend fun execute(args: Args): Result<String> = withContext(Dispatchers.IO) {
        val type = WidgetType.parse(args.str("type"))
        val query = args.str("query", "name", "search", "text")
        when (args.action) {
            "find", "search", "list" -> {
                val hits = MediaScanner.find(context, query, type)
                if (hits.isEmpty()) {
                    val hint = if (!MediaScanner.hasAllFilesAccess()) " Files outside Pictures/Movies/Music need All-files access, or push them to the media folder." else ""
                    Result.success("No files match \"${query ?: type?.name ?: ""}\".$hint")
                } else Result.success("Matches:\n" + hits.mapIndexed { i, h ->
                    "${i + 1}. ${h.name} (${h.type.name.lowercase(Locale.US)}, ${h.sizeBytes / 1024} KB) path=${h.path}"
                }.joinToString("\n"))
            }
            "open", "show", "play", "add" -> {
                val path = args.str("path", "file")
                if (path != null) WidgetOps.add(context, args, forcedType = type ?: WidgetType.forExtension(path.substringAfterLast('.', "")), forcedSource = path)
                else {
                    val hit = MediaScanner.find(context, query, type).firstOrNull()
                    if (hit != null) WidgetOps.add(context, args, forcedType = hit.type, forcedSource = hit.path)
                    // Nothing on the glasses by that name: for a 3D model, try fetching one rather
                    // than just reporting failure — MediaScanner never looks past local storage.
                    else if (type == WidgetType.MODEL3D && !query.isNullOrBlank()) fetchModel(context, query, args)
                    // Likewise a book: "open Frankenstein" should get you Frankenstein whether or
                    // not anybody pushed it over adb first.
                    else if ((type == WidgetType.EPUB || looksLikeABook(args)) && !query.isNullOrBlank())
                        fetchBook(context, query, args)
                    else Result.success("No files match \"${query ?: ""}\".")
                }
            }
            "search_online", "find_model", "fetch_model" -> {
                if (query.isNullOrBlank()) return@withContext Result.success("What model?")
                fetchModel(context, query, args)
            }
            "fetch_book", "find_book", "download_book", "get_book", "read_book" -> {
                if (query.isNullOrBlank()) return@withContext Result.success("Which book?")
                fetchBook(context, query, args)
            }
            else -> Result.failure(IllegalArgumentException("Unknown media action '${args.action}'."))
        }
    }

    /**
     * Poly Pizza, on request: search, download the best match, add it. Gated on a key the user
     * has to obtain themselves (see ModelStore) — with no key this says so plainly instead of a
     * bare "not found", since the two cases mean different things to fix.
     */
    /** Words that mean the thing being asked for is a book, when no type was given. */
    private fun looksLikeABook(args: Args): Boolean {
        val said = args.raw.values.joinToString(" ").lowercase(Locale.US)
        return listOf("book", "ebook", "epub", "novel", "gutenberg", "read").any { it in said }
    }

    /**
     * Fetch a book from Project Gutenberg and open it. Public domain in the US and free to
     * download, which is why this archive and not a bookshop; the file lands in the media folder,
     * so asking for it again opens the copy already there.
     */
    private suspend fun fetchBook(context: Context, query: String, args: Args): Result<String> {
        val hits = com.tapgem.app.core.media.BookStore.search(query)
        if (hits.isEmpty()) return Result.success("Project Gutenberg has nothing matching \"$query\".")
        // Gutendex already sorts by popularity, which settles most bare titles; a real title match
        // still beats a popular book that merely mentions the words.
        val q = query.trim().lowercase(Locale.US)
        val pick = hits.maxWithOrNull(compareBy({ b ->
            val t = b.title.lowercase(Locale.US)
            when {
                t == q -> 4
                t.startsWith(q) || t.substringBefore(';').trim() == q -> 3
                t.contains(q) -> 2
                else -> 1
            }
        }, { b -> b.downloads })) ?: hits.first()
        val file = com.tapgem.app.core.media.BookStore.fetch(context, pick)
            ?: return Result.success("Found \"${pick.title}\" on Project Gutenberg but the download failed.")
        WidgetOps.add(context, args, forcedType = WidgetType.EPUB, forcedSource = file.absolutePath,
            forcedTitle = pick.title.substringBefore(';').trim().take(40))
        val by = if (pick.author.isNotBlank()) " by ${pick.author}" else ""
        return Result.success("Opened \"${pick.title.substringBefore(';').trim()}\"$by from Project Gutenberg. " +
            "Say \"read it to me from the beginning\" to have it read aloud.")
    }

    private suspend fun fetchModel(context: Context, query: String, args: Args): Result<String> {
        if (!com.tapgem.app.core.media.ModelStore.hasKey()) {
            return Result.success("I don't have a 3D-model source configured — only what is already on the glasses. " +
                "Adding one needs a free Poly Pizza API key on the device (poly.pizza), which I can't sign up for myself.")
        }
        // Most of the archive — everything inherited from Google Poly — is CC-BY, so searching CC0
        // only reports "nothing found" for models that plainly exist. Search everything and rank by
        // how well the title actually answers the question, because a CC0 "Astronaut" is not a
        // better answer than a CC-BY "International Space Station". Licence only breaks a tie.
        val q = query.trim().lowercase()
        val hits = com.tapgem.app.core.media.ModelStore.search(query, ccbyToo = true, limit = 12)
        val pick = hits.maxWithOrNull(compareBy({ m ->
            val t = m.title.trim().lowercase()
            when {
                t == q -> 4
                t.contains(q) || q.contains(t) -> 3
                q.split(' ').count { it.length > 2 && t.contains(it) } > 0 -> 2
                else -> 1
            }
        }, { m -> if (m.licence.startsWith("CC0", true)) 1 else 0 }))
            ?: return Result.success("No model matched \"$query\" on Poly Pizza.")
        val file = com.tapgem.app.core.media.ModelStore.fetch(pick)
            ?: return Result.success("Found \"${pick.title}\" but the download failed.")
        WidgetOps.add(context, args, forcedType = WidgetType.MODEL3D, forcedSource = file.absolutePath, forcedTitle = pick.title)
        // A licence on the upload does not license a copyrighted design it happens to depict —
        // said once, plainly, rather than presented as settled.
        val note = if (pick.title.lowercase() != query.trim().lowercase())
            " (closest match — if this depicts something copyrighted, the file's licence covers the uploader's work, not that design.)" else ""
        val by = if (pick.licence.startsWith("CC-BY", true) && pick.creator.isNotBlank()) " by ${pick.creator}" else ""
        return Result.success("Added \"${pick.title}\"$by (${pick.licence}) from Poly Pizza.$note")
    }
}

// ─────────────────────────────────────────────────────────────────────
// web — operate pages and apps like a user would
// ─────────────────────────────────────────────────────────────────────

class WebTool(private val context: Context) : AiTool {
    override val name = "web"

    companion object {
        /** A page this short is its own summary: hand it over untouched, no second call. */
        private const val SHORT_PAGE = 1_200

        /** "Read this" returns the words themselves up to here — a few minutes of speech. */
        private const val VERBATIM_LIMIT = 6_000

        /** How much of a book to hand the reader at once; roughly 100k tokens. */
        private const val EPUB_LIMIT = 400_000
    }

    private val actions = setOf("search", "inspect", "read", "read_aloud", "stop_reading", "click", "type", "press", "scroll", "zoom", "play", "pause", "url", "back", "forward", "reload", "eval")

    override suspend fun execute(args: Args): Result<String> {
        val action = when (args.action) {
            "find", "query", "lookup" -> "search"
            "zoom_in", "zoom_out", "pinch" -> "zoom"
            "tap", "press_button", "select" -> "click"
            "enter", "fill", "input", "write" -> "type"
            "key", "keypress" -> "press"
            "open", "go", "navigate", "goto" -> "url"
            "refresh" -> "reload"
            "look", "list", "elements", "see" -> "inspect"
            "text", "content", "summarize" -> "read"
            "read_out", "read_to_me", "narrate", "aloud" -> "read_aloud"
            "stop_read", "stop_aloud", "quiet" -> "stop_reading"
            "stop" -> "pause"
            "resume", "start" -> "play"
            else -> args.action
        }
        if (action !in actions) return Result.failure(IllegalArgumentException("Unknown web action '${args.action}'. Use search, inspect, read, click, type, press, scroll, play, pause, url, back, forward, reload."))
        val named = args.str("target", "id", "title", "widget", "name") != null
        val w = resolveTarget(args) ?: return Result.success("No web page or app is open. Add one with widget action=add type=web url=…")
        // eval reaches a book only while it is showing the read-along page (our own, scripted);
        // on the chapter itself scripting is off and the window answers "Not available".
        if (!w.type.isWebLike && !(w.type == WidgetType.EPUB && action in setOf("scroll", "read", "read_aloud", "stop_reading", "eval")) && !(w.type == WidgetType.MAP && action in setOf("scroll", "press", "click", "zoom", "eval"))) {
            return Result.success("\"${w.title}\" is a ${w.type.name.lowercase(Locale.US)} widget, not a web page. Use widget action=navigate for it.")
        }
        DesktopBridge.setActive(w.id)
        if (action == "url") {
            val u = args.str("url", "value", "text") ?: return Result.failure(IllegalArgumentException("url needs 'url'."))
            val norm = WidgetOps.normalizeUrl(u)
            if (w.type == WidgetType.WEB) {
                // Sent to a different site: a hand-written title ("Radio Garden") would now lie, so
                // fall back to the new host and let the page's own navigation keep it honest.
                val oldHost = hostOf(w.source); val newHost = hostOf(norm)
                val sameSite = oldHost.isBlank() || newHost.isBlank() || oldHost == newHost
                val title = args.str("new_title") ?: if (sameSite) w.title else newHost.take(32)
                DesktopBridge.mutateWidget(w.id) { it.copy(source = norm, title = title).withState("reload" to System.currentTimeMillis().toString()) }
            }
            val r = WebCommandBus.execute(w.id, WebCommandBus.Command("url", mapOf("url" to norm)))
            return Result.success(r)
        }
        var passthrough = args.raw.filterKeys { it != "action" }
        if (action == "zoom" && args.action in setOf("zoom_in", "zoom_out")) passthrough = passthrough + ("direction" to args.action.removePrefix("zoom_"))
        // The tile map (TapGem navigation) zooms through its own state, not a page.
        if (action == "zoom" && w.type == WidgetType.MAP) {
            val dir = (args.str("direction", "value") ?: "in").lowercase(Locale.US)
            return WidgetTool(context).execute(Args(mapOf("action" to "navigate", "id" to w.id, "nav" to (if (dir.startsWith("out")) "out" else "in")) + (args.str("amount", "levels")?.let { mapOf("value" to it) } ?: emptyMap())))
        }
        if (action == "read") return Result.success(readPage(w, args))
        if (action == "stop_reading") {
            com.tapgem.app.core.read.BookReader.stop()
            // The book was showing the read-along; give it back its chapter.
            DesktopBridge.current().widgets.filter { it.state["readAlong"] == "1" }
                .forEach { b -> DesktopBridge.mutateWidget(b.id) { it.withState("readAlong" to "") } }
            return Result.success("Stopped reading.")
        }
        if (action == "read_aloud") {
            // The app reads, not the conversation: the Live model ends a turn after a paragraph.
            // The whole book, not the chapter in view: reading aloud should carry on past a
            // chapter end rather than stopping there, and the first "chapter" of an epub is
            // usually a title page that finishes in five seconds.
            // A book is read as one run of text with the chapter offsets alongside, so the reader
            // can open the chapter it has reached instead of leaving the page where it started.
            val book = if (w.type == WidgetType.EPUB) epubBook(w) else null
            val text = book?.first
                ?: WebCommandBus.execute(w.id, WebCommandBus.Command("read",
                    mapOf("cap" to "400000")), timeoutMs = 30_000L)
            if (text.isBlank()) return Result.success("Nothing to read in \"${w.title}\".")
            val resume = args.bool("continue", "more", "next") == true
            val from = (if (resume) w.state["readAt"]?.toIntOrNull() else args.int("from")) ?: 0
            // Read-along happens in its own window: it shows the exact words being spoken and
            // lights each one as it is said, which is the point for anyone who needs to see where
            // they are. The book's own window keeps its place and is left alone.
            LiveApps.install(context, "reader.html", LiveApps.READER)
            // A book reads in the book's own window. Two windows meant looking away from the book
            // to follow the words in it, and the one you needed could sit behind the other.
            // Anything that is not a book keeps its own page, so the read-along gets a window.
            val reader = if (w.type == WidgetType.EPUB) {
                LiveApps.window(LiveApps.READER)?.let { stale ->
                    DesktopBridge.mutate { d -> d.copy(widgets = d.widgets.filterNot { it.id == stale.id }) }
                }
                DesktopBridge.mutateWidget(w.id) { it.withState("readAlong" to "1") }
                w.id
            } else LiveApps.window(LiveApps.READER)?.id ?: run {
                LiveApps.ensureWindow(context, "reader.html", LiveApps.READER, "Read-along",
                    Args(mapOf("w" to "420", "h" to "330", "anchor" to "bottom right")))
                LiveApps.window(LiveApps.READER)?.id
            }
            // A read-along in its own window goes in front of the page it is reading: run 3's lit
            // word was completely hidden behind the book's cover art in one frame, which defeats
            // the point. (A book reads in its own window and is already where the reader is looking.)
            if (reader != null && reader != w.id) DesktopBridge.mutate(pushUndo = false) { d ->
                d.widget(reader)?.let { d.replaceWidget(it.copy(z = (d.widgets.maxOfOrNull { o -> o.z } ?: 0) + 1)) } ?: d
            }
            com.tapgem.app.core.read.BookReader.start(context, reader, text, from, w.title,
                onProgress = { at, _ -> DesktopBridge.mutateWidget(w.id) { it.withState("readAt" to at.toString()) } },
                onDone = { msg -> HudStateBridge.notice(msg) })
            return Result.success("Reading \"${w.title}\" aloud now, with the words highlighted in the " +
                "read-along window — say stop to end it. Do not read anything yourself; the glasses are speaking it.")
        }
        val result = WebCommandBus.execute(w.id, WebCommandBus.Command(action, passthrough))
        // A search that landed on whichever window happened to be active: say which site answered,
        // so "restaurants near X" typed into Radio Garden is recognised as the wrong tool, not a result.
        if (action == "search" && !named) {
            val site = hostOf(w.source).ifBlank { w.title }
            return Result.success("Searched within $site (the active window) — for places use widget add type=map, for another site open it first. $result")
        }
        return Result.success(result)
    }

    /**
     * Read a page properly, rather than posting the first 2500 characters of it into the
     * conversation and hoping the answer was near the top.
     *
     * The page hands over as much as it has; a cheap reader model turns that into the few lines
     * worth saying, guided by the question when there is one. Two things come out of that: long
     * articles stop being truncated before anyone sees them, and the expensive conversation gets a
     * short answer instead of pages of raw text. When the page is short enough to speak for itself
     * there is no second call at all — no cost, no added wait.
     */
    private suspend fun readPage(w: Widget, args: Args): String {
        val focus = args.str("query", "question", "about", "find", "text")
        val raw = if (w.type == WidgetType.EPUB) epubText(w, whole = !focus.isNullOrBlank())
                  else WebCommandBus.execute(w.id, WebCommandBus.Command("read", mapOf("cap" to "400000")),
                      timeoutMs = 30_000L)
        if (raw.isBlank()) return "Nothing to read in \"${w.title}\"."

        // "Read this" means read it, not tell me about it. Anything short enough to be spoken comes
        // back as it was written; only a wall of text gets condensed, and only then is it worth a
        // second call. A question is different — that is always answered rather than recited.
        if (focus.isNullOrBlank() && raw.length <= VERBATIM_LIMIT) return raw
        if (focus.isNullOrBlank() && raw.length <= SHORT_PAGE) return raw

        val system = "You are reading for someone wearing AR glasses. Use only the text given; if it " +
            "does not say, say so. No preamble, no markdown, no bullet characters."
        val ask = if (focus.isNullOrBlank())
            "Summarise this, then say roughly how long it is so the listener knows what was skipped."
        else "From this text, answer: $focus"
        return GeminiRest.generateText(context, "$ask\n\n---\n$raw", system = system,
            model = GeminiRest.READ_MODEL)
            // Reading failed, but the text is in hand — a truncated page beats no page.
            .getOrElse { raw.take(VERBATIM_LIMIT) + "\n\n(Couldn't condense this; showing the start.)" }
    }

    /**
     * An ebook's words, taken from the unpacked chapter files rather than the page showing them.
     *
     * The reader renders a chapter with JavaScript off, so the usual read — which asks the page for
     * its own text — was refused outright and ebooks could not be read at all. The files are right
     * there and hold the whole book, so ask them instead: the chapter on screen for "read this",
     * and everything for a question, since the answer is rarely in the chapter you happen to be on.
     */
    private fun epubText(w: Widget, whole: Boolean): String = runCatching {
        val chapters = EpubUnpacker.chapters(context, File(w.source))
        if (chapters.isEmpty()) return ""
        val at = (w.state["chapter"]?.toIntOrNull() ?: 0).coerceIn(0, chapters.size - 1)
        val take = if (whole) chapters else listOf(chapters[at])
        val sb = StringBuilder()
        if (whole) sb.append("Book: ").append(w.title).append(" (").append(chapters.size).append(" chapters)\n\n")
        else sb.append(w.title).append(" — chapter ").append(at + 1).append(" of ").append(chapters.size).append("\n\n")
        for (f in take) {
            sb.append(stripHtml(f.readText()))
            sb.append("\n\n")
            if (sb.length > EPUB_LIMIT) break
        }
        sb.take(EPUB_LIMIT).toString().trim()
    }.onFailure { Log.w("WebTool", "epub read failed: ${it.message}") }.getOrDefault("")

    /** The whole book as one run of text, with the offset each chapter starts at. */
    private fun epubBook(w: Widget): Pair<String, List<Int>>? = runCatching {
        val chapters = EpubUnpacker.chapters(context, File(w.source))
        if (chapters.isEmpty()) return null
        val sb = StringBuilder()
        val starts = ArrayList<Int>(chapters.size)
        for (f in chapters) {
            starts += sb.length
            sb.append(stripHtml(f.readText())).append("\n\n")
            if (sb.length > EPUB_LIMIT) break
        }
        sb.toString().trim() to starts
    }.onFailure { Log.w("WebTool", "epub book read failed: ${it.message}") }.getOrNull()

    /** Chapter files are XHTML; the words are all that is wanted. */
    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<(br|/p|/div|/h[1-6])[^>]*>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lsquo;", "'").replace("&rsquo;", "'")
        .replace("&ldquo;", "\"").replace("&rdquo;", "\"").replace("&mdash;", "—")
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** Registrable-ish host for "same site" checks: www./m./open. prefixes dropped. */
    private fun hostOf(url: String): String = runCatching { java.net.URL(url).host }.getOrDefault("")
        .lowercase(Locale.US).removePrefix("www.").removePrefix("m.").removePrefix("open.")

    private fun resolveTarget(args: Args): Widget? {
        val ref = args.str("target", "id", "title", "widget", "name")
        if (ref != null) DesktopBridge.resolveWidget(ref)?.let { return it }
        val d = DesktopBridge.current()
        DesktopBridge.activeWidgetId?.let { id -> d.widget(id)?.takeIf { it.type.isWebLike }?.let { return it } }
        return d.widgets.filter { it.type.isWebLike }.maxByOrNull { it.z }
            ?: d.widgets.filter { it.type == WidgetType.EPUB || it.type == WidgetType.MAP }.maxByOrNull { it.z }
    }
}
