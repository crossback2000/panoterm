package dev.panoterm.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

class HostRepository(private val file: File) {
    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<Host>> = _hosts

    private fun load(): List<Host> {
        val arr = readJsonArray(file) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { runCatching { Host.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun get(id: String): Host? = _hosts.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(h: Host) {
        val cur = _hosts.value
        val next = if (cur.any { it.id == h.id }) cur.map { if (it.id == h.id) h else it } else cur + h
        save(next)
    }

    @Synchronized
    fun delete(id: String) = save(_hosts.value.filterNot { it.id == id })

    private fun save(list: List<Host>) {
        writeAtomic(file, JSONArray(list.map { it.toJson() }).toString().toByteArray())
        _hosts.value = list
    }
}
