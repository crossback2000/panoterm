package dev.panoterm.data

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
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Snippet.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(DEFAULTS)
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
        val DEFAULTS = listOf(
            Snippet("/compact", "/compact"),
            Snippet("/clear", "/clear"),
            Snippet("/resume", "/resume"),
            Snippet("/model", "/model"),
            Snippet("/context", "/context"),
            Snippet("claude 시작", "claude"),
            Snippet("claude 이어하기", "claude --continue"),
            Snippet("git status", "git status"),
            Snippet("git diff --stat", "git diff --stat"),
        )
    }
}
