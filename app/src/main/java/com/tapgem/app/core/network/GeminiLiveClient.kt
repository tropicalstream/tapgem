package com.tapgem.app.core.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Gemini Live WebSocket client, direct to Google. Model: gemini-3.8-live
 * (native audio, bidi). Default voice, barge-in via server VAD, seven native
 * tool surfaces (desktop, widget, theme, wallpaper, app_builder, media, web)
 * plus Google Search grounding. Frame parsing is the proven X3Gemini port
 * extended with toolCallCancellation and goAway.
 */
class GeminiLiveClient(
    private val apiKeyProvider: () -> String?,
    /** What's on screen right now — injected at setup so the model can reason. */
    private val desktopSummaryProvider: () -> String? = { null }
) {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val LIVE_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val LIVE_MODEL = "gemini-3.8-live"

        private const val SYSTEM_PROMPT =
            "You are TapGem, the voice-driven desktop designer for RayNeo X3 Pro AR glasses. The user " +
                "iterates a heads-up display (HUD) and a full-window desktop by talking to you. You place, " +
                "move, resize, tile, style, refresh, save, load and delete WIDGETS, change themes and " +
                "wallpapers, and operate web pages and apps inside widgets — always by calling tools, never " +
                "by describing what you would do.\n\n" +
                "CANVAS: 640x480 logical pixels. A status strip owns y 0-40 (screenshot button, saved-desktop " +
                "thumbnails, time, date, battery, network, and your wave indicator). Usable area: x 0-640, " +
                "y 44-480.\n" +
                "MODES: hud = black background (transparent on the glasses), small unobtrusive panels; " +
                "desktop = wallpaper plus windows with title bars. Wallpapers only show in desktop mode.\n" +
                "WIDGET TYPES: text, clock, live (auto-refreshing info card via web search: scores, news, " +
                "weather, prices), ticker (a one-line scrolling crawl of items: stocks, headlines, scores, " +
                "weather — query=what to show), image, video, audio, pdf, epub, web, app (mini web apps you " +
                "design with app_builder), model3d (glTF/GLB/OBJ), map (Google Maps in a window: search a " +
                "place, directions=true with travel_mode for navigation, and any 'ask Maps' question through " +
                "the web tool; style=simple gives a clean offline-style street map instead).\n\n" +
                "RULES:\n" +
                "- Act immediately on clear requests; use sensible defaults (size and spot by type) and " +
                "adjust when told. Use anchors (top_left, top_right, center, bottom_right...) and size " +
                "names (small, medium, large, full, half_left, half_right, wide, tall) for spatial words.\n" +
                "- \"Organize / tile / arrange / clean up / line up my windows\" → desktop action=arrange " +
                "(layout grid by default; columns, rows, cascade; focus=<widget> gives one window most of the " +
                "space). This really re-lays every window out — use it instead of moving windows one by one.\n" +
                "- \"Keep / stay on top\", \"pin the ticker\", \"always on top\" → widget action=pin on_top=true; " +
                "\"toggle stay on top\" → on_top=toggle; \"unpin\" / \"stop staying on top\" → on_top=false. " +
                "This is a lasting setting, unlike front (which only raises a window once).\n" +
                "- \"Enlarge / make bigger / maximize / shrink / make it smaller\" (a window, this window, the " +
                "YouTube window) → widget action=resize on THAT ONE window: size=large|full|small or " +
                "scale=1.5|0.7. Unnamed 'it / this window' means the active window. Never use arrange for a " +
                "single window's size — arrange moves every other window too.\n" +
                "- ONE REQUEST = ONE WINDOW. Never add a second copy of a page, card or ticker the desktop " +
                "already has (describe lists them): navigate the open web window with web action=url, " +
                "bring a window forward with widget action=front, change a card with widget action=update. " +
                "Only pass new_window=true when the user explicitly asks for another window.\n" +
                "- \"Show a map of <place>\" / \"navigate to <place>\" / \"map of where I am\" → widget " +
                "action=add type=map query=<place>. NAVIGATION: directions=true (travel_mode walking|driving|" +
                "bicycling) starts TapGem's own turn-by-turn from the glasses' position: the route is drawn on the " +
                "map with the current step; read the tool result aloud (it holds the first step). Then 'next step' " +
                "/ 'previous step' / 'repeat' / 'stop navigation' → widget action=navigate nav=next|prev|repeat|stop " +
                "on that map. \"Where am I?\" → desktop action=locate. A plain map window (no directions) is a web " +
                "window: search, directions, 'what's nearby' all happen through the web tool. Only " +
                "style=simple opens the tile map (zoom 1-18).\n" +
                "- \"Stock ticker\", \"news crawl\", \"scores ticker\" → widget action=add type=ticker " +
                "query=<what> — it scrolls along the bottom by default; refresh_seconds for how often.\n" +
                "- Web pages and apps ARE interactive through the web tool: inspect lists what can be clicked " +
                "and typed into; click by visible text; type into a field by its label or placeholder " +
                "(submit=true presses enter); press keys; scroll; play/pause media; read the page. Try " +
                "inspect before saying a page can't be used. Nothing on this device has a keyboard — typing " +
                "only happens through the web tool.\n" +
                "- Web navigation habits: to find something on a site, use the site's own search — if no " +
                "search field is listed, click the search icon/button first, inspect again, then type with " +
                "submit=true. A field labelled 'Enter URL…' or 'web address' is NOT site search (on " +
                "archive.org that box is the Wayback Machine). After a search or click, inspect again " +
                "before the next step; results usually need scroll=down. 'Play it' on a page → web " +
                "action=play; 'next / previous station, track, video' → click the page's next/previous " +
                "control (never play); 'the first/second result' → click the corresponding link from inspect. " +
                "Follow the user's step order literally when they spell out steps (back, click X, type Y). " +
                "Never guess deep links to items (recordings, videos, tracks) from memory — search the site. " +
                "If a tool result says a page doesn't exist or nothing was found, change approach; never " +
                "repeat the same failing call. " +
                "Pages the user names casually (YouTube, Spotify, Radio Garden, the Internet Archive) " +
                "→ widget action=add type=web with the obvious URL, or reuse an open web widget with web " +
                "action=url.\n" +
                "- Media the user names (\"my vacation video\", \"the Tolkien ebook\"): media action=find, " +
                "then widget action=add with the returned path. If nothing matches, say so briefly.\n" +
                "- Wallpaper/background requests: wallpaper action=set with a vivid visual description " +
                "(this also switches to desktop mode). Themes: theme action=set.\n" +
                "- Update times (\"refresh every 10 minutes\") → refresh_seconds. \"Undo\" → desktop " +
                "action=undo. \"Take a screenshot\" → desktop action=screenshot.\n" +
                "- To change what a widget shows (page, chapter, play/pause, mute, reload, new URL) use " +
                "widget action=navigate. To rename a widget pass new_title; 'id' or 'title' only identify it.\n" +
                "- YOU CAN SEE THE DISPLAY: a screenshot of the glasses arrives as a video frame every few " +
                "seconds, and a fresh one is sent immediately BEFORE each tool result that changed the " +
                "screen. So when a result arrives, the latest frame already shows the outcome — check it " +
                "and confirm right away (never stay silent waiting for another frame). Verify what matters: " +
                "is the video actually showing and playing, did the page change, is the window where the " +
                "user wanted it? If the screen shows something different from what the tool reported (an " +
                "overlay, an intro screen, a login wall, 'station unresponsive', a blank window), say what " +
                "you see and fix it (click the overlay's button, press next, scroll, retry) instead of " +
                "claiming success. desktop action=describe gives exact widget ids when needed.\n" +
                "- After tools return, confirm in ONE short spoken sentence. Replies are read aloud on " +
                "glasses: no lists, no markdown, never read URLs, ids or file paths aloud.\n" +
                "- Answer in the language the user speaks."
    }

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }

    interface LiveSessionListener {
        fun onSessionReady()
        fun onInputTranscription(text: String)
        fun onOutputTranscription(text: String)
        fun onModelText(text: String)
        fun onModelAudio(mimeType: String, data: ByteArray)
        fun onToolCall(callId: String, name: String, args: String)
        /** The server withdrew these pending tool calls (user barged in). */
        fun onToolCallCancellation(ids: List<String>) {}
        /** The server will close the connection soon. */
        fun onGoAway(timeLeft: String?) {}
        fun onTurnComplete(finishReason: String?)
        fun onInterrupted() {}
        fun onError(message: String)
        fun onClosed(code: Int, reason: String)
    }

    class LiveSessionHandle internal constructor(private val socket: WebSocket) {
        fun sendAudioChunkPcm16(bytes: ByteArray, size: Int, sampleRateHz: Int = 16_000): Boolean {
            if (size <= 0) return false
            val chunk = JSONObject()
                .put("mimeType", "audio/pcm;rate=$sampleRateHz")
                .put("data", Base64.getEncoder().encodeToString(bytes.copyOf(size)))
            return socket.send(JSONObject().put("realtimeInput", JSONObject().put("audio", chunk)).toString())
        }

        /** A still of the display as a video frame (image/jpeg) — the model's eyes on the glasses. */
        fun sendImageFrame(jpeg: ByteArray): Boolean {
            if (jpeg.isEmpty()) return false
            val frame = JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", Base64.getEncoder().encodeToString(jpeg))
            return socket.send(JSONObject().put("realtimeInput", JSONObject().put("video", frame)).toString())
        }

        fun sendClientText(text: String): Boolean {
            if (text.isBlank()) return false
            val payload = JSONObject().put(
                "clientContent",
                JSONObject()
                    .put("turns", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))))
                    .put("turnComplete", true)
            )
            return socket.send(payload.toString())
        }

        fun sendToolResponse(callId: String, functionName: String, result: String): Boolean {
            if (callId.isBlank() || functionName.isBlank()) return false
            val fr = JSONObject().put("id", callId).put("name", functionName)
                .put("response", JSONObject().put("result", result))
            val payload = JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(fr)))
            return socket.send(payload.toString())
        }

        fun close() {
            socket.close(1000, "client_close")
        }
    }

    fun startLiveAudioSession(listener: LiveSessionListener): LiveSessionHandle? {
        val apiKey = apiKeyProvider()?.trim()?.takeIf { it.isNotBlank() }
        if (apiKey == null) {
            listener.onError("Gemini API key missing — push it via adb (see README).")
            return null
        }
        // The bidi endpoint authenticates with ?key=; the value is never logged.
        val request = Request.Builder().url("$LIVE_WS_URL?key=$apiKey").build()
        Log.d(TAG, "Starting Gemini Live model=$LIVE_MODEL")

        val socket = wsClient.newWebSocket(request, object : WebSocketListener() {
            private var setupReady = false
            private var setupSent = false

            private fun notifySetupReady() {
                if (setupReady) return
                setupReady = true
                listener.onSessionReady()
            }

            private fun sendSetup(webSocket: WebSocket): Boolean {
                if (setupSent) return true
                val prompt = buildString {
                    append(SYSTEM_PROMPT)
                    val now = java.util.Date()
                    val zone = java.util.TimeZone.getDefault()
                    val ts = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy h:mm a", java.util.Locale.US)
                        .apply { timeZone = zone }.format(now)
                    append("\n\nCURRENT DATE/TIME: $ts (${zone.id}). Authoritative device clock.")
                    desktopSummaryProvider()?.trim()?.takeIf { it.isNotBlank() }?.let {
                        append("\n\nON SCREEN NOW:\n").append(it)
                    }
                }
                val setupContent = JSONObject()
                    .put("model", "models/$LIVE_MODEL")
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                // Screen frames must stay legible (small UI text on a 640x480 display).
                .put("mediaResolution", "MEDIA_RESOLUTION_HIGH"))
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject())
                    .put("tools", JSONArray()
                        .put(JSONObject().put("functionDeclarations", buildToolDeclarations()))
                        .put(JSONObject().put("googleSearch", JSONObject())))
                    // Long design sessions: let the server compress old context
                    // instead of cutting the connection at the window limit.
                    .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
                    .put("realtimeInputConfig", JSONObject().put(
                        "automaticActivityDetection", JSONObject()
                            .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                            .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                            .put("silenceDurationMs", 400)))
                val setup = JSONObject().put("setup", setupContent)
                Log.d(TAG, "setup payload: ${setup.toString().take(200)}…")
                if (!webSocket.send(setup.toString())) {
                    listener.onError("Failed to send Gemini Live setup message.")
                    return false
                }
                setupSent = true
                return true
            }

            private fun JSONObject.optCompletionFlag(vararg keys: String): Boolean {
                for (key in keys) {
                    when (val value = opt(key) ?: continue) {
                        is Boolean -> if (value) return true
                        is String -> if (value.equals("true", ignoreCase = true)) return true
                        is JSONObject -> if (value.optBoolean("complete") || value.optBoolean("completed") ||
                            value.optBoolean("turnComplete") || value.optBoolean("turn_complete")) return true
                    }
                }
                return false
            }

            private fun handleLiveMessage(decoded: String) {
                if (decoded.contains("\"inlineData\"") && decoded.contains("\"audio/")) {
                    Log.d(TAG, "inbound: <audio frame ${decoded.length} chars>")
                } else {
                    Log.d(TAG, "inbound: ${decoded.take(300)}")
                }
                runCatching {
                    val root = JSONObject(decoded)
                    root.optJSONObject("error")?.let {
                        listener.onError(it.optString("message", "Gemini Live returned an error."))
                        return@runCatching
                    }
                    if (root.has("setupComplete") || root.has("setup_complete")) notifySetupReady()

                    (root.optJSONObject("goAway") ?: root.optJSONObject("go_away"))?.let {
                        listener.onGoAway(it.optString("timeLeft").takeIf { t -> t.isNotBlank() })
                    }

                    val sc = root.optJSONObject("serverContent") ?: root.optJSONObject("server_content")
                    if (sc != null) {
                        notifySetupReady()
                        if (sc.optBoolean("interrupted", false)) listener.onInterrupted()
                        (sc.optJSONObject("inputTranscription") ?: sc.optJSONObject("input_transcription"))
                            ?.optString("text", "")?.trim()?.takeIf { it.isNotBlank() }
                            ?.let { listener.onInputTranscription(it) }
                        (sc.optJSONObject("outputTranscription") ?: sc.optJSONObject("output_transcription"))
                            ?.optString("text", "")?.trim()?.takeIf { it.isNotBlank() }
                            ?.let { listener.onOutputTranscription(it) }
                        val parts = (sc.optJSONObject("modelTurn") ?: sc.optJSONObject("model_turn"))?.optJSONArray("parts")
                        if (parts != null) for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            part.optString("text", "").trim().takeIf { it.isNotBlank() }?.let { listener.onModelText(it) }
                            val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                            if (inline != null) {
                                val mime = inline.optString("mimeType", "")
                                val encoded = inline.optString("data", "")
                                if (mime.startsWith("audio/") && encoded.isNotBlank()) {
                                    listener.onModelAudio(mime, Base64.getDecoder().decode(encoded))
                                }
                            }
                        }
                        if (sc.optCompletionFlag("turnComplete", "turn_complete", "generationComplete", "generation_complete")) {
                            listener.onTurnComplete(null)
                        }
                    }

                    val cancel = root.optJSONObject("toolCallCancellation") ?: root.optJSONObject("tool_call_cancellation")
                    cancel?.optJSONArray("ids")?.let { arr ->
                        val ids = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                        if (ids.isNotEmpty()) listener.onToolCallCancellation(ids)
                    }

                    val toolCall = root.optJSONObject("toolCall") ?: root.optJSONObject("tool_call")
                    val calls = toolCall?.optJSONArray("functionCalls") ?: toolCall?.optJSONArray("function_calls")
                    if (calls != null) for (i in 0 until calls.length()) {
                        val call = calls.optJSONObject(i) ?: continue
                        val fc = call.optJSONObject("functionCall") ?: call.optJSONObject("function_call")
                        val callId = sequenceOf(call.optString("id", "").trim(), call.optString("callId", "").trim(),
                            fc?.optString("id", "")?.trim().orEmpty()).firstOrNull { it.isNotBlank() }
                            ?: "tool-call-${System.currentTimeMillis()}-$i"
                        val name = sequenceOf(fc?.optString("name", "")?.trim().orEmpty(), call.optString("name", "").trim())
                            .firstOrNull { it.isNotBlank() }.orEmpty()
                        val args = sequenceOf(fc?.optJSONObject("args")?.toString().orEmpty(),
                            fc?.optString("args", "")?.trim().orEmpty(), call.optJSONObject("args")?.toString().orEmpty(),
                            call.optString("args", "")?.trim().orEmpty()).firstOrNull { it.isNotBlank() }.orEmpty()
                        if (name.isNotBlank()) listener.onToolCall(callId, name, args)
                    }
                }.onFailure { listener.onError("Failed to parse Live response: ${it.message}") }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "websocket opened"); sendSetup(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handleLiveMessage(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { bytes.utf8() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { handleLiveMessage(it) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { listener.onClosed(code, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "websocket failure: ${t.message} (${response?.code})")
                val http = response?.code?.let { " (HTTP $it)" }.orEmpty()
                listener.onError((t.message ?: "Gemini Live connection failed") + http)
            }
        })
        return LiveSessionHandle(socket)
    }

    // ── Tool declarations ───────────────────────────────────────────

    private fun prop(desc: String) = JSONObject().put("type", "STRING").put("description", desc)

    private fun decl(name: String, description: String, props: Map<String, String>): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, prop(v)) }
        return JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", p)
                .put("required", JSONArray().put("action")))
    }

    private val geometryProps = mapOf(
        "x" to "Left edge in px (0-640).", "y" to "Top edge in px (44-480).",
        "w" to "Width in px.", "h" to "Height in px.",
        "anchor" to "top_left|top|top_right|left|center|right|bottom_left|bottom|bottom_right.",
        "size" to "small|medium|large|full|half_left|half_right|wide|tall."
    )

    private fun buildToolDeclarations(): JSONArray = JSONArray()
        .put(decl("desktop",
            "Whole-desktop operations. describe: what's on screen. arrange: re-lay out ALL windows cleanly " +
                "(layout grid|columns|rows|cascade; focus=<widget> makes that window big and tiles the rest). " +
                "new: fresh empty desktop (name). save: snapshot under a name (thumbnail appears in the strip). " +
                "load: switch to a saved desktop by name. delete: remove a saved desktop by exact name. " +
                "list: saved desktops. rename. set_mode: hud or desktop. undo: revert the last change. " +
                "clear: remove all widgets. screenshot: save a picture of the display to the photo gallery. " +
                "locate: where the glasses are right now (place name + coordinates).",
            mapOf("action" to "describe|arrange|new|save|load|delete|list|rename|set_mode|undo|clear|screenshot|locate",
                "name" to "Desktop name for new/save/load/delete/rename.",
                "mode" to "set_mode: hud or desktop.",
                "layout" to "arrange: grid (default) | columns | rows | cascade.",
                "focus" to "arrange: id/title of the window that should get most of the space.",
                "gap" to "arrange: gap between windows in px (default 8).")))
        .put(decl("widget",
            "Add, change, move, resize, style, refresh, navigate or remove one widget. " +
                "add needs type; content by type: text (text, or prompt= to generate), clock (format), " +
                "live (query), image/video/audio/pdf/epub/model3d (path or url — use media find first for " +
                "local files), web (url), app (via app_builder), map (query=place, zoom). Position via x,y or " +
                "anchor; size via w,h or size name. navigate: nav=next|prev|page|chapter|play|pause|mute|" +
                "unmute|loop|reload|url|seek with value. Identify existing widgets by id, title, or type; " +
                "use new_title to rename. pin: keep a window/ticker above all others (on_top=true|false|toggle).",
            mapOf("action" to "add|update|remove|move|resize|front|pin|list|navigate|refresh",
                "id" to "Widget id or title (fuzzy) for non-add actions; or 'last'.",
                "type" to "add: text|clock|live|ticker|image|video|audio|pdf|epub|web|app|model3d|map.",
                "new_window" to "add: true to open a second window even if the same content is already open.",
                "on_top" to "pin/update: true = stay on top of all other windows, false = stop, toggle = flip.",
                "directions" to "map: true for navigation/directions to the place.",
                "travel_mode" to "map directions: driving|walking|bicycling|transit.",
                "style" to "map: google (default) | simple (clean offline-style tile map).",
                "title" to "add: display title. Other actions: identifies the widget (does NOT rename).",
                "new_title" to "update: rename the widget.",
                "text" to "text: literal body. update: new body.",
                "prompt" to "text: instruction to GENERATE the body (regenerated each refresh).",
                "query" to "live/ticker: what to watch or scroll. map: the place (or destination).",
                "url" to "web/image/video/audio/pdf/epub/model3d: http(s) URL.",
                "path" to "Local file path from media find.",
                "format" to "clock: time | time+date | time+seconds.",
                "zoom" to "map style=simple: 1 (world) to 18 (street); default 13.") + geometryProps + mapOf(
                "dx" to "move: horizontal delta px.", "dy" to "move: vertical delta px.",
                "scale" to "resize: multiply size (1.5 bigger, 0.7 smaller).",
                "refresh_seconds" to "0 = never; e.g. 300 for every 5 minutes.",
                "text_color" to "Hex or color name.", "bg_color" to "Hex or color name.",
                "opacity" to "0.1-1.", "font_size" to "Text size px (e.g. 14, 22).",
                "corner_radius" to "Panel corner radius px.",
                "chrome" to "true/false: show the title bar.",
                "autoplay" to "video/audio add: true/false.", "loop" to "video/audio: true/false.",
                "muted" to "video/audio: true/false.",
                "page" to "pdf: 1-based page.", "chapter" to "epub: 1-based chapter.",
                "nav" to "navigate: next|prev|page|chapter|play|pause|mute|unmute|loop|reload|url|seek | navigation: start|next|prev|repeat|stop.",
                "value" to "navigate: page/chapter number, seconds, or url.")))
        .put(decl("web",
            "Operate a web page or app widget like a user would. inspect: numbered list of visible " +
                "buttons, links, fields and media. read: page title and main text. click: press the " +
                "element whose visible text/label matches target_text (or index from inspect). type: put " +
                "text into the field matched by field_text (label, placeholder or name); submit=true " +
                "presses enter afterwards. press: send a key (enter|escape|space|tab|arrow_down|arrow_up|" +
                "backspace). scroll: direction up|down|left|right|top|bottom, amount px (default 300). " +
                "play / pause: media on the page. url: open a URL in the same widget. back, forward, reload.",
            mapOf("action" to "inspect|read|click|type|press|scroll|play|pause|url|back|forward|reload",
                "target" to "Widget id/title; defaults to the most recent web or app widget.",
                "target_text" to "click: visible text, aria-label or placeholder of the element.",
                "index" to "click: number from the last inspect.",
                "field_text" to "type: label/placeholder/name of the field (omit = the focused field).",
                "text" to "type: the text to enter.",
                "submit" to "type: true to press enter after typing.",
                "key" to "press: enter|escape|space|tab|arrow_down|arrow_up|backspace.",
                "direction" to "scroll: up|down|left|right|top|bottom.",
                "amount" to "scroll: pixels (default 300).",
                "url" to "url: address to open.")))
        .put(decl("theme",
            "Set the look of all widgets. Presets: midnight, neon, paper, forest, sunset, mono, ocean; " +
                "or custom accent/panel/text colors, font_scale, corner_radius.",
            mapOf("action" to "set|list", "name" to "Preset name.",
                "accent" to "Hex/name.", "panel" to "Panel background hex/name (can include alpha).",
                "text_color" to "Hex/name.", "font_scale" to "0.6-2.2.", "corner_radius" to "px.")))
        .put(decl("wallpaper",
            "Desktop background. set with description → an image is painted from the words (switches to " +
                "desktop mode). Or kind=gradient/color with colors (comma-separated hex or names). clear = none.",
            mapOf("action" to "set|clear", "description" to "Vivid visual description to paint.",
                "kind" to "image|gradient|color|none.", "colors" to "Comma-separated colors for gradient/color.")))
        .put(decl("app_builder",
            "Vibe-code a mini web app as a widget from a plain-language description (calculator, timer, " +
                "pomodoro, notes, dice, breathing guide, unit converter, tiny game...). create builds and " +
                "places it; update changes an existing app by name.",
            mapOf("action" to "create|update", "name" to "App name (also the widget title).",
                "description" to "What it should do and look like; for update, what to change.") + geometryProps))
        .put(decl("media",
            "Find media files on the glasses by name/type ('vacation', 'tolkien', 'podcast'). find returns " +
                "matches with paths; open finds and adds the best match as a widget in one step.",
            mapOf("action" to "find|open", "query" to "Words from the file name.",
                "type" to "image|video|audio|pdf|epub|model3d|text|any.",
                "path" to "open: exact path if already known.",
                "anchor" to geometryProps.getValue("anchor"), "size" to geometryProps.getValue("size"))))
}
