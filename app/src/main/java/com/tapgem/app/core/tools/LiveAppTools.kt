package com.tapgem.app.core.tools

import android.content.Context
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.livex.Interpreter
import com.tapgem.app.core.livex.MicOwner
import com.tapgem.app.core.livex.Tutor
import com.tapgem.app.core.model.WidgetType
import com.tapgem.app.core.store.DesktopStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Bundled pages become saved apps so the drawer lists them and a window can hold them. */
object LiveApps {
    const val INTERPRETER = "interpreter_client.html"
    const val TUTOR = "tutor_client.html"
    const val MUSIC = "music_player.html"
    const val READER = "reader.html"
    const val MUSIC_SKINS = "music_skins.html"
    fun install(context: Context, asset: String, file: String): File {
        val f = File(DesktopStore.appsDir, file)
        val html = context.assets.open(asset).bufferedReader().readText()
        if (!f.exists() || f.readText() != html) {
            f.parentFile?.mkdirs(); f.writeText(html)
            DesktopBridge.current().widgets.filter { it.type == WidgetType.APP && it.source == f.absolutePath }.forEach { w ->
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { com.tapgem.app.core.bridge.WebCommandBus.execute(w.id, com.tapgem.app.core.bridge.WebCommandBus.Command("reload", emptyMap())) } }
            }
        }
        return f
    }
    fun window(file: String) = DesktopBridge.current().widgets.firstOrNull { it.type == WidgetType.APP && it.source.endsWith(file) }
    suspend fun ensureWindow(context: Context, asset: String, file: String, title: String, args: Args): String {
        val f = install(context, asset, file)
        if (window(file) != null) return ""
        val a = HashMap<String, String>(); args.str("anchor", "position")?.let { a["anchor"] = it }
        for (k in listOf("x", "y", "w", "h")) args.str(k)?.let { a[k] = it }
        if (args.str("size") != null) a["size"] = args.str("size")!!
        else if (a["w"] == null && a["h"] == null) { a["w"] = "624"; a["h"] = "420"; a.putIfAbsent("anchor", "top left") }
        WidgetOps.add(context, Args(a), forcedType = WidgetType.APP, forcedSource = f.absolutePath, forcedTitle = title)
        return " The $title window is on the desktop."
    }
}

class InterpreterTool(private val context: Context) : AiTool {
    override val name = "interpreter"
    override suspend fun execute(args: Args): Result<String> = withContext(Dispatchers.IO) {
        val i = Interpreter
        when (args.action) {
            "open", "show" -> { val o = LiveApps.ensureWindow(context, "interpreter.html", LiveApps.INTERPRETER, "Interpreter", args); i.configure(args.str("mode"), args.str("my_language", "mine"), args.str("their_language", "language", "target", "theirs")); Result.success("Interpreter: ${i.mode} mode, ${i.langName(i.mine)} ↔ ${i.langName(i.theirs)}.$o Say start to begin.") }
            "start", "begin", "translate", "listen", "speak", "conversation" -> {
                val o = LiveApps.ensureWindow(context, "interpreter.html", LiveApps.INTERPRETER, "Interpreter", args)
                val mode = args.str("mode") ?: args.action.takeIf { it in setOf("listen", "speak", "conversation") }
                i.configure(mode, args.str("my_language", "mine"), args.str("their_language", "language", "target", "theirs"))
                val now = MicOwner.whenMicFree { i.start() }
                Result.success((if (now) "Interpreter running: " else "Interpreter will start the moment I stop talking — say nothing for a second. ") +
                    "${i.mode} mode, ${when (i.mode) { "listen" -> "the room → ${i.langName(i.mine)}"; "speak" -> "you → ${i.langName(i.theirs)}"; else -> "${i.langName(i.mine)} ↔ ${i.langName(i.theirs)} both ways" }}.$o Tap the desktop to talk to me again; that pauses it.")
            }
            "stop", "pause", "end" -> { i.stop(); Result.success("Interpreter stopped.") }
            "set", "configure", "language", "mode" -> { i.configure(args.str("mode"), args.str("my_language", "mine"), args.str("their_language", "language", "target", "theirs")); Result.success("Interpreter set: ${i.mode}, ${i.langName(i.mine)} ↔ ${i.langName(i.theirs)}${if (i.status != "off") " (restarting)" else ""}.") }
            "clear" -> { i.clear(); Result.success("Transcript cleared.") }
            "read", "recent", "what_was_said" -> { val r = i.recent((args.int("count") ?: 6).coerceIn(1, 20)); Result.success(if (r.isEmpty()) "Nothing translated yet." else r.joinToString(" | ") { "${if (it.kind == "in") "heard" else "said"} (${it.dir}): ${it.text.take(120)}" }) }
            "status", "describe", "languages" -> Result.success("Interpreter ${i.status}: ${i.mode}, ${i.langName(i.mine)} ↔ ${i.langName(i.theirs)}. ${if (args.action == "languages") "Languages: " + Interpreter.LANGS.joinToString(", ") { it.second } else "70+ languages; ask for the list."}")
            else -> Result.failure(IllegalArgumentException("Unknown interpreter action. Use open, start, stop, set, read, status, languages."))
        }
    }
}

class TutorTool(private val context: Context) : AiTool {
    override val name = "tutor"
    override suspend fun execute(args: Args): Result<String> = withContext(Dispatchers.IO) {
        val t = Tutor
        when (args.action) {
            "open", "show" -> { val o = LiveApps.ensureWindow(context, "tutor.html", LiveApps.TUTOR, "Tutor", args); t.configure(args.str("language"), args.str("native", "my_language"), args.str("level"), args.str("scenario", "topic")); Result.success("Tutor: ${t.langName()} at ${t.level}, scenario \"${t.scenario}\".$o Say start the lesson to begin.") }
            "start", "begin", "lesson" -> {
                val o = LiveApps.ensureWindow(context, "tutor.html", LiveApps.TUTOR, "Tutor", args)
                t.configure(args.str("language"), args.str("native", "my_language"), args.str("level"), args.str("scenario", "topic"))
                val now = MicOwner.whenMicFree { t.start() }
                Result.success((if (now) "Lesson starting: " else "The lesson starts the moment I stop talking — say nothing for a second. ") + "${t.langName()} at ${t.level}, \"${t.scenario}\".$o Tap the desktop to come back to me; that ends the lesson.")
            }
            "stop", "end" -> { t.stop(); Result.success("Lesson ended.") }
            "set", "configure" -> { t.configure(args.str("language"), args.str("native", "my_language"), args.str("level"), args.str("scenario", "topic")); Result.success("Tutor set: ${t.langName()} at ${t.level}, \"${t.scenario}\"${if (t.status != "off") " — restart the lesson to apply" else ""}.") }
            "status", "describe", "progress" -> { val s = t.snapshot(); Result.success("Tutor ${t.status}: ${t.langName()} at ${t.level}, \"${t.scenario}\"; ${s.getJSONArray("corrections").length()} corrections this lesson, ${s.getJSONArray("vocab").length()} words in the tray." + (t.summary.takeIf { it.isNotBlank() }?.let { " Summary: $it" } ?: "")) }
            else -> Result.failure(IllegalArgumentException("Unknown tutor action. Use open, start, stop, set, status."))
        }
    }
    private fun Tutor.langName() = Interpreter.langName(language)
}
