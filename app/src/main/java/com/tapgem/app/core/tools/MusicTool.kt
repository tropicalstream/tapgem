package com.tapgem.app.core.tools

import android.content.Context
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.music.MusicPlayer
import com.tapgem.app.core.music.SkinStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Voice control for the local music player.
 *
 * There is deliberately no phrase parsing here. "Play that jazz album", "something by her",
 * "the third one", "put on whatever is in Downloads" all arrive as different words for the same
 * two steps: list what exists, then play a chosen id. The model does the resolving — it is already
 * holding the conversation — and this tool just answers with enough structure for it to choose.
 */
class MusicTool(private val context: Context) : AiTool {
    override val name = "music"

    private fun lines(tracks: List<MusicPlayer.Track>, from: Int = 1) =
        tracks.mapIndexed { i, t ->
            val mins = if (t.durationMs > 0) " (${t.durationMs / 60000}:${"%02d".format((t.durationMs / 1000) % 60)})" else ""
            "${i + from}. [id ${t.id}] ${t.artist} — ${t.title}${if (t.album.isNotBlank()) " · ${t.album}" else ""}$mins"
        }.joinToString("\n")

    /**
     * The player opens at the skin's own size — 275x116 blitted 1:1, plus the status strip — because
     * that is what the artwork was drawn for and it leaves the rest of the glasses free. The panels
     * (library, skins, visualiser) need room, so asking for one grows the window; [COMPACT] puts it back.
     */
    private val COMPACT = 281 to 158
    private val EXPANDED = 624 to 430

    private suspend fun window(args: Args, expanded: Boolean): String {
        LiveApps.install(context, "winamp.html", LiveApps.MUSIC)
        val existing = LiveApps.window(LiveApps.MUSIC)
        if (existing == null) {
            val a = HashMap<String, String>()
            args.str("anchor", "position")?.let { a["anchor"] = it }
            for (k in listOf("x", "y", "w", "h", "size")) args.str(k)?.let { a[k] = it }
            if (a["w"] == null && a["h"] == null && a["size"] == null) {
                val (w, h) = if (expanded) EXPANDED else COMPACT
                a["w"] = "$w"; a["h"] = "$h"; a.putIfAbsent("anchor", "top left")
            }
            return LiveApps.ensureWindow(context, "winamp.html", LiveApps.MUSIC, "Music", Args(a))
        }
        // Only grow on demand; never shrink a window the user has sized themselves by voice.
        if (expanded && existing.h < EXPANDED.second - 40) {
            DesktopBridge.mutateWidget(existing.id) { f -> f.copy(w = maxOf(f.w, EXPANDED.first), h = EXPANDED.second) }
        }
        return ""
    }

    /** The gallery: wide and tall enough for several rows of thumbnails at once. */
    private val GALLERY = 620 to 300

    private suspend fun gallery(args: Args): String {
        // Install first, always: it rewrites the page when the bundled asset has changed and reloads
        // any open window. Returning early on "already open" left a stale page on the glasses.
        LiveApps.install(context, "skins.html", LiveApps.MUSIC_SKINS)
        LiveApps.window(LiveApps.MUSIC_SKINS)?.let { return "" }
        val a = HashMap<String, String>()
        a["w"] = "${GALLERY.first}"; a["h"] = "${GALLERY.second}"; a["anchor"] = "bottom left"
        return LiveApps.ensureWindow(context, "skins.html", LiveApps.MUSIC_SKINS, "Skins", Args(a))
    }

    /** A query resolves to tracks; an explicit id wins, then a search, then everything. */
    private fun resolve(args: Args): List<MusicPlayer.Track> {
        args.str("id", "track_id")?.toLongOrNull()?.let { id -> MusicPlayer.byId(id)?.let { return listOf(it) } }
        val q = args.str("query", "title", "name", "artist", "album", "search", "text")
        return if (q.isNullOrBlank()) MusicPlayer.library() else MusicPlayer.search(q)
    }

    override suspend fun execute(args: Args): Result<String> = withContext(Dispatchers.IO) {
        when (args.action) {
            "open", "show", "window" -> {
                val o = window(args, expanded = false)
                Result.success("Music player is on the desktop.$o")
            }

            "compact", "small", "shrink" -> {
                LiveApps.window(LiveApps.MUSIC)?.let { w ->
                    DesktopBridge.mutateWidget(w.id) { f -> f.copy(w = COMPACT.first, h = COMPACT.second) }
                    Result.success("Back to just the skin.")
                } ?: Result.success("The player is not open.")
            }

            "list", "library", "browse", "what_do_i_have" -> {
                if (LiveApps.window(LiveApps.MUSIC) != null) window(args, expanded = true)
                val all = MusicPlayer.library(refresh = true)
                if (all.isEmpty()) return@withContext Result.success(
                    "No audio on the glasses yet. Push files to /sdcard/Music or /sdcard/Download and ask again.")
                val n = (args.int("count", "limit") ?: 25).coerceIn(1, 60)
                Result.success("${all.size} track${if (all.size == 1) "" else "s"}:\n${lines(all.take(n))}" +
                    if (all.size > n) "\n… ${all.size - n} more." else "")
            }

            "search", "find" -> {
                val hits = MusicPlayer.search(args.str("query", "text", "title", "artist", "album", "name"))
                Result.success(if (hits.isEmpty()) "Nothing matched." else "${hits.size} match${if (hits.size == 1) "" else "es"}:\n${lines(hits)}")
            }

            "play", "start", "listen", "put_on" -> {
                window(args, expanded = false)
                val tracks = resolve(args)
                // Say what is true: it is not here. Falling back to a web player silently is how a
                // request for one album ends up playing an account's recommendations with the
                // right artwork on screen and the wrong music coming out.
                if (tracks.isEmpty()) return@withContext Result.success(
                    "\"${args.str("query", "text", "title", "artist", "album", "name") ?: "that"}\" isn't on the glasses. " +
                    "The library here is: ${MusicPlayer.library().map { it.album }.filter { it.isNotBlank() }.distinct().take(4).joinToString(", ").ifBlank { "empty" }}. " +
                    "I can look for it on a music site instead — say so and I'll search there, " +
                    "or push the files to /sdcard/Music.")
                // "the third one" arrives as an index into whatever was just read out.
                val pick = args.int("index", "number", "position")?.minus(1)
                Result.success(
                    if (pick != null && pick in tracks.indices) MusicPlayer.play(tracks, pick)
                    else MusicPlayer.play(tracks, 0))
            }

            "queue", "add", "play_next", "enqueue" -> Result.success(MusicPlayer.enqueue(resolve(args)))

            "pause", "stop_playing", "hold" -> Result.success(MusicPlayer.pause())
            "resume", "unpause", "continue" -> Result.success(MusicPlayer.resume())
            "toggle", "play_pause" -> Result.success(MusicPlayer.toggle())
            "next", "skip", "forward" -> Result.success(MusicPlayer.next())
            "previous", "prev", "back", "again" -> Result.success(MusicPlayer.previous())
            "stop", "off", "quiet" -> Result.success(MusicPlayer.stop())

            "seek" -> {
                val to = args.int("to", "position", "seconds")
                val by = args.int("by", "delta")
                when {
                    by != null -> { MusicPlayer.seekBy(by * 1000); Result.success("${if (by > 0) "Forward" else "Back"} ${kotlin.math.abs(by)}s.") }
                    to != null -> { MusicPlayer.seekTo(to * 1000); Result.success("Jumped to ${to / 60}:${"%02d".format(to % 60)}.") }
                    else -> Result.success("Say how far to skip.")
                }
            }

            "shuffle" -> Result.success(MusicPlayer.setShuffle(args.str("value", "mode", "state")?.lowercase() !in setOf("off", "false", "no")))
            "repeat", "loop" -> Result.success(MusicPlayer.setRepeat(args.str("value", "mode", "state") ?: "all"))
            "volume" -> Result.success(MusicPlayer.volume(args.int("level", "value", "percent")))

            "status", "now_playing", "what_is_playing", "whats_playing" -> {
                val t = MusicPlayer.current()
                Result.success(if (t == null) "Nothing playing."
                else "${if (MusicPlayer.playing) "Playing" else "Paused"}: ${t.artist} — ${t.title}" +
                    (if (t.album.isNotBlank()) " · ${t.album}" else "") +
                    " · ${MusicPlayer.positionMs() / 1000}s in · ${MusicPlayer.queueSnapshot().size} queued" +
                    (if (MusicPlayer.shuffle) " · shuffle" else "") + (if (MusicPlayer.repeat != "off") " · repeat ${MusicPlayer.repeat}" else ""))
            }

            "up_next", "queue_list", "queue_view", "show_queue" -> {
                // Deliberately not expanded: the window is the owner's to size. Asking what is
                // next answers out loud either way, and on a player left at skin height there is
                // no room for a panel to appear in.
                window(args, expanded = false)
                com.tapgem.app.core.music.MusicBridgeEvents.emitQueue()
                val q = MusicPlayer.queueSnapshot()
                Result.success(if (q.isEmpty()) "Queue empty." else "Up next:\n${lines(q.take(10))}")
            }

            // ---- skins & visualiser ----
            "skins", "skin_search", "browse_skins" -> {
                // Its own window: the gallery wants room to show rows of thumbnails, and the player
                // wants to stay the size its artwork was drawn for. One window cannot be both.
                window(args, expanded = false)
                gallery(args)
                val q = args.str("query", "text", "name", "theme", "keyword")
                val found = SkinStore.search(q)
                if (found.isEmpty()) return@withContext Result.success("No skins matched \"$q\".")
                com.tapgem.app.core.music.MusicBridgeEvents.emitSkinResults(q, found)
                Result.success("Showing ${found.size} skin${if (found.size == 1) "" else "s"}" +
                    (if (q.isNullOrBlank()) "" else " for \"$q\"") + " — tap one to wear it.")
            }

            "close_skins", "hide_skins" -> {
                LiveApps.window(LiveApps.MUSIC_SKINS)?.let { w ->
                    DesktopBridge.mutate { d -> d.copy(widgets = d.widgets.filterNot { it.id == w.id }) }
                    Result.success("Closed the skin gallery.")
                } ?: Result.success("The gallery is not open.")
            }

            "skin", "set_skin", "use_skin" -> {
                window(args, expanded = false)
                val id = args.str("id", "skin", "identifier")
                    ?: args.str("query", "name", "text")?.let { SkinStore.search(it, rows = 1).firstOrNull()?.id }
                    ?: return@withContext Result.success("Say which skin, or ask to browse skins.")
                val res = SkinStore.installedJson(id)
                if (!res.optBoolean("ok")) return@withContext Result.success("Could not fetch that skin.")
                com.tapgem.app.core.music.MusicBridgeEvents.emitSkin(res)
                Result.success("Wearing ${id.removePrefix("was-").replace('-', ' ')}.")
            }

            "visualizer", "visualiser", "milkdrop" -> {
                window(args, expanded = true)
                val v = args.str("value", "mode", "state", "type")?.lowercase()
                val mode = when {
                    v == null || v in setOf("on", "true", "yes", "bars", "spectrum") -> "bars"
                    v in setOf("off", "false", "no", "none") -> "off"
                    v.contains("scope") || v.contains("osc") || v.contains("wave") -> "scope"
                    // "flow" is the shader-style field; real .milk presets would need the audio graph
                    // inside the page, so MilkDrop-by-name lands on the closest thing that exists.
                    v.contains("flow") || v.contains("milk") || v.contains("butter") || v.contains("trip") -> "flow"
                    else -> "bars"
                }
                com.tapgem.app.core.music.MusicBridgeEvents.emitVisualizer(mode)
                Result.success(if (mode == "off") "Visualiser off." else "Visualiser: $mode.")
            }

            "rescan", "refresh" -> {
                val n = MusicPlayer.library(refresh = true).size
                Result.success("Found $n track${if (n == 1) "" else "s"}.")
            }

            else -> Result.failure(IllegalArgumentException(
                "music: open|compact|close_skins|list|search|play|queue|pause|resume|next|previous|seek|shuffle|repeat|volume|status|up_next|skins|skin|visualizer|rescan"))
        }
    }
}
