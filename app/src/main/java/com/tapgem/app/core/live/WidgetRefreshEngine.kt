package com.tapgem.app.core.live

import android.content.Context
import android.util.Log
import com.tapgem.app.core.bridge.DesktopBridge
import com.tapgem.app.core.model.Widget
import com.tapgem.app.core.model.WidgetType
import com.tapgem.app.core.network.GeminiRest
import com.tapgem.app.core.store.DesktopStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * "How often it updates": every widget with refreshSec > 0 is re-fetched on
 * schedule. LIVE and prompt-TEXT widgets ask gemini-3.8-flash (with Google
 * Search grounding for LIVE); WEB / IMAGE / 3D / MAP widgets get a reload
 * nonce the view reacts to. Failed fetches back off (1, 2, 3… minutes, capped
 * at the widget's own interval) instead of hammering. Runs process-wide.
 */
object WidgetRefreshEngine {

    private const val TAG = "WidgetRefresh"
    private const val TICK_MS = 10_000L
    private const val GC_EVERY_MS = 30 * 60_000L
    private const val NAV_TRACK_MS = 8_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private lateinit var appContext: Context
    private var lastGcMs = 0L

    fun start(context: Context) {
        appContext = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            delay(4_000)
            while (isActive) {
                runCatching { tick() }.onFailure { Log.w(TAG, "tick: ${it.message}") }
                delay(TICK_MS)
            }
        }
    }

    private var lastNavTrackMs = 0L

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        DesktopBridge.current().widgets
            .filter { it.refreshSec > 0 && it.id !in inFlight && now - it.updatedAt >= dueAfterMs(it) }
            .forEach { refresh(it) }
        if (now - lastNavTrackMs >= NAV_TRACK_MS) { lastNavTrackMs = now; trackNavigation() }
        if (now - lastGcMs > GC_EVERY_MS) { lastGcMs = now; DesktopStore.gc() }
    }

    /**
     * Navigating maps follow the glasses: a fresh fix moves the position dot
     * and, when it is a real (Wi-Fi/GPS) fix within 30 m of the next
     * manoeuvre, advances the step. Coarse IP estimates never advance steps.
     */
    private suspend fun trackNavigation() {
        val navs = DesktopBridge.current().widgets.filter { it.type == WidgetType.MAP && it.state["nav"] == "on" && it.content.isNotBlank() }
        if (navs.isEmpty()) return
        com.tapgem.app.core.location.LocationSource.keepPhoneStream(appContext)
        val fix = com.tapgem.app.core.location.LocationSource.current(appContext, allowIpFallback = false, maxAgeMs = NAV_TRACK_MS) ?: return
        val pos = "%.6f,%.6f,%d".format(java.util.Locale.US, fix.lat, fix.lon, fix.accuracyM.toInt())
        navs.forEach { w ->
            val route = com.tapgem.app.core.network.Router.Route.fromJson(w.content) ?: return@forEach
            val step = w.state["step"]?.toIntOrNull() ?: 0
            var next = step
            if (fix.isPrecise && fix.accuracyM < 80f) {
                val target = route.steps.getOrNull(step + 1)
                if (target != null && com.tapgem.app.core.network.Router.distanceM(fix.lat, fix.lon, target.lat, target.lon) < 30.0) next = step + 1
            }
            DesktopBridge.mutateWidget(w.id, pushUndo = false) { it.withState("pos" to pos, "posSrc" to fix.source, "step" to next.toString()) }
        }
    }

    private fun dueAfterMs(w: Widget): Long {
        val fails = w.state["failCount"]?.toIntOrNull() ?: 0
        val base = w.refreshSec * 1000L
        return if (fails == 0) base else minOf(base, 60_000L * fails.coerceAtMost(10))
    }

    fun refreshNow(id: String) {
        val w = DesktopBridge.current().widget(id) ?: return
        scope.launch { refresh(w) }
    }

    /** Fetch fresh content for one widget and publish it (no undo entry). */
    suspend fun refresh(w: Widget): Result<String> {
        if (!inFlight.add(w.id)) return Result.failure(IllegalStateException("busy"))
        try {
            val now = System.currentTimeMillis()
            val textual = w.type.isFetched || (w.type == WidgetType.TEXT && w.source.startsWith("prompt:"))
            val result: Result<String> = when {
                w.type == WidgetType.LIVE -> fetchLive(w.source)
                w.type == WidgetType.TICKER -> fetchTicker(w.source)
                w.type == WidgetType.TEXT && w.source.startsWith("prompt:") ->
                    GeminiRest.generateText(appContext, w.source.removePrefix("prompt:").trim(), system = PROMPT_TEXT_SYSTEM, search = true)
                else -> Result.success("")
            }
            DesktopBridge.mutate(pushUndo = false) { d ->
                val cur = d.widget(w.id) ?: return@mutate d
                // The user changed what this widget shows while we were fetching — drop the stale answer.
                if (cur.source != w.source || cur.type != w.type) return@mutate d
                val updated = if (textual) {
                    result.fold(
                        onSuccess = { cur.copy(content = it, updatedAt = now).withState("failCount" to "") },
                        onFailure = {
                            val fails = (cur.state["failCount"]?.toIntOrNull() ?: 0) + 1
                            cur.copy(updatedAt = now, content = cur.content.ifBlank { "Couldn't fetch yet — retrying." })
                                .withState("failCount" to fails.toString())
                        }
                    )
                } else cur.copy(updatedAt = now).withState("reload" to now.toString())
                d.replaceWidget(updated)
            }
            return result
        } finally {
            inFlight.remove(w.id)
        }
    }

    fun fetchLive(query: String): Result<String> = GeminiRest.generateText(
        appContext,
        prompt = "Query: $query",
        system = LIVE_SYSTEM,
        search = true
    )

    fun fetchTicker(query: String): Result<String> = GeminiRest.generateText(
        appContext,
        prompt = "Ticker topic: $query",
        system = TICKER_SYSTEM,
        search = true
    )

    private const val TICKER_SYSTEM =
        "You feed a one-line scrolling ticker on AR glasses. Using web search, return 6 to 10 CURRENT items " +
            "for the topic, separated by ' | ' on ONE line. Each item at most 60 characters, the key number or " +
            "name first (e.g. 'AAPL 231.40 +1.2%', 'Warriors 112–104 Lakers', 'Oakland 64°F cloudy'). Plain text, " +
            "no markdown, no preamble, no trailing period."

    private const val LIVE_SYSTEM =
        "You refresh a small heads-up display card on AR glasses. Answer the query with the CURRENT " +
            "facts using web search. Reply in at most 4 short lines of plain text — no markdown, no " +
            "preamble. Put the key number/name first (score, temperature, price, headline)."

    const val PROMPT_TEXT_SYSTEM =
        "You generate the text for a small panel on AR glasses. Plain text only, no markdown, at most " +
            "6 short lines. Be concrete and current; use web search when the topic is time-sensitive."
}
