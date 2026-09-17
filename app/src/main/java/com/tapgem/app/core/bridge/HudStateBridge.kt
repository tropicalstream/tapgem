package com.tapgem.app.core.bridge

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local bridge for the assistant's HUD state: voice phase, mic/model
 * level, connection status, the live user transcript, the assistant caption,
 * and a one-line transient notification. Updates are atomic read-modify-
 * write; listeners fire on the publisher's thread; UI consumers hop to main.
 */
object HudStateBridge {

    enum class VoicePhase { IDLE, LISTENING, THINKING, SPEAKING }
    enum class Channel { USER, MODEL }
    enum class ConnectionStatus { IDLE, CONNECTING, CONNECTED, ERROR }

    data class State(
        val phase: VoicePhase = VoicePhase.IDLE,
        val transcript: String? = null,
        /** What the assistant is saying (streamed output transcription). */
        val caption: String? = null,
        val level: Float = 0f,
        val channel: Channel = Channel.USER,
        val connection: ConnectionStatus = ConnectionStatus.IDLE,
        val notification: String? = null,
        /** Bumps whenever [notification] is (re)set, so the UI can time it. */
        val notificationSeq: Long = 0L
    )

    private val state = AtomicReference(State())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    fun publish(next: State) {
        state.set(next)
        for (l in listeners) runCatching { l(next) }
    }

    /** Atomic transform-and-publish (safe from any thread). */
    fun update(transform: (State) -> State) {
        val next = state.updateAndGet { transform(it) }
        for (l in listeners) runCatching { l(next) }
    }

    fun current(): State = state.get()

    fun observe(listener: (State) -> Unit): AutoCloseable {
        listeners.add(listener)
        runCatching { listener(state.get()) }
        return AutoCloseable { listeners.remove(listener) }
    }

    fun notice(text: String?) = update { it.copy(notification = text, notificationSeq = it.notificationSeq + 1) }
}
