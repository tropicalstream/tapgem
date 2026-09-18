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
        /** Context window of the Live model (models.get inputTokenLimit); compression keeps sessions under it. */
        const val LIVE_CONTEXT_TOKENS = 131_072

        private const val SYSTEM_PROMPT =
            "You are TapGem, the voice-driven desktop designer for RayNeo X3 Pro AR glasses. The user " +
                "iterates a heads-up display (HUD) and a full-window desktop by talking to you. You place, " +
                "move, resize, tile, style, refresh, save, load and delete WIDGETS, change themes and " +
                "wallpapers, and operate web pages and apps inside widgets — always by calling tools, never " +
                "by describing what you would do.\n\n" +
                "CANVAS: 640x480 logical pixels. A status strip owns y 0-40 (three drawer buttons — apps & widgets, " +
                "bookmarks, wallpapers & themes — then saved-desktop " +
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
                "already has (describe lists them): bring it forward with widget action=front, change a card " +
                "with widget action=update, move within the SAME site with web action=url. A window is reused " +
                "only for its own site: a Radio Garden or YouTube window is never sent to Google Maps or " +
                "Spotify — a different site gets its own window (the user keeps what was open). Only pass " +
                "new_window=true when the user explicitly asks for a second window of the same site.\n" +
                "- \"Show a map of <place>\" / \"navigate to <place>\" / \"map of where I am\" → widget " +
                "action=add type=map query=<place>. Places and errands — restaurants, coffee, gas, 'near me', " +
                "'on the way to X', 'what's around here' — are map requests too: type=map query=<what> near " +
                "<where> (while navigating, 'on the way' means near the destination or route), never a " +
                "web search in some other window. NAVIGATION: directions=true (travel_mode walking|driving|" +
                "bicycling) starts TapGem's own turn-by-turn from the glasses' position: the route is drawn on the " +
                "map with the current step; read the tool result aloud (it holds the first step). 'Take me to X on " +
                "the way to Y' / 'stop at X first' → query=Y via=X (the route goes through X, then on to Y). Then " +
                "'next step' / 'previous step' / 'repeat' / 'stop navigation' → widget action=navigate " +
                "nav=next|prev|repeat|stop on that map; 'zoom in / zoom out / recenter' on it → nav=in|out|center " +
                "(exactly ONE call per request — a single call already zooms two levels; 'a lot' / 'all the way' → " +
                "value=6; on a Google Maps or any web window → web action=zoom direction=in|out — also ONE call, two levels; never by clicking around). \"Where am I?\" → desktop action=locate. A plain map window (no directions) is a web " +
                "window: search, directions, 'what's nearby' all happen through the web tool. Only " +
                "style=simple opens the tile map (zoom 1-18).\n" +
                "- CLOCKS: five faces — digital, thin, led, analog, modern — plus 12/24 h, seconds, date and world " +
                "cities. \"Make the clock analog\" / \"switch to 24 hour\" / \"show seconds\" → widget action=update " +
                "style|hours|seconds; \"add a Tokyo clock\" → add type=clock zones=Tokyo; \"world clock with London, " +
                "Tokyo and New York\" → add type=clock zones='London, Tokyo, New York' (one window, several cities); " +
                "\"add Paris to the clock\" → update add_zone=Paris. \"Open the clock settings\" / \"settings for the " +
                "ticker\" / \"window options\" → widget action=settings (every window has a ⚙ sheet: clocks their " +
                "faces and cities, cards their refresh rate, all windows stay-on-top, opacity, text size).\n" +
                "- \"Stock ticker\", \"news crawl\", \"scores ticker\" → widget action=add type=ticker " +
                "query=<what> — it scrolls along the bottom by default; refresh_seconds for how often.\n" +
                "- WEB PAGES are operated through the web tool. Every result tells you where the page is " +
                "now, what is on it (numbered items — click by index or by visible text; items marked 'below' " +
                "are scrolled to automatically) and whether the glasses are making sound. Act on that " +
                "directly; inspect only when you need the full list. Procedure to find something on a site: " +
                "web action=search text=<what> (finds the site's search box, opens it if it hides behind an " +
                "icon, presses enter), then click the matching result, then action=play if the user wants " +
                "it played (play verifies sound). 'Next / previous station, track, video' → click the page's " +
                "next/previous control (never search for the word 'next'). Radio Garden: 'balloon ride' → " +
                "click 'Balloon Ride Radio' then 'Take a ride'; 'exit the ride' → click 'Exit Balloon Mode'; " +
                "a station or city by name → action=search. Spotify: these glasses have no Widevine DRM, so its " +
                "web player streams only 30-second previews and only while signed out; signed in it shows " +
                "'Playback disabled' — say so, offer to sign out (web action=url https://www.spotify.com/logout/) " +
                "or to play the song on YouTube instead. YouTube Music (music.youtube.com): once a track plays, " +
                "its player page has a Song / Video switch — 'show the video' → click 'Video', 'just the audio' → click 'Song'; " +
                "'full screen' → click 'Enter full screen' (fills the window; action=back leaves it). " +
                "Never guess deep links from memory; never repeat a call that just failed — change " +
                "approach or say what is blocking (login wall, intro overlay, nothing found). Follow " +
                "spelled-out steps literally. Sites the user names casually (YouTube, Radio " +
                "Garden, the Internet Archive) → widget action=add type=web with the obvious URL, or " +
                "web action=url in the open web window. Nothing on this device has a keyboard — typing " +
                "only happens through the web tool.\n" +
                "- \"Open / show / bring up / launch X\" means something that EXISTS: a window on this desktop " +
                "(widget action=front), a bookmark (bookmark action=open), a saved app (app_builder action=open), a " +
                "media file (media action=find), or a website. Try those. Only the thing NAMED counts — a different " +
                "saved app or game is not a match (asked for golf, don't open checkers). If nothing matches, say so " +
                "and ASK whether to build it — e.g. \"There's no golf game yet; want me to make one?\" — then " +
                "call app_builder create only after the user says yes, or when they explicitly asked to " +
                "make/build/create it. Never build or substitute something the user only asked to open.\n" +
                "- \"Bookmark this / save the checkers game for later / keep this window\" → bookmark action=save " +
                "(the active window unless one is named). \"Open my checkers bookmark / bring back the radio\" → " +
                "bookmark action=open name=<it>. \"Keep / bookmark / save this wallpaper (background)\" → bookmark " +
                "action=save target=wallpaper; \"use my reef wallpaper / put the coral background back\" → " +
                "bookmark action=open name=<it> (applies it to this desktop). \"Show / hide my bookmarks\" → " +
                "action=show|hide; \"forget the … bookmark\" → action=delete. Three drawers, no overlap: APPS (every " +
                "app, opening with its last saved state — saving an app window files it there), BOOKMARKS (saved " +
                "pages and other windows: a video, a PDF at its page, a map), WALLPAPERS & THEMES. A desktop is the " +
                "whole layout (save/load); a bookmark is one window you can drop onto any desktop.\n" +
                "- Media the user names (\"my vacation video\", \"the Tolkien ebook\"): media action=find, " +
                "then widget action=add with the returned path. If nothing matches, say so briefly.\n" +
                "- Wallpaper/background requests: wallpaper action=set with a vivid visual description " +
                "(this also switches to desktop mode). Themes: theme action=set.\n" +
                "- Update times (\"refresh every 10 minutes\") → refresh_seconds. \"Undo\" → desktop " +
                "action=undo. \"Show my apps / open the app drawer\" → desktop action=apps; \"show wallpapers / " +
                "show themes\" → desktop action=wallpapers (the drawer lists every wallpaper and the theme presets).\n" +
                "- To change what a widget shows (page, chapter, play/pause, mute, reload, new URL) use " +
                "widget action=navigate. To rename a widget pass new_title; 'id' or 'title' only identify it.\n" +
                "- YOU CAN SEE THE DISPLAY: a frame of the glasses arrives every few seconds and a fresh one " +
                "right before every tool result that changed the screen, so the latest frame already shows " +
                "the outcome — check it and confirm at once; never wait silently for another frame. If the " +
                "screen disagrees with a result (intro overlay, login wall, blank window, wrong page), say " +
                "what you see and fix it instead of claiming success. desktop action=describe gives exact " +
                "widget ids when needed.\n" +
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
        /** Token accounting for the session so far (prompt = everything the model holds, response = what it produced). */
        fun onUsage(promptTokens: Int, responseTokens: Int, totalTokens: Int, byModality: Map<String, Int>) {}
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
                    (root.optJSONObject("usageMetadata") ?: root.optJSONObject("usage_metadata"))?.let { u ->
                        val mods = HashMap<String, Int>()
                        (u.optJSONArray("promptTokensDetails") ?: u.optJSONArray("prompt_tokens_details"))?.let { arr ->
                            for (i in 0 until arr.length()) { val d = arr.optJSONObject(i) ?: continue; mods[d.optString("modality", "?")] = d.optInt("tokenCount", d.optInt("token_count")) }
                        }
                        listener.onUsage(u.optInt("promptTokenCount", u.optInt("prompt_token_count")),
                            u.optInt("responseTokenCount", u.optInt("response_token_count")),
                            u.optInt("totalTokenCount", u.optInt("total_token_count")), mods)
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
                "clear: remove all widgets. apps: open the apps & widgets drawer (lists every app, widget kind and " +
                "site). wallpapers: open the wallpapers & themes drawer. " +
                "locate: where the glasses are right now (place name + coordinates). phone_gps: check whether " +
                "the paired phone is streaming its GPS to the glasses (troubleshooting). usage: which models " +
                "are in use and this session's token/turn/tool counts — call it for any question about the " +
                "model, tokens, context or quota, and read the numbers back plainly.",
            mapOf("action" to "describe|arrange|new|save|load|delete|list|rename|set_mode|undo|clear|apps|wallpapers|locate|phone_gps|usage",
                "name" to "Desktop name for new/save/load/delete/rename.",
                "mode" to "set_mode: hud or desktop.",
                "layout" to "arrange: grid (default) | columns | rows | cascade.",
                "focus" to "arrange: id/title of the window that should get most of the space.",
                "gap" to "arrange: gap between windows in px (default 8).")))
        .put(decl("widget",
            "Add, change, move, resize, style, refresh, navigate or remove one widget. " +
                "add needs type; content by type: text (text, or prompt= to generate), clock (style, hours, seconds, date, zones), " +
                "live (query), image/video/audio/pdf/epub/model3d (path or url — use media find first for " +
                "local files), web (url), app (via app_builder), map (query=place, zoom). Position via x,y or " +
                "anchor; size via w,h or size name. navigate: nav=next|prev|page|chapter|play|pause|mute|" +
                "unmute|loop|reload|url|seek with value. Identify existing widgets by id, title, or type; " +
                "use new_title to rename. pin: keep a window/ticker above all others (on_top=true|false|toggle).",
            mapOf("action" to "add|update|remove|move|resize|front|pin|list|navigate|refresh|settings",
                "id" to "Widget id or title (fuzzy) for non-add actions; or 'last'.",
                "type" to "add: text|clock|live|ticker|image|video|audio|pdf|epub|web|app|model3d|map.",
                "new_window" to "add: true to open a second window even if the same content is already open.",
                "on_top" to "pin/update: true = stay on top of all other windows, false = stop, toggle = flip.",
                "directions" to "map: true for navigation/directions to the place.",
                "travel_mode" to "map directions: driving|walking|bicycling|transit.",
                "via" to "map directions: stop(s) to route through first, comma-separated ('Glenview Taqueria').",
                "style" to "map: google (default) | simple (clean offline-style tile map).",
                "title" to "add: display title. Other actions: identifies the widget (does NOT rename).",
                "new_title" to "update: rename the widget.",
                "text" to "text: literal body. update: new body.",
                "prompt" to "text: instruction to GENERATE the body (regenerated each refresh).",
                "query" to "live/ticker: what to watch or scroll. map: the place (or destination).",
                "url" to "web/image/video/audio/pdf/epub/model3d: http(s) URL.",
                "path" to "Local file path from media find.",
                "format" to "clock: time | time+date | time+seconds.",
                "style" to "clock: digital|thin|led|analog|modern (the five faces).",
                "hours" to "clock: 12|24.",
                "seconds" to "clock: true|false — show seconds / a second hand.",
                "date" to "clock: true|false — show the date.",
                "zones" to "clock: world-clock cities or zone ids, comma-separated ('Tokyo, London'; 'local' = here). add_zone / remove_zone edit the list.",
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
            "Operate a web page or app widget like a user would. Every action's result reports the page " +
                "it landed on, the items now on it (numbered; usable as index) and whether sound is playing. " +
                "search: type text into the search box OF THE SITE ALREADY OPEN in the target window and press " +
                "enter (opens the box if it hides behind an icon) — it searches within that site only, e.g. " +
                "songs on Spotify, stations on Radio Garden; it is NOT a web search. Places, restaurants, " +
                "'near me' → widget add type=map; facts → widget add type=live. inspect: full numbered list of buttons, links, fields, rows and media, " +
                "including ones below the fold. read: page title and main text. click: the item whose " +
                "visible text/label matches target_text, or index from the last list. type: put text into " +
                "the field matched by field_text (label/placeholder); submit=true presses enter. press: a " +
                "key (enter|escape|space|tab|arrow_down|arrow_up|backspace). scroll: direction " +
                "up|down|left|right|top|bottom, amount px (default 300). zoom: direction in|out (Google Maps zooms " +
                "the map; other pages scale). play / pause: the page's media, " +
                "verified by sound. url: open a URL in the same widget. back, forward, reload.",
            mapOf("action" to "search|inspect|read|click|type|press|scroll|zoom|play|pause|url|back|forward|reload",
                "target" to "Widget id/title; defaults to the most recent web or app widget.",
                "target_text" to "click: visible text, aria-label or placeholder of the element.",
                "index" to "click: number from the last inspect.",
                "field_text" to "type: label/placeholder/name of the field (omit = the focused field).",
                "text" to "search / type: the text to enter.",
                "submit" to "type: true to press enter after typing.",
                "key" to "press: enter|escape|space|tab|arrow_down|arrow_up|backspace.",
                "direction" to "scroll: up|down|left|right|top|bottom. zoom: in|out.",
                "amount" to "scroll: pixels (default 300).",
                "url" to "url: address to open.")))
        .put(decl("bookmark",
            "Windows and wallpapers saved for later, shared by every desktop, shown in the bookmarks panel " +
                "(the ribbon next to the camera). save: snapshot a window with its current state (an app " +
                "mid-game, a page, a PDF at its page), or target=wallpaper to keep this desktop's wallpaper; " +
                "open: put a saved window on this desktop, or apply a saved wallpaper to it; list; delete; " +
                "show/hide the panel.",
            mapOf("action" to "save|open|list|delete|show|hide",
                "target" to "save: the window to save (id/title; defaults to the active window), or 'wallpaper'.",
                "name" to "save: a name for it (defaults to the window title / wallpaper description). open/delete: which bookmark.")))
        .put(decl("theme",
            "Set the look of all widgets. Presets: midnight, neon, paper, forest, sunset, mono, ocean, wood (dark " +
                "walnut grain with brass accents); " +
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
            "Mini web apps as widgets. open: put a previously built app back on the desktop by name; list: " +
                "the saved apps. create: vibe-code a NEW app from a plain-language description (calculator, " +
                "timer, notes, dice, tiny game...) — only when the user asked to make/build one or said yes to " +
                "your offer; if a saved app or bookmark with that name exists it is opened instead. update " +
                "changes an existing app by name.",
            mapOf("action" to "open|list|create|update", "name" to "App name (also the widget title).",
                "description" to "create: what it should do and look like; update: what to change.",
                "rebuild" to "create: true to build a fresh version even though one is saved.") + geometryProps))
        .put(decl("media",
            "Find media files on the glasses by name/type ('vacation', 'tolkien', 'podcast'). find returns " +
                "matches with paths; open finds and adds the best match as a widget in one step.",
            mapOf("action" to "find|open", "query" to "Words from the file name.",
                "type" to "image|video|audio|pdf|epub|model3d|text|any.",
                "path" to "open: exact path if already known.",
                "anchor" to geometryProps.getValue("anchor"), "size" to geometryProps.getValue("size"))))
}
