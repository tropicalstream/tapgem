package com.tapgem.app.core.bridge

/** Binder surface of the voice foreground Service (same process, no AIDL). */
interface VoiceServiceApi {
    /** Begin a Gemini Live session. Idempotent. */
    fun activateVoice()
    /** End the current session. Idempotent. */
    fun shutdownVoice()
    fun currentState(): HudStateBridge.State

    companion object {
        const val SERVICE_FQN: String = "com.tapgem.app.core.session.TapGemForegroundService"
    }
}
