package com.tapgem.app.core.tools

/** A native tool declared to Gemini Live and executed on the device. */
interface AiTool {
    val name: String
    suspend fun execute(args: Args): Result<String>
}

/** Forgiving accessor over the model's string-valued arguments. */
class Args(private val m: Map<String, String>) {
    val action: String get() = (str("action") ?: "").lowercase().trim()

    fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
        m[k]?.trim()?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
    }

    fun int(vararg keys: String): Int? = str(*keys)?.replace("px", "")?.trim()?.toDoubleOrNull()?.toInt()
    fun float(vararg keys: String): Float? = str(*keys)?.toDoubleOrNull()?.toFloat()
    fun bool(vararg keys: String): Boolean? = str(*keys)?.lowercase()?.let {
        when (it) { "true", "yes", "on", "1" -> true; "false", "no", "off", "0" -> false; else -> null }
    }

    fun has(key: String) = str(key) != null
    val raw: Map<String, String> get() = m
}
