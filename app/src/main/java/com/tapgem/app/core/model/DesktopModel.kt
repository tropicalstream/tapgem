package com.tapgem.app.core.model

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** The logical canvas everything is designed against (1dp == 1px on the X3). */
object Canvas {
    const val WIDTH = 640
    const val HEIGHT = 480
    /** The status strip owns y 0..STRIP_H; widgets live below it. */
    const val STRIP_H = 40
    const val CONTENT_TOP = 44
    const val MIN_W = 80
    const val MIN_H = 56
}

enum class WidgetType {
    TEXT, CLOCK, LIVE, IMAGE, VIDEO, AUDIO, PDF, EPUB, WEB, APP, MODEL3D, MAP, TICKER;

    /** Default window size for a freshly added widget of this type. */
    fun defaultSize(): Pair<Int, Int> = when (this) {
        TEXT -> 220 to 120
        CLOCK -> 170 to 72
        LIVE -> 250 to 140
        IMAGE -> 240 to 180
        VIDEO -> 320 to 200
        AUDIO -> 230 to 84
        PDF -> 300 to 360
        EPUB -> 320 to 380
        WEB -> 520 to 400   // mobile layouts hide players and menus below ~360 px of height
        APP -> 320 to 240
        MODEL3D -> 280 to 240
        MAP -> 400 to 320
        TICKER -> 480 to 50
    }

    /** Widgets whose panel is a WebView we can drive with the web tool. */
    val isWebLike: Boolean get() = this == WEB || this == APP

    /** Costly to bring up (a WebView or a decoder) — staggered on cold starts. */
    val isHeavy: Boolean get() = this == WEB || this == APP || this == EPUB || this == MODEL3D || this == MAP || this == VIDEO

    /** Content the user scrolls / pans / drags inside, as opposed to a passive panel. */
    val isScrollable: Boolean get() = this == WEB || this == APP || this == EPUB || this == MAP || this == TEXT || this == LIVE || this == PDF

    /** Text fetched from the model on a schedule (live cards and tickers). */
    val isFetched: Boolean get() = this == LIVE || this == TICKER

    companion object {
        /** Loose, voice-friendly type names → type. */
        fun parse(raw: String?): WidgetType? {
            val k = raw?.trim()?.lowercase(Locale.US)?.replace(Regex("[^a-z0-9]"), "")
                ?: return null
            if (k.isBlank()) return null
            return when (k) {
                "text", "note", "label", "caption", "sticky", "postit", "txt" -> TEXT
                "clock", "time", "watch" -> CLOCK
                "live", "feed", "card", "news", "score", "scores", "weather", "stock",
                "stocks", "price", "prices", "headline", "headlines" -> LIVE
                "image", "picture", "photo", "img", "png", "jpg", "jpeg", "gif", "wallpaperimage" -> IMAGE
                "video", "movie", "mp4", "clip", "film" -> VIDEO
                "audio", "music", "mp3", "song", "sound", "podcast", "track", "player" -> AUDIO
                "pdf", "document", "doc" -> PDF
                "epub", "ebook", "book", "novel", "reader" -> EPUB
                "web", "webpage", "website", "browser", "url", "page", "site", "link" -> WEB
                "app", "miniapp", "widgetapp", "game", "tool", "html" -> APP
                "model3d", "3d", "model", "gltf", "glb", "obj", "3dmodel", "object", "3dobject",
                "mesh" -> MODEL3D
                "map", "minimap", "streetmap", "citymap", "location", "maps", "navigation", "directions" -> MAP
                "ticker", "marquee", "crawl", "scroller", "scrollingtext", "newsticker", "stockticker", "tickertape" -> TICKER
                else -> entries.firstOrNull { it.name.lowercase(Locale.US) == k }
            }
        }

        fun forExtension(ext: String): WidgetType? = when (ext.lowercase(Locale.US)) {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic" -> IMAGE
            "mp4", "mkv", "webm", "mov", "3gp", "m4v" -> VIDEO
            "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus" -> AUDIO
            "pdf" -> PDF
            "epub" -> EPUB
            "txt", "md", "log", "csv", "json" -> TEXT
            "glb", "gltf", "obj" -> MODEL3D
            "html", "htm" -> APP
            else -> null
        }
    }
}

enum class DesktopMode { HUD, DESKTOP }
enum class WallpaperKind { NONE, COLOR, GRADIENT, IMAGE }

object ColorUtil {
    fun parse(raw: String?): Int? {
        val s = raw?.trim() ?: return null
        if (s.isBlank()) return null
        val candidate = if (s.startsWith("#") || s.matches(Regex("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}"))) {
            if (s.startsWith("#")) s else "#$s"
        } else s.lowercase(Locale.US).replace(Regex("[\\s_-]+"), "")
        return runCatching { Color.parseColor(candidate) }.getOrNull()
            ?: NAMED[candidate]
    }

    /** True when [raw] was given but names no colour we know — tools warn instead of silently ignoring. */
    fun isUnknown(raw: String?): Boolean = !raw.isNullOrBlank() && parse(raw) == null

    fun hex(c: Int): String = String.format(Locale.US, "#%08X", c)

    fun withAlpha(c: Int, alpha: Float): Int =
        (c and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private val NAMED = mapOf(
        "orange" to 0xFFFF9F0A.toInt(), "pink" to 0xFFFF375F.toInt(), "indigo" to 0xFF5E5CE6.toInt(),
        "violet" to 0xFFBF5AF2.toInt(), "gold" to 0xFFFFD60A.toInt(), "mint" to 0xFF66D4CF.toInt(),
        "sky" to 0xFF64D2FF.toInt(), "skyblue" to 0xFF64D2FF.toInt(), "coral" to 0xFFFF6B6B.toInt(),
        "amber" to 0xFFFFB347.toInt(), "emerald" to 0xFF34D399.toInt(), "slate" to 0xFF2A3441.toInt(),
        "charcoal" to 0xFF1A1D22.toInt(), "cream" to 0xFFFFF8E7.toInt(), "transparent" to 0x00000000,
        "lavender" to 0xFFB39DDB.toInt(), "peach" to 0xFFFFB38A.toInt(), "salmon" to 0xFFFA8072.toInt(),
        "turquoise" to 0xFF40E0D0.toInt(), "brown" to 0xFF8B5A2B.toInt(), "tan" to 0xFFD2B48C.toInt(),
        "beige" to 0xFFF5F5DC.toInt(), "ivory" to 0xFFFFFFF0.toInt(), "crimson" to 0xFFDC143C.toInt(),
        "scarlet" to 0xFFFF2400.toInt(), "rose" to 0xFFFF66AA.toInt(), "lightblue" to 0xFF9AD6FF.toInt(),
        "darkblue" to 0xFF0B1F4D.toInt(), "lightgreen" to 0xFF9AE6B4.toInt(), "darkgreen" to 0xFF0B3D1E.toInt(),
        "neongreen" to 0xFF39FF14.toInt(), "hotpink" to 0xFFFF69B4.toInt(), "electricblue" to 0xFF7DF9FF.toInt(),
        "midnight" to 0xFF0B1020.toInt(), "offwhite" to 0xFFF2F2F2.toInt()
    )
}

data class WidgetStyle(
    val bgColor: Int? = null,
    val textColor: Int? = null,
    val opacity: Float = 1f,
    val cornerRadius: Int? = null,
    val fontSize: Float? = null,
    /** Show the title bar / close glyph. Null = follow the desktop mode. */
    val chrome: Boolean? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        bgColor?.let { put("bg", ColorUtil.hex(it)) }
        textColor?.let { put("text", ColorUtil.hex(it)) }
        put("opacity", opacity.toDouble())
        cornerRadius?.let { put("corner", it) }
        fontSize?.let { put("font", it.toDouble()) }
        chrome?.let { put("chrome", it) }
    }

    companion object {
        fun fromJson(o: JSONObject?): WidgetStyle {
            if (o == null) return WidgetStyle()
            return WidgetStyle(
                bgColor = ColorUtil.parse(o.optString("bg", "")),
                textColor = ColorUtil.parse(o.optString("text", "")),
                opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0.05f, 1f),
                cornerRadius = if (o.has("corner")) o.optInt("corner") else null,
                fontSize = if (o.has("font")) o.optDouble("font").toFloat() else null,
                chrome = if (o.has("chrome")) o.optBoolean("chrome") else null
            )
        }
    }
}

/**
 * One window/panel on the desktop.
 *
 * [source] meaning by [type]: TEXT → literal text, or "prompt:<instruction>"
 * regenerated on refresh; CLOCK → "time" | "time+date"; LIVE → the watch query;
 * IMAGE/VIDEO/AUDIO/PDF/EPUB/MODEL3D → local path or http(s) URL; WEB → URL;
 * APP → path of the generated HTML file.
 *
 * [state] is small per-widget runtime state that must persist: page, chapter,
 * playing, muted, loop, seekMs, reload (nonce). [content] is the last fetched
 * text for LIVE / prompt widgets.
 */
data class Widget(
    val id: String = UUID.randomUUID().toString().take(8),
    val type: WidgetType,
    val title: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val z: Int = 0,
    val source: String = "",
    val refreshSec: Int = 0,
    val style: WidgetStyle = WidgetStyle(),
    val state: Map<String, String> = emptyMap(),
    val content: String = "",
    val updatedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    /** Pinned above every other window regardless of focus ("stay on top"). */
    val onTop: Boolean = false
) {
    /** Merge state; an empty value REMOVES that key. */
    fun withState(vararg pairs: Pair<String, String>): Widget = withState(pairs.toMap())

    fun withState(delta: Map<String, String>): Widget {
        val m = state.toMutableMap()
        delta.forEach { (k, v) -> if (v.isEmpty()) m.remove(k) else m[k] = v }
        return copy(state = m)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type.name); put("title", title)
        put("x", x); put("y", y); put("w", w); put("h", h); put("z", z)
        put("source", source); put("refreshSec", refreshSec)
        put("style", style.toJson())
        put("state", JSONObject().also { s -> state.forEach { (k, v) -> s.put(k, v) } })
        put("content", content); put("updatedAt", updatedAt); put("createdAt", createdAt)
        put("onTop", onTop)
    }

    companion object {
        fun fromJson(o: JSONObject): Widget? {
            val type = WidgetType.parse(o.optString("type")) ?: return null
            val st = mutableMapOf<String, String>()
            o.optJSONObject("state")?.let { s -> for (k in s.keys()) st[k] = s.optString(k) }
            return Widget(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                type = type,
                title = o.optString("title"),
                x = o.optInt("x"), y = o.optInt("y"),
                w = o.optInt("w", 200), h = o.optInt("h", 120), z = o.optInt("z"),
                source = o.optString("source"),
                refreshSec = o.optInt("refreshSec"),
                style = WidgetStyle.fromJson(o.optJSONObject("style")),
                state = st,
                content = o.optString("content"),
                updatedAt = o.optLong("updatedAt"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                onTop = o.optBoolean("onTop", false)
            )
        }
    }
}

data class Theme(
    val name: String,
    val accent: Int,
    val panel: Int,
    val text: Int,
    val fontScale: Float = 1f,
    val corner: Int = 10,
    /** Procedural panel texture name ("wood"), or null for a flat panel colour. */
    val texture: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("accent", ColorUtil.hex(accent)).put("panel", ColorUtil.hex(panel))
        .put("text", ColorUtil.hex(text)).put("fontScale", fontScale.toDouble()).put("corner", corner)
        .put("texture", texture ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject?): Theme {
            if (o == null) return Themes.DEFAULT
            val base = Themes.byName(o.optString("name")) ?: Themes.DEFAULT
            return Theme(
                name = o.optString("name").ifBlank { base.name },
                accent = ColorUtil.parse(o.optString("accent")) ?: base.accent,
                panel = ColorUtil.parse(o.optString("panel")) ?: base.panel,
                text = ColorUtil.parse(o.optString("text")) ?: base.text,
                fontScale = o.optDouble("fontScale", base.fontScale.toDouble()).toFloat().coerceIn(0.6f, 2.2f),
                corner = o.optInt("corner", base.corner),
                texture = if (o.has("texture") && !o.isNull("texture")) o.optString("texture").takeIf { it.isNotBlank() } else base.texture
            )
        }
    }
}

/** Built-in themes; "give it a neon theme" → [byName]. */
object Themes {
    val MIDNIGHT = Theme("midnight", 0xFF64D2FF.toInt(), 0xCC0B1020.toInt(), 0xFFE6F1FF.toInt())
    val NEON = Theme("neon", 0xFFFF2BD6.toInt(), 0xCC050008.toInt(), 0xFFB8FFEA.toInt(), 1f, 6)
    // "paper" is warm sepia on dark: no theme may paint a light panel — a white window
    // full-screen is exactly what browns out the unplugged X3.
    val PAPER = Theme("paper", 0xFFD9A066.toInt(), 0xE6221A12.toInt(), 0xFFF3E6CF.toInt(), 1f, 8)
    val FOREST = Theme("forest", 0xFF7CFC9A.toInt(), 0xCC0E1A12.toInt(), 0xFFE3F6E8.toInt())
    val SUNSET = Theme("sunset", 0xFFFFB347.toInt(), 0xCC2A0F14.toInt(), 0xFFFFE9D6.toInt(), 1f, 14)
    val MONO = Theme("mono", 0xFFFFFFFF.toInt(), 0xCC101010.toInt(), 0xFFF2F2F2.toInt(), 1f, 4)
    val OCEAN = Theme("ocean", 0xFF5E5CE6.toInt(), 0xCC061428.toInt(), 0xFFD9ECFF.toInt(), 1f, 12)
    /** Dark walnut panels with a brass accent and cream text — the grain is drawn procedurally. */
    val WOOD = Theme("wood", 0xFFD9A066.toInt(), 0xFF2B1A10.toInt(), 0xFFF3E6CF.toInt(), 1f, 8, texture = "wood")
    val DEFAULT = MIDNIGHT
    val ALL = listOf(MIDNIGHT, NEON, PAPER, FOREST, SUNSET, MONO, OCEAN, WOOD)
    fun byName(n: String?): Theme? {
        val k = n?.trim()?.lowercase(Locale.US) ?: return null
        return ALL.firstOrNull { it.name == k } ?: ALL.firstOrNull { k.contains(it.name) }
    }
}

data class Wallpaper(
    val kind: WallpaperKind = WallpaperKind.NONE,
    val colors: List<Int> = emptyList(),
    val imagePath: String? = null,
    val description: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("colors", JSONArray().also { a -> colors.forEach { a.put(ColorUtil.hex(it)) } })
        .put("imagePath", imagePath ?: JSONObject.NULL)
        .put("description", description)

    companion object {
        fun fromJson(o: JSONObject?): Wallpaper {
            if (o == null) return Wallpaper()
            val cols = mutableListOf<Int>()
            o.optJSONArray("colors")?.let { a ->
                for (i in 0 until a.length()) ColorUtil.parse(a.optString(i))?.let { cols += it }
            }
            return Wallpaper(
                kind = runCatching { WallpaperKind.valueOf(o.optString("kind", "NONE")) }.getOrDefault(WallpaperKind.NONE),
                colors = cols,
                imagePath = o.optString("imagePath").takeIf { it.isNotBlank() && it != "null" },
                description = o.optString("description")
            )
        }
    }
}

data class Desktop(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val mode: DesktopMode = DesktopMode.HUD,
    val theme: Theme = Themes.DEFAULT,
    val wallpaper: Wallpaper = Wallpaper(),
    val widgets: List<Widget> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun widget(id: String): Widget? = widgets.firstOrNull { it.id == id }

    fun replaceWidget(w: Widget): Desktop =
        copy(widgets = widgets.map { if (it.id == w.id) w else it })

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("mode", mode.name)
        put("theme", theme.toJson()); put("wallpaper", wallpaper.toJson())
        put("widgets", JSONArray().also { a -> widgets.forEach { a.put(it.toJson()) } })
        put("createdAt", createdAt); put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Desktop? {
            val ws = mutableListOf<Widget>()
            o.optJSONArray("widgets")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { Widget.fromJson(it)?.let(ws::add) }
            }
            return Desktop(
                id = o.optString("id").ifBlank { return null },
                name = o.optString("name").ifBlank { "Desktop" },
                mode = runCatching { DesktopMode.valueOf(o.optString("mode", "HUD")) }.getOrDefault(DesktopMode.HUD),
                theme = Theme.fromJson(o.optJSONObject("theme")),
                wallpaper = Wallpaper.fromJson(o.optJSONObject("wallpaper")),
                widgets = ws,
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}
