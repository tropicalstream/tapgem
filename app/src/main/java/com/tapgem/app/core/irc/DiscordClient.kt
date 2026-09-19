package com.tapgem.app.core.irc

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A user-account Discord session the way third-party terminal clients (Discordo and friends)
 * do it: the account's auth token, the real-time gateway over a WebSocket, messages over the REST
 * API. Token only — a password login through the API from an unknown client gets the token
 * revoked on the spot and the account forced into a password reset (seen in the field).
 * Discord's terms forbid third-party clients on user accounts; the login screen says so. The
 * token is kept in app-private storage and never logged.
 */
object DiscordClient {
    private const val TAG = "DiscordClient"
    private const val API = "https://discord.com/api/v9"
    private const val GATEWAY = "wss://gateway.discord.gg/?v=9&encoding=json"
    private const val KEEP = 300
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    private const val BUILD = 584177

    class Line(val seq: Long, val t: Long, val channel: String, val kind: String, val from: String, val text: String, val id: String = "") {
        fun json() = JSONObject().put("seq", seq).put("t", t).put("channel", channel).put("kind", kind).put("from", from).put("text", text).put("id", id)
    }
    class Channel(val id: String, val name: String, val guildId: String, val guildName: String, val dm: Boolean)

    fun interface Listener { fun onEvent(json: JSONObject) }
    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun emit(o: JSONObject) { for (l in listeners) runCatching { l.onEvent(o) } }

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val lock = Any()
    private lateinit var appContext: Context
    @Volatile var status = "offline"; private set        // offline | connecting | online
    @Volatile var username = ""; private set
    @Volatile var userId = ""; private set
    @Volatile private var token: String? = null
    private var ws: WebSocket? = null
    private var epoch = 0
    private var heartbeat: Thread? = null
    private var seqNo: Long? = null
    private val channels = LinkedHashMap<String, Channel>()
    private val lines = ArrayList<Line>()
    private var seq = 0L
    @Volatile private var current = ""
    @Volatile var pending: Pair<String, String>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        token = runCatching { tokenFile().takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() } }.getOrNull()
    }
    private fun tokenFile() = File(appContext.filesDir, "discord_token")
    fun hasToken() = token != null

    private fun superProps(): String {
        val o = JSONObject().put("os", "Windows").put("browser", "Chrome").put("device", "").put("system_locale", "en-US")
            .put("browser_user_agent", UA).put("browser_version", "140.0.0.0").put("os_version", "10").put("referrer", "").put("referring_domain", "")
            .put("referrer_current", "").put("referring_domain_current", "").put("release_channel", "stable").put("client_build_number", BUILD)
            .put("client_event_source", JSONObject.NULL).put("client_launch_id", UUID.randomUUID().toString()).put("has_client_mods", false)
        return Base64.encodeToString(o.toString().toByteArray(), Base64.NO_WRAP)
    }
    private fun req(url: String, auth: Boolean = true) = Request.Builder().url(url).header("User-Agent", UA).header("X-Super-Properties", superProps())
        .header("Origin", "https://discord.com").header("Referer", "https://discord.com/channels/@me").also { b -> if (auth) token?.let { b.header("Authorization", it) } }

    private fun post(path: String, body: JSONObject, auth: Boolean = true): Pair<Int, JSONObject> = http.newCall(req(API + path, auth).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
        r.code to (runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrDefault(JSONObject()))
    }
    private fun get(path: String): Pair<Int, String> = http.newCall(req(API + path).get().build()).execute().use { it.code to it.body?.string().orEmpty() }

    fun useToken(tok: String) {
        token = tok.trim(); runCatching { tokenFile().writeText(token!!) }
        connect()
    }
    fun logout() {
        disconnect(); token = null; runCatching { tokenFile().delete() }
        synchronized(lock) { channels.clear(); lines.clear(); current = ""; username = "" }
        emit(JSONObject().put("type", "status").put("status", "offline"))
    }

    fun connect() {
        if (token == null) { emit(JSONObject().put("type", "status").put("status", "offline")); return }
        disconnect(quiet = true)
        val e: Int
        synchronized(lock) { epoch += 1; e = epoch; status = "connecting"; seqNo = null }
        emit(JSONObject().put("type", "status").put("status", status))
        val tok = token!!
        ws = http.newWebSocket(req(GATEWAY, auth = false).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { if (epoch == e) runCatching { onGateway(webSocket, JSONObject(text), tok, e) }.onFailure { Log.w(TAG, "gateway: ${it.message}") } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { if (epoch == e) closed(e, "closed ($code $reason)", code) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { if (epoch == e) closed(e, t.message ?: "connection failed", 0) }
        })
    }
    private fun closed(e: Int, why: String, code: Int) {
        synchronized(lock) { if (epoch != e) return; status = "offline"; ws = null }
        heartbeat?.interrupt(); heartbeat = null
        if (code == 4004) { token = null; runCatching { tokenFile().delete() }; emit(JSONObject().put("type", "error").put("text", "Discord rejected the token — log in again.")) }
        else emit(JSONObject().put("type", "error").put("text", "Gateway $why"))
        emit(JSONObject().put("type", "status").put("status", "offline"))
    }
    fun disconnect(quiet: Boolean = false) {
        val w: WebSocket?
        synchronized(lock) { epoch += 1; w = ws; ws = null; status = "offline" }
        heartbeat?.interrupt(); heartbeat = null
        runCatching { w?.close(1000, "bye") }
        if (!quiet) emit(JSONObject().put("type", "status").put("status", "offline"))
    }

    private fun onGateway(s: WebSocket, o: JSONObject, tok: String, e: Int) {
        o.opt("s")?.let { if (it is Number) seqNo = it.toLong() }
        when (o.optInt("op")) {
            10 -> {
                val interval = o.getJSONObject("d").getLong("heartbeat_interval")
                heartbeat?.interrupt()
                heartbeat = Thread({
                    try { while (true) { Thread.sleep(interval); if (epoch != e) return@Thread; s.send(JSONObject().put("op", 1).put("d", seqNo ?: JSONObject.NULL).toString()) } } catch (_: InterruptedException) {}
                }, "discord-heartbeat").also { it.isDaemon = true; it.start() }
                val props = JSONObject().put("os", "Windows").put("browser", "Chrome").put("device", "").put("system_locale", "en-US").put("browser_user_agent", UA)
                    .put("browser_version", "140.0.0.0").put("os_version", "10").put("referrer", "").put("referring_domain", "").put("referrer_current", "").put("referring_domain_current", "")
                    .put("release_channel", "stable").put("client_build_number", BUILD).put("client_event_source", JSONObject.NULL).put("is_fast_connect", true)
                val presence = JSONObject().put("status", "online").put("since", 0).put("activities", JSONArray()).put("afk", false)
                s.send(JSONObject().put("op", 2).put("d", JSONObject().put("token", tok).put("properties", props).put("presence", presence).put("compress", false)).toString())
            }
            7, 9 -> { Log.i(TAG, "gateway asks to reconnect (op ${o.optInt("op")})"); Thread { Thread.sleep(2000); if (epoch == e) connect() }.start() }
            0 -> dispatch(o.optString("t"), o.optJSONObject("d") ?: JSONObject())
        }
    }

    private fun dispatch(t: String, d: JSONObject) {
        when (t) {
            "READY" -> {
                val u = d.optJSONObject("user")
                synchronized(lock) {
                    username = u?.optString("global_name")?.takeIf { it.isNotBlank() && it != "null" } ?: u?.optString("username").orEmpty(); userId = u?.optString("id").orEmpty()
                    channels.clear()
                    val pcs = d.optJSONArray("private_channels") ?: JSONArray()
                    for (i in 0 until pcs.length()) { val c = pcs.getJSONObject(i); val who = (c.optJSONArray("recipients") ?: JSONArray()).let { r -> (0 until r.length()).joinToString(", ") { r.getJSONObject(it).let { x -> x.optString("global_name").takeIf { g -> g.isNotBlank() && g != "null" } ?: x.optString("username") } } }
                        channels[c.getString("id")] = Channel(c.getString("id"), c.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: who.ifBlank { "dm" }, "", "Direct messages", true) }
                    val gs = d.optJSONArray("guilds") ?: JSONArray()
                    for (i in 0 until gs.length()) {
                        val g = gs.getJSONObject(i); val gid = g.optString("id"); val gname = g.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: g.optJSONObject("properties")?.optString("name") ?: gid
                        val cs = g.optJSONArray("channels") ?: JSONArray()
                        for (j in 0 until cs.length()) { val c = cs.getJSONObject(j); if (c.optInt("type") == 0 || c.optInt("type") == 5) channels[c.getString("id")] = Channel(c.getString("id"), c.optString("name"), gid, gname, false) }
                    }
                    status = "online"
                }
                emit(JSONObject().put("type", "status").put("status", "online").put("username", username))
                emit(JSONObject().put("type", "channels"))
            }
            "MESSAGE_CREATE" -> {
                val ch = d.optString("channel_id"); val a = d.optJSONObject("author")
                val from = a?.optString("global_name")?.takeIf { it.isNotBlank() && it != "null" } ?: a?.optString("username").orEmpty()
                var text = d.optString("content")
                val att = d.optJSONArray("attachments"); if (att != null && att.length() > 0) text += (if (text.isBlank()) "" else " ") + "[${att.length()} attachment${if (att.length() > 1) "s" else ""}]"
                if (text.isBlank()) return
                val own = a?.optString("id") == userId
                add(ch, if (own) "own" else if (text.contains("<@$userId>") || (username.isNotBlank() && text.contains(username, true))) "highlight" else "msg", from, clean(text), d.optString("id"))
            }
        }
    }
    private fun clean(s: String) = s.replace(Regex("<@!?(\\d+)>"), "@user").replace(Regex("<#(\\d+)>")) { m -> "#" + (channels[m.groupValues[1]]?.name ?: "channel") }.replace(Regex("<a?:(\\w+):\\d+>"), ":$1:")

    private fun add(channel: String, kind: String, from: String, text: String, id: String = "") {
        val l: Line
        synchronized(lock) { l = Line(++seq, System.currentTimeMillis(), channel, kind, from, text, id); lines += l; if (lines.size > KEEP * 4) lines.subList(0, lines.size - KEEP * 3).clear() }
        emit(JSONObject().put("type", "line").put("line", l.json()))
    }

    /** Pull the last messages of a channel into the buffer (once per switch). */
    fun history(channelId: String, limit: Int = 40) {
        if (status != "online") return
        val (code, body) = runCatching { get("/channels/$channelId/messages?limit=$limit") }.getOrElse { return }
        if (code !in 200..299) { add(channelId, "error", "", "Couldn't load history (HTTP $code)"); return }
        val a = runCatching { JSONArray(body) }.getOrNull() ?: return
        val have = synchronized(lock) { lines.filter { it.channel == channelId }.map { it.id }.toSet() }
        for (i in a.length() - 1 downTo 0) {
            val m = a.getJSONObject(i); val id = m.optString("id"); if (id in have) continue
            val au = m.optJSONObject("author"); val from = au?.optString("global_name")?.takeIf { it.isNotBlank() && it != "null" } ?: au?.optString("username").orEmpty()
            var text = m.optString("content"); val att = m.optJSONArray("attachments"); if (att != null && att.length() > 0) text += " [${att.length()} attachment${if (att.length() > 1) "s" else ""}]"
            if (text.isBlank()) continue
            val ts = runCatching { java.time.Instant.parse(m.optString("timestamp")).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
            val l: Line; synchronized(lock) { l = Line(++seq, ts, channelId, if (au?.optString("id") == userId) "own" else "msg", from, clean(text), id); lines += l }
            emit(JSONObject().put("type", "line").put("line", l.json()))
        }
    }

    fun say(channelId: String, text: String): Boolean {
        if (status != "online" || text.isBlank()) return false
        val cid = channelId.ifBlank { current }; if (cid.isBlank()) return false
        val (code, o) = runCatching { post("/channels/$cid/messages", JSONObject().put("content", text).put("nonce", System.currentTimeMillis().toString()).put("tts", false).put("flags", 0)) }.getOrElse { return false }
        if (code !in 200..299) { add(cid, "error", "", o.optString("message").ifBlank { "Send failed (HTTP $code)" }); return false }
        return true
    }
    fun setCurrent(id: String) { synchronized(lock) { if (channels.containsKey(id)) current = id }; emit(JSONObject().put("type", "current").put("channel", current)) }
    fun currentChannel() = current
    fun channelList(): List<Channel> = synchronized(lock) { channels.values.toList() }
    fun find(name: String): Channel? {
        val q = name.trim().trimStart('#').lowercase()
        val all = channelList()
        return all.firstOrNull { it.name.lowercase() == q } ?: all.firstOrNull { it.name.lowercase().replace('-', ' ') == q.replace('-', ' ') } ?: all.firstOrNull { it.name.lowercase().contains(q) }
    }
    fun findGuild(name: String): String? { val q = name.trim().lowercase(); return channelList().firstOrNull { it.guildName.lowercase() == q }?.guildName ?: channelList().firstOrNull { it.guildName.lowercase().contains(q) }?.guildName }
    fun recent(channelId: String, n: Int) = synchronized(lock) { lines.filter { it.channel == channelId && it.kind in setOf("msg", "own", "highlight") }.takeLast(n) }

    fun snapshot(since: Long = 0L): JSONObject = synchronized(lock) {
        JSONObject().put("status", status).put("username", username).put("current", current).put("hasToken", token != null)
            .put("channels", JSONArray().also { a -> channels.values.forEach { c -> a.put(JSONObject().put("id", c.id).put("name", c.name).put("guild", c.guildName).put("dm", c.dm)) } })
            .put("lines", JSONArray().also { a -> lines.filter { it.seq > since }.takeLast(KEEP).forEach { a.put(it.json()) } })
            .put("pending", pending?.let { JSONObject().put("channel", it.first).put("text", it.second) })
    }
    fun setTheme(name: String) = emit(JSONObject().put("type", "theme").put("name", name))
}
