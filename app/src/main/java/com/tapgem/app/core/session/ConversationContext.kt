package com.tapgem.app.core.session

import android.os.SystemClock
import java.util.Locale

/**
 * What the user actually said lately (from the Live session's input
 * transcription), so tools can tell "open the golf game" from "make me a
 * golf game" instead of trusting the model's reading of it.
 */
object ConversationContext {

    private class Utterance(val atMs: Long, val text: String)
    private val recent = ArrayDeque<Utterance>()
    private const val KEEP_MS = 5 * 60_000L

    @Synchronized fun reset() { recent.clear() }

    @Synchronized fun noteUser(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        recent.addLast(Utterance(now, t))
        while (recent.isNotEmpty() && now - recent.first().atMs > KEEP_MS) recent.removeFirst()
    }

    /** True when any transcription has arrived this session (if not, intent can't be judged). */
    @Synchronized fun hasTranscript(): Boolean = recent.isNotEmpty()

    /** The user's words within the last [windowMs], oldest first, lower-cased. */
    @Synchronized fun recentUserText(windowMs: Long): String {
        val now = SystemClock.uptimeMillis()
        return recent.filter { now - it.atMs <= windowMs }.joinToString(" ") { it.text }.lowercase(Locale.US)
    }

    private val CREATE = Regex("\\b(make|build|create|code|design|write|generate|vibe|program|develop|whip up|put together|invent|new (app|game|widget|tool|timer|clock))\\b")
    private val YES = Regex("\\b(yes|yeah|yep|yup|sure|ok|okay|go ahead|do it|please do|build it|make it|create it|absolutely|of course|let'?s do it|why not|go for it|sounds good)\\b")

    /** Did the user ask for something to be built — or just say yes to building it? */
    fun wantsCreation(): Boolean {
        if (!hasTranscript()) return true   // no transcript at all: don't block on a guess
        return CREATE.containsMatchIn(recentUserText(120_000L)) || YES.containsMatchIn(recentUserText(45_000L))
    }
}
