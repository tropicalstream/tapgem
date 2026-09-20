package com.tapgem.app.core.music

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One-way channel from the voice tools to the open player page.
 *
 * Playback state rides on [MusicPlayer]'s own listeners; this carries the things only a spoken
 * command produces — a skin gallery to show, a skin to wear, a visualiser mode — so the page can
 * react to "show me space skins" without polling for it.
 */
object MusicBridgeEvents {
    fun interface Listener { fun onEvent(json: JSONObject) }
    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun emit(o: JSONObject) { for (l in listeners) runCatching { l.onEvent(o) } }

    fun emitSkinResults(query: String?, skins: List<SkinStore.Skin>) = emit(
        JSONObject().put("type", "skins").put("query", query ?: "")
            .put("results", JSONArray().also { a -> skins.forEach { a.put(it.json()) } }))

    fun emitSkin(installed: JSONObject) = emit(JSONObject().put("type", "skin").put("skin", installed))

    fun emitVisualizer(mode: String) = emit(JSONObject().put("type", "visualizer").put("mode", mode))
}
