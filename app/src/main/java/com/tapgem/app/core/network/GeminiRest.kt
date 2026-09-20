package com.tapgem.app.core.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tapgem.app.core.store.ApiKeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One-shot generateContent calls that back the tools: gemini-3.8-flash for
 * text (live-widget refreshes, prompt widgets, vibe-coded app HTML — with
 * Google Search grounding when asked) and gemini-3.1-flash-image for
 * wallpapers painted from a verbal description. Blocking; call off-main.
 * The key travels in the x-goog-api-key header, never in a URL.
 */
object GeminiRest {

    private const val TAG = "GeminiRest"
    const val TEXT_MODEL = "gemini-3.8-flash"

    /**
     * Reading, as opposed to writing. Pulling the sense out of a page someone is already looking at
     * is comprehension, not invention, and the lite tier does it for less — with the same
     * million-token window, so a whole article fits where the raw text never would.
     * Generation (apps, live cards, prompt widgets) stays on [TEXT_MODEL], which is better at it.
     */
    const val READ_MODEL = "gemini-3.5-flash-lite"

    /**
     * A voice that is not the conversation. The Live model ends a spoken turn after a paragraph,
     * so it cannot read a book; this speaks whatever it is handed. Measured on the glasses' key:
     * ~1000 characters costs about 26s to generate and yields about 65s of speech, so generation
     * runs comfortably ahead of playback and passages can be prepared while the last one plays.
     */
    const val SPEECH_MODEL = "gemini-2.5-flash-preview-tts"

    /** 24 kHz mono PCM16 — what [SPEECH_MODEL] returns, and what the reader plays. */
    const val SPEECH_RATE_HZ = 24_000

    /**
     * The speech model refused because a limit was hit. Two very different things arrive as the
     * same 429, and a reader has to tell them apart: a per-minute rate limit, which is over in
     * seconds and is merely a pause in a reading, and the free tier's cap of 100 requests per day
     * per model, which is the end of one. Measured mid-test on the glasses' key: quotaMetric
     * `generate_requests_per_model_per_day`, limit 100, retryDelay 6931s.
     */
    class SpeechLimited(val retrySecs: Long, val perDay: Boolean) :
        IllegalStateException("speech limited (${if (perDay) "daily quota" else "rate"}); retry in ${retrySecs}s")

    /** Read the kind of limit and the wait out of the error body the API sends with a 429. */
    private fun limitFrom(raw: String): SpeechLimited {
        val err = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
        val details = err?.optJSONArray("details")
        var secs = 0L
        var perDay = err?.optString("message")?.contains("per_day") == true
        for (i in 0 until (details?.length() ?: 0)) {
            val d = details?.optJSONObject(i) ?: continue
            val type = d.optString("@type")
            if (type.endsWith("RetryInfo"))
                secs = d.optString("retryDelay").removeSuffix("s").substringBefore('.').toLongOrNull() ?: 0L
            if (type.endsWith("QuotaFailure")) {
                val v = d.optJSONArray("violations")
                for (j in 0 until (v?.length() ?: 0))
                    if (v?.optJSONObject(j)?.optString("quotaMetric")?.contains("per_day") == true) perDay = true
            }
        }
        // A wait measured in hours is a daily cap whatever the body called it.
        return SpeechLimited(secs, perDay || secs > 300)
    }

    /** Raw PCM for [text], or a failure. Voices are Gemini's prebuilt set; Kore reads plainly. */
    fun speak(context: Context, text: String, voice: String = "Kore"): Result<ByteArray> =
        callAudio(context, text, voice)

    private fun callAudio(context: Context, text: String, voice: String): Result<ByteArray> = runCatching {
        val key = ApiKeyStore.resolve(context)?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No Gemini API key")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        val req = Request.Builder()
            .url("$BASE$SPEECH_MODEL:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        audioHttp.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (resp.code == 429) throw limitFrom(raw)
            if (!resp.isSuccessful) throw IllegalStateException("speech HTTP ${resp.code}: ${raw.take(140)}")
            val part = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?: throw IllegalStateException("No audio in reply")
            val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                ?: throw IllegalStateException("No audio part")
            Base64.decode(inline.optString("data"), Base64.DEFAULT)
        }
    }

    /** Speech takes far longer than text: a minute of audio is ~26s of generation. */
    private val audioHttp by lazy {
        http.newBuilder()
            .callTimeout(java.time.Duration.ofSeconds(180))
            .readTimeout(java.time.Duration.ofSeconds(180))
            .build()
    }
    const val IMAGE_MODEL = "gemini-3.1-flash-image"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun generateText(
        context: Context,
        prompt: String,
        system: String? = null,
        search: Boolean = false,
        model: String = TEXT_MODEL
    ): Result<String> = call(context, model, prompt, system, search, image = false).map { parts ->
        parts.mapNotNull { it.optString("text").takeIf { t -> t.isNotBlank() } }.joinToString("").trim()
    }.mapCatching { if (it.isBlank()) throw IllegalStateException("Empty reply from $model") else it }

    /** Returns PNG/JPEG bytes of the first image part; 4:3 to match the display. */
    fun generateImage(context: Context, prompt: String): Result<ByteArray> {
        val first = call(context, IMAGE_MODEL, prompt, null, false, image = true, aspect = "4:3")
        val parts = if (first.isFailure && first.exceptionOrNull()?.message?.contains("400") == true) {
            // Older image endpoints reject imageConfig — retry without it.
            call(context, IMAGE_MODEL, prompt, null, false, image = true, aspect = null)
        } else first
        return parts.mapCatching { ps ->
            val img = ps.firstNotNullOfOrNull { p ->
                (p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data"))
                    ?.takeIf { it.optString("mimeType").startsWith("image/") }
            } ?: throw IllegalStateException("No image in reply")
            Base64.decode(img.optString("data"), Base64.DEFAULT)
        }
    }

    private fun call(
        context: Context,
        model: String,
        prompt: String,
        system: String?,
        search: Boolean,
        image: Boolean,
        aspect: String? = null
    ): Result<List<JSONObject>> = runCatching {
        val key = ApiKeyStore.resolve(context)?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No Gemini API key")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        if (!system.isNullOrBlank()) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        if (search) body.put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
        if (image) {
            val gc = JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
            if (aspect != null) gc.put("imageConfig", JSONObject().put("aspectRatio", aspect))
            body.put("generationConfig", gc)
        }
        val req = Request.Builder()
            .url("$BASE$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                Log.w(TAG, "$model HTTP ${resp.code}: ${text.take(240)}")
                throw IllegalStateException("${msg?.takeIf { it.isNotBlank() } ?: "Gemini error"} (HTTP ${resp.code})")
            }
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("No candidates from $model")
            (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
        }
    }
}
