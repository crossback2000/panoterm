package dev.panoterm.data

import android.content.Context
import org.json.JSONArray

/** Per-host history of prompts sent from the compose box (local only, last 50). */
class PromptHistory(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("prompt_history", Context.MODE_PRIVATE)

    fun get(hostId: String): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(hostId, "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun add(hostId: String, text: String) {
        if (text.isBlank()) return
        val list = (listOf(text) + get(hostId).filterNot { it == text }).take(50)
        prefs.edit().putString(hostId, JSONArray(list).toString()).apply()
    }
}
