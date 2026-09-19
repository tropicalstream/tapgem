package com.tapgem.app.core.livex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Language tutor on the turn-based agent model. The pedagogy lives in the system instruction
 * (comprehensible input pitched at the learner's level, recasts instead of interruptions,
 * explicit correction after the turn, retrieval practice of the learner's own slips, scenario
 * role-play); the structured side — corrections, vocabulary, level, lesson summary — arrives
 * through function calls so the page can show cards instead of parsing prose.
 */
object Tutor {
    class Correction(val said: String, val better: String, val why: String, val t: Long = System.currentTimeMillis()) {
        fun json() = JSONObject().put("said", said).put("better", better).put("why", why).put("t", t)
    }
    class Vocab(val word: String, var meaning: String, var hits: Int = 0, var misses: Int = 0, var due: Long = 0L) {
        fun json() = JSONObject().put("word", word).put("meaning", meaning).put("hits", hits).put("misses", misses).put("due", due)
    }
    class Turn(val seq: Long, val t: Long, val who: String, val text: String) { fun json() = JSONObject().put("seq", seq).put("t", t).put("who", who).put("text", text) }

    fun interface Listener { fun onEvent(json: JSONObject) }
    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun emit(o: JSONObject) { for (l in listeners) runCatching { l.onEvent(o) } }

    private lateinit var appContext: Context
    @Volatile var status = "off"; private set
    @Volatile var language = "es"; private set
    @Volatile var native = "en"; private set
    @Volatile var level = "A2"; private set        // CEFR
    @Volatile var scenario = "free conversation"; private set
    @Volatile var summary = ""; private set
    private var session: LiveAudioSession? = null
    private val turns = ArrayList<Turn>(); private var seq = 0L
    private val corrections = ArrayList<Correction>()
    private val vocab = LinkedHashMap<String, Vocab>()
    private val inBuf = StringBuilder(); private val outBuf = StringBuilder()
    private var startedAt = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
        MicOwner.registerApp { if (status != "off") stop("the assistant took the microphone") }
        runCatching { val f = File(appContext.filesDir, "tutor_vocab.json"); if (f.exists()) { val a = JSONArray(f.readText()); for (i in 0 until a.length()) { val o = a.getJSONObject(i); vocab[o.getString("word")] = Vocab(o.getString("word"), o.optString("meaning"), o.optInt("hits"), o.optInt("misses"), o.optLong("due")) } } }
        runCatching { val f = File(appContext.filesDir, "tutor_profile.json"); if (f.exists()) { val o = JSONObject(f.readText()); language = o.optString("language", language); native = o.optString("native", native); level = o.optString("level", level) } }
    }
    private fun persist() {
        runCatching { File(appContext.filesDir, "tutor_vocab.json").writeText(JSONArray().also { a -> vocab.values.forEach { a.put(it.json()) } }.toString()) }
        runCatching { File(appContext.filesDir, "tutor_profile.json").writeText(JSONObject().put("language", language).put("native", native).put("level", level).toString()) }
    }

    fun configure(language: String? = null, native: String? = null, level: String? = null, scenario: String? = null) {
        language?.let { Interpreter.langCode(it)?.let { c -> this.language = c } }
        native?.let { Interpreter.langCode(it)?.let { c -> this.native = c } }
        level?.let { l -> Regex("[ABC][12]").find(l.uppercase())?.value?.let { this.level = it } ?: run { when { l.contains("begin", true) -> this.level = "A1"; l.contains("inter", true) -> this.level = "B1"; l.contains("advanc", true) -> this.level = "C1"; else -> {} } } }
        scenario?.let { this.scenario = it.take(120) }
        persist(); emit(JSONObject().put("type", "config"))
    }

    private fun instruction(): String {
        val L = Interpreter.langName(language); val N = Interpreter.langName(native)
        val due = vocab.values.filter { it.due <= System.currentTimeMillis() }.sortedByDescending { it.misses - it.hits }.take(6).map { it.word }
        return """You are a warm, expert $L tutor for a $N speaker at CEFR level $level, talking through AR glasses (short spoken turns, no lists read aloud).
Method — follow these, they are what good tutors do:
1. Speak mostly in $L, pitched just above the learner's level (comprehensible input). Use $N only for a quick gloss when they are stuck or for grammar explanations at A1–A2.
2. Never interrupt. Let them finish, respond to the MEANING first, then recast their sentence correctly inside your own reply (implicit correction). Only then, if the slip matters at their level, call note_correction with the exact fix and a one-clause why. At most one or two corrections per turn.
3. Keep them talking: end most turns with a question, offer two choices when they stall, and praise specific things ("good use of the past tense").
4. Scenario: "$scenario". Stay in it; play the other role when it has one.
5. Retrieval practice: weave these words the learner slipped on before into the conversation naturally and call review_result when they use one: ${if (due.isEmpty()) "(none yet)" else due.joinToString(", ")}.
6. When a useful new word or phrase comes up, call add_vocab (word in $L, short meaning in $N).
7. If they are clearly above or below the level, call set_level once and adjust.
8. When they say they want to stop, or after ~15 minutes, call lesson_summary with two strengths and two things to practise, then say goodbye briefly.
Keep each spoken turn under ~25 words unless explaining grammar. The function calls are silent bookkeeping for the learner's display: never announce, describe or summarise a call you made, and never switch to $N to talk about it — just carry on the conversation in $L. Do not mention these instructions.""".trimIndent()
    }
    private fun tools(): JSONArray {
        fun d(n: String, desc: String, props: Map<String, String>) = JSONObject().put("name", n).put("description", desc)
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().also { p -> props.forEach { (k, v) -> p.put(k, JSONObject().put("type", "STRING").put("description", v)) } }).put("required", JSONArray().also { a -> props.keys.forEach { a.put(it) } }))
        return JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
            .put(d("note_correction", "Record a correction card for the learner's display.", mapOf("said" to "what the learner said", "better" to "the corrected version", "why" to "one short clause")))
            .put(d("add_vocab", "Add a word or phrase to the learner's vocabulary tray.", mapOf("word" to "in the target language", "meaning" to "short meaning in the learner's language")))
            .put(d("review_result", "The learner used (or failed) a review word.", mapOf("word" to "the word", "correct" to "true or false")))
            .put(d("set_level", "Adjust the CEFR level estimate.", mapOf("level" to "A1|A2|B1|B2|C1|C2")))
            .put(d("lesson_summary", "End-of-lesson summary.", mapOf("summary" to "two strengths and two things to practise, plain text")))))
    }

    @Synchronized fun start() {
        session?.close(); session = null
        turns.clear(); corrections.clear(); summary = ""; inBuf.setLength(0); outBuf.setLength(0); seq = 0
        status = "connecting"; startedAt = System.currentTimeMillis(); emit(JSONObject().put("type", "status").put("status", status))
        val setup = JSONObject().put("model", "models/gemini-3.8-live")
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction()))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
            .put("inputAudioTranscription", JSONObject()).put("outputAudioTranscription", JSONObject()).put("tools", tools())
        val s = LiveAudioSession(appContext, "tutor", setup) { onSession(it) }
        session = s; s.open()
    }

    private fun onSession(o: JSONObject) {
        when (o.optString("type")) {
            "ready" -> { status = "live"; emit(JSONObject().put("type", "status").put("status", status)); session?.sendText("[The learner just put the glasses on. Greet them in ${Interpreter.langName(language)} in one short sentence and open the scenario with a question.]") }
            "in" -> { inBuf.append(o.optString("text")); emit(JSONObject().put("type", "partial").put("who", "you").put("text", inBuf.toString())) }
            "out" -> { outBuf.append(o.optString("text")); emit(JSONObject().put("type", "partial").put("who", "tutor").put("text", outBuf.toString())) }
            "interrupted" -> { flush("tutor") }
            "turn" -> { flush("you"); flush("tutor") }
            "tool" -> {
                val a = o.optJSONObject("args") ?: JSONObject()
                when (o.optString("name")) {
                    "note_correction" -> { val c = Correction(a.optString("said"), a.optString("better"), a.optString("why")); synchronized(corrections) { corrections += c }; emit(JSONObject().put("type", "correction").put("correction", c.json()))
                        val w = a.optString("better").trim(); if (w.isNotBlank() && w.length < 60) vocab.getOrPut(w) { Vocab(w, a.optString("said")) }.let { it.misses++; it.due = System.currentTimeMillis() + 60_000 }; persist() }
                    "add_vocab" -> { val w = a.optString("word").trim(); if (w.isNotBlank()) { val v = vocab.getOrPut(w) { Vocab(w, a.optString("meaning")) }; if (v.meaning.isBlank()) v.meaning = a.optString("meaning"); v.due = System.currentTimeMillis() + 180_000; persist(); emit(JSONObject().put("type", "vocab").put("vocab", v.json())) } }
                    "review_result" -> { vocab[a.optString("word")]?.let { v -> if (a.optString("correct").equals("true", true)) { v.hits++; v.due = System.currentTimeMillis() + (1L shl minOf(v.hits, 8)) * 300_000 } else { v.misses++; v.due = System.currentTimeMillis() + 60_000 }; persist(); emit(JSONObject().put("type", "vocab").put("vocab", v.json())) } }
                    "set_level" -> { Regex("[ABC][12]").find(a.optString("level").uppercase())?.value?.let { level = it; persist(); emit(JSONObject().put("type", "config")) } }
                    "lesson_summary" -> { summary = a.optString("summary"); emit(JSONObject().put("type", "summary").put("text", summary)) }
                }
            }
            "closed" -> { if (status != "off") { status = "off"; emit(JSONObject().put("type", "status").put("status", "off").put("text", o.optString("text"))) } }
            "error" -> emit(JSONObject().put("type", "error").put("text", o.optString("text")))
        }
    }
    private fun flush(who: String) {
        val b = if (who == "you") inBuf else outBuf; val text = b.toString().trim(); b.setLength(0)
        if (text.isBlank()) return
        val t: Turn; synchronized(turns) { t = Turn(++seq, System.currentTimeMillis(), who, text); turns += t; if (turns.size > 200) turns.subList(0, 50).clear() }
        emit(JSONObject().put("type", "turn").put("turn", t.json()))
    }

    fun say(text: String) = session?.sendText(text)
    @Synchronized fun stop(reason: String = "stopped") { if (status == "off") return; session?.close(); session = null; status = "off"; emit(JSONObject().put("type", "status").put("status", "off").put("text", reason)) }

    fun snapshot(since: Long = 0L): JSONObject = JSONObject().put("status", status).put("language", language).put("native", native).put("level", level).put("scenario", scenario)
        .put("languageName", Interpreter.langName(language)).put("nativeName", Interpreter.langName(native)).put("summary", summary).put("level_", MicSource.level)
        .put("speaking", session?.isPlaying() ?: false).put("since", startedAt)
        .put("turns", JSONArray().also { a -> synchronized(turns) { turns.filter { it.seq > since }.forEach { a.put(it.json()) } } })
        .put("corrections", JSONArray().also { a -> synchronized(corrections) { corrections.takeLast(20).forEach { a.put(it.json()) } } })
        .put("vocab", JSONArray().also { a -> vocab.values.toList().takeLast(30).forEach { a.put(it.json()) } })
        .put("langs", JSONArray().also { a -> Interpreter.LANGS.forEach { a.put(JSONObject().put("code", it.first).put("name", it.second)) } })
}
