package com.tapgem.app.core.irc

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * One IRC connection for the whole app (the irc app window and the `irc` voice tool share it).
 * Plain RFC 1459 over TLS: NICK/USER, PING, JOIN/PART, PRIVMSG/NOTICE, NICK, TOPIC, NAMES.
 * Every target (server, channel, query) keeps its last [KEEP] lines with a running sequence
 * number so a page can catch up with `since`.
 */
object IrcClient {
    private const val TAG = "IrcClient"
    private const val KEEP = 400

    class Server(val key: String, val label: String, val host: String, val port: Int = 6697, val tls: Boolean = true, val channels: List<String>)
    val SERVERS = listOf(
        Server("libera", "Libera.Chat", "irc.libera.chat", channels = listOf("#libera")),
        Server("efnet", "EFnet", "irc.efnet.org", channels = listOf("#efnet")),
        Server("ocf", "OCF Berkeley", "irc.ocf.berkeley.edu", channels = listOf("#ocf", "#rebuild")),
    )
    const val DEFAULT_NICK = "gomie_"

    fun serverFor(name: String?): Server? {
        val k = name?.trim()?.lowercase() ?: return null
        return SERVERS.firstOrNull { it.key == k || it.host == k || it.label.lowercase() == k }
            ?: SERVERS.firstOrNull { k.contains(it.key) || k.contains(it.label.lowercase().substringBefore(' ')) }
    }

    class Line(val seq: Long, val t: Long, val target: String, val kind: String, val from: String, val text: String) {
        fun json() = JSONObject().put("seq", seq).put("t", t).put("target", target).put("kind", kind).put("from", from).put("text", text)
    }

    /** Sent to the page and the HUD: "connected", "joined", "message", "nick", "error", … */
    fun interface Listener { fun onEvent(json: JSONObject) }
    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    private val lock = Any()
    @Volatile var server: Server? = null; private set
    @Volatile var nick: String = DEFAULT_NICK; private set
    @Volatile var status: String = "offline"; private set          // offline | connecting | registered
    private val lines = ArrayList<Line>()
    private var seq = 0L
    private val targets = LinkedHashSet<String>()                  // "*" is the server console
    private val members = HashMap<String, LinkedHashSet<String>>()
    private val topics = HashMap<String, String>()
    @Volatile private var current = "*"
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var thread: Thread? = null
    private var epoch = 0
    private var wantedNick = DEFAULT_NICK

    /** What the user wants to send but has not confirmed yet (voice path). */
    @Volatile var pending: Pair<String, String>? = null

    fun connect(srv: Server, nickname: String? = null) {
        disconnect(quiet = true)
        val e: Int
        synchronized(lock) {
            epoch += 1; e = epoch
            server = srv; wantedNick = (nickname ?: nick).ifBlank { DEFAULT_NICK }; nick = wantedNick; status = "connecting"
            targets.clear(); targets += "*"; members.clear(); topics.clear(); current = "*"; pending = null
        }
        add("*", "status", "", "Connecting to ${srv.host}:${srv.port}${if (srv.tls) " (TLS)" else ""} as $wantedNick…")
        emit(JSONObject().put("type", "status").put("status", status))
        thread = Thread({ run(srv, e) }, "irc-${srv.key}").also { it.isDaemon = true; it.start() }
    }

    fun disconnect(quiet: Boolean = false) {
        val s: Socket?
        synchronized(lock) {
            epoch += 1
            s = socket; socket = null; writer = null
            if (!quiet && status != "offline") add("*", "status", "", "Disconnected.")
            status = "offline"
        }
        runCatching { s?.let { w -> runCatching { w.getOutputStream().write("QUIT :TapGem\r\n".toByteArray()); w.getOutputStream().flush() }; w.close() } }
        if (!quiet) emit(JSONObject().put("type", "status").put("status", "offline"))
    }

    private fun run(srv: Server, e: Int) {
        try {
            // Round-robin hosts hand out v6 addresses the glasses' Wi-Fi often cannot route: try every address, IPv4 first.
            val addrs = java.net.InetAddress.getAllByName(srv.host).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
            var raw: Socket? = null; var lastErr: Throwable? = null
            for (a in addrs) {
                if (epoch != e) return
                raw = runCatching { Socket().apply { connect(InetSocketAddress(a, srv.port), 8_000); soTimeout = 0 } }
                    .onFailure { lastErr = it; Log.w(TAG, "${srv.host} ${a.hostAddress}: ${it.message}") }.getOrNull()
                if (raw != null) break
            }
            if (raw == null) throw (lastErr ?: java.io.IOException("no address for ${srv.host}"))
            val s: Socket = if (srv.tls) tlsWrap(raw, srv, e) else raw
            val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            synchronized(lock) { if (epoch != e) { s.close(); return }; socket = s; writer = w }
            sendRaw("NICK $wantedNick"); sendRaw("USER $wantedNick 0 * :TapGem on RayNeo X3 Pro")
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = r.readLine() ?: break
                if (epoch != e) break
                runCatching { handle(line) }.onFailure { Log.w(TAG, "line failed: ${it.message}") }
            }
            if (epoch == e) { synchronized(lock) { status = "offline"; socket = null; writer = null }; add("*", "status", "", "Connection closed by ${srv.host}."); emit(JSONObject().put("type", "status").put("status", "offline")) }
        } catch (t: Throwable) {
            if (epoch != e) return
            synchronized(lock) { status = "offline"; socket = null; writer = null }
            add("*", "error", "", "Couldn't connect to ${srv.host}: ${t.message ?: t.javaClass.simpleName}")
            emit(JSONObject().put("type", "error").put("text", "Couldn't connect to ${srv.label}: ${t.message ?: "network error"}"))
            emit(JSONObject().put("type", "status").put("status", "offline"))
        }
    }

    /**
     * Some EFnet servers present certificates Android has no anchor for. Chat on IRC is public
     * anyway, so a cert that does not verify degrades to "encrypted, unverified" with a console
     * note rather than no connection; the first attempt always verifies.
     */
    private fun tlsWrap(raw: Socket, srv: Server, e: Int): Socket {
        val a = raw.inetAddress
        return try {
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, srv.host, srv.port, true) as SSLSocket).also { it.startHandshake() }
        } catch (ex: javax.net.ssl.SSLHandshakeException) {
            Log.w(TAG, "${srv.host}: ${ex.message}")
            runCatching { raw.close() }
            if (epoch != e) throw ex
            val again = Socket().apply { connect(InetSocketAddress(a, srv.port), 8_000) }
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, t: String) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, t: String) {}
                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            }), null)
            add("*", "status", "", "${srv.host}'s certificate could not be verified — encrypted, unverified.")
            (ctx.socketFactory.createSocket(again, srv.host, srv.port, true) as SSLSocket).also { it.startHandshake() }
        }
    }

    private fun sendRaw(s: String) {
        val w = synchronized(lock) { writer } ?: return
        synchronized(w) { runCatching { w.write(s.take(500)); w.write("\r\n"); w.flush() }.onFailure { Log.w(TAG, "write failed: ${it.message}") } }
    }

    private fun handle(raw: String) {
        var s = raw
        if (s.startsWith("@")) s = s.substringAfter(' ')                              // message tags
        var prefix = ""
        if (s.startsWith(":")) { prefix = s.substring(1).substringBefore(' '); s = s.substringAfter(' ') }
        val trailing = if (s.contains(" :")) s.substringAfter(" :") else null
        val head = (if (trailing != null) s.substringBefore(" :") else s).split(' ').filter { it.isNotEmpty() }
        val cmd = head.getOrNull(0)?.uppercase() ?: return
        val args = head.drop(1) + listOfNotNull(trailing)
        val from = prefix.substringBefore('!')
        when (cmd) {
            "PING" -> sendRaw("PONG :${trailing ?: args.firstOrNull().orEmpty()}")
            "001" -> {
                synchronized(lock) { status = "registered"; nick = args.getOrNull(0) ?: nick }
                add("*", "status", "", "Connected to ${server?.label} as $nick.")
                emit(JSONObject().put("type", "status").put("status", "registered").put("nick", nick))
                server?.channels?.forEach { join(it) }
            }
            "433", "437" -> {            // nick in use / temporarily unavailable
                if (status != "registered") { wantedNick = wantedNick.take(12) + "_"; nick = wantedNick; sendRaw("NICK $wantedNick"); add("*", "status", "", "Nick taken, trying $wantedNick") }
                else add(current, "error", "", "That nick is taken.")
            }
            "332" -> { val ch = args.getOrNull(1) ?: return; synchronized(lock) { topics[ch] = trailing.orEmpty() }; add(ch, "topic", "", trailing.orEmpty()) }
            "353" -> {
                val ch = args.getOrNull(2) ?: return
                synchronized(lock) { members.getOrPut(ch) { LinkedHashSet() }.addAll(trailing.orEmpty().split(' ').filter { it.isNotBlank() }.map { it.trimStart('@', '+', '%', '~', '&') }) }
            }
            "366" -> { val ch = args.getOrNull(1) ?: return; emit(JSONObject().put("type", "names").put("target", ch).put("count", members[ch]?.size ?: 0)) }
            "JOIN" -> {
                val ch = args.firstOrNull() ?: return
                if (from.equals(nick, true)) {
                    synchronized(lock) { targets += ch; members.getOrPut(ch) { LinkedHashSet() }; current = ch }
                    add(ch, "status", "", "You joined $ch")
                    emit(JSONObject().put("type", "joined").put("target", ch))
                } else { synchronized(lock) { members[ch]?.add(from) }; add(ch, "join", from, "joined") }
            }
            "PART" -> {
                val ch = args.firstOrNull() ?: return
                if (from.equals(nick, true)) {
                    synchronized(lock) { targets -= ch; members.remove(ch); if (current == ch) current = targets.lastOrNull() ?: "*" }
                    emit(JSONObject().put("type", "parted").put("target", ch))
                } else { synchronized(lock) { members[ch]?.remove(from) }; add(ch, "part", from, "left") }
            }
            "KICK" -> { val ch = args.getOrNull(0) ?: return; val who = args.getOrNull(1) ?: return
                if (who.equals(nick, true)) { synchronized(lock) { targets -= ch; members.remove(ch) }; add("*", "status", "", "Kicked from $ch: ${trailing.orEmpty()}"); emit(JSONObject().put("type", "parted").put("target", ch)) }
                else add(ch, "part", who, "was kicked (${trailing.orEmpty()})") }
            "QUIT" -> synchronized(lock) { members.filter { it.value.remove(from) }.keys }.forEach { add(it, "part", from, "quit") }
            "NICK" -> {
                val to = args.firstOrNull() ?: return
                if (from.equals(nick, true)) { synchronized(lock) { nick = to; wantedNick = to }; add(current, "status", "", "You are now $to"); emit(JSONObject().put("type", "nick").put("nick", to)) }
                else synchronized(lock) { members.filter { it.value.remove(from) }.keys.onEach { members[it]?.add(to) } }.forEach { add(it, "status", "", "$from is now $to") }
            }
            "PRIVMSG", "NOTICE" -> {
                val target = args.getOrNull(0) ?: return
                var text = trailing.orEmpty()
                if (text.startsWith("VERSION")) { if (cmd == "PRIVMSG") sendRaw("NOTICE $from :VERSION TapGem IRC (RayNeo X3 Pro)"); return }
                val action = text.startsWith("ACTION ")
                if (action) text = text.removePrefix("ACTION ").removeSuffix("")
                val where = if (target.equals(nick, true)) { if (cmd == "NOTICE" && !from.contains('.') && from.isNotBlank()) from else if (from.contains('.') || from.isBlank()) "*" else from } else target
                if (where != "*" && !where.startsWith("#") && !where.startsWith("&")) synchronized(lock) { targets += where; members.getOrPut(where) { LinkedHashSet(listOf(where, nick)) } }
                val kind = when { action -> "action"; cmd == "NOTICE" -> "notice"; text.contains(nick, true) -> "highlight"; else -> "msg" }
                add(where, kind, from, text.replace(Regex("[](\\d{1,2}(,\\d{1,2})?)?"), ""))
            }
            "TOPIC" -> { val ch = args.getOrNull(0) ?: return; synchronized(lock) { topics[ch] = trailing.orEmpty() }; add(ch, "topic", from, trailing.orEmpty()) }
            "ERROR" -> add("*", "error", "", trailing.orEmpty())
            else -> if (cmd.all { it.isDigit() }) {
                val n = cmd.toInt()
                if (n in 2..5 || n in 250..266 || n == 372 || n == 375 || n == 376 || n == 396 || n == 265 || n == 266) { if (n == 372) add("*", "motd", "", trailing.orEmpty()) }
                else if (n >= 400) add(current, "error", "", (args.drop(1).joinToString(" ")).ifBlank { raw })
                else add("*", "info", "", args.drop(1).joinToString(" "))
            }
        }
    }

    private fun add(target: String, kind: String, from: String, text: String) {
        val l: Line
        synchronized(lock) {
            if (target != "*") targets += target
            l = Line(++seq, System.currentTimeMillis(), target, kind, from, text)
            lines += l; if (lines.size > KEEP * 4) lines.subList(0, lines.size - KEEP * 3).clear()
        }
        emit(JSONObject().put("type", "line").put("line", l.json()))
    }

    private fun emit(o: JSONObject) { for (l in listeners) runCatching { l.onEvent(o) } }

    // ── commands ──
    fun join(channel: String) { val ch = if (channel.startsWith("#") || channel.startsWith("&")) channel else "#$channel"; sendRaw("JOIN $ch") }
    fun part(channel: String) { val ch = if (channel.startsWith("#") || channel.startsWith("&")) channel else "#$channel"; sendRaw("PART $ch :TapGem"); synchronized(lock) { targets -= ch; members.remove(ch); if (current == ch) current = targets.lastOrNull() ?: "*" }; emit(JSONObject().put("type", "parted").put("target", ch)) }
    fun changeNick(n: String) { val c = n.trim().replace(Regex("[^A-Za-z0-9_\\-\\[\\]{}^`|\\\\]"), "").take(16); if (c.isBlank()) return; wantedNick = c; if (status == "registered") sendRaw("NICK $c") else synchronized(lock) { nick = c } }
    fun say(target: String, text: String): Boolean {
        if (status != "registered" || text.isBlank()) return false
        val t = if (target == "*" || target.isBlank()) current else target
        if (t == "*") return false
        val action = text.startsWith("/me ")
        sendRaw("PRIVMSG $t :" + (if (action) "ACTION ${text.removePrefix("/me ")}" else text))
        add(t, if (action) "action" else "own", nick, if (action) text.removePrefix("/me ") else text)
        return true
    }
    fun raw(cmd: String) = sendRaw(cmd)
    fun setTheme(name: String) = emit(JSONObject().put("type", "theme").put("name", name))
    fun setCurrent(t: String) { synchronized(lock) { if (t == "*" || targets.contains(t)) current = t } ; emit(JSONObject().put("type", "current").put("target", current)) }
    fun currentTarget() = current

    /** Full picture for a page that just opened (or a voice question). */
    fun snapshot(since: Long = 0L): JSONObject = synchronized(lock) {
        JSONObject().put("status", status).put("server", server?.let { JSONObject().put("key", it.key).put("label", it.label).put("host", it.host) })
            .put("nick", nick).put("current", current)
            .put("targets", JSONArray().also { a -> targets.forEach { t -> a.put(JSONObject().put("name", t).put("members", members[t]?.size ?: 0).put("topic", topics[t].orEmpty())) } })
            .put("lines", JSONArray().also { a -> lines.filter { it.seq > since }.takeLast(KEEP).forEach { a.put(it.json()) } })
            .put("pending", pending?.let { JSONObject().put("target", it.first).put("text", it.second) })
            .put("servers", JSONArray().also { a -> SERVERS.forEach { a.put(JSONObject().put("key", it.key).put("label", it.label).put("host", it.host)) } })
    }

    fun membersOf(t: String): List<String> = synchronized(lock) { members[t]?.toList().orEmpty() }
    fun channels(): List<String> = synchronized(lock) { targets.filter { it.startsWith("#") || it.startsWith("&") } }
    fun recent(target: String, n: Int): List<Line> = synchronized(lock) { lines.filter { it.target == target && it.kind in setOf("msg", "own", "action", "highlight", "notice") }.takeLast(n) }
}
