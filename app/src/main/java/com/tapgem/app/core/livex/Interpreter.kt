package com.tapgem.app.core.livex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live interpreter on gemini-3.5-live-translate-preview: a continuous stream, not turns.
 *  listen        — the room → me: target = my language; speech already in it stays silent.
 *  speak         — me → them: target = their language.
 *  conversation  — both at once: two sessions on the same mic, each silent for its own language.
 */
object Interpreter {
    val LANGS: List<Pair<String, String>> = listOf(
        "af" to "Afrikaans", "ak" to "Akan", "sq" to "Albanian", "am" to "Amharic", "ar" to "Arabic", "hy" to "Armenian", "az" to "Azerbaijani", "eu" to "Basque",
        "be" to "Belarusian", "bn" to "Bengali", "bg" to "Bulgarian", "my" to "Burmese", "ca" to "Catalan", "zh-Hans" to "Chinese (Simplified)", "zh-Hant" to "Chinese (Traditional)",
        "hr" to "Croatian", "cs" to "Czech", "da" to "Danish", "nl" to "Dutch", "en" to "English", "et" to "Estonian", "fil" to "Filipino", "fi" to "Finnish", "fr" to "French",
        "gl" to "Galician", "ka" to "Georgian", "de" to "German", "el" to "Greek", "gu" to "Gujarati", "ha" to "Hausa", "he" to "Hebrew", "hi" to "Hindi", "hu" to "Hungarian",
        "is" to "Icelandic", "id" to "Indonesian", "it" to "Italian", "ja" to "Japanese", "jv" to "Javanese", "kn" to "Kannada", "kk" to "Kazakh", "km" to "Khmer",
        "rw" to "Kinyarwanda", "ko" to "Korean", "lo" to "Lao", "lv" to "Latvian", "lt" to "Lithuanian", "mk" to "Macedonian", "ms" to "Malay", "ml" to "Malayalam",
        "mr" to "Marathi", "mn" to "Mongolian", "ne" to "Nepali", "no" to "Norwegian", "fa" to "Persian", "pl" to "Polish", "pt-BR" to "Portuguese (Brazil)", "pt-PT" to "Portuguese (Portugal)",
        "pa" to "Punjabi", "ro" to "Romanian", "ru" to "Russian", "sr" to "Serbian", "sd" to "Sindhi", "si" to "Sinhala", "sk" to "Slovak", "sl" to "Slovenian", "es" to "Spanish",
        "su" to "Sundanese", "sw" to "Swahili", "sv" to "Swedish", "ta" to "Tamil", "te" to "Telugu", "th" to "Thai", "tr" to "Turkish", "uk" to "Ukrainian", "ur" to "Urdu",
        "uz" to "Uzbek", "vi" to "Vietnamese", "zu" to "Zulu")
    private val ALIASES = mapOf("mandarin" to "zh-Hans", "chinese" to "zh-Hans", "simplified chinese" to "zh-Hans", "traditional chinese" to "zh-Hant", "cantonese" to "zh-Hant",
        "portuguese" to "pt-BR", "brazilian" to "pt-BR", "tagalog" to "fil", "farsi" to "fa", "castilian" to "es", "norsk" to "no")

    fun langCode(name: String?): String? {
        val q = name?.trim()?.lowercase() ?: return null
        LANGS.firstOrNull { it.first.lowercase() == q }?.let { return it.first }
        ALIASES[q]?.let { return it }
        LANGS.firstOrNull { it.second.lowercase() == q }?.let { return it.first }
        return LANGS.firstOrNull { it.second.lowercase().startsWith(q) || q.startsWith(it.second.lowercase()) }?.first
    }
    fun langName(code: String) = LANGS.firstOrNull { it.first.equals(code, true) }?.second ?: LANGS.firstOrNull { it.first.substringBefore('-').equals(code.substringBefore('-'), true) }?.second ?: code

    class Entry(val seq: Long, val t: Long, val dir: String, val kind: String, val text: String, val lang: String) {
        fun json() = JSONObject().put("seq", seq).put("t", t).put("dir", dir).put("kind", kind).put("text", text).put("lang", lang).put("langName", langName(lang))
    }
    fun interface Listener { fun onEvent(json: JSONObject) }
    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun emit(o: JSONObject) { for (l in listeners) runCatching { l.onEvent(o) } }

    private lateinit var appContext: Context
    @Volatile var mode = "listen"; private set
    @Volatile var mine = "en"; private set
    @Volatile var theirs = "es"; private set
    @Volatile var status = "off"; private set     // off | connecting | live
    private val sessions = ArrayList<LiveAudioSession>()
    private val entries = ArrayList<Entry>()
    private var seq = 0L
    private val partial = HashMap<String, StringBuilder>()   // "<dir>:<in|out>" → text of the phrase being built
    private var startedAt = 0L

    fun init(context: Context) { appContext = context.applicationContext; MicOwner.registerApp { if (status != "off") stop("the assistant took the microphone") } }

    fun configure(mode: String? = null, mine: String? = null, theirs: String? = null) {
        mode?.let { if (it in setOf("listen", "speak", "conversation")) this.mode = it }
        mine?.let { langCode(it)?.let { c -> this.mine = c } }
        theirs?.let { langCode(it)?.let { c -> this.theirs = c } }
        if (status != "off") start()
        emit(JSONObject().put("type", "config"))
    }

    @Synchronized fun start() {
        stopSessions()
        status = "connecting"; startedAt = System.currentTimeMillis(); emit(JSONObject().put("type", "status").put("status", status))
        startFlusher()
        // dir "in": the room → me (target = mine); dir "out": me → them (target = theirs)
        val plan = when (mode) { "listen" -> listOf("in" to mine); "speak" -> listOf("out" to theirs); else -> listOf("in" to mine, "out" to theirs) }
        for ((dir, target) in plan) {
            val setup = JSONObject().put("model", "models/gemini-3.5-live-translate-preview")
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")).put("translationConfig", JSONObject().put("targetLanguageCode", target).put("echoTargetLanguage", false)))
                .put("inputAudioTranscription", JSONObject()).put("outputAudioTranscription", JSONObject())
            val s = LiveAudioSession(appContext, dir, setup) { onSession(it) }
            sessions += s; s.open()
        }
    }

    private fun onSession(o: JSONObject) {
        val dir = o.optString("session")
        when (o.optString("type")) {
            "ready" -> { if (sessions.all { it.ready }) { status = "live"; emit(JSONObject().put("type", "status").put("status", status)) } }
            "in", "out" -> {
                // In conversation mode both sessions transcribe the same input; keep one copy of each source phrase.
                if (o.optString("type") == "in" && mode == "conversation" && dir == "out") return
                val kind = o.optString("type"); val key = "$dir:$kind"; val sb = partial.getOrPut(key) { StringBuilder() }
                sb.append(o.optString("text")); lastText[key] = System.currentTimeMillis(); o.optString("lang").takeIf { it.isNotBlank() }?.let { langOf[key] = it }
                emit(JSONObject().put("type", "partial").put("dir", dir).put("kind", kind).put("text", sb.toString().trim()).put("lang", langOf[key] ?: "").put("langName", langOf[key]?.let { langName(it) } ?: ""))
                val t = sb.toString().trimEnd()
                // a phrase is done at sentence punctuation, when it gets long, or after a pause (see flusher)
                if (sb.length > 300 || t.endsWith(".") || t.endsWith("?") || t.endsWith("!") || t.endsWith("。") || t.endsWith("？") || t.endsWith("！")) commit(key)
            }
            "closed" -> { if (status != "off") { status = "off"; emit(JSONObject().put("type", "status").put("status", "off").put("text", o.optString("text"))) } }
            "error" -> emit(JSONObject().put("type", "error").put("text", o.optString("text")))
        }
    }

    private val lastText = HashMap<String, Long>()
    private val langOf = HashMap<String, String>()
    private var flusher: Thread? = null
    private fun commit(key: String) {
        val sb = partial[key] ?: return; val text = sb.toString().trim(); sb.setLength(0)
        val (dir, kind) = key.split(':')
        if (text.isNotBlank()) add(dir, kind, text, langOf[key] ?: "") else emit(JSONObject().put("type", "partial").put("dir", dir).put("kind", kind).put("text", ""))
    }
    private fun startFlusher() {
        flusher?.interrupt()
        flusher = Thread({
            try { while (status != "off") { Thread.sleep(300); val now = System.currentTimeMillis()
                synchronized(partial) { for (k in partial.keys.toList()) if ((partial[k]?.length ?: 0) > 0 && now - (lastText[k] ?: now) > 1200) commit(k) } } } catch (_: InterruptedException) {}
        }, "interp-flush").also { it.isDaemon = true; it.start() }
    }

    private fun add(dir: String, kind: String, text: String, lang: String) {
        val e: Entry
        synchronized(entries) { e = Entry(++seq, System.currentTimeMillis(), dir, kind, text, lang); entries += e; if (entries.size > 400) entries.subList(0, 100).clear() }
        emit(JSONObject().put("type", "entry").put("entry", e.json()))
    }

    private fun stopSessions() { sessions.forEach { runCatching { it.close() } }; sessions.clear(); synchronized(partial) { for (k in partial.keys.toList()) commit(k); partial.clear() }; flusher?.interrupt(); flusher = null }
    @Synchronized fun stop(reason: String = "stopped") {
        if (status == "off") return
        stopSessions(); status = "off"; emit(JSONObject().put("type", "status").put("status", "off").put("text", reason))
    }
    fun clear() { synchronized(entries) { entries.clear() }; emit(JSONObject().put("type", "cleared")) }

    fun snapshot(since: Long = 0L): JSONObject = JSONObject().put("status", status).put("mode", mode).put("mine", mine).put("theirs", theirs)
        .put("mineName", langName(mine)).put("theirsName", langName(theirs)).put("level", MicSource.level)
        .put("speaking", sessions.any { it.isPlaying() }).put("since", startedAt)
        .put("entries", JSONArray().also { a -> synchronized(entries) { entries.filter { it.seq > since }.forEach { a.put(it.json()) } } })
        .put("langs", JSONArray().also { a -> LANGS.forEach { a.put(JSONObject().put("code", it.first).put("name", it.second)) } })

    fun recent(n: Int): List<Entry> = synchronized(entries) { entries.takeLast(n) }
}
