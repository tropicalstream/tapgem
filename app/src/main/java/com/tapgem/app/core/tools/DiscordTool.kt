package com.tapgem.app.core.tools

import android.content.Context
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.irc.DiscordClient
import com.tapgem.app.core.model.WidgetType
import com.tapgem.app.core.store.DesktopStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** The Discord window and its voice side. Same two-step sending as IRC: `say` stages, `confirm` sends. */
class DiscordTool(private val context: Context) : AiTool {
    override val name = "discord"

    companion object {
        const val APP_FILE = "discord_client.html"
        fun installApp(context: Context): File {
            val f = File(DesktopStore.appsDir, APP_FILE)
            val html = context.assets.open("discord.html").bufferedReader().readText()
            if (!f.exists() || f.readText() != html) {
                f.parentFile?.mkdirs(); f.writeText(html)
                // a window already showing the old copy re-reads the file
                DesktopBridge.current().widgets.filter { it.type == WidgetType.APP && it.source == f.absolutePath }.forEach { w ->
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { com.tapgem.app.core.bridge.WebCommandBus.execute(w.id, com.tapgem.app.core.bridge.WebCommandBus.Command("reload", emptyMap())) } }
                }
            }
            return f
        }
    }

    private fun window() = DesktopBridge.current().widgets.firstOrNull { it.type == WidgetType.APP && it.source.endsWith(APP_FILE) }

    private suspend fun ensureWindow(args: Args): String {
        val f = installApp(context)
        if (window() != null) return ""
        val a = HashMap<String, String>(); args.str("anchor", "position")?.let { a["anchor"] = it }
        if (args.str("size") != null) a["size"] = args.str("size")!! else { a["w"] = "624"; a["h"] = "420"; a.putIfAbsent("anchor", "top left") }
        WidgetOps.add(context, Args(a), forcedType = WidgetType.APP, forcedSource = f.absolutePath, forcedTitle = "Discord")
        return " The Discord window is on the desktop."
    }

    override suspend fun execute(args: Args): Result<String> = withContext(Dispatchers.IO) {
        val c = DiscordClient
        when (args.action) {
            "open", "show", "connect", "start" -> {
                val opened = ensureWindow(args)
                if (c.status == "online") return@withContext Result.success("Discord is connected as ${c.username}.$opened")
                if (!c.hasToken()) return@withContext Result.success("Not logged in — the window shows a login screen; the user pastes their Discord token there (typed over scrcpy). Tell them that.$opened")
                c.connect()
                var w = 0; while (c.status == "connecting" && w < 20_000) { delay(250); w += 250 }
                Result.success(if (c.status == "online") "Connected to Discord as ${c.username}; ${c.channelList().count { !it.dm }} channels across ${c.channelList().map { it.guildName }.distinct().size - 1} servers.$opened" else "Discord is still connecting.$opened")
            }
            "logout", "sign_out" -> { c.logout(); Result.success("Logged out of Discord; the token is gone.") }
            "disconnect", "close" -> { c.disconnect(); Result.success("Disconnected from Discord (still logged in).") }
            "servers", "guilds", "list_servers" -> Result.success("Servers: " + c.channelList().map { it.guildName }.distinct().joinToString(", ").ifBlank { "none (not connected?)" })
            "channels", "list_channels" -> {
                val g = args.str("server", "guild")?.let { c.findGuild(it) }
                val cs = c.channelList().filter { g == null || it.guildName == g }
                Result.success((if (g != null) "$g: " else "") + cs.take(40).joinToString(", ") { (if (g == null && !it.dm) it.guildName + "/" else "") + "#" + it.name }.ifBlank { "no channels" })
            }
            "switch", "join", "open_channel", "view" -> {
                val n = args.str("channel", "name", "target") ?: return@withContext Result.failure(IllegalArgumentException("switch needs 'channel'."))
                var ch = c.find(n)
                args.str("server", "guild")?.let { g -> c.findGuild(g)?.let { gn -> c.channelList().firstOrNull { it.guildName == gn && it.name.lowercase(Locale.US).contains(n.trimStart('#').lowercase(Locale.US)) }?.let { ch = it } } }
                val real = ch ?: return@withContext Result.success("No channel called $n. Try discord channels.")
                c.setCurrent(real.id); c.history(real.id)
                Result.success("Showing #${real.name} in ${real.guildName}.")
            }
            "say", "send", "message", "msg", "reply" -> {
                val text = args.str("text", "message", "content")?.trim() ?: return@withContext Result.failure(IllegalArgumentException("say needs 'text'."))
                if (c.status != "online") return@withContext Result.success("Not connected to Discord.")
                val ch = args.str("channel", "target", "to")?.let { c.find(it) } ?: c.channelList().firstOrNull { it.id == c.currentChannel() } ?: return@withContext Result.success("No channel is open — switch to one first.")
                c.pending = ch.id to text
                Result.success("STAGED, NOT SENT. Read this back to the user word for word and ask them to confirm: to #${ch.name} (${ch.guildName}) — \"$text\". Call discord confirm only after they say yes; discord cancel if not.")
            }
            "confirm", "yes", "send_pending" -> {
                val p = c.pending ?: return@withContext Result.success("Nothing is staged to send.")
                c.pending = null
                Result.success(if (c.say(p.first, p.second)) "Sent." else "Couldn't send — see the window.")
            }
            "cancel", "no", "discard" -> { c.pending = null; Result.success("Discarded.") }
            "read", "recent", "catch_up", "what_did_they_say" -> {
                val ch = args.str("channel", "name", "target")?.let { c.find(it) } ?: c.channelList().firstOrNull { it.id == c.currentChannel() } ?: return@withContext Result.success("No channel is open.")
                c.history(ch.id, 20)
                val n = (args.int("count", "lines") ?: 8).coerceIn(1, 30)
                val recent = c.recent(ch.id, n)
                Result.success(if (recent.isEmpty()) "Nothing in #${ch.name} yet." else "Last ${recent.size} in #${ch.name}: " + recent.joinToString(" | ") { "${it.from}: ${it.text.take(140)}" })
            }
            "status", "describe" -> Result.success(when (c.status) {
                "online" -> "Discord: online as ${c.username}, ${c.channelList().map { it.guildName }.distinct().size - 1} servers; showing ${c.channelList().firstOrNull { it.id == c.currentChannel() }?.let { "#${it.name} (${it.guildName})" } ?: "nothing"}." + (c.pending?.let { " A message is staged, waiting for confirmation." } ?: "")
                "connecting" -> "Discord: connecting…"
                else -> if (c.hasToken()) "Discord: logged in but offline — say connect." else "Discord: not logged in — the window has the login screen."
            } + " Window: ${if (window() != null) "open" else "closed"}.")
            "theme", "look", "style" -> {
                val t = args.str("theme", "name", "style")?.lowercase(Locale.US)?.replace(Regex("[^a-z0-9]"), "") ?: return@withContext Result.success("Themes: ${IrcTool.THEMES.joinToString(", ")}.")
                val pick = IrcTool.THEMES.firstOrNull { it == t } ?: when { t.contains("scan") -> "scanlines"; t.contains("mono") || t.contains("green") -> "green"; t.contains("apple") -> "apple2"; t.contains("dos") || t.contains("blue") -> "dos"; t.contains("3278") || t.contains("ibm") -> "ibm3278"; t.contains("futur") || t.contains("cyan") -> "futuristic"; t.contains("pix") -> "pixel"; t.contains("vint") -> "vintage"; t.contains("amb") || t.contains("orange") -> "amber"; else -> IrcTool.THEMES.firstOrNull { t.contains(it) || it.contains(t) } }
                    ?: return@withContext Result.success("No theme called $t. Themes: ${IrcTool.THEMES.joinToString(", ")}.")
                ensureWindow(args); window()?.let { w -> DesktopBridge.mutateWidget(w.id) { it.withState("app.theme" to pick) } }
                c.setTheme(pick); Result.success("Discord theme: $pick.")
            }
            else -> Result.failure(IllegalArgumentException("Unknown discord action '${args.action}'. Use open, connect, disconnect, logout, servers, channels, switch, say, confirm, cancel, read, status, theme."))
        }
    }
}
