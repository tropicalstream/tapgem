package com.tapgem.app.core.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject

/** Routes Gemini Live tool calls to the native tools by name. */
class ToolDispatcher(context: Context) {

    companion object { private const val TAG = "ToolDispatcher" }

    private val tools: Map<String, AiTool> = listOf(
        DesktopTool(context), WidgetTool(context), ThemeTool(), WallpaperTool(context),
        AppBuilderTool(context), MediaTool(context), WebTool(context), BookmarkTool(), IrcTool(context), DiscordTool(context), InterpreterTool(context), TutorTool(context)
    ).associateBy { it.name }

    fun isSupported(name: String): Boolean = tools.containsKey(name.trim())

    suspend fun dispatch(name: String, argsJson: String): Result<String> {
        val tool = tools[name.trim()] ?: return Result.failure(IllegalArgumentException("Unknown tool: $name"))
        val args = parseArgs(argsJson)
        Log.d(TAG, "dispatch $name ${args.keys}")
        return runCatching { tool.execute(Args(args)) }.getOrElse { Result.failure(it) }
    }

    private fun parseArgs(argsJson: String): Map<String, String> {
        if (argsJson.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(argsJson)
            val out = mutableMapOf<String, String>()
            for (key in obj.keys()) out[key] = obj.opt(key)?.toString().orEmpty()
            out
        }.getOrDefault(emptyMap())
    }
}
