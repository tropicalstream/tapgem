package com.tapgem.app.core.tools

import android.content.Context
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.irc.IrcClient
import com.tapgem.app.core.model.WidgetType
import com.tapgem.app.core.store.DesktopStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The IRC window and the voice side of it. Sending is two-step on purpose: `say` only stages a
 * message and hands the model the words to read back; `confirm` is what actually transmits.
 */
class IrcTool(private val context: Context) : AiTool {
    override val name = "irc"

    companion object {
        const val APP_FILE = "irc_client.html"
        val THEMES = listOf("amber", "green", "scanlines", "pixel", "apple2", "vintage", "dos", "ibm3278", "futuristic")
        fun appFile(): File = File(DesktopStore.appsDir, APP_FILE)
        /** The bundled page is the source of truth; the copy in the apps folder is what the window loads. */
        fun installApp(context: Context): File {
            val f = appFile()
            val html = context.assets.open("irc.html").bufferedReader().readText()
            if (!f.exists() || f.readText() != html) { f.parentFile?.mkdirs(); f.writeText(html) }
            return f
        }
    }

    private fun window() = DesktopBridge.current().widgets.firstOrNull { it.type == WidgetType.APP && it.source.endsWith(APP_FILE) }

    private suspend fun ensureWindow(args: Args): String {
        if (window() != null) return ""
        val f = installApp(context)
        val a = HashMap<String, String>(); args.str("anchor", "position")?.let { a["anchor"] = it }
        if (args.str("size") != null) a["size"] = args.str("size")!! else { a["w"] = "624"; a["h"] = "420"; a.putIfAbsent("anchor", "top left") }
        WidgetOps.add(context, Args(a), forcedType = WidgetType.APP, forcedSource = f.absolutePath, forcedTitle = "IRC")
        return " The IRC window is on the desktop."
    }

    private fun channelArg(args: Args) = args.str("channel", "target", "name")?.trim()?.let { if (it.startsWith("#") || it.startsWith("&")) it else "#$it" }

    override suspend fun execute(args: Args): Result<String> = withContext(Dispatchers.IO) {
        val c = IrcClient
        when (args.action) {
            "open", "show", "connect", "start" -> {
                val opened = ensureWindow(args)
                val srv = IrcClient.serverFor(args.str("server", "network", "host"))
                    ?: if (args.action == "open" || args.action == "show") (c.server ?: IrcClient.SERVERS[0]) else return@withContext Result.success("Which network — Libera.Chat, EFnet or OCF Berkeley?$opened")
                val nick = args.str("nick", "nickname", "username")
                if (c.status == "registered" && c.server?.key == srv.key && (nick == null || nick == c.nick)) return@withContext Result.success("Already connected to ${srv.label} as ${c.nick}.$opened")
                c.connect(srv, nick)
                var waited = 0
                while (c.status == "connecting" && waited < 40_000) { delay(250); waited += 250 }   // EFnet's ident/hostname checks take ~20 s
                Result.success(if (c.status == "registered") "Connected to ${srv.label} as ${c.nick}; joined ${srv.channels.joinToString(", ")}.$opened"
                    else if (c.status == "connecting") "Still connecting to ${srv.label} — it can take a moment; I'll show it in the window.$opened" else "Couldn't reach ${srv.label} — the window shows why.$opened")
            }
            "disconnect", "quit", "close" -> {
                c.disconnect()
                if (args.bool("close_window") == true) window()?.let { w -> DesktopBridge.mutate { d -> d.copy(widgets = d.widgets.filterNot { it.id == w.id }) } }
                Result.success("Disconnected from IRC.")
            }
            "join" -> {
                val ch = channelArg(args) ?: return@withContext Result.failure(IllegalArgumentException("join needs 'channel'."))
                if (c.status != "registered") return@withContext Result.success("Not connected yet — connect to a network first (libera, efnet or ocf).")
                c.join(ch); delay(1200)
                Result.success(if (c.channels().any { it.equals(ch, true) }) "Joined $ch (${c.membersOf(ch).size} people)." else "Asked to join $ch.")
            }
            "part", "leave" -> {
                val ch = channelArg(args) ?: c.currentTarget().takeIf { it.startsWith("#") } ?: return@withContext Result.failure(IllegalArgumentException("part needs 'channel'."))
                c.part(ch); Result.success("Left $ch.")
            }
            "nick", "rename", "set_nick" -> {
                val n = args.str("nick", "nickname", "name") ?: return@withContext Result.failure(IllegalArgumentException("nick needs 'nick'."))
                c.changeNick(n); delay(800); Result.success("Nick is now ${c.nick}.")
            }
            "switch", "focus", "view" -> {
                val t = args.str("channel", "target", "name") ?: return@withContext Result.failure(IllegalArgumentException("switch needs 'channel'."))
                val real = c.snapshot().getJSONArray("targets").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
                    .firstOrNull { it.equals(t, true) || it.equals("#$t", true) } ?: return@withContext Result.success("No window called $t. Open: ${c.channels().joinToString(", ").ifBlank { "none" }}.")
                c.setCurrent(real); Result.success("Showing $real.")
            }
            "say", "send", "message", "msg" -> {
                val text = args.str("text", "message", "content")?.trim() ?: return@withContext Result.failure(IllegalArgumentException("say needs 'text'."))
                if (c.status != "registered") return@withContext Result.success("Not connected — connect first.")
                val target = args.str("channel", "target", "to")?.let { if (it.startsWith("#") || it.startsWith("&") || !c.channels().any { ch -> ch.equals("#$it", true) }) it else "#$it" } ?: c.currentTarget()
                if (target == "*") return@withContext Result.success("No channel is open — join one first.")
                c.pending = target to text
                Result.success("STAGED, NOT SENT. Read this back to the user word for word and ask them to confirm: to $target — \"$text\". Call irc confirm only after they say yes; irc cancel if not.")
            }
            "confirm", "yes", "send_pending" -> {
                val p = c.pending ?: return@withContext Result.success("Nothing is staged to send.")
                c.pending = null
                Result.success(if (c.say(p.first, p.second)) "Sent to ${p.first}." else "Couldn't send — not connected.")
            }
            "cancel", "no", "discard" -> { c.pending = null; Result.success("Discarded.") }
            "read", "recent", "catch_up", "what_did_they_say" -> {
                val t = args.str("channel", "target", "name")?.let { if (it.startsWith("#") || it.startsWith("&")) it else "#$it" } ?: c.currentTarget()
                val n = (args.int("count", "lines") ?: 8).coerceIn(1, 30)
                val recent = c.recent(t, n)
                Result.success(if (recent.isEmpty()) "Nothing in $t yet." else "Last ${recent.size} in $t: " + recent.joinToString(" | ") { "${it.from}: ${it.text.take(140)}" })
            }
            "status", "describe", "who", "list" -> {
                val s = c.snapshot()
                val ch = c.channels()
                Result.success(when (c.status) {
                    "registered" -> "Connected to ${c.server?.label} as ${c.nick}. Channels: ${ch.joinToString(", ").ifBlank { "none" }}. Showing ${c.currentTarget()}." +
                        (args.str("channel")?.let { t -> " People in $t: ${c.membersOf(if (t.startsWith("#")) t else "#$t").take(25).joinToString(", ")}" } ?: "") +
                        (c.pending?.let { " A message to ${it.first} is staged and waiting for confirmation." } ?: "")
                    "connecting" -> "Connecting to ${c.server?.label}…"
                    else -> "Not connected. Networks: Libera.Chat, EFnet, OCF Berkeley. Default nick ${IrcClient.DEFAULT_NICK}."
                } + " Window: ${if (window() != null) "open" else "closed"}.")
            }
            "theme", "look", "style" -> {
                val t = args.str("theme", "name", "style")?.lowercase(Locale.US)?.replace(Regex("[^a-z0-9]"), "") ?: return@withContext Result.success("Themes: ${THEMES.joinToString(", ")}.")
                val pick = THEMES.firstOrNull { it == t }
                    ?: when { t.contains("scan") -> "scanlines"; t.contains("mono") || t.contains("green") -> "green"; t.contains("apple") -> "apple2"; t.contains("dos") || t.contains("blue") -> "dos"; t.contains("3278") || t.contains("ibm") -> "ibm3278"; t.contains("futur") || t.contains("cyan") -> "futuristic"; t.contains("pix") -> "pixel"; t.contains("vint") -> "vintage"; t.contains("amb") || t.contains("orange") -> "amber"; else -> THEMES.firstOrNull { t.contains(it) || it.contains(t) } }
                    ?: return@withContext Result.success("No theme called $t. Themes: ${THEMES.joinToString(", ")}.")
                ensureWindow(args)
                window()?.let { w -> DesktopBridge.mutateWidget(w.id) { it.withState("app.theme" to pick) } }
                IrcClient.setTheme(pick)
                Result.success("IRC theme: $pick.")
            }
            else -> Result.failure(IllegalArgumentException("Unknown irc action '${args.action}'. Use open, connect, disconnect, join, part, nick, say, confirm, cancel, read, status, switch, theme."))
        }
    }
}
