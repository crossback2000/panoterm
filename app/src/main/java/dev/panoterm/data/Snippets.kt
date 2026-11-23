package dev.panoterm.data

import dev.panoterm.str
import dev.panoterm.R
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A saved command for the command palette. [submit] = press Enter after typing it. */
data class Snippet(val label: String, val text: String, val submit: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("label", label).put("text", text).put("submit", submit)

    companion object {
        fun fromJson(o: JSONObject) = Snippet(o.optString("label"), o.optString("text"), o.optBoolean("submit", true))
    }
}

class Snippets(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("snippets", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Snippet>> = _items

    private fun load(): List<Snippet> {
        val raw = prefs.getString("items", null) ?: return DEFAULTS
        val saved = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Snippet.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(DEFAULTS)
        // v1.2.2: add /usage once to lists the user had already customised.
        if (!prefs.getBoolean("added_usage", false)) {
            prefs.edit().putBoolean("added_usage", true).apply()
            if (saved.none { it.text == "/usage" }) {
                val i = saved.indexOfFirst { it.text == "/context" }.let { if (it < 0) saved.size else it + 1 }
                val next = saved.toMutableList().apply { add(i, USAGE) }
                prefs.edit().putString("items", JSONArray(next.map { it.toJson() }).toString()).apply()
                return next
            }
        }
        return saved
    }

    fun set(list: List<Snippet>) {
        prefs.edit().putString("items", JSONArray(list.map { it.toJson() }).toString()).apply()
        _items.value = list
    }

    fun add(s: Snippet) = set(_items.value + s)
    fun remove(index: Int) = set(_items.value.filterIndexed { i, _ -> i != index })
    fun move(index: Int, delta: Int) {
        val l = _items.value.toMutableList()
        val j = index + delta
        if (index !in l.indices || j !in l.indices) return
        l.add(j, l.removeAt(index))
        set(l)
    }

    fun toJson(): JSONArray = JSONArray(_items.value.map { it.toJson() })
    fun importJson(arr: JSONArray) {
        val incoming = (0 until arr.length()).map { Snippet.fromJson(arr.getJSONObject(it)) }
        set((_items.value + incoming).distinctBy { it.label to it.text })
    }

    companion object {
        val USAGE = Snippet("/usage", "/usage")
        val DEFAULTS = listOf(
            Snippet("/compact", "/compact"),
            Snippet("/clear", "/clear"),
            Snippet("/resume", "/resume"),
            Snippet("/model", "/model"),
            Snippet("/context", "/context"),
            USAGE,
            Snippet(str(R.string.snippet_claude_start), "claude"),
            Snippet(str(R.string.snippet_claude_continue), "claude --continue"),
            Snippet("git status", "git status"),
            Snippet("git diff --stat", "git diff --stat"),
        )
    }
}
